package lk.gov.moh.dhp.rl.consent;

import lk.gov.moh.dhp.rl.pointer.PointerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ConsentService — thin delegation to PointerRepository.
 * No Spring context; pure mock-based.
 */
class ConsentServiceTest {

    PointerRepository pointerRepository;
    ConsentService consentService;

    @BeforeEach
    void setUp() {
        pointerRepository = Mockito.mock(PointerRepository.class);
        consentService = new ConsentService(pointerRepository);
    }

    @Test
    void isOptedOut_delegatesToRepository_returnsFalse() {
        when(pointerRepository.existsInaccessibleByPhn("PHN001")).thenReturn(false);

        assertThat(consentService.isOptedOut("PHN001")).isFalse();
    }

    @Test
    void isOptedOut_delegatesToRepository_returnsTrue() {
        when(pointerRepository.existsInaccessibleByPhn("PHN002")).thenReturn(true);

        assertThat(consentService.isOptedOut("PHN002")).isTrue();
    }

    @Test
    void isOptedOut_nullPhn_delegatesAnyway() {
        when(pointerRepository.existsInaccessibleByPhn(null)).thenReturn(false);

        assertThat(consentService.isOptedOut(null)).isFalse();
    }
}
