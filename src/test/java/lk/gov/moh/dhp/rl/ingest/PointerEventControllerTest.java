package lk.gov.moh.dhp.rl.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.gov.moh.dhp.rl.config.TestSecurityConfig;
import lk.gov.moh.dhp.rl.consent.Consent;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice tests for PointerEventController — uses @WebMvcTest.
 *
 * Updated for H-8 fix: PointerFactory.ensure() now returns EnsureResult,
 * and PointerEventController no longer depends on PointerRepository.
 *
 * @Import(TestSecurityConfig.class) supplies a lightweight JwtDecoder so the
 * Spring Security oauth2ResourceServer config can start without a real JWK
 * endpoint.  Actual JWT content is provided by MockMvc's .with(jwt()).
 */
@WebMvcTest(PointerEventController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class PointerEventControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean PointerFactory pointerFactory;

    @Test
    void post_validRequest_newPointer_returns201() throws Exception {
        PointerEventRequest req = validRequest();
        // H-8: ensure() returns EnsureResult with created=true for a new pointer
        when(pointerFactory.ensure(any()))
                .thenReturn(EnsureResult.created(buildPointer("study-001", "1234567890")));

        mockMvc.perform(post("/pointer-events")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));
    }

    @Test
    void post_validRequest_existingPointer_returns200() throws Exception {
        PointerEventRequest req = validRequest();
        // H-8: ensure() returns EnsureResult with created=false for an existing pointer
        when(pointerFactory.ensure(any()))
                .thenReturn(EnsureResult.updated(buildPointer("study-001", "1234567890")));

        mockMvc.perform(post("/pointer-events")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    void post_missingPhn_returns400() throws Exception {
        // phn is blank — @NotBlank should reject
        PointerEventRequest invalid = new PointerEventRequest(
                "study-001", "", null, "ImagingStudy",
                "18747-6", "application/dicom+json", "http://wado",
                "HIN001", "Hosp", null, null);

        mockMvc.perform(post("/pointer-events")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_missingId_returns400() throws Exception {
        PointerEventRequest invalid = new PointerEventRequest(
                "", "1234567890", null, "ImagingStudy",
                "18747-6", "application/dicom+json", "http://wado",
                "HIN001", "Hosp", null, null);

        mockMvc.perform(post("/pointer-events")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_noJwt_returns401() throws Exception {
        mockMvc.perform(post("/pointer-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_convertsToNewPointerRequest_withNationalSyncedFalse() throws Exception {
        PointerEventRequest req = validRequest();
        when(pointerFactory.ensure(any()))
                .thenReturn(EnsureResult.created(buildPointer("study-001", "1234567890")));

        mockMvc.perform(post("/pointer-events")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)));

        verify(pointerFactory).ensure(argThat(npr -> !npr.nationalSynced()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PointerEventRequest validRequest() {
        return new PointerEventRequest(
                "study-001", "1234567890", null, "ImagingStudy",
                "18747-6", "application/dicom+json", "http://wado",
                "HIN001", "Hosp", null, null);
    }

    private Pointer buildPointer(String id, String phn) {
        return Pointer.builder()
                .id(id)
                .phn(phn)
                .resourceType("ImagingStudy")
                .contentUrl("http://wado")
                .facilityHin("HIN001")
                .facilityName("Hosp")
                .consent(Consent.defaultAccessible())
                .nationalSynced(false)
                .build();
    }

    // NOTE: buildPointer phn must be a valid 10-digit string per @Pattern validation
}
