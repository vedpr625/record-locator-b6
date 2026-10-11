package lk.gov.moh.dhp.rl.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.gov.moh.dhp.rl.config.TestSecurityConfig;
import lk.gov.moh.dhp.rl.consent.PatientConsentRepository;
import lk.gov.moh.dhp.rl.pointer.PointerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for ConsentEventController — uses @WebMvcTest.
 *
 * Updated for C-4: ConsentEventController now requires PatientConsentRepository
 * (to persist the consent decision before propagating to pointers), so it must
 * be mocked alongside PointerRepository.
 *
 * @Import(TestSecurityConfig.class) supplies a lightweight JwtDecoder so the
 * Spring Security oauth2ResourceServer config can start without a real JWK
 * endpoint.
 */
@WebMvcTest(ConsentEventController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class ConsentEventControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean PatientConsentRepository patientConsentRepository;
    @MockBean PointerRepository pointerRepository;

    @Test
    void post_validOptOut_returns204() throws Exception {
        when(pointerRepository.applyConsentByPhn(anyString(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(3);

        mockMvc.perform(post("/consent-events")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ConsentEventRequest("PHN001", false, false, null))))
                .andExpect(status().isNoContent());

        verify(pointerRepository).applyConsentByPhn(eq("PHN001"), eq(false), eq(false), isNull(), any());
    }

    @Test
    void post_validOptIn_returns204() throws Exception {
        when(pointerRepository.applyConsentByPhn(anyString(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(2);

        mockMvc.perform(post("/consent-events")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ConsentEventRequest("PHN001", true, false, "TREAT"))))
                .andExpect(status().isNoContent());

        verify(pointerRepository).applyConsentByPhn(eq("PHN001"), eq(true), eq(false), eq("TREAT"), any());
    }

    @Test
    void post_locationExcluded_passedToRepository() throws Exception {
        when(pointerRepository.applyConsentByPhn(anyString(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(5);

        mockMvc.perform(post("/consent-events")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ConsentEventRequest("PHN001", true, true, null))))
                .andExpect(status().isNoContent());

        verify(pointerRepository).applyConsentByPhn(eq("PHN001"), eq(true), eq(true), isNull(), any());
    }

    @Test
    void post_missingPhn_returns400() throws Exception {
        mockMvc.perform(post("/consent-events")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ConsentEventRequest("", false, false, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_noJwt_returns401() throws Exception {
        mockMvc.perform(post("/consent-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ConsentEventRequest("PHN001", true, false, null))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void post_noPointersExist_stillReturns204() throws Exception {
        // 0 rows updated is still success; consent event is stored for future pointers
        when(pointerRepository.applyConsentByPhn(anyString(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(0);

        mockMvc.perform(post("/consent-events")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ConsentEventRequest("NEWPHN", false, false, null))))
                .andExpect(status().isNoContent());
    }
}
