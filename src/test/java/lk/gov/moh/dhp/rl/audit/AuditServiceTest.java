package lk.gov.moh.dhp.rl.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for AuditService.
 * AuditService.writeAuditRecord() has @Transactional(REQUIRES_NEW) but this unit test
 * calls it directly without a proxy, so the annotation is transparent.
 * The integration smoke test exercises it via Spring's AOP proxy.
 */
class AuditServiceTest {

    AuditEventRepository repository;
    AuditService auditService;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        // In unit tests there is no Spring AOP proxy.  The constructor now accepts
        // the self reference via constructor injection (S4091-compliant).
        //
        // We build a two-instance chain: the inner instance is used only as the
        // `self` delegate and only has writeAuditRecord() called on it (which does
        // not use `self`), so passing null as its own self is safe.
        // Both instances share the same repository mock, so verify(repository)
        // captures saves made by either instance.
        AuditService selfDelegate = new AuditService(repository, null);
        auditService = new AuditService(repository, selfDelegate);
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

    // ── writeAuditRecord (resilience) ─────────────────────────────────────────

    @Test
    void writeAuditRecord_repositoryThrows_doesNotPropagate() {
        doThrow(new RuntimeException("DB down")).when(repository).save(any());

        // Must not throw — audit is best-effort
        auditService.writeAuditRecord("ITI-67", "PHN001", null, "SUCCESS");

        assertThat(auditService).isNotNull();
    }

    @Test
    void writeAuditRecord_savesAuditEventWithAllFields() {
        auditService.writeAuditRecord("CUSTOM", "PHN001", "doc-001", "FAILURE");

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
