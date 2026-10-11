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
    void checkSearch_accessible_returnsFalse() {
        // C-3: isAccessible() returns true when patient CAN be accessed → gate does NOT block
        when(consentService.isAccessible("PHN123")).thenReturn(true);
        assertThat(gate.checkSearch("PHN123", "ITI-67")).isFalse();
        verifyNoInteractions(auditService);
    }

    @Test
    void checkSearch_inaccessible_returnsTrueAndAudits() {
        // isAccessible() returns false when patient is opted out or no consent record
        when(consentService.isAccessible("PHN123")).thenReturn(false);
        assertThat(gate.checkSearch("PHN123", "ITI-67")).isTrue();
        verify(auditService).recordConsentBlocked("PHN123", "ITI-67");
    }

    @Test
    void checkSearch_failClosed_noConsentRecord_returnsTrueAndAudits() {
        // Fail-closed: isAccessible() returns false when no consent record → gate blocks
        when(consentService.isAccessible("PHN123")).thenReturn(false);
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
    void checkRetrieve_accessible_doesNotThrow() {
        // isAccessible() returns true → patient can be retrieved → no exception
        when(consentService.isAccessible("PHN123")).thenReturn(true);
        // should complete without throwing
        gate.checkRetrieve("PHN123", "doc1", "DocumentReference", "ITI-67");
    }

    @Test
    void checkRetrieve_inaccessible_throws404AndAudits() {
        // isAccessible() returns false → patient opted out or no consent record → block
        when(consentService.isAccessible("PHN123")).thenReturn(false);
        assertThatThrownBy(() ->
            gate.checkRetrieve("PHN123", "doc1", "DocumentReference", "ITI-67"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("DocumentReference/doc1");
        verify(auditService).recordConsentBlocked("PHN123", "ITI-67");
    }
}
