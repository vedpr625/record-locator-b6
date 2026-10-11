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
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
public class DocumentReferenceProvider implements IResourceProvider {

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

        if (period != null) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(
                    "period search is not yet supported");
        }

        String phn = resolvePhn(patient, patientIdentifier);

        if (consentGate.checkSearch(phn, "ITI-67")) {
            return emptyBundle();
        }

        SearchQuery query = new SearchQuery(
                phn,
                patientName == null ? null : patientName.getValue(),
                parseStatus(status == null ? null : status.getValue()),
                date == null || date.getLowerBound() == null ? null : toLocalDate(date.getLowerBound().getValue()),
                date == null || date.getUpperBound() == null ? null : toLocalDate(date.getUpperBound().getValue()),
                type == null ? null : type.getValue(),
                facility == null ? null : facility.getValue(),
                province == null ? null : province.getValue(),
                identifier == null ? null : identifier.getValue());

        List<Pointer> pointers = searchService.search(query);
        auditService.recordSearch("ITI-67", phn, true);

        return toBundle(pointers);
    }

    /**
     * ITI-67 accepts either patient (a direct reference) or
     * patient.identifier (a chained search, for a caller who's
     * resolved a PIXm/PDQm identifier but doesn't have our internal
     * reference). When _name is supplied without either, phn is null
     * and the OpenSearch path in SearchService drives the query.
     */
    private String resolvePhn(ReferenceParam patient, TokenParam patientIdentifier) {
        if (patient != null) {
            return patient.getIdPart();
        }
        if (patientIdentifier != null) {
            return patientIdentifier.getValue();
        }
        return null; // _name-only query — SearchService will use OpenSearch
    }

    @Read
    public DocumentReference read(@IdParam IdType id) {
        String resourceId = id.getIdPart();
        Pointer pointer = searchService.findById(resourceId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentReference/" + resourceId));

        consentGate.checkRetrieve(pointer.phn(), resourceId, "DocumentReference", "ITI-67");
        auditService.recordSearch("ITI-67", pointer.phn(), true);

        return mapper.toFhir(pointer);
    }

    private Bundle toBundle(List<Pointer> pointers) {
        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTimestamp(new Date());
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
        bundle.setTimestamp(new Date());
        return bundle;
    }

    private Pointer.Status parseStatus(String value) {
        if (value == null) return Pointer.Status.CURRENT;
        return switch (value) {
            case "superseded" -> Pointer.Status.SUPERSEDED;
            case "entered-in-error" -> Pointer.Status.ENTERED_IN_ERROR;
            default -> Pointer.Status.CURRENT;
        };
    }

    private LocalDate toLocalDate(Date date) {
        // Always UTC — using systemDefault() shifts the date on a Sri Lanka (UTC+5:30)
        // JVM and silently excludes studies created in early UTC hours.
        return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
