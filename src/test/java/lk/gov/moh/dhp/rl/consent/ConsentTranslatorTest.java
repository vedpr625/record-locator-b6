package lk.gov.moh.dhp.rl.consent;

import org.hl7.fhir.r4.model.Coding;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ConsentTranslator — translates FHIR Consent to domain Consent.
 */
class ConsentTranslatorTest {

    private final ConsentTranslator translator = new ConsentTranslator();

    @Test
    void translate_denyProvision_setsInaccessible() {
        org.hl7.fhir.r4.model.Consent fhir = new org.hl7.fhir.r4.model.Consent();
        var provision = new org.hl7.fhir.r4.model.Consent.provisionComponent();
        provision.setType(org.hl7.fhir.r4.model.Consent.ConsentProvisionType.DENY);
        fhir.setProvision(provision);

        Consent result = translator.translate(fhir);

        assertThat(result.isAccessible()).isFalse();
    }

    @Test
    void translate_permitProvision_setsAccessible() {
        org.hl7.fhir.r4.model.Consent fhir = new org.hl7.fhir.r4.model.Consent();
        var provision = new org.hl7.fhir.r4.model.Consent.provisionComponent();
        provision.setType(org.hl7.fhir.r4.model.Consent.ConsentProvisionType.PERMIT);
        fhir.setProvision(provision);

        Consent result = translator.translate(fhir);

        assertThat(result.isAccessible()).isTrue();
    }

    @Test
    void translate_noProvision_defaultsAccessible() {
        // No provision set at all
        org.hl7.fhir.r4.model.Consent fhir = new org.hl7.fhir.r4.model.Consent();

        Consent result = translator.translate(fhir);

        assertThat(result.isAccessible()).isTrue();
    }

    @Test
    void translate_locationExcluded_whenSecurityLabelPresent() {
        org.hl7.fhir.r4.model.Consent fhir = new org.hl7.fhir.r4.model.Consent();
        var provision = new org.hl7.fhir.r4.model.Consent.provisionComponent();
        provision.setType(org.hl7.fhir.r4.model.Consent.ConsentProvisionType.PERMIT);
        provision.addSecurityLabel(new Coding().setCode("dhp-exclude-location"));
        fhir.setProvision(provision);

        Consent result = translator.translate(fhir);

        assertThat(result.isLocationExcluded()).isTrue();
    }

    @Test
    void translate_locationNotExcluded_withOtherSecurityLabel() {
        org.hl7.fhir.r4.model.Consent fhir = new org.hl7.fhir.r4.model.Consent();
        var provision = new org.hl7.fhir.r4.model.Consent.provisionComponent();
        provision.setType(org.hl7.fhir.r4.model.Consent.ConsentProvisionType.PERMIT);
        provision.addSecurityLabel(new Coding().setCode("other-label"));
        fhir.setProvision(provision);

        Consent result = translator.translate(fhir);

        assertThat(result.isLocationExcluded()).isFalse();
    }

    @Test
    void translate_purposeOfUseCodes_captured() {
        org.hl7.fhir.r4.model.Consent fhir = new org.hl7.fhir.r4.model.Consent();
        var provision = new org.hl7.fhir.r4.model.Consent.provisionComponent();
        provision.setType(org.hl7.fhir.r4.model.Consent.ConsentProvisionType.PERMIT);
        provision.addPurpose(new Coding().setCode("TREAT"));
        provision.addPurpose(new Coding().setCode("HPAYMT"));
        fhir.setProvision(provision);

        Consent result = translator.translate(fhir);

        assertThat(result.allows("TREAT")).isTrue();
        assertThat(result.allows("HPAYMT")).isTrue();
        assertThat(result.allows("RESEARCH")).isFalse();
    }
}
