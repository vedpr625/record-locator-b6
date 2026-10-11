package lk.gov.moh.dhp.rl.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.mock;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OpenSearchService — no Spring context.
 *
 * Name search uses a bool/should query with two clauses:
 *   1. phrase_prefix (boost 3) — exact prefix match
 *   2. fuzzy best_fields (boost 1, prefix_length=2) — typo tolerance
 * These tests verify the query shape and PHN extraction, not OpenSearch scoring.
 */
class OpenSearchServiceTest {

    RestTemplate restTemplate;
    OpenSearchService service;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        service = new OpenSearchService(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseUrl", "http://opensearch:9200");
        ReflectionTestUtils.setField(service, "index", "rl-patients");
        ReflectionTestUtils.setField(service, "maxNameResults", 200);
    }

    // ── findPhnsByName ────────────────────────────────────────────────────────

    @Test
    void findPhnsByName_nullName_returnsEmpty() {
        assertThat(service.findPhnsByName(null)).isEmpty();
        verifyNoInteractions(restTemplate);
    }

    @Test
    void findPhnsByName_blankName_returnsEmpty() {
        assertThat(service.findPhnsByName("   ")).isEmpty();
        verifyNoInteractions(restTemplate);
    }

    @Test
    void findPhnsByName_extractsPhns() throws Exception {
        String response = """
            {
              "hits": {
                "hits": [
                  {"_source":{"phn":"PHN001"}},
                  {"_source":{"phn":"PHN002"}}
                ]
              }
            }
            """;
        when(restTemplate.postForObject(contains("/_search"), any(), eq(String.class)))
                .thenReturn(response);

        List<String> phns = service.findPhnsByName("Perera");

        assertThat(phns).containsExactlyInAnyOrder("PHN001", "PHN002");
    }

    @Test
    void findPhnsByName_opensearchError_returnsEmpty() {
        // R-6: service now catches RestClientException specifically; use a concrete subtype.
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        assertThat(service.findPhnsByName("Perera")).isEmpty();
    }

    @Test
    void findPhnsByName_emptyHits_returnsEmpty() throws Exception {
        String response = """
            {
              "hits": {
                "hits": []
              }
            }
            """;
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn(response);

        assertThat(service.findPhnsByName("Unknown")).isEmpty();
    }

    @Test
    void findPhnsByName_queryContainsBothPrefixAndFuzzyClauses() throws Exception {
        // Verify the bool/should query structure — both clauses must be present
        // so that prefix match and fuzzy match both fire against OpenSearch.
        String response = """
            {"hits":{"hits":[]}}
            """;
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        when(restTemplate.postForObject(anyString(), bodyCaptor.capture(), eq(String.class)))
                .thenReturn(response);

        service.findPhnsByName("Kasun");

        String body = bodyCaptor.getValue().toString();
        assertThat(body).contains("phrase_prefix");   // clause 1 — prefix match
        assertThat(body).contains("fuzziness");        // clause 2 — fuzzy match
        assertThat(body).contains("prefix_length");    // safety guardrail
        assertThat(body).contains("minimum_should_match");
    }

    @Test
    void findPhnsByName_skipsBlankPhn() throws Exception {
        String response = """
            {
              "hits": {
                "hits": [
                  {"_source":{"phn":"PHN001"}},
                  {"_source":{"phn":""}},
                  {"_source":{}}
                ]
              }
            }
            """;
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn(response);

        List<String> phns = service.findPhnsByName("Test");
        assertThat(phns).containsExactly("PHN001");
    }

    // ── indexPatient ──────────────────────────────────────────────────────────

    @Test
    void indexPatient_postsToCorrectUrl() {
        PatientDocument doc = PatientDocument.fromDicom("PHN001", "Perera^Kasun", "HIN001");
        service.indexPatient(doc);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).postForObject(urlCaptor.capture(), any(), eq(String.class));
        assertThat(urlCaptor.getValue()).contains("/_update/PHN001");
    }

    @Test
    void indexPatient_opensearchError_doesNotThrow() {
        // R-6: service now catches RestClientException specifically; use a concrete subtype.
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        PatientDocument doc = PatientDocument.fromDicom("PHN001", "Perera^Kasun", "HIN001");

        // Should not propagate exception
        service.indexPatient(doc);

        assertThat(service).isNotNull();
    }

    @Test
    void indexPatient_nullName_usesEmptyStrings() {
        PatientDocument doc = PatientDocument.fromDicom("PHN001", null, "HIN001");
        service.indexPatient(doc);

        verify(restTemplate).postForObject(anyString(), any(), eq(String.class));
    }
}
