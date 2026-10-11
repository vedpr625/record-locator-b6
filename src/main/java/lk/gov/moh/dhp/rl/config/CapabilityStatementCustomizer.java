package lk.gov.moh.dhp.rl.config;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.springframework.stereotype.Component;

/**
 * ITI-66/67/68 all require a CapabilityStatement declaring conformance.
 * We only claim the base MHD Document Responder profile - not the
 * Full-Text Search Option (our free text search doesn't implement
 * the full AND/OR/NOT precedence grammar the option requires) and
 * not Target Communities (our homeCommunityId handling supports our
 * own edge/national topology, not literal cross-domain XCA routing).
 */
@Component
@Interceptor
public class CapabilityStatementCustomizer {

    private static final String DOCUMENT_RESPONDER_PROFILE =
            "https://profiles.ihe.net/ITI/MHD/CapabilityStatement/IHE.MHD.DocumentResponder";

    @Hook(Pointcut.SERVER_CAPABILITY_STATEMENT_GENERATED)
    public void customize(IBaseConformance conformance) {
        if (!(conformance instanceof CapabilityStatement statement)) {
            return;
        }
        statement.getSoftware().setName("DHP Record Locator").setVersion("2.0.0");
        statement.setPublisher("Ministry of Health, Sri Lanka");
        statement.addInstantiates(DOCUMENT_RESPONDER_PROFILE);
    }
}
