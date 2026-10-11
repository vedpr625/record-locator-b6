package lk.gov.moh.dhp.rl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring Boot integration smoke test.
 *
 * Loads the full application context against H2 (test profile), verifies:
 * 1. Context starts (implicit — if it doesn't, every @Test fails)
 * 2. Actuator /health is reachable without auth
 * 3. FHIR DocumentReference search requires auth
 * 4. /pointer-events requires auth
 * 5. /consent-events requires auth
 * 6. /pointer-events with JWT creates a new pointer (201)
 * 7. FHIR search with JWT and PHN returns a valid searchset bundle
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SmokeTest {

    @Autowired
    MockMvc mockMvc;

    // ── Actuator ──────────────────────────────────────────────────────────────

    @Test
    void actuatorHealth_isReachableWithoutAuth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    // ── Unauthenticated ───────────────────────────────────────────────────────

    @Test
    void fhirSearch_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/fhir/DocumentReference?patient=PHN001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pointerEvents_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/pointer-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"id":"doc-001","phn":"PHN001","resourceType":"DocumentReference",
                             "contentUrl":"http://example.com","facilityHin":"HIN001"}
                            """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void consentEvents_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/consent-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"phn":"PHN001","accessible":false,
                             "locationExcluded":false}
                            """))
                .andExpect(status().isUnauthorized());
    }

    // ── Authenticated ─────────────────────────────────────────────────────────

    @Test
    void pointerEvents_withJwt_creates201() throws Exception {
        mockMvc.perform(post("/pointer-events")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"id":"smoke-doc-001","phn":"PHN-SMOKE","resourceType":"DocumentReference",
                             "contentUrl":"http://example.com/smoke","facilityHin":"HIN-SMOKE",
                             "facilityName":"Smoke Hospital"}
                            """))
                .andExpect(status().isCreated());
    }

    @Test
    void pointerEvents_withJwt_idempotentReturns200() throws Exception {
        String payload = """
                {"id":"smoke-doc-idem","phn":"PHN-IDEM","resourceType":"DocumentReference",
                 "contentUrl":"http://example.com/idem","facilityHin":"HIN-IDEM"}
                """;

        // First call → 201
        mockMvc.perform(post("/pointer-events")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        // Second call with same id → 200
        mockMvc.perform(post("/pointer-events")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());
    }

    @Test
    void consentEvents_withJwt_returns204() throws Exception {
        mockMvc.perform(post("/consent-events")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"phn":"PHN-CONSENT","accessible":false,"locationExcluded":false}
                            """))
                .andExpect(status().isNoContent());
    }

    @Test
    void fhirSearch_withJwt_returns200AndSearchset() throws Exception {
        mockMvc.perform(get("/fhir/DocumentReference?patient=PHN-NOBODY")
                        .with(jwt())
                        .accept("application/fhir+json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("Bundle"))
                .andExpect(jsonPath("$.type").value("searchset"));
    }

    @Test
    void fhirSearch_invalidStatus_stillReturns200() throws Exception {
        // Unknown status value falls back to CURRENT — no error
        mockMvc.perform(get("/fhir/DocumentReference?patient=PHN-NOBODY&status=unknown-value")
                        .with(jwt())
                        .accept("application/fhir+json"))
                .andExpect(status().isOk());
    }
}
