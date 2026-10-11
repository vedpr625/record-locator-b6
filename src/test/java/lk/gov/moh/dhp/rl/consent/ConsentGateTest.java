package lk.gov.moh.dhp.rl.consent;

import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import lk.gov.moh.dhp.rl.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConsentGate — no Spring context.
 */
class ConsentGateTest {

    ConsentService consentService;
    AuditService auditService;
    ConsentGate gate;

    @BeforeEach
    void setUp() {
        consentService = Mockito.mock(ConsentService.class);
        auditService = Mockito.mock(AuditService.class);
        gate = new ConsentGate(consentService, auditService);
    }

    // ── checkSearch ──────────────────────────────────────────────────────────

    @Test
    void checkSearch_nullPhn_returnsFalse() {
        assertThat(gate.checkSearch(null, "ITI-67")).isFalse();
        verifyNoInteractions(consentService, auditService);
    }

    @Test
    void checkSearch_blankPhn_returnsFalse() {
        assertThat(gate.checkSearch("  ", "ITI-67")).isFalse();
    }

    @Test
    void checkSearch_notOptedOut_returnsFalse() {
        when(consentService.isOptedOut("PHN123")).thenReturn(false);
        assertThat(gate.checkSearch("PHN123", "ITI-67")).isFalse();
        verifyNoInteractions(auditService);
    }

    @Test
    void checkSearch_optedOut_returnsTrueAndAudits() {
        when(consentService.isOptedOut("PHN123")).thenReturn(true);
        assertThat(gate.checkSearch("PHN123", "ITI-67")).isTrue();
        verify(auditService).recordConsentBlocked("PHN123", "ITI-67");
    }

    // ── checkRetrieve ────────────────────────────────────────────────────────

    @Test
    void checkRetrieve_nullPhn_throws404() {
        assertThatThrownBy(() ->
            gate.checkRetrieve(null, "doc1", "DocumentReference", "ITI-67"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void checkRetrieve_notOptedOut_doesNotThrow() {
        when(consentService.isOptedOut("PHN123")).thenReturn(false);
        // should complete without throwing
        gate.checkRetrieve("PHN123", "doc1", "DocumentReference", "ITI-67");
    }

    @Test
    void checkRetrieve_optedOut_throws404AndAudits() {
        when(consentService.isOptedOut("PHN123")).thenReturn(true);
        assertThatThrownBy(() ->
            gate.checkRetrieve("PHN123", "doc1", "DocumentReference", "ITI-67"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("DocumentReference/doc1");
        verify(auditService).recordConsentBlocked("PHN123", "ITI-67");
    }
}
