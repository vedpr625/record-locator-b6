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

import java.util.Comparator;
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
        if (date.getLowerBound() != null && listDate.before(date.getLowerBound().getValue())) {
            return false;
        }
        if (date.getUpperBound() != null && listDate.after(date.getUpperBound().getValue())) {
            return false;
        }
        return true;
    }

    private String resolvePhn(ReferenceParam patient, TokenParam patientIdentifier) {
        if (patient != null) {
            return patient.getIdPart();
        }
        if (patientIdentifier != null) {
            return patientIdentifier.getValue();
        }
        throw new InvalidRequestException("either patient or patient.identifier is required");
    }

    private Pointer.Status parseStatus(String value) {
        return switch (value) {
            case "superseded" -> Pointer.Status.SUPERSEDED;
            case "entered-in-error" -> Pointer.Status.ENTERED_IN_ERROR;
            default -> Pointer.Status.CURRENT;
        };
    }

    private static final String SUBMISSION_SET_UNIQUE_ID_TYPE_SYSTEM =
            "https://profiles.ihe.net/ITI/MHD/CodeSystem/IHE.MHD.MHDIdentifierType";
    private static final String URN_OID_SYSTEM = "urn:ietf:rfc:3986";
    private static final String IHE_SOURCE_ID_EXT = "https://profiles.ihe.net/ITI/MHD/StructureDefinition/ihe-sourceId";

    private ListResource toListResource(String submissionSetId, String phn, List<Pointer> members) {
        ListResource list = new ListResource();
        String entryUuid = UUID.nameUUIDFromBytes(
                submissionSetId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        // L-2: use UTF-8 explicitly so deterministic UUID generation is stable
        // regardless of the JVM's default charset (which varies by OS/container).
        // The previous getBytes() call used the platform default, meaning the same
        // submissionSetId could produce a different UUID on a system with a
        // non-UTF-8 default charset, breaking idempotency.
        list.setId(entryUuid);

        // The uniqueId slice (SubmissionSet.uniqueId per the real
        // IHE.MHD.SubmissionSetUniqueIdIdentifier profile) - was
        // previously a plain, half-conformant identifier with the
        // wrong value (submissionSetId with no urn:uuid: prefix) and
        // no type marker at all. Fixed to match the confirmed real
        // shape: a fixed type.coding identifying this AS the
        // uniqueId slice, system is our own namespace, value is the
        // actual submission set business id.
        list.addIdentifier(new Identifier()
                .setSystem(homeCommunityId)
                .setValue(submissionSetId)
                .setType(new CodeableConcept()
                        .addCoding(new Coding(SUBMISSION_SET_UNIQUE_ID_TYPE_SYSTEM, "uniqueId", null))));

        // The entryUUID slice - identifies the List RESOURCE itself.
        // Reusing the same deterministic UUID as the resource's own
        // id is correct here (unlike Pointer, which needed a
        // separately-stored random UUID because Pointer.id isn't
        // guaranteed UUID-shaped) - this UUID IS already guaranteed
        // UUID-shaped by construction (nameUUIDFromBytes).
        list.addIdentifier(new Identifier()
                .setUse(Identifier.IdentifierUse.OFFICIAL)
                .setSystem(URN_OID_SYSTEM)
                .setValue("urn:uuid:" + entryUuid));

        list.setStatus(ListResource.ListStatus.CURRENT);
        list.setMode(ListResource.ListMode.WORKING);
        list.setCode(new CodeableConcept().addCoding(
                new Coding(SUBMISSION_SET_SYSTEM, "submissionset", "SubmissionSet")));
        list.setSubject(new Reference("Patient/" + phn));

        // sourceId is 1..1 MANDATORY under the real profile -
        // "Publisher organization identity of the SubmissionSet."
        // No per-submission publisher identity exists anywhere in
        // this pipeline (same honest gap as author on
        // DocumentReference), so homeCommunityId - the one
        // organization-level identity this app actually has - is
        // used as the closest available value rather than leaving a
        // mandatory field unset.
        list.addExtension(new Extension(IHE_SOURCE_ID_EXT,
                new Identifier().setSystem(URN_OID_SYSTEM).setValue(homeCommunityId)));

        members.stream()
                .map(Pointer::indexedAt)
                .min(Comparator.naturalOrder())
                .ifPresent(t -> list.setDate(java.util.Date.from(t)));

        for (Pointer member : members) {
            ListResource.ListEntryComponent entry = list.addEntry();
            entry.setItem(new Reference("DocumentReference/" + member.id()));
            entry.setDate(java.util.Date.from(member.indexedAt()));
        }
        return list;
    }

    private Bundle toBundle(List<ListResource> lists) {
        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(lists.size());
        bundle.setTimestamp(new java.util.Date());
        for (ListResource list : lists) {
            bundle.addEntry()
                    .setFullUrl("List/" + list.getIdElement().getIdPart())
                    .setResource(list)
                    .getSearch().setMode(Bundle.SearchEntryMode.MATCH);
        }
        return bundle;
    }
}
