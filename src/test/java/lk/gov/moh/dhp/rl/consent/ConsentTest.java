package lk.gov.moh.dhp.rl.consent;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Consent value object — no Spring context required.
 */
class ConsentTest {

    @Test
    void defaultAccessible_isAccessible() {
        Consent c = Consent.defaultAccessible();
        assertThat(c.isAccessible()).isTrue();
        assertThat(c.isLocationExcluded()).isFalse();
        assertThat(c.source()).isEqualTo(Consent.Source.UNKNOWN);
    }

    @Test
    void unknown_isNotAccessible() {
        Consent c = Consent.unknown();
        assertThat(c.isAccessible()).isFalse();
    }

    @Test
    void from_optedOut_setsInaccessible() {
        Consent c = Consent.from(true, false, Set.of());
        assertThat(c.isAccessible()).isFalse();
        assertThat(c.source()).isEqualTo(Consent.Source.BUNDLE_2A);
    }

    @Test
    void from_optedIn_setsAccessible() {
        Consent c = Consent.from(false, false, Set.of());
        assertThat(c.isAccessible()).isTrue();
    }

    @Test
    void from_locationExcluded() {
        Consent c = Consent.from(false, true, Set.of());
        assertThat(c.isLocationExcluded()).isTrue();
    }

    @Test
    void allows_nullPurpose_returnsTrue() {
        Consent c = Consent.from(false, false, Set.of("TREAT"));
        assertThat(c.allows(null)).isTrue();
    }

    @Test
    void allows_matchingPurpose_returnsTrue() {
        Consent c = Consent.from(false, false, Set.of("TREAT", "HPAYMT"));
        assertThat(c.allows("TREAT")).isTrue();
        assertThat(c.allows("HPAYMT")).isTrue();
    }

    @Test
    void allows_nonMatchingPurpose_returnsFalse() {
        Consent c = Consent.from(false, false, Set.of("TREAT"));
        assertThat(c.allows("HPAYMT")).isFalse();
    }

    @Test
    void allows_noPurposeRestriction_allowsAll() {
        // no purpose-of-use set = unrestricted = any purpose is allowed
        Consent c = Consent.from(false, false, Set.of());
        assertThat(c.allows("TREAT")).isTrue();
        assertThat(c.allows("ANYTHING")).isTrue();
    }

    @Test
    void allows_substringShouldNotMatch() {
        // "TREAT" must not match "TREATMENT" (was a bug candidate before Set.of split)
        Consent c = Consent.from(false, false, Set.of("TREATMENT"));
        assertThat(c.allows("TREAT")).isFalse();
    }

    @Test
    void purposeOfUseRaw_joinsWithComma() {
        Consent c = Consent.from(false, false, Set.of("TREAT", "HPAYMT"));
        String raw = c.purposeOfUseRaw();
        assertThat(raw).contains("TREAT");
        assertThat(raw).contains("HPAYMT");
        assertThat(raw).contains(",");
    }
}
