package lk.gov.moh.dhp.rl.facility;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Facility JPA entity.
 * Covers the public constructor and all accessor methods.
 * The protected no-arg constructor is for JPA only and is not tested here.
 */
class FacilityTest {

    @Test
    void constructor_setsAllFields() {
        Facility facility = new Facility("HIN-001", "Colombo General Hospital", "WP");

        assertThat(facility.hin()).isEqualTo("HIN-001");
        assertThat(facility.name()).isEqualTo("Colombo General Hospital");
        assertThat(facility.provinceCode()).isEqualTo("WP");
    }

    @Test
    void hin_returnsCorrectValue() {
        Facility facility = new Facility("HIN-KANDY", "Kandy Hospital", "CP");
        assertThat(facility.hin()).isEqualTo("HIN-KANDY");
    }

    @Test
    void name_returnsCorrectValue() {
        Facility facility = new Facility("HIN-002", "Teaching Hospital Jaffna", "NP");
        assertThat(facility.name()).isEqualTo("Teaching Hospital Jaffna");
    }

    @Test
    void provinceCode_returnsCorrectValue() {
        Facility facility = new Facility("HIN-003", "Matara Hospital", "SP");
        assertThat(facility.provinceCode()).isEqualTo("SP");
    }

    @Test
    void constructor_withBlankValues_doesNotThrow() {
        Facility facility = new Facility("", "", "");
        assertThat(facility.hin()).isEmpty();
        assertThat(facility.name()).isEmpty();
        assertThat(facility.provinceCode()).isEmpty();
    }

    @Test
    void twoFacilitiesWithSameHin_areIndependent() {
        Facility f1 = new Facility("HIN-001", "Hospital A", "WP");
        Facility f2 = new Facility("HIN-001", "Hospital B", "CP");

        assertThat(f1.name()).isEqualTo("Hospital A");
        assertThat(f2.name()).isEqualTo("Hospital B");
        assertThat(f1.provinceCode()).isEqualTo("WP");
        assertThat(f2.provinceCode()).isEqualTo("CP");
    }
}
