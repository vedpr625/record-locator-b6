package lk.gov.moh.dhp.rl.consent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Everything in this module ultimately answers one question: is this
 * patient's data currently accessible? We never call Bundle 2a on the
 * query path — RL-NFR2's 2-second budget doesn't leave room for a
 * network hop on every search. Instead the webhook keeps the
 * patient_consent table current and this class reads it in O(1).
 *
 * <p>Default behaviour when no consent record exists is controlled by
 * {@code rl.consent.default-accessible} (default: {@code true}):
 * <ul>
 *   <li>{@code true}  — opt-out model (Sri Lanka national deployment):
 *       data is accessible unless the patient has explicitly opted out.
 *       No consent record → accessible.</li>
 *   <li>{@code false} — opt-in / fail-closed model: data is withheld
 *       unless a consent record with {@code accessible=true} exists.
 *       No consent record → blocked.</li>
 * </ul>
 */
@Service
public class ConsentService {

    private final PatientConsentRepository patientConsentRepository;
    private final boolean defaultAccessible;

    public ConsentService(PatientConsentRepository patientConsentRepository,
                          @Value("${rl.consent.default-accessible:true}") boolean defaultAccessible) {
        this.patientConsentRepository = patientConsentRepository;
        this.defaultAccessible = defaultAccessible;
    }

    /**
     * Returns {@code true} when the patient's data is accessible.
     *
     * <p>If a {@code patient_consent} record exists for this PHN, its
     * {@code accessible} flag is the authoritative answer (opt-in or opt-out
     * has been explicitly recorded).
     *
     * <p>If no record exists, falls back to {@code rl.consent.default-accessible}:
     * {@code true} for Sri Lanka's opt-out deployment (accessible by default),
     * {@code false} for opt-in / fail-closed deployments.
     */
    public boolean isAccessible(String phn) {
        return patientConsentRepository.findByPhn(phn)
                .map(PatientConsent::isAccessible)
                .orElse(defaultAccessible);
    }
}
