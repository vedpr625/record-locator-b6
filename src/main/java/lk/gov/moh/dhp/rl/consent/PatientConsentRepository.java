package lk.gov.moh.dhp.rl.consent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Persistence layer for {@link PatientConsent}.
 *
 * <p>One row per PHN — operations are primary-key lookups, so all access is O(1).
 */
public interface PatientConsentRepository extends JpaRepository<PatientConsent, String> {

    /**
     * Returns the stored consent for {@code phn}, if any.
     * Returns empty when no consent event has ever been received for this PHN.
     */
    Optional<PatientConsent> findByPhn(String phn);
}
