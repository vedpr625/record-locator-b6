package lk.gov.moh.dhp.rl.consent;

import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import lk.gov.moh.dhp.rl.audit.AuditService;
import org.springframework.stereotype.Component;

/**
 * Every endpoint that touches patient data goes through here. Search
 * endpoints call checkSearch and get a boolean back so they can build
 * their own empty response; retrieve endpoints (ITI-68, and the plain
 * DocumentReference read) call checkRetrieve and let it throw.
 *
 * We deliberately return a 404 instead of a 403 when blocking a
 * retrieve - otherwise an unauthorised caller could tell the
 * difference between "this document doesn't exist" and "this patient
 * opted out", which leaks information we don't want to leak.
 */
@Component
public class ConsentGate {

    private final ConsentService consentService;
    private final AuditService auditService;

    public ConsentGate(ConsentService consentService, AuditService auditService) {
        this.consentService = consentService;
        this.auditService = auditService;
    }

    public boolean checkSearch(String phn, String transaction) {
        if (phn == null || phn.isBlank()) {
            return false;
        }
        if (!consentService.isOptedOut(phn)) {
            return false;
        }
        auditService.recordConsentBlocked(phn, transaction);
        return true;
    }

    public void checkRetrieve(String phn, String resourceId, String resourceType, String transaction) {
        if (phn == null || phn.isBlank()) {
            // Blank phn means the pointer has no patient linkage — block rather
            // than fail open. Callers should never reach this with a real pointer,
            // but if they do, 404 is the right response (same as opted-out).
            throw new ResourceNotFoundException(resourceType + "/" + resourceId);
        }
        if (!consentService.isOptedOut(phn)) {
            return;
        }
        auditService.recordConsentBlocked(phn, transaction);
        throw new ResourceNotFoundException(resourceType + "/" + resourceId);
    }
}
