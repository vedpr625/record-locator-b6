package lk.gov.moh.dhp.rl.consent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ConsentService — delegates to PatientConsentRepository.
 *
 * The behaviour when no consent record exists is controlled by
 * rl.consent.default-accessible:
 *   true  = opt-out model (Sri Lanka): accessible by default, blocked on
 *           explicit opt-out. No record → accessible.
 *   false = opt-in / fail-closed: blocked unless explicitly opted in.
 *           No record → inaccessible.
 */
class ConsentServiceTest {

    PatientConsentRepository patientConsentRepository;

    @BeforeEach
    void setUp() {
        patientConsentRepository = mock(PatientConsentRepository.class);
    }

    // --- explicit consent record present (same in both models) ---

    @Test
    void isAccessible_whenConsentRowExistsAndAccessibleTrue_returnsTrue() {
        PatientConsent pc = PatientConsent.of("PHN001", true, false, null);
        when(patientConsentRepository.findByPhn("PHN001")).thenReturn(Optional.of(pc));

        ConsentService service = new ConsentService(patientConsentRepository, true);
        assertThat(service.isAccessible("PHN001")).isTrue();
    }

    @Test
    void isAccessible_whenConsentRowExistsAndAccessibleFalse_returnsFalse() {
        PatientConsent pc = PatientConsent.of("PHN002", false, false, null);
        when(patientConsentRepository.findByPhn("PHN002")).thenReturn(Optional.of(pc));

        ConsentService service = new ConsentService(patientConsentRepository, true);
        assertThat(service.isAccessible("PHN002")).isFalse();
    }

    // --- no consent record: opt-out model (Sri Lanka, default-accessible=true) ---

    @Test
    void isAccessible_whenNoConsentRow_optOut_returnsTrue() {
        // Opt-out (Sri Lanka): no record means the patient has not opted out,
        // so data is accessible by default.
        when(patientConsentRepository.findByPhn("PHN003")).thenReturn(Optional.empty());

        ConsentService service = new ConsentService(patientConsentRepository, true);
        assertThat(service.isAccessible("PHN003")).isTrue();
    }

    @Test
    void isAccessible_whenNoConsentRow_optIn_returnsFalse() {
        // Opt-in / fail-closed: no record means consent not yet received,
        // data must be withheld.
        when(patientConsentRepository.findByPhn("PHN004")).thenReturn(Optional.empty());

        ConsentService service = new ConsentService(patientConsentRepository, false);
        assertThat(service.isAccessible("PHN004")).isFalse();
    }

    // --- null PHN edge case ---

    @Test
    void isAccessible_nullPhn_optOut_returnsTrue() {
        when(patientConsentRepository.findByPhn(null)).thenReturn(Optional.empty());

        ConsentService service = new ConsentService(patientConsentRepository, true);
        assertThat(service.isAccessible(null)).isTrue();
    }

    @Test
    void isAccessible_nullPhn_optIn_returnsFalse() {
        when(patientConsentRepository.findByPhn(null)).thenReturn(Optional.empty());

        ConsentService service = new ConsentService(patientConsentRepository, false);
        assertThat(service.isAccessible(null)).isFalse();
    }
}
