package lk.gov.moh.dhp.rl.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ITI-66/67/68 all require the responder to be able to record an
 * audit event. We never let an audit write failure fail the request
 * it's auditing for - if this throws, we log it and move on.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public void recordSearch(String transaction, String phn, boolean success) {
        record(transaction, phn, null, success ? "SUCCESS" : "FAILURE");
    }

    public void recordRetrieve(String phn, String resourceId, boolean success) {
        record("ITI-68", phn, resourceId, success ? "SUCCESS" : "FAILURE");
    }

    public void recordConsentBlocked(String phn, String transaction) {
        record(transaction, phn, null, "CONSENT_BLOCKED");
    }

    /**
     * public, not package-private — Spring's @Transactional relies on
     * an AOP proxy that only intercepts public method calls from
     * outside the class. REQUIRES_NEW: this audit write must succeed or
     * fail independently of whatever transaction the caller is in.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String eventType, String phn, String resourceId, String outcome) {
        try {
            repository.save(AuditEvent.of(eventType, phn, resourceId, outcome));
        } catch (Exception e) {
            log.warn("failed to write audit event type={} phn={}: {}", eventType, phn, e.getMessage());
        }
    }
}
