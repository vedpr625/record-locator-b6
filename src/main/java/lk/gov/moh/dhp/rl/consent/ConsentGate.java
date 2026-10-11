package lk.gov.moh.dhp.rl.consent;

import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import lk.gov.moh.dhp.rl.audit.AuditService;
import org.springframework.stereotype.Component;

/**
 * Every endpoint that touches patient data goes through here. Search
 * endpoints call {@link #checkSearch} and get a boolean back so they can build
 * their own empty response; retrieve endpoints (ITI-68, and the plain
 * DocumentReference read) call {@link #checkRetrieve} and let it throw.
 *
 * <p>We deliberately return a 404 instead of a 403 when blocking a
 * retrieve — otherwise an unauthorised caller could distinguish between
 * "this document doesn't exist" and "this patient opted out", leaking
 * information we don't want to leak.
 *
 * <p>Fail-closed: {@link ConsentService#isAccessible(String)} returns
 * {@code false} when no consent record exists, so the gate blocks rather
 * than opens on missing data.
 */
@Component
public class ConsentGate {

    private final ConsentService consentService;
    private final AuditService auditService;

    public ConsentGate(ConsentService consentService, AuditService auditService) {
        this.consentService = consentService;
        this.auditService = auditService;
    }

    /**
     * Returns {@code true} when the search should be blocked (patient opted out
     * or no consent record exists).
     */
    public boolean checkSearch(String phn, String transaction) {
        if (phn == null || phn.isBlank()) {
            return false;
        }
        if (!consentService.isAccessible(phn)) {
            auditService.recordConsentBlocked(phn, transaction);
            return true;
        }
        return false;
    }

    /**
     * Throws {@link ResourceNotFoundException} when the retrieve should be blocked
     * (patient opted out or no consent record exists for this PHN).
     */
    public void checkRetrieve(String phn, String resourceId, String resourceType, String transaction) {
        if (phn == null || phn.isBlank()) {
            // Blank phn means the pointer has no patient linkage — block rather
            // than fail open. Callers should never reach this with a real pointer,
            // but if they do, 404 is the right response (same as opted-out).
            throw new ResourceNotFoundException(resourceType + "/" + resourceId);
        }
        if (!consentService.isAccessible(phn)) {
            auditService.recordConsentBlocked(phn, transaction);
            throw new ResourceNotFoundException(resourceType + "/" + resourceId);
        }
    }
}
