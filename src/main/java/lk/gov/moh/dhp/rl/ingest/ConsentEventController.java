package lk.gov.moh.dhp.rl.ingest;

import jakarta.validation.Valid;
import lk.gov.moh.dhp.rl.consent.PatientConsent;
import lk.gov.moh.dhp.rl.consent.PatientConsentRepository;
import lk.gov.moh.dhp.rl.pointer.PointerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * REST endpoint for Bundle 2a (the national consent service) to push consent
 * decisions to the National Record Locator.
 *
 * POST /consent-events
 *   Body: ConsentEventRequest (JSON)
 *   → 204 No Content (consent applied)
 *   → 400            (validation failure)
 *   → 401/403        (JWT missing/expired, or mTLS check failed)
 *
 * Security:
 *   - JWT OAuth2 resource server always active (configured in SecurityConfig).
 *   - mTLS header check enforced when rl.security.mtls-header-enabled=true.
 *
 * Consent propagation:
 *   1. Upserts ONE row into patient_consent (keyed by PHN) so the consent
 *      decision is durably stored even when no pointers exist yet.
 *   2. Bulk-updates ALL existing pointer rows for the PHN via
 *      applyConsentByPhn() so they reflect the new decision immediately.
 *
 * This two-step approach eliminates the race condition where a consent event
 * arriving before the patient's first study was silently lost: PointerFactory
 * now reads from patient_consent on new pointer creation rather than scanning
 * existing pointer rows.
 *
 * Idempotency: applying the same consent twice is safe — both steps are
 * unconditional on prior values; 0 rows updated in step 2 is normal for a
 * first-ever consent event.
 */
@RestController
@RequestMapping("/consent-events")
@Validated
public class ConsentEventController {

    private static final Logger log = LoggerFactory.getLogger(ConsentEventController.class);

    private final PatientConsentRepository patientConsentRepository;
    private final PointerRepository pointerRepository;

    public ConsentEventController(PatientConsentRepository patientConsentRepository,
                                   PointerRepository pointerRepository) {
        this.patientConsentRepository = patientConsentRepository;
        this.pointerRepository = pointerRepository;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Void> handle(@Valid @RequestBody ConsentEventRequest request) {
        log.info("consent-event: phn=*** accessible={} locationExcluded={}",
                request.accessible(), request.locationExcluded());

        // Step 1: persist the consent decision regardless of whether any pointers
        // exist.  This row is the canonical source of truth that PointerFactory
        // reads when creating a new pointer for this PHN.
        PatientConsent record = PatientConsent.of(
                request.phn(),
                request.accessible(),
                request.locationExcluded(),
                request.purposeOfUse());
        patientConsentRepository.save(record);

        // Step 2: propagate to existing pointer rows so live search results change
        // immediately without waiting for pointer recreation.
        int updated = pointerRepository.applyConsentByPhn(
                request.phn(),
                request.accessible(),
                request.locationExcluded(),
                request.purposeOfUse(),
                Instant.now());

        log.info("consent-event saved for phn=***; {} existing pointer(s) updated", updated);

        return ResponseEntity.noContent().build();
    }
}
