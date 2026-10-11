package lk.gov.moh.dhp.rl.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuditService.
 * AuditService.record() has @Transactional(REQUIRES_NEW) but this unit test
 * calls it directly without a proxy, so the annotation is transparent.
 * The integration smoke test exercises it via Spring's AOP proxy.
 */
class AuditServiceTest {

    AuditEventRepository repository;
    AuditService auditService;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(AuditEventRepository.class);
        auditService = new AuditService(repository);
    }

    // ── recordSearch ──────────────────────────────────────────────────────────

    @Test
    void recordSearch_success_savesSuccessOutcome() {
        auditService.recordSearch("ITI-67", "PHN001", true);

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(cap.capture());

        AuditEvent event = cap.getValue();
        assertThat(field(event, "eventType")).isEqualTo("ITI-67");
        assertThat(field(event, "phn")).isEqualTo("PHN001");
        assertThat(field(event, "outcome")).isEqualTo("SUCCESS");
        assertThat(field(event, "resourceId")).isNull();
    }

    @Test
    void recordSearch_failure_savesFailureOutcome() {
        auditService.recordSearch("ITI-66", "PHN002", false);

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(cap.capture());
        assertThat(field(cap.getValue(), "outcome")).isEqualTo("FAILURE");
    }

    // ── recordRetrieve ────────────────────────────────────────────────────────

    @Test
    void recordRetrieve_success_savesIti68WithResourceId() {
        auditService.recordRetrieve("PHN001", "doc-001", true);

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(cap.capture());

        AuditEvent event = cap.getValue();
        assertThat(field(event, "eventType")).isEqualTo("ITI-68");
        assertThat(field(event, "phn")).isEqualTo("PHN001");
        assertThat(field(event, "resourceId")).isEqualTo("doc-001");
        assertThat(field(event, "outcome")).isEqualTo("SUCCESS");
    }

    // ── recordConsentBlocked ──────────────────────────────────────────────────

    @Test
    void recordConsentBlocked_savesConsentBlockedOutcome() {
        auditService.recordConsentBlocked("PHN001", "ITI-67");

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(cap.capture());
        assertThat(field(cap.getValue(), "outcome")).isEqualTo("CONSENT_BLOCKED");
        assertThat(field(cap.getValue(), "eventType")).isEqualTo("ITI-67");
    }

    // ── record (resilience) ───────────────────────────────────────────────────

    @Test
    void record_repositoryThrows_doesNotPropagate() {
        doThrow(new RuntimeException("DB down")).when(repository).save(any());

        // Must not throw — audit is best-effort
        auditService.record("ITI-67", "PHN001", null, "SUCCESS");
    }

    @Test
    void record_savesAuditEventWithAllFields() {
        auditService.record("CUSTOM", "PHN001", "doc-001", "FAILURE");

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(cap.capture());

        AuditEvent event = cap.getValue();
        assertThat(field(event, "eventType")).isEqualTo("CUSTOM");
        assertThat(field(event, "phn")).isEqualTo("PHN001");
        assertThat(field(event, "resourceId")).isEqualTo("doc-001");
        assertThat(field(event, "outcome")).isEqualTo("FAILURE");
        assertThat(field(event, "recordedAt")).isNotNull();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Object field(AuditEvent event, String name) {
        return ReflectionTestUtils.getField(event, name);
    }
}
