package lk.gov.moh.dhp.rl.consent;

import org.hl7.fhir.r4.model.Coding;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Translates a FHIR Consent resource (from Bundle 2a) into our own
 * Consent value object. Kept separate from ConsentClient so we're
 * not tangled up with the HAPI client when all we want to test is
 * "does this Consent resource mean opted out".
 */
@Component
public class ConsentTranslator {

    public Consent translate(org.hl7.fhir.r4.model.Consent fhirConsent) {
        boolean optedOut = fhirConsent.hasProvision()
                && fhirConsent.getProvision().hasType()
                && fhirConsent.getProvision().getType() == org.hl7.fhir.r4.model.Consent.ConsentProvisionType.DENY;

        // Guard all provision sub-field accesses — a DENY provision might omit
        // securityLabel/purpose, and a full-access grant has no provision at all.
        // Note: HAPI 7.x uses provisionComponent (lowercase p) as the inner class name.
        org.hl7.fhir.r4.model.Consent.provisionComponent provision =
                fhirConsent.hasProvision() ? fhirConsent.getProvision() : null;

        boolean locationExcluded = provision != null
                && provision.hasSecurityLabel()
                && provision.getSecurityLabel().stream()
                        .anyMatch(c -> "dhp-exclude-location".equals(c.getCode()));

        Set<String> purposes = new HashSet<>();
        if (provision != null && provision.hasPurpose()) {
            for (Coding coding : provision.getPurpose()) {
                if (coding.hasCode()) {
                    purposes.add(coding.getCode());
                }
            }
        }

        return Consent.from(optedOut, locationExcluded, purposes);
    }
}
