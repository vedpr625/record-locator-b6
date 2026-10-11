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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * REST endpoint for hospital nodes to push imaging / document pointers to the
 * National Record Locator.
 *
 * POST /pointer-events
 *   Body: PointerEventRequest (JSON)
 *   → 201 Created  (Location: /{resourceType}/{id}, pointer was new)
 *   → 200 OK        (pointer already existed — idempotent)
 *   → 400           (validation failure — missing required fields)
 *   → 401/403       (JWT missing/expired, or mTLS check failed)
 *
 * Security:
 *   - JWT OAuth2 resource server always active (configured in SecurityConfig).
 *   - mTLS header check enforced when rl.mtls.enabled=true (MtlsHeaderFilter
 *     already covers the /pointer-events path). Both layers must pass.
 *
 * Idempotency: a hospital node may replay the same pointer (e.g. after a
 * network timeout). PointerFactory.ensure() handles deduplication — if a
 * pointer with the same id already exists, updatedAt is refreshed and 200
 * is returned instead of 201.
 *
 * TODO: when domain-affinity federation is implemented, route the pointer to
 *       the appropriate domain node in addition to local storage.
 *       // TODO: route to domain node based on facilityHin → domain mapping
 */
@RestController
@RequestMapping("/pointer-events")
@Validated
public class PointerEventController {

    private static final Logger log = LoggerFactory.getLogger(PointerEventController.class);

    private final PointerFactory pointerFactory;
    private final PointerRepository pointerRepository;

    public PointerEventController(PointerFactory pointerFactory,
                                   PointerRepository pointerRepository) {
        this.pointerFactory = pointerFactory;
        this.pointerRepository = pointerRepository;
    }

    @PostMapping
    public ResponseEntity<Void> handle(@Valid @RequestBody PointerEventRequest request) {
        log.info("pointer-event: id={} phn={} facility={}",
                request.id(), request.phn(), request.facilityHin());

        boolean existed = pointerRepository.existsById(request.id());

        pointerFactory.ensure(request.toNewPointerRequest());

        if (existed) {
            // Idempotent: already known, cursor touched by ensure()
            return ResponseEntity.ok().build();
        }

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/{type}/{id}")
                .buildAndExpand(request.resourceType(), request.id())
                .toUri();
        return ResponseEntity.created(location).build();
    }
}
