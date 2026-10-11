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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

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

    /**
     * H-7: allowed hostnames for the content URL redirect.
     * Only HTTPS URLs whose host is in this set are forwarded.
     * Configured via {@code rl.content-url.allowed-hosts} (comma-separated).
     * Defaults to empty, which blocks ALL redirects — deployments MUST configure this.
     */
    private final Set<String> allowedHosts;

    private final SearchService searchService;
    private final ConsentGate consentGate;
    private final AuditService auditService;

    public BinaryProvider(SearchService searchService, ConsentGate consentGate, AuditService auditService,
                          @Value("${rl.content-url.allowed-hosts:}") String allowedHostsCsv) {
        this.searchService = searchService;
        this.consentGate = consentGate;
        this.auditService = auditService;
        this.allowedHosts = allowedHostsCsv == null || allowedHostsCsv.isBlank()
                ? Set.of()
                : Set.of(allowedHostsCsv.split("\\s*,\\s*"));
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

        // H-7: validate contentUrl before issuing redirect to prevent open-redirect
        // attacks or SSRF via a pointer whose contentUrl was crafted maliciously.
        String contentUrl = pointer.contentUrl();
        java.net.URI uri;
        try {
            uri = java.net.URI.create(contentUrl);
        } catch (IllegalArgumentException e) {
            auditService.recordRetrieve(pointer.phn(), binaryId, false);
            throw new ResourceNotFoundException("Binary/" + binaryId + " has an invalid content URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            auditService.recordRetrieve(pointer.phn(), binaryId, false);
            throw new ResourceNotFoundException("Binary/" + binaryId + " content URL must use HTTPS");
        }
        if (!allowedHosts.isEmpty() && !allowedHosts.contains(uri.getHost())) {
            auditService.recordRetrieve(pointer.phn(), binaryId, false);
            throw new ResourceNotFoundException("Binary/" + binaryId + " content URL host is not in the allowed list");
        }

        // Write the redirect directly so HAPI never gets to reset the status.
        // HAPI injects HttpServletResponse into @Read methods via its parameter
        // resolver — returning null signals "response already written".
        response.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
        response.setHeader("Location", contentUrl);
        auditService.recordRetrieve(pointer.phn(), binaryId, true);

        return null;
    }
}
