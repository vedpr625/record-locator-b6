package lk.gov.moh.dhp.rl.web;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import lk.gov.moh.dhp.rl.audit.AuditService;
import lk.gov.moh.dhp.rl.consent.ConsentGate;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import lk.gov.moh.dhp.rl.search.SearchService;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.IdType;
import org.springframework.stereotype.Component;

/**
 * ITI-68 Retrieve Document — always a 307 redirect to the actual content URL
 * (WADO-RS for imaging, SeaweedFS for documents). We never proxy the bytes
 * through this service; that keeps large binary streaming out of scope.
 *
 * The redirect is written directly to HttpServletResponse and null is returned
 * so HAPI does not touch the response status. Returning a Binary object after
 * calling response.setStatus(307) causes HAPI to reset the status to 200 after
 * serialising the resource body — confirmed behaviour with HAPI 7.x.
 */
@Component
public class BinaryProvider implements IResourceProvider {

    private final SearchService searchService;
    private final ConsentGate consentGate;
    private final AuditService auditService;

    public BinaryProvider(SearchService searchService, ConsentGate consentGate, AuditService auditService) {
        this.searchService = searchService;
        this.consentGate = consentGate;
        this.auditService = auditService;
    }

    @Override
    public Class<Binary> getResourceType() {
        return Binary.class;
    }

    @Read
    public Binary read(@IdParam IdType id, HttpServletResponse response) {
        String binaryId = id.getIdPart();
        Pointer pointer = searchService.findById(binaryId)
                .orElseThrow(() -> new ResourceNotFoundException("Binary/" + binaryId));

        consentGate.checkRetrieve(pointer.phn(), binaryId, "Binary", "ITI-68");

        if (pointer.status() == Pointer.Status.SUPERSEDED) {
            auditService.recordRetrieve(pointer.phn(), binaryId, false);
            throw new ResourceGoneException("Binary/" + binaryId + " has been superseded");
        }

        if (pointer.contentUrl() == null) {
            auditService.recordRetrieve(pointer.phn(), binaryId, false);
            throw new ResourceNotFoundException("Binary/" + binaryId + " has no content yet");
        }

        // Write the redirect directly so HAPI never gets to reset the status.
        // HAPI injects HttpServletResponse into @Read methods via its parameter
        // resolver — returning null signals "response already written".
        response.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
        response.setHeader("Location", pointer.contentUrl());
        auditService.recordRetrieve(pointer.phn(), binaryId, true);

        return null;
    }
}
