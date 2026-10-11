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
 * Updated for C-3 fix: ConsentService now uses PatientConsentRepository
 * (not PointerRepository), the method is isAccessible() (not isOptedOut()),
 * and the semantics are fail-closed (returns false when no record exists).
 */
class ConsentServiceTest {

    PatientConsentRepository patientConsentRepository;
    ConsentService consentService;

    @BeforeEach
    void setUp() {
        patientConsentRepository = mock(PatientConsentRepository.class);
        consentService = new ConsentService(patientConsentRepository);
    }

    @Test
    void isAccessible_whenConsentRowExistsAndAccessibleTrue_returnsTrue() {
        PatientConsent pc = PatientConsent.of("PHN001", true, false, null);
        when(patientConsentRepository.findByPhn("PHN001")).thenReturn(Optional.of(pc));

        assertThat(consentService.isAccessible("PHN001")).isTrue();
    }

    @Test
    void isAccessible_whenConsentRowExistsAndAccessibleFalse_returnsFalse() {
        PatientConsent pc = PatientConsent.of("PHN002", false, false, null);
        when(patientConsentRepository.findByPhn("PHN002")).thenReturn(Optional.of(pc));

        assertThat(consentService.isAccessible("PHN002")).isFalse();
    }

    @Test
    void isAccessible_whenNoConsentRow_returnsFalseFailClosed() {
        // C-3: fail-closed — unknown consent = inaccessible, not accessible.
        // This is the critical behaviour: if consent hasn't arrived yet,
        // we must not expose PHI.
        when(patientConsentRepository.findByPhn("PHN003")).thenReturn(Optional.empty());

        assertThat(consentService.isAccessible("PHN003")).isFalse();
    }

    @Test
    void isAccessible_nullPhn_delegatesAndReturnsFalse() {
        when(patientConsentRepository.findByPhn(null)).thenReturn(Optional.empty());

        assertThat(consentService.isAccessible(null)).isFalse();
    }
}
