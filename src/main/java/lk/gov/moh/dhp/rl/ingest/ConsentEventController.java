package lk.gov.moh.dhp.rl.ingest;

import jakarta.validation.Valid;
import lk.gov.moh.dhp.rl.pointer.PointerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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
 *   → 204 No Content (consent applied — rows updated, or no pointers exist yet)
 *   → 400            (validation failure)
 *   → 401/403        (JWT missing/expired, or mTLS check failed)
 *
 * Security:
 *   - JWT OAuth2 resource server always active (configured in SecurityConfig).
 *   - mTLS header check enforced when rl.mtls.enabled=true (MtlsHeaderFilter
 *     already covers the /consent-events path). Both layers must pass.
 *
 * Consent propagation:
 *   applyConsentByPhn() bulk-updates ALL pointer rows for the PHN in one
 *   JPQL UPDATE, sets nationalSynced=false on changed rows (so the national
 *   sync worker will re-push them), and handles location-exclusion by
 *   nulling facilityHin/facilityName inline in SQL.
 *
 * Idempotency: applying the same consent twice is safe — the UPDATE is
 * unconditional on the field values; a no-op update returns 0 rows changed
 * but still returns 204.
 *
 * Note: if no pointers exist for this PHN yet (consent arrives before the
 * first study), the update returns 0 rows. The consent is effectively lost
 * for those future pointers — PointerFactory.resolveKnownConsent() will
 * return the default. This is a known limitation for first-time opt-out;
 * see PointerFactory for the defaultAccessible flag.
 *
 * TODO: when domain-affinity federation is implemented, route consent events
 *       to the appropriate domain nodes.
 *       // TODO: route consent event to domain node based on PHN → domain mapping
 */
@RestController
@RequestMapping("/consent-events")
@Validated
public class ConsentEventController {

    private static final Logger log = LoggerFactory.getLogger(ConsentEventController.class);

    private final PointerRepository pointerRepository;

    public ConsentEventController(PointerRepository pointerRepository) {
        this.pointerRepository = pointerRepository;
    }

    @PostMapping
    public ResponseEntity<Void> handle(@Valid @RequestBody ConsentEventRequest request) {
        log.info("consent-event: phn={} accessible={} locationExcluded={}",
                request.phn(), request.accessible(), request.locationExcluded());

        int updated = pointerRepository.applyConsentByPhn(
                request.phn(),
                request.accessible(),
                request.locationExcluded(),
                request.purposeOfUse(),
                Instant.now());

        log.info("consent-event applied to {} pointer(s) for phn={}", updated, request.phn());

        return ResponseEntity.noContent().build();
    }
}
