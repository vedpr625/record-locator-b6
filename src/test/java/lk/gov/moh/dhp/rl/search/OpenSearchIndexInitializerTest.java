package lk.gov.moh.dhp.rl.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.mock;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OpenSearchIndexInitializer — no Spring context.
 */
class OpenSearchIndexInitializerTest {

    RestTemplate restTemplate;
    ObjectMapper objectMapper = new ObjectMapper();
    OpenSearchIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        initializer = new OpenSearchIndexInitializer(restTemplate, objectMapper);
        ReflectionTestUtils.setField(initializer, "baseUrl", "http://opensearch:9200");
        ReflectionTestUtils.setField(initializer, "index", "rl-patients");
    }

    @Test
    void ensureIndex_indexExists_doesNotCreate() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.HEAD), any(), eq(Void.class)))
                .thenReturn(ResponseEntity.ok().build());

        initializer.ensureIndex();

        verify(restTemplate, never()).postForObject(anyString(), any(), eq(String.class));
    }

    @Test
    void ensureIndex_indexMissing_createsIndex() throws Exception {
        // HEAD returns 404 → index absent.
        // Must throw HttpClientErrorException.NotFound (subclass), not the base
        // HttpClientErrorException, because the production code catches the specific
        // inner class HttpClientErrorException.NotFound.
        when(restTemplate.exchange(anyString(), eq(HttpMethod.HEAD), any(), eq(Void.class)))
                .thenThrow(HttpClientErrorException.NotFound.create(
                        HttpStatus.NOT_FOUND, "Not Found",
                        org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0], null));
        // PUT returns acknowledged=true
        String ackResponse = "{\"acknowledged\":true}";
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn(ackResponse);

        initializer.ensureIndex();

        verify(restTemplate).postForObject(eq("http://opensearch:9200/rl-patients"), anyString(), eq(String.class));
    }

    @Test
    void ensureIndex_opensearchUnreachable_doesNotThrow() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.HEAD), any(), eq(Void.class)))
                .thenThrow(new RuntimeException("connection refused"));

        // Must not propagate exception — non-fatal
        initializer.ensureIndex();

        assertThat(initializer).isNotNull();
    }

    @Test
    void ensureIndex_createdBody_containsCorrectMappings() throws Exception {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.HEAD), any(), eq(Void.class)))
                .thenThrow(HttpClientErrorException.NotFound.create(
                        HttpStatus.NOT_FOUND, "Not Found",
                        org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0], null));
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenReturn("{\"acknowledged\":true}");

        initializer.ensureIndex();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).postForObject(anyString(), bodyCaptor.capture(), eq(String.class));

        String body = bodyCaptor.getValue();
        assertThat(body)
                .contains("\"phn\"")
                .contains("\"keyword\"")
                .contains("\"givenName\"")
                .contains("\"familyName\"")
                .contains("\"fullName\"")
                .contains("\"facilityHin\"");
    }
}
