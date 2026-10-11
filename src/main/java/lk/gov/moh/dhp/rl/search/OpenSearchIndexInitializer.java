package lk.gov.moh.dhp.rl.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;

/**
 * Ensures the OpenSearch "rl-patients" index exists with the correct
 * mapping before the application starts serving traffic.
 *
 * Previously the index had to be created manually (PUT /rl-patients)
 * before enabling rl.opensearch.enabled=true — this was a deployment
 * foot-gun: if the index was missing, indexPatient() silently failed
 * and name search returned no results with no obvious error.
 *
 * This initializer runs on startup (PostConstruct) and:
 *   1. Checks if the index already exists (HEAD /{index}).
 *   2. Creates it with the correct mapping if it does not.
 *   3. Logs a warning and continues if OpenSearch is unreachable
 *      (the app starts anyway — index creation will be retried on the
 *       next deploy / restart; meanwhile name search degrades gracefully
 *       to empty results, same as when the index is absent).
 *
 * Only active when rl.opensearch.enabled=true.
 */
@Component
@ConditionalOnProperty(name = "rl.opensearch.enabled", havingValue = "true", matchIfMissing = false)
public class OpenSearchIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchIndexInitializer.class);

    @Value("${rl.opensearch.url:http://localhost:9200}")
    private String baseUrl;

    @Value("${rl.opensearch.index:rl-patients}")
    private String index;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OpenSearchIndexInitializer(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void ensureIndex() {
        try {
            String indexUrl = baseUrl + "/" + index;

            // 1. Check existence — HEAD returns 200 if present, 404 if absent.
            try {
                ResponseEntity<Void> head = restTemplate.exchange(
                        indexUrl, HttpMethod.HEAD, null, Void.class);
                if (head.getStatusCode() == HttpStatus.OK) {
                    log.info("OpenSearch index '{}' already exists", index);
                    return;
                }
            } catch (HttpClientErrorException.NotFound ignored) {
                // Expected path: index does not exist yet — fall through to create.
            }

            // 2. Create with standard rl-patients mapping.
            // M-9: replica count defaults to 0 so a single-node OpenSearch cluster
            // (common in dev/edge deployments) doesn't go yellow-status at startup.
            // In multi-node deployments, set OPENSEARCH_REPLICAS=1 (or more) via
            // the environment — the index template can be updated later with a PUT
            // /{index}/_settings call; it does not require a re-index.
            String body = """
                    {
                      "settings": {
                        "number_of_shards": 1,
                        "number_of_replicas": 0
                      },
                      "mappings": {
                        "properties": {
                          "phn":         { "type": "keyword" },
                          "givenName":   { "type": "text", "analyzer": "standard" },
                          "familyName":  { "type": "text", "analyzer": "standard" },
                          "fullName":    { "type": "text", "analyzer": "standard" },
                          "facilityHin": { "type": "keyword" }
                        }
                      }
                    }
                    """;

            String response = restTemplate.postForObject(indexUrl, body, String.class);
            JsonNode resp = objectMapper.readTree(response);
            if (resp.path("acknowledged").asBoolean(false)) {
                log.info("OpenSearch index '{}' created successfully", index);
            } else {
                log.warn("OpenSearch index '{}' creation response unexpected: {}", index, response);
            }

        } catch (Exception e) {
            // Non-fatal: the app starts and name search degrades to empty results.
            // The most common cause is OpenSearch not yet ready at boot time.
            log.warn("Could not ensure OpenSearch index '{}': {}. " +
                     "Name search will return empty results until the index is created. " +
                     "Re-deploy or restart to retry.", index, e.getMessage());
        }
    }
}
