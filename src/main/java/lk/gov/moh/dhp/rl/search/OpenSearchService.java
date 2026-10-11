package lk.gov.moh.dhp.rl.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Wraps the OpenSearch REST API for patient-name lookups and patient
 * document indexing.
 *
 * Uses plain REST (not the opensearch-java SDK) so we don't take a
 * dependency on the opensearch-java client at all — the SDK adds ~15 MB
 * of transitive deps and its Spring Boot autoconfiguration conflicts with
 * HAPI's own Jackson setup. The REST API is stable and simple enough that
 * raw RestTemplate + Jackson is the right trade-off here.
 *
 * Conditional on rl.opensearch.enabled=true (defaults to false). When
 * disabled, findPhnsByName() returns an empty list and indexPatient() is a
 * no-op — the app works as a pure PostgreSQL service, which is what
 * Bundle 6 needs in the interim before OpenSearch is deployed.
 *
 * <p><b>I-4 – Authentication gap:</b> this client sends no credentials to
 * OpenSearch. This is intentional for the current deployment topology
 * (OpenSearch is reachable only from within the cluster, not from the
 * internet), but must be revisited before any multi-tenant or
 * internet-reachable deployment. To add authentication, inject an
 * {@code HttpHeaders}-customizing {@code RestTemplateCustomizer} bean
 * (Basic auth or a bearer token from AWS Secrets Manager / Vault)
 * and remove this comment once the gap is closed.
 */
@Service
@ConditionalOnProperty(name = "rl.opensearch.enabled", havingValue = "true", matchIfMissing = false)
public class OpenSearchService extends OpenSearchServicePort {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchService.class);

    // S-3: default to HTTPS; operators must explicitly opt down to HTTP if needed.
    @Value("${rl.opensearch.url:https://localhost:9200}")
    private String baseUrl;

    @Value("${rl.opensearch.index:rl-patients}")
    private String index;

    @Value("${rl.opensearch.max-name-results:200}")
    private int maxNameResults;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OpenSearchService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /** S-3: warn at startup when the OpenSearch URL is not HTTPS. */
    @PostConstruct
    void checkBaseUrl() {
        if (baseUrl != null && !baseUrl.startsWith("https://")) {
            log.warn("rl.opensearch.url '{}' is not HTTPS — patient data may be transmitted in plain text",
                    baseUrl);
        }
    }

    /**
     * Free-text name search. Returns PHNs of all patients whose name
     * matches the query string (any of givenName, familyName, fullName).
     *
     * Uses a multi_match query across all three name fields with
     * match_phrase_prefix so partial names work ("Kasu" matches "Kasun").
     *
     * Returns an empty list on any error — name search degrading to no
     * results is better than taking down the FHIR endpoint.
     */
    public List<String> findPhnsByName(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        try {
            String trimmed = name.trim();

            // Two-clause bool/should query:
            //   1. phrase_prefix  (boost 3) — exact prefix match, highest relevance.
            //      "Kasu" → "Kasun", "Per" → "Perera"
            //   2. fuzzy best_fields (boost 1) — catches transposition / missing-letter typos.
            //      "Kasn" → "Kasun", "Perara" → "Perera"
            //
            // Safety guardrails on the fuzzy clause:
            //   fuzziness AUTO  → 1 edit for 3–5 char tokens, 2 edits for 6+ chars
            //   prefix_length 2 → the first 2 characters must match exactly, preventing
            //                     cross-patient false positives (e.g. "John" ≠ "Jane")
            //
            // Results are deduplicated by PHN in the caller, so a PHN that scores on
            // both clauses appears only once in the final list.
            ObjectNode query = objectMapper.createObjectNode();
            ObjectNode bool = query.putObject("query").putObject("bool");
            var should = bool.putArray("should");

            // Clause 1 — prefix match (high boost)
            should.addObject()
                    .putObject("multi_match")
                    .put("query", trimmed)
                    .put("type", "phrase_prefix")
                    .put("boost", 3)
                    .putArray("fields")
                    .add("givenName").add("familyName").add("fullName");

            // Clause 2 — fuzzy match (low boost, safety-gated by prefix_length)
            should.addObject()
                    .putObject("multi_match")
                    .put("query", trimmed)
                    .put("type", "best_fields")
                    .put("fuzziness", "AUTO")
                    .put("prefix_length", 2)
                    .put("boost", 1)
                    .putArray("fields")
                    .add("givenName").add("familyName").add("fullName");

            bool.put("minimum_should_match", 1);

            query.put("size", maxNameResults);
            // Only fetch _source.phn — no need for the rest of the document
            query.putArray("_source").add("phn");

            String url = baseUrl + "/" + index + "/_search";
            String responseBody = restTemplate.postForObject(url, query, String.class);
            JsonNode response = objectMapper.readTree(responseBody);

            List<String> phns = new ArrayList<>();
            for (JsonNode hit : response.path("hits").path("hits")) {
                String phn = hit.path("_source").path("phn").asText(null);
                if (phn != null && !phn.isBlank()) {
                    phns.add(phn);
                }
            }
            return phns;
        } catch (RestClientException | JsonProcessingException e) {
            // R-6: catch specific types; RestClientException covers HTTP/IO errors from
            // RestTemplate; JsonProcessingException covers malformed OpenSearch responses.
            log.warn("OpenSearch name search failed for '{}': {}", name, e.getMessage());
            return List.of();
        }
    }

    /**
     * Upsert a patient document by PHN. Called from OrthancPoller and
     * the FHIR ingest path whenever a new pointer is created, so the
     * name index stays current without a separate sync job.
     *
     * Uses the OpenSearch update API with doc_as_upsert=true so:
     *   - first call creates the document
     *   - subsequent calls update only the fields that changed
     *     (a later study from the same patient doesn't wipe the name
     *      set by an earlier study)
     */
    public void indexPatient(PatientDocument doc) {
        try {
            // H-6: use UriComponentsBuilder so the PHN is percent-encoded and cannot
            // produce a malformed URL if it contains '/', '?', '#', or '+'.
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/{index}/_update/{phn}")
                    .buildAndExpand(index, doc.phn())
                    .toUriString();
            Map<String, Object> body = Map.of(
                    "doc", Map.of(
                            "phn",        doc.phn(),
                            "givenName",  doc.givenName()  != null ? doc.givenName()  : "",
                            "familyName", doc.familyName() != null ? doc.familyName() : "",
                            "fullName",   doc.fullName()   != null ? doc.fullName()   : "",
                            "facilityHin",doc.facilityHin()!= null ? doc.facilityHin(): ""
                    ),
                    "doc_as_upsert", true);
            restTemplate.postForObject(url, body, String.class);
        } catch (RestClientException e) {
            // Catch is intentionally narrow: only HTTP and IO errors from RestTemplate.
            // PHN is masked in the log because the OpenSearch index key must not appear in logs.
            // Index failure does not fail pointer creation; the pointer is already persisted
            // in PostgreSQL. The patient will be absent from name searches until the index
            // catches up via a re-index job.
            log.warn("failed to index patient *** in OpenSearch: {}", e.getMessage());
        }
    }
}
