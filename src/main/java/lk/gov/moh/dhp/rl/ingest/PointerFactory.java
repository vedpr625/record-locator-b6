package lk.gov.moh.dhp.rl.ingest;

import lk.gov.moh.dhp.rl.consent.Consent;
import lk.gov.moh.dhp.rl.consent.PatientConsentRepository;
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
    private final PatientConsentRepository patientConsentRepository;
    private final OpenSearchServicePort openSearchService;

    /**
     * When true (edge/Bundle-6 default), new pointers are created with
     * consent.accessible=true when no patient_consent row exists yet.
     * Set to false when a Bundle 2a consent webhook is active — in that case
     * defaulting to inaccessible (unknown) is safe because the webhook will
     * write a patient_consent row before (or very shortly after) the pointer.
     */
    private final boolean defaultAccessible;

    public PointerFactory(PointerRepository pointerRepository,
                          PatientConsentRepository patientConsentRepository,
                          OpenSearchServicePort openSearchService,
                          @Value("${rl.consent.default-accessible:true}") boolean defaultAccessible) {
        this.pointerRepository = pointerRepository;
        this.patientConsentRepository = patientConsentRepository;
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
     *
     * <p>H-8: returns {@link EnsureResult} so the caller can distinguish a
     * new pointer (HTTP 201) from an existing one (HTTP 200) without a
     * separate {@code existsById} round-trip.
     */
    public EnsureResult ensure(NewPointerRequest request) {
        return pointerRepository.findById(request.id())
                .map(existing -> {
                    existing.touch();
                    return EnsureResult.updated(pointerRepository.save(existing));
                })
                .orElseGet(() -> EnsureResult.created(create(request)));
    }

    /**
     * Resolves the consent to assign to a brand-new pointer for {@code phn}.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Check {@code patient_consent} table (keyed by PHN, O(1)).
     *       This row is written by {@link ConsentEventController} <em>before</em>
     *       any pointer rows exist, so a prior opt-out from Bundle 2a is honoured
     *       even for a patient's very first study.</li>
     *   <li>Fall back to {@code defaultAccessible}: true for edge/Bundle-6 deployments
     *       that have no consent webhook; false for national deployments where
     *       Bundle 2a is the authoritative source.</li>
     * </ol>
     */
    private Consent resolveKnownConsent(String phn) {
        return patientConsentRepository.findByPhn(phn)
                .map(pc -> pc.toConsent())
                .orElse(defaultAccessible ? Consent.defaultAccessible() : Consent.unknown());
    }
}
