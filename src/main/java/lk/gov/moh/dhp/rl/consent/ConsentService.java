package lk.gov.moh.dhp.rl.consent;

import org.springframework.stereotype.Service;

/**
 * Everything in this module ultimately answers one question: is this
 * patient's data currently accessible? We never call Bundle 2a on the
 * query path — RL-NFR2's 2-second budget doesn't leave room for a
 * network hop on every search. Instead the webhook keeps the
 * patient_consent table current and this class reads it in O(1).
 *
 * <p>Fail-closed design: if no consent record exists for the PHN (patient
 * never registered, consent webhook not yet active, or data missing)
 * {@code isAccessible} returns {@code false} — data is withheld rather
 * than accidentally exposed.
 */
@Service
public class ConsentService {

    private final PatientConsentRepository patientConsentRepository;

    public ConsentService(PatientConsentRepository patientConsentRepository) {
        this.patientConsentRepository = patientConsentRepository;
    }

    /**
     * Returns {@code true} when the patient has an active consent record
     * that allows access to their data.
     *
     * <p>Returns {@code false} (fail-closed) when:
     * <ul>
     *   <li>No consent record exists for this PHN.</li>
     *   <li>The patient has opted out ({@code accessible = false}).</li>
     * </ul>
     */
    public boolean isAccessible(String phn) {
        return patientConsentRepository.findByPhn(phn)
                .map(PatientConsent::isAccessible)
                .orElse(false);
    }
}
