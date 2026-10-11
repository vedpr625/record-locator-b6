package lk.gov.moh.dhp.rl.fhir;

import lk.gov.moh.dhp.rl.consent.Consent;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Set;

/**
 * Only place in the app that converts between our Pointer entity and
 * the FHIR wire format, in either direction. Keeping this in one spot
 * is what stops a field being forwarded correctly in one place and
 * silently dropped in another - we had exactly that bug in the
 * previous version.
 */
@Component
public class PointerFhirMapper {

    private static final String LOINC = "http://loinc.org";
    private static final String EXT_BASE = "https://dhp.gov.lk/fhir/StructureDefinition/";
    private static final String EXT_CONSENT_ACCESSIBLE = EXT_BASE + "consent-accessible";
    private static final String EXT_LOCATION_EXCLUDED = EXT_BASE + "location-excluded";
    private static final String EXT_SUBMISSION_SET = EXT_BASE + "submission-set-id";
    private static final String EXT_SOURCE_RESOURCE_TYPE = EXT_BASE + "source-resource-type";
    private static final String DICOM_UID_SYSTEM = "urn:dicom:uid";
    private static final String PURPOSE_OF_USE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ActReason";

    // Real IHE MHD identifiers, confirmed against the actual
    // StructureDefinition pages for IHE.MHD.UniqueIdIdentifier,
    // IHE.MHD.EntryUUID.Identifier, and ihe-HomeCommunityId - not
    // invented, and not to be confused with DHP's own custom
    // extensions above.
    private static final String IHE_HOME_COMMUNITY_ID_EXT =
            "https://profiles.ihe.net/ITI/MHD/StructureDefinition/ihe-HomeCommunityId";
    private static final String IHE_IDENTIFIER_TYPE_SYSTEM =
            "https://profiles.ihe.net/ITI/MHD/CodeSystem/IHE.MHD.MHDIdentifierType";
    private static final String URN_OID_SYSTEM = "urn:ietf:rfc:3986";

    @Value("${rl.home-community-id}")
    private String homeCommunityId;

    @Value("${rl.nehr-base-url:}")
    private String nehrBaseUrl;

    public DocumentReference toFhir(Pointer pointer) {
        DocumentReference dr = new DocumentReference();
        dr.setId(pointer.id());

        // masterIdentifier.type is mandatory under IHE.MHD.UniqueIdIdentifier
        // - a fixed coding identifying this AS the uniqueId slice.
        // Confirmed against the real profile page; the previous
        // version set system/value but never type, which is a real
        // conformance gap against the named profile, not base R4.
        Identifier masterIdentifier = dr.getMasterIdentifier();
        masterIdentifier.setSystem(homeCommunityId).setValue(pointer.id());
        masterIdentifier.setType(new CodeableConcept()
                .addCoding(new Coding(IHE_IDENTIFIER_TYPE_SYSTEM, "uniqueId", null)));

        // The uniqueId slice on identifier[] - confirmed the profile
        // requires this to exactly match masterIdentifier when
        // present. Same value, same type marker, separate list entry
        // per the slicing rule (identifier is sliced by value+type).
        dr.addIdentifier(new Identifier()
                .setSystem(homeCommunityId)
                .setValue(pointer.id())
                .setType(new CodeableConcept()
                        .addCoding(new Coding(IHE_IDENTIFIER_TYPE_SYSTEM, "uniqueId", null))));

        // The entryUUID slice - identifies the DocumentReference
        // RESOURCE itself, a genuinely different thing from the
        // document it points at (masterIdentifier/uniqueId above).
        // Confirmed against IHE.MHD.EntryUUID.Identifier: use=official,
        // system fixed to urn:ietf:rfc:3986, value must start with
        // urn:uuid: - satisfied by Pointer.entryUuid(), a separate
        // UUID generated once at creation specifically for this.
        dr.addIdentifier(new Identifier()
                .setUse(Identifier.IdentifierUse.OFFICIAL)
                .setSystem(URN_OID_SYSTEM)
                .setValue("urn:uuid:" + pointer.entryUuid()));

        // The real, named IHE extension for the affinity domain OID -
        // previously this app repurposed masterIdentifier.system for
        // this instead of using the actual extension IHE defines.
        // homeCommunityId is configured as urn:oid:2.16... but
        // OidType's constructor documentation says it "prepends
        // urn:oid: to the OID string" - unclear whether it detects an
        // already-present prefix, so stripping it explicitly first
        // rather than risk a doubled urn:oid:urn:oid:... value.
        String bareOid = homeCommunityId.startsWith("urn:oid:")
                ? homeCommunityId.substring("urn:oid:".length())
                : homeCommunityId;
        dr.addExtension(new Extension(IHE_HOME_COMMUNITY_ID_EXT, new org.hl7.fhir.r4.model.OidType(bareOid)));

        dr.setStatus(toFhirStatus(pointer.status()));
        // Builds a plain local reference (Patient/{phn}). By FHIR convention
        // this means "the Patient resource with this logical id on this server",
        // but this RL doesn't host a Patient resource. A logical reference using
        // identifier.system+value would be more correct for a business identifier
        // on a server that doesn't own the Patient resource — flagging, not fixing,
        // as this is a separate conformance concern.
        dr.setSubject(new Reference("Patient/" + pointer.phn()));

        // type is 1..1 MANDATORY under IHE MHD (base R4 makes it
        // optional, but the actual profile does not) - confirmed
        // against the real MHD Minimal DocumentReference structure.
        // A pointer with no LOINC code still needs SOME type; falling
        // back to the generic "unspecified document type" code rather
        // than silently omitting a mandatory field.
        dr.setType(new CodeableConcept().addCoding(pointer.docTypeLoinc() != null
                ? new Coding(LOINC, pointer.docTypeLoinc(), null)
                : new Coding(LOINC, "34133-9", "Summary of episode note")));

        if ("ImagingStudy".equals(pointer.resourceType())) {
            dr.addIdentifier(new Identifier().setSystem(DICOM_UID_SYSTEM).setValue(pointer.id()));
        } else if (!"DocumentReference".equals(pointer.resourceType())) {
            // A NEHR-originated pointer (DiagnosticReport, Encounter,
            // etc.) wrapped as a DocumentReference - previously
            // indistinguishable from an actual document once rendered,
            // which silently defeated the whole point of ingesting
            // these separately. Flagging the real underlying resource
            // type so a consumer can tell "this points at a NEHR
            // structured resource, not a document" before following
            // the content.attachment.url.
            dr.addExtension(new Extension(EXT_SOURCE_RESOURCE_TYPE, new StringType(pointer.resourceType())));
        }

        Attachment attachment = new Attachment();
        attachment.setContentType(pointer.contentType());
        attachment.setUrl(pointer.contentUrl());
        dr.addContent().setAttachment(attachment);

        // context.facilityType is the native R4 field for this - no
        // separate extension needed. facilityHin was previously
        // ALSO written as a custom extension here, duplicating what
        // context.facilityType.coding[0].code already carries -
        // found by checking every extension against the real R4
        // structure rather than assuming each one earned its place.
        if (pointer.facilityHin() != null) {
            dr.getContext().setFacilityType(new CodeableConcept()
                    .addCoding(new Coding(homeCommunityId, pointer.facilityHin(), pointer.facilityName())));
        }

        // context.encounter is the native R4 field for linking a
        // document to the NEHR encounter it relates to - agreed
        // earlier as worth adding, never actually built until now.
        //
        // I-2: guard against nehrBaseUrl being empty (it defaults to "").
        // An empty base produces "/Encounter/{id}" which is a relative reference —
        // valid FHIR but not resolvable outside the RL server itself and not what
        // any consumer expects. Omit the encounter link entirely when the base URL
        // is not configured rather than silently writing a broken reference.
        if (pointer.nehrEncounterRef() != null
                && nehrBaseUrl != null && !nehrBaseUrl.isBlank()) {
            dr.getContext().addEncounter(new Reference(nehrBaseUrl + "/Encounter/" + pointer.nehrEncounterRef()));
        }

        dr.setDate(Date.from(pointer.indexedAt()));

        addConsentExtensions(dr, pointer.consent());

        if (pointer.submissionSetId() != null) {
            dr.addExtension(new Extension(EXT_SUBMISSION_SET, new StringType(pointer.submissionSetId())));
        }

        return dr;
    }

    private void addConsentExtensions(DocumentReference dr, Consent consent) {
        dr.addExtension(new Extension(EXT_CONSENT_ACCESSIBLE, new BooleanType(consent.isAccessible())));
        dr.addExtension(new Extension(EXT_LOCATION_EXCLUDED, new BooleanType(consent.isLocationExcluded())));
        // purpose-of-use moved from a custom extension to
        // DocumentReference.securityLabel - confirmed against the
        // real R4 valueset (v3-PurposeOfUse is explicitly imported
        // into the securityLabel valueset for this exact resource)
        // that this is the spec-native place for it, not something
        // that needed inventing. Found by checking every custom
        // extension against the real structure instead of assuming
        // a extension was the only option.
        if (consent.purposeOfUseRaw() != null) {
            for (String purpose : consent.purposeOfUseRaw().split(",")) {
                dr.addSecurityLabel(new CodeableConcept()
                        .addCoding(new Coding(PURPOSE_OF_USE_SYSTEM, purpose, null)));
            }
        }
    }

    /**
     * Builds a fresh pointer from a resource received over the wire -
     * used both when a forwarded pointer lands from a child node and
     * when parsing new source data. Throws if the consent extensions
     * aren't present; a pointer with no stated consent is a protocol
     * violation here, not something we default to accessible.
     */
    public Pointer.Builder fromFhir(DocumentReference dr) {
        // resourceType defaults to DocumentReference; overridden below
        // if the source-resource-type extension says otherwise (a
        // forwarded NEHR-originated pointer).
        Pointer.Builder builder = Pointer.builder()
                .id(dr.getIdElement().getIdPart())
                .resourceType("DocumentReference")
                .status(fromFhirStatus(dr.getStatus()));

        // entryUUID slice - system fixed to urn:ietf:rfc:3986, value
        // is urn:uuid:{uuid}. If absent (a non-conformant source),
        // Pointer's own constructor generates a fresh one rather than
        // this method failing - a missing entryUUID isn't a protocol
        // violation the way a missing consent extension is.
        dr.getIdentifier().stream()
                .filter(id -> URN_OID_SYSTEM.equals(id.getSystem())
                        && id.hasValue() && id.getValue().startsWith("urn:uuid:"))
                .findFirst()
                .ifPresent(id -> builder.entryUuid(id.getValue().substring("urn:uuid:".length())));

        if (dr.hasSubject() && dr.getSubject().hasReference()) {
            // M-7: use lastIndexOf('/') instead of String.replace("Patient/", "")
            // so a PHN that happens to contain the substring "Patient/" (unlikely
            // but theoretically possible for numeric PHNs from a different system)
            // is not silently mangled. lastIndexOf correctly strips only the final
            // path segment prefix regardless of the reference's shape.
            String ref = dr.getSubject().getReference();
            int slash = ref.lastIndexOf('/');
            builder.phn(slash >= 0 ? ref.substring(slash + 1) : ref);
        }

        if (dr.hasType() && !dr.getType().getCoding().isEmpty()) {
            builder.docTypeLoinc(dr.getType().getCodingFirstRep().getCode());
        }

        if (dr.hasContent() && !dr.getContent().isEmpty()) {
            Attachment attachment = dr.getContentFirstRep().getAttachment();
            builder.contentType(attachment.getContentType());
            builder.contentUrl(attachment.getUrl());
        }

        // context.facilityType is native R4, read directly rather
        // than from an extension - matches the corrected toFhir,
        // which stopped writing a redundant extension for the same
        // data.
        if (dr.hasContext() && dr.getContext().hasFacilityType()
                && !dr.getContext().getFacilityType().getCoding().isEmpty()) {
            Coding facilityCoding = dr.getContext().getFacilityType().getCodingFirstRep();
            builder.facilityHin(facilityCoding.getCode());
        }

        // context.encounter - the native field, holds a full URL as
        // built by toFhir(); store back just the id portion, same
        // shape Pointer.nehrEncounterRef expects.
        if (dr.hasContext() && dr.getContext().hasEncounter()
                && !dr.getContext().getEncounter().isEmpty()) {
            String encounterRef = dr.getContext().getEncounterFirstRep().getReference();
            if (encounterRef != null) {
                int lastSlash = encounterRef.lastIndexOf('/');
                builder.nehrEncounterRef(lastSlash >= 0 ? encounterRef.substring(lastSlash + 1) : encounterRef);
            }
        }

        Boolean accessible = null;
        boolean locationExcluded = false;

        for (Extension ext : dr.getExtension()) {
            String url = ext.getUrl();
            if (EXT_CONSENT_ACCESSIBLE.equals(url) && ext.getValue() instanceof BooleanType bool) {
                accessible = bool.getValue();
            } else if (EXT_LOCATION_EXCLUDED.equals(url) && ext.getValue() instanceof BooleanType bool) {
                locationExcluded = bool.getValue();
            } else if (EXT_SUBMISSION_SET.equals(url) && ext.getValue() != null) {
                builder.submissionSetId(ext.getValue().primitiveValue());
            } else if (EXT_SOURCE_RESOURCE_TYPE.equals(url) && ext.getValue() != null) {
                builder.resourceType(ext.getValue().primitiveValue());
            }
        }

        // purpose-of-use now travels in securityLabel, not a custom
        // extension - see addConsentExtensions(). Collect every
        // coding under the PurposeOfUse system rather than assuming
        // there's at most one.
        Set<String> purposes = dr.getSecurityLabel().stream()
                .flatMap(cc -> cc.getCoding().stream())
                .filter(c -> PURPOSE_OF_USE_SYSTEM.equals(c.getSystem()))
                .map(Coding::getCode)
                .collect(java.util.stream.Collectors.toSet());

        // The javadoc on this method says a missing consent extension
        // is a protocol violation, not something to default to
        // accessible=true for - the code used to do exactly that
        // (initialised accessible=true and never actually checked
        // whether the extension was present at all), which silently
        // defeated the whole point. Fixed to actually match what was
        // already documented.
        if (accessible == null) {
            throw new IllegalArgumentException(
                    "DocumentReference " + dr.getIdElement().getIdPart()
                            + " has no consent-accessible extension - refusing to default it");
        }

        builder.consent(Consent.from(!accessible, locationExcluded, purposes));

        return builder;
    }

    private Enumerations.DocumentReferenceStatus toFhirStatus(Pointer.Status status) {
        return switch (status) {
            case CURRENT -> Enumerations.DocumentReferenceStatus.CURRENT;
            case SUPERSEDED -> Enumerations.DocumentReferenceStatus.SUPERSEDED;
            case ENTERED_IN_ERROR -> Enumerations.DocumentReferenceStatus.ENTEREDINERROR;
        };
    }

    private Pointer.Status fromFhirStatus(Enumerations.DocumentReferenceStatus status) {
        if (status == null) {
            return Pointer.Status.CURRENT;
        }
        return switch (status) {
            case SUPERSEDED -> Pointer.Status.SUPERSEDED;
            case ENTEREDINERROR -> Pointer.Status.ENTERED_IN_ERROR;
            default -> Pointer.Status.CURRENT;
        };
    }
}
