package lk.gov.moh.dhp.rl.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ITI-66/67/68 all require the responder to be able to record an
 * audit event. We never let an audit write failure fail the request
 * it's auditing for - if this throws, we log it and move on.
 *
 * <p>Self-injection pattern: {@code self} is the Spring AOP proxy for this bean.
 * Calling {@code writeAuditRecord()} via {@code self} lets Spring intercept the
 * {@code @Transactional(REQUIRES_NEW)} on that method; a direct {@code this.writeAuditRecord()}
 * call bypasses the proxy and the transaction annotation has no effect (S3252).
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;

    /**
     * Lazily injected proxy of this same bean so that {@code record()} is called
     * through the AOP proxy and @Transactional(REQUIRES_NEW) is honoured.
     * @Lazy breaks the circular-dependency cycle that self-injection would otherwise cause.
     */
    @Autowired
    @Lazy
    private AuditService self;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public void recordSearch(String transaction, String phn, boolean success) {
        self.writeAuditRecord(transaction, phn, null, success ? "SUCCESS" : "FAILURE");
    }

    public void recordRetrieve(String phn, String resourceId, boolean success) {
        self.writeAuditRecord("ITI-68", phn, resourceId, success ? "SUCCESS" : "FAILURE");
    }

    public void recordConsentBlocked(String phn, String transaction) {
        self.writeAuditRecord(transaction, phn, null, "CONSENT_BLOCKED");
    }

    /**
     * public, not package-private — Spring's @Transactional relies on
     * an AOP proxy that only intercepts public method calls from
     * outside the class. REQUIRES_NEW: this audit write must succeed or
     * fail independently of whatever transaction the caller is in.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeAuditRecord(String eventType, String phn, String resourceId, String outcome) {
        try {
            repository.save(AuditEvent.of(eventType, phn, resourceId, outcome));
        } catch (Exception e) {
            log.warn("failed to write audit event type={} phn=***: {}", eventType, e.getMessage());
        }
    }
}
