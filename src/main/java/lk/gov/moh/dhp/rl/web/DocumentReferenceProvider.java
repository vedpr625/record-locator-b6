package lk.gov.moh.dhp.rl.web;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import lk.gov.moh.dhp.rl.audit.AuditService;
import lk.gov.moh.dhp.rl.consent.ConsentGate;
import lk.gov.moh.dhp.rl.fhir.PointerFhirMapper;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import lk.gov.moh.dhp.rl.common.SearchQuery;
import lk.gov.moh.dhp.rl.search.SearchService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.InstantType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
public class DocumentReferenceProvider implements IResourceProvider {

    private static final Logger log = LoggerFactory.getLogger(DocumentReferenceProvider.class);

    /**
     * Standard IHE/HL7 purpose-of-use claim name in JWT.
     * SMART on FHIR uses "purposeOfUse"; some IDPs use "purpose_of_use".
     * We check both; the first non-null value wins.
     */
    private static final String[] PURPOSE_CLAIM_NAMES = {"purposeOfUse", "purpose_of_use"};
    private static final String ITI_67 = "ITI-67";

    private final SearchService searchService;
    private final ConsentGate consentGate;
    private final PointerFhirMapper mapper;
    private final AuditService auditService;

    public DocumentReferenceProvider(SearchService searchService, ConsentGate consentGate,
                                      PointerFhirMapper mapper, AuditService auditService) {
        this.searchService = searchService;
        this.consentGate = consentGate;
        this.mapper = mapper;
        this.auditService = auditService;
    }

    @Override
    public Class<DocumentReference> getResourceType() {
        return DocumentReference.class;
    }

    // HAPI FHIR discovers @OptionalParam parameters by reflection and requires them
    // to be declared directly on the @Search method — a parameter object cannot be
    // used for the method signature itself. S107 is suppressed here: all 10 parameters
    // are mandatory FHIR search params defined by IHE MHD ITI-67 and cannot be reduced.
    // The body is delegated to executeSearch() and buildQuery() to keep each method focused.
    @SuppressWarnings("java:S107")
    @Search
    public Bundle search(
            @OptionalParam(name = DocumentReference.SP_PATIENT) ReferenceParam patient,
            @OptionalParam(name = "patient.identifier") TokenParam patientIdentifier,
            // IHE MHD ITI-67 defines status as SHOULD (not SHALL), so @OptionalParam
            // is correct. Defaulting to CURRENT when omitted matches the most common
            // use case and avoids breaking conformant clients that omit the parameter.
            @OptionalParam(name = DocumentReference.SP_STATUS) TokenParam status,
            @OptionalParam(name = DocumentReference.SP_DATE) DateRangeParam date,
            @OptionalParam(name = "period") DateRangeParam period,
            @OptionalParam(name = DocumentReference.SP_TYPE) TokenParam type,
            @OptionalParam(name = "facility") TokenParam facility,
            @OptionalParam(name = "_province") StringParam province,
            @OptionalParam(name = DocumentReference.SP_IDENTIFIER) TokenParam identifier,
            // OpenSearch-backed: free-text match against DICOM PatientName / FHIR Patient.name.
            // When supplied without a patient/patient.identifier, drives an OpenSearch name
            // lookup first, then resolves matching PHNs against PostgreSQL.
            @OptionalParam(name = "_name") StringParam patientName) {

        String phn = resolvePhn(patient, patientIdentifier);
        // M-5: the IHE MHD ITI-67 `period` parameter maps to the same date range
        // as the `date` parameter in this implementation.  Treat it as an alias
        // rather than rejecting with 400, which would break MHD conformance.
        DateRangeParam effectiveDate = (date != null) ? date : period;
        SearchParams params = new SearchParams(patientName, status, type, facility, province, identifier);
        SearchQuery query = buildQuery(phn, effectiveDate, params);
        return executeSearch(phn, query);
    }

    /**
     * Carries the optional FHIR search parameters (other than phn and date) into
     * {@link #buildQuery} so that method stays within the 7-parameter limit (S107).
     */
    private record SearchParams(StringParam patientName, TokenParam status, TokenParam type,
                                TokenParam facility, StringParam province, TokenParam identifier) {}

    /**
     * Assembles a SearchQuery from the individual FHIR search parameters.
     * Extracted from search() to keep the @Search dispatcher under the
     * SonarQube S107 parameter-count limit; SearchParams groups the remaining
     * optional params to keep buildQuery itself within the limit.
     */
    private SearchQuery buildQuery(String phn, DateRangeParam effectiveDate, SearchParams p) {
        LocalDate dateFrom = effectiveDate == null || effectiveDate.getLowerBound() == null ? null
                : toLocalDate(effectiveDate.getLowerBound().getValue());
        LocalDate dateTo = effectiveDate == null || effectiveDate.getUpperBound() == null ? null
                : toLocalDate(effectiveDate.getUpperBound().getValue());
        return new SearchQuery(
                phn,
                p.patientName() == null ? null : p.patientName().getValue(),
                parseStatus(p.status() == null ? null : p.status().getValue()),
                dateFrom,
                dateTo,
                p.type() == null ? null : p.type().getValue(),
                p.facility() == null ? null : p.facility().getValue(),
                p.province() == null ? null : p.province().getValue(),
                p.identifier() == null ? null : p.identifier().getValue(),
                extractPurposeOfUse());
    }

    /**
     * Applies consent check, runs the search, audits the outcome, and returns the bundle.
     * Extracted from search() to keep each method focused on a single concern.
     */
    private Bundle executeSearch(String phn, SearchQuery query) {
        if (consentGate.checkSearch(phn, ITI_67)) {
            return emptyBundle();
        }

        // H-4: audit outcome reflects the actual result, not an unconditional
        // SUCCESS.  A downstream exception after this call records FAILURE.
        List<Pointer> pointers;
        try {
            pointers = searchService.search(query);
        } catch (Exception ex) {
            auditService.recordSearch(ITI_67, phn, false);
            throw ex;
        }
        auditService.recordSearch(ITI_67, phn, true);

        return toBundle(pointers);
    }

    /**
     * ITI-67 accepts either patient (a direct reference) or
     * patient.identifier (a chained search, for a caller who's
     * resolved a PIXm/PDQm identifier but doesn't have our internal
     * reference). When _name is supplied without either, phn is null
     * and the OpenSearch path in SearchService drives the query.
     *
     * <p>M-12: delegates to shared utility — was duplicated verbatim in ListProvider.
     * Uses the null-returning variant because ITI-67 supports _name-only queries.
     */
    private String resolvePhn(ReferenceParam patient, TokenParam patientIdentifier) {
        return FhirParamUtil.resolvePhn(patient, patientIdentifier);
    }

    /**
     * Reads the purpose-of-use claim from the current request's JWT.
     * Returns null when:
     *   - running in dev profile (no JWT)
     *   - the IDP does not issue a purpose claim
     * In both cases SearchService skips the purpose-of-use filter.
     */
    private String extractPurposeOfUse() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
                return null;
            }
            for (String claimName : PURPOSE_CLAIM_NAMES) {
                Object value = jwt.getClaim(claimName);
                if (value != null) {
                    return value.toString();
                }
            }
        } catch (Exception e) {
            log.debug("could not extract purpose-of-use from JWT: {}", e.getMessage());
        }
        return null;
    }

    @Read
    public DocumentReference read(@IdParam IdType id) {
        String resourceId = id.getIdPart();
        Pointer pointer = searchService.findById(resourceId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentReference/" + resourceId));

        consentGate.checkRetrieve(pointer.phn(), resourceId, "DocumentReference", ITI_67);
        auditService.recordSearch(ITI_67, pointer.phn(), true);

        return mapper.toFhir(pointer);
    }

    private Bundle toBundle(List<Pointer> pointers) {
        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTimestampElement(InstantType.now());
        bundle.setTotal(pointers.size());
        for (Pointer pointer : pointers) {
            bundle.addEntry()
                    .setFullUrl("DocumentReference/" + pointer.id())
                    .setResource(mapper.toFhir(pointer))
                    .getSearch().setMode(Bundle.SearchEntryMode.MATCH);
        }
        return bundle;
    }

    private Bundle emptyBundle() {
        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(0);
        bundle.setTimestampElement(InstantType.now());
        return bundle;
    }

    /**
     * L-6: Unknown status values now return 400 as the FHIR spec requires.
     * Previously they silently fell back to CURRENT, which could mislead callers
     * into thinking a typo'd status was accepted and returning results.
     */
    private Pointer.Status parseStatus(String value) {
        if (value == null) return Pointer.Status.CURRENT;
        return switch (value.toLowerCase()) {
            case "current" -> Pointer.Status.CURRENT;
            case "superseded" -> Pointer.Status.SUPERSEDED;
            case "entered-in-error" -> Pointer.Status.ENTERED_IN_ERROR;
            default -> throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(
                    "Unknown status value '" + value + "'; expected current, superseded, or entered-in-error");
        };
    }

    private LocalDate toLocalDate(Date date) {
        // Always UTC — using systemDefault() shifts the date on a Sri Lanka (UTC+5:30)
        // JVM and silently excludes studies created in early UTC hours.
        return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
