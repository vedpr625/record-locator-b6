package lk.gov.moh.dhp.rl.web;

import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.RequiredParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import lk.gov.moh.dhp.rl.audit.AuditService;
import lk.gov.moh.dhp.rl.consent.ConsentGate;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import lk.gov.moh.dhp.rl.search.SearchService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
public class ListProvider implements IResourceProvider {

    private static final String SUBMISSION_SET_SYSTEM =
            "https://profiles.ihe.net/ITI/MHD/CodeSystem/MHDlistTypes";

    private final SearchService searchService;
    private final ConsentGate consentGate;
    private final AuditService auditService;

    @Value("${rl.home-community-id}")
    private String homeCommunityId;

    public ListProvider(SearchService searchService, ConsentGate consentGate, AuditService auditService) {
        this.searchService = searchService;
        this.consentGate = consentGate;
        this.auditService = auditService;
    }

    @Override
    public Class<ListResource> getResourceType() {
        return ListResource.class;
    }

    @Search
    public Bundle search(
            @OptionalParam(name = ListResource.SP_PATIENT) ReferenceParam patient,
            @OptionalParam(name = "patient.identifier") TokenParam patientIdentifier,
            @OptionalParam(name = ListResource.SP_CODE) TokenParam code,
            @RequiredParam(name = ListResource.SP_STATUS) TokenParam status,
            @OptionalParam(name = ListResource.SP_DATE) DateRangeParam date,
            @OptionalParam(name = ListResource.SP_IDENTIFIER) TokenParam identifier) {

        if (code != null && !"submissionset".equals(code.getValue())) {
            throw new InvalidRequestException("only code=submissionset is supported");
        }

        String phn = resolvePhn(patient, patientIdentifier);

        if (consentGate.checkSearch(phn, "ITI-66")) {
            return toBundle(List.of());
        }

        Pointer.Status parsedStatus = parseStatus(status.getValue());
        java.util.Map<String, List<Pointer>> submissionSets =
                searchService.submissionSetsWithMembers(phn, parsedStatus);
        auditService.recordSearch("ITI-66", phn, true);

        // identifier here is the SubmissionSet id itself, so we can
        // filter before ever building the ListResource - no point
        // constructing one just to throw it away.
        java.util.Set<String> submissionSetIds = identifier != null
                ? java.util.Set.of(identifier.getValue())
                : submissionSets.keySet();

        List<ListResource> lists = submissionSetIds.stream()
                .filter(submissionSets::containsKey)
                .map(id -> toListResource(id, phn, submissionSets.get(id)))
                .filter(list -> !list.getEntry().isEmpty())
                .filter(list -> matchesDate(list, date))
                .toList();

        return toBundle(lists);
    }

    private boolean matchesDate(ListResource list, DateRangeParam date) {
        if (date == null || !list.hasDate()) {
            return true;
        }
        java.util.Date listDate = list.getDate();
        return (date.getLowerBound() == null || !listDate.before(date.getLowerBound().getValue()))
                && (date.getUpperBound() == null || !listDate.after(date.getUpperBound().getValue()));
    }

    // M-12: delegate to shared utility — was duplicated verbatim from DocumentReferenceProvider.
    private String resolvePhn(ReferenceParam patient, TokenParam patientIdentifier) {
        return FhirParamUtil.requirePhn(patient, patientIdentifier);
    }

    /**
     * M-5: Unknown status values now return 400 as the FHIR spec requires.
     * Previously, unknown values silently fell through to CURRENT, which masked
     * caller typos and could return unexpected results.
     */
    private Pointer.Status parseStatus(String value) {
        if (value == null) return Pointer.Status.CURRENT;
        return switch (value.toLowerCase()) {
            case "current" -> Pointer.Status.CURRENT;
            case "superseded" -> Pointer.Status.SUPERSEDED;
            case "entered-in-error" -> Pointer.Status.ENTERED_IN_ERROR;
            default -> throw new InvalidRequestException(
                    "Unknown status value '" + value + "'; expected current, superseded, or entered-in-error");
        };
    }

    private static final String SUBMISSION_SET_UNIQUE_ID_TYPE_SYSTEM =
            "https://profiles.ihe.net/ITI/MHD/CodeSystem/IHE.MHD.MHDIdentifierType";
    private static final String URN_OID_SYSTEM = "urn:ietf:rfc:3986";
    private static final String IHE_SOURCE_ID_EXT = "https://profiles.ihe.net/ITI/MHD/StructureDefinition/ihe-sourceId";

    // M-3: toListResource() was ~65 lines; extracted addSubmissionSetIdentifiers() helper
    // to bring each logical section under the SonarQube S138 method-length limit.
    // S6885: HAPI FHIR ListResource.setDate() and ListEntryComponent.setDate() require
    // java.util.Date; Date.from(Instant) is the correct java.time interop pattern.
    @SuppressWarnings("java:S6885")
    private ListResource toListResource(String submissionSetId, String phn, List<Pointer> members) {
        ListResource list = new ListResource();
        // L-2: UTF-8 explicit so deterministic UUID is stable regardless of JVM default charset.
        String entryUuid = UUID.nameUUIDFromBytes(
                submissionSetId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        list.setId(entryUuid);
        addSubmissionSetIdentifiers(list, submissionSetId, entryUuid);
        list.setStatus(ListResource.ListStatus.CURRENT);
        list.setMode(ListResource.ListMode.WORKING);
        list.setCode(new CodeableConcept().addCoding(
                new Coding(SUBMISSION_SET_SYSTEM, "submissionset", "SubmissionSet")));
        list.setSubject(new Reference("Patient/" + phn));
        // sourceId is 1..1 MANDATORY — use homeCommunityId as the closest available
        // publisher identity (no per-submission publisher exists in this pipeline).
        list.addExtension(new Extension(IHE_SOURCE_ID_EXT,
                new Identifier().setSystem(URN_OID_SYSTEM).setValue(homeCommunityId)));
        members.stream()
                .map(Pointer::indexedAt)
                .min(Comparator.naturalOrder())
                .ifPresent(t -> list.setDate(Date.from(t)));
        for (Pointer member : members) {
            ListResource.ListEntryComponent entry = list.addEntry();
            entry.setItem(new Reference("DocumentReference/" + member.id()));
            entry.setDate(Date.from(member.indexedAt()));
        }
        return list;
    }

    /**
     * Adds the two IHE-required identifier slices to a SubmissionSet List resource:
     * the uniqueId slice (business id with type marker) and the entryUUID slice
     * (the resource's own UUID, use=official, system=urn:ietf:rfc:3986).
     */
    private void addSubmissionSetIdentifiers(ListResource list, String submissionSetId, String entryUuid) {
        // uniqueId slice: type marker + our namespace + submission set business id.
        list.addIdentifier(new Identifier()
                .setSystem(homeCommunityId)
                .setValue(submissionSetId)
                .setType(new CodeableConcept()
                        .addCoding(new Coding(SUBMISSION_SET_UNIQUE_ID_TYPE_SYSTEM, "uniqueId", null))));
        // entryUUID slice: identifies the List resource itself (not the document).
        list.addIdentifier(new Identifier()
                .setUse(Identifier.IdentifierUse.OFFICIAL)
                .setSystem(URN_OID_SYSTEM)
                .setValue("urn:uuid:" + entryUuid));
    }

    // S6885: HAPI FHIR Bundle.setTimestamp() requires java.util.Date; Date.from(Instant) is correct interop.
    @SuppressWarnings("java:S6885")
    private Bundle toBundle(List<ListResource> lists) {
        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(lists.size());
        bundle.setTimestamp(Date.from(Instant.now()));
        for (ListResource list : lists) {
            bundle.addEntry()
                    .setFullUrl("List/" + list.getIdElement().getIdPart())
                    .setResource(list)
                    .getSearch().setMode(Bundle.SearchEntryMode.MATCH);
        }
        return bundle;
    }
}
