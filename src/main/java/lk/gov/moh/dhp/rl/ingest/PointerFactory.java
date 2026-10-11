package lk.gov.moh.dhp.rl.ingest;

import lk.gov.moh.dhp.rl.consent.Consent;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import lk.gov.moh.dhp.rl.pointer.PointerRepository;
import lk.gov.moh.dhp.rl.search.OpenSearchServicePort;
import lk.gov.moh.dhp.rl.search.PatientDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * All pointers are created here — no other code path should call
 * pointerRepository.save() with a brand-new Pointer. Centralising
 * creation here is what ensures consent is always resolved consistently.
 *
 * Bundle 6 / edge profile: no FederationClient, no RabbitMQ ingest consumer.
 */
@Component
public class PointerFactory {

    private final PointerRepository pointerRepository;
    private final OpenSearchServicePort openSearchService;

    /**
     * When true (edge/Bundle-6 default), new pointers are created with
     * consent.accessible=true. Set to false when a Bundle 2a consent
     * webhook is active — in that case defaulting to inaccessible
     * (unknown) is safe because the webhook will immediately update consent.
     */
    private final boolean defaultAccessible;

    public PointerFactory(PointerRepository pointerRepository,
                          OpenSearchServicePort openSearchService,
                          @Value("${rl.consent.default-accessible:true}") boolean defaultAccessible) {
        this.pointerRepository = pointerRepository;
        this.openSearchService = openSearchService;
        this.defaultAccessible = defaultAccessible;
    }

    public Pointer create(NewPointerRequest request) {
        Consent consent = resolveKnownConsent(request.phn());

        Pointer pointer = Pointer.builder()
                .id(request.id())
                .phn(request.phn())
                .resourceType(request.resourceType())
                .docTypeLoinc(request.docTypeLoinc())
                .contentType(request.contentType())
                .contentUrl(request.contentUrl())
                .facilityHin(request.facilityHin())
                .facilityName(request.facilityName())
                .submissionSetId(request.submissionSetId())
                .nehrEncounterRef(request.nehrEncounterRef())
                .consent(consent)
                .nationalSynced(request.nationalSynced())
                .build();

        Pointer saved = pointerRepository.save(pointer);

        // Index the patient's name in OpenSearch so _name queries work.
        // This is a best-effort call — index failure must never fail pointer creation.
        if (request.patientName() != null) {
            openSearchService.indexPatient(
                    PatientDocument.fromDicom(request.phn(), request.patientName(), request.facilityHin()));
        }

        return saved;
    }

    /**
     * For idempotent creates - if a pointer with this id already exists,
     * touch its updatedAt and return it; otherwise create fresh.
     * Used when a study reappears after a restart replay from change 0.
     */
    public Pointer ensure(NewPointerRequest request) {
        return pointerRepository.findById(request.id())
                .map(existing -> {
                    existing.touch();
                    return pointerRepository.save(existing);
                })
                .orElseGet(() -> create(request));
    }

    /**
     * If we already have a consent decision on file for this patient
     * (from an earlier pointer), a brand new pointer inherits it immediately.
     *
     * For first-ever pointers: use defaultAccessible to control the initial
     * state. Edge/Bundle-6 deployments (no consent webhook) default to
     * accessible=true so newly-polled studies appear immediately.
     */
    private Consent resolveKnownConsent(String phn) {
        return pointerRepository.findTopByPhnOrderByUpdatedAtDesc(phn)
                .map(Pointer::consent)
                .orElse(defaultAccessible ? Consent.defaultAccessible() : Consent.unknown());
    }
}
