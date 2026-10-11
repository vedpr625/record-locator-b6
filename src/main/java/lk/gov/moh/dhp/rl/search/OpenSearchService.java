package lk.gov.moh.dhp.rl.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 */
@Service
@ConditionalOnProperty(name = "rl.opensearch.enabled", havingValue = "true", matchIfMissing = false)
public class OpenSearchService extends OpenSearchServicePort {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchService.class);

    @Value("${rl.opensearch.url:http://localhost:9200}")
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
            ObjectNode query = objectMapper.createObjectNode();
            query.putObject("query")
                    .putObject("multi_match")
                    .put("query", name.trim())
                    .put("type", "phrase_prefix")
                    .putArray("fields")
                    .add("givenName")
                    .add("familyName")
                    .add("fullName");
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
        } catch (Exception e) {
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
            String url = baseUrl + "/" + index + "/_update/" + doc.phn();
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
        } catch (Exception e) {
            // Index failure must never fail pointer creation — the pointer is in
            // PostgreSQL already at this point; the patient just won't appear in
            // name searches until the index catches up (or a re-index job runs).
            log.warn("failed to index patient {} in OpenSearch: {}", doc.phn(), e.getMessage());
        }
    }
}
