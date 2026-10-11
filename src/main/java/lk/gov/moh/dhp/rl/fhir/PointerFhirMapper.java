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
    private static final String URN_UUID_PREFIX = "urn:uuid:";

    @Value("${rl.home-community-id}")
    private String homeCommunityId;

    @Value("${rl.nehr-base-url:}")
    private String nehrBaseUrl;

    // M-1: toFhir() was ~120 lines; extracted helpers to bring each logical section
    // under the SonarQube S138 method-length limit.
    public DocumentReference toFhir(Pointer pointer) {
        DocumentReference dr = new DocumentReference();
        dr.setId(pointer.id());
        addIheIdentifiers(dr, pointer);
        dr.setStatus(toFhirStatus(pointer.status()));
        dr.setSubject(new Reference("Patient/" + pointer.phn()));
        dr.setType(buildDocType(pointer));
        addResourceTypeMarker(dr, pointer);
        addAttachment(dr, pointer);
        addContextBlock(dr, pointer);
        dr.setDate(Date.from(pointer.indexedAt()));
        addConsentExtensions(dr, pointer.consent());
        if (pointer.submissionSetId() != null) {
            dr.addExtension(new Extension(EXT_SUBMISSION_SET, new StringType(pointer.submissionSetId())));
        }
        return dr;
    }

    /**
     * Writes the three IHE-required identifier slices and the homeCommunityId extension.
     * masterIdentifier (uniqueId), identifier[uniqueId slice], identifier[entryUUID slice],
     * and the IHE ihe-HomeCommunityId extension.
     */
    private void addIheIdentifiers(DocumentReference dr, Pointer pointer) {
        // masterIdentifier.type is mandatory under IHE.MHD.UniqueIdIdentifier.
        Identifier masterIdentifier = dr.getMasterIdentifier();
        masterIdentifier.setSystem(homeCommunityId).setValue(pointer.id());
        masterIdentifier.setType(new CodeableConcept()
                .addCoding(new Coding(IHE_IDENTIFIER_TYPE_SYSTEM, "uniqueId", null)));

        // The uniqueId slice on identifier[]: must exactly match masterIdentifier.
        dr.addIdentifier(new Identifier()
                .setSystem(homeCommunityId)
                .setValue(pointer.id())
                .setType(new CodeableConcept()
                        .addCoding(new Coding(IHE_IDENTIFIER_TYPE_SYSTEM, "uniqueId", null))));

        // The entryUUID slice: use=official, system=urn:ietf:rfc:3986, value=urn:uuid:{uuid}.
        dr.addIdentifier(new Identifier()
                .setUse(Identifier.IdentifierUse.OFFICIAL)
                .setSystem(URN_OID_SYSTEM)
                .setValue(URN_UUID_PREFIX + pointer.entryUuid()));

        // IHE ihe-HomeCommunityId extension (strip urn:oid: prefix before wrapping).
        String bareOid = homeCommunityId.startsWith("urn:oid:")
                ? homeCommunityId.substring("urn:oid:".length())
                : homeCommunityId;
        dr.addExtension(new Extension(IHE_HOME_COMMUNITY_ID_EXT, new org.hl7.fhir.r4.model.OidType(bareOid)));
    }

    /**
     * Builds the mandatory DocumentReference.type CodeableConcept.
     * Falls back to LOINC 34133-9 (summary of episode note) when no LOINC code is set
     * to satisfy the IHE MHD 1..1 cardinality constraint.
     */
    private CodeableConcept buildDocType(Pointer pointer) {
        return new CodeableConcept().addCoding(pointer.docTypeLoinc() != null
                ? new Coding(LOINC, pointer.docTypeLoinc(), null)
                : new Coding(LOINC, "34133-9", "Summary of episode note"));
    }

    /**
     * Adds a DICOM UID identifier for imaging studies, or a source-resource-type
     * extension for NEHR-originated non-DocumentReference pointers.
     */
    private void addResourceTypeMarker(DocumentReference dr, Pointer pointer) {
        if ("ImagingStudy".equals(pointer.resourceType())) {
            dr.addIdentifier(new Identifier().setSystem(DICOM_UID_SYSTEM).setValue(pointer.id()));
        } else if (!"DocumentReference".equals(pointer.resourceType())) {
            dr.addExtension(new Extension(EXT_SOURCE_RESOURCE_TYPE, new StringType(pointer.resourceType())));
        }
    }

    /** Adds the content attachment (contentType + contentUrl). */
    private void addAttachment(DocumentReference dr, Pointer pointer) {
        Attachment attachment = new Attachment();
        attachment.setContentType(pointer.contentType());
        attachment.setUrl(pointer.contentUrl());
        dr.addContent().setAttachment(attachment);
    }

    /**
     * Populates context.facilityType and context.encounter when the pointer has the
     * relevant data. I-2: omits context.encounter when nehrBaseUrl is blank to avoid
     * writing an unresolvable relative reference.
     */
    private void addContextBlock(DocumentReference dr, Pointer pointer) {
        if (pointer.facilityHin() != null) {
            dr.getContext().setFacilityType(new CodeableConcept()
                    .addCoding(new Coding(homeCommunityId, pointer.facilityHin(), pointer.facilityName())));
        }
        if (pointer.nehrEncounterRef() != null && nehrBaseUrl != null && !nehrBaseUrl.isBlank()) {
            dr.getContext().addEncounter(new Reference(nehrBaseUrl + "/Encounter/" + pointer.nehrEncounterRef()));
        }
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
     * Builds a fresh pointer from a resource received over the wire.
     * Used both when a forwarded pointer lands from a child node and when parsing new source data.
     * Throws if the consent extensions aren't present; a pointer with no stated consent is a
     * protocol violation here, not something we default to accessible.
     *
     * <p>M-2: fromFhir() was ~108 lines; extracted helpers to bring each logical section
     * under the SonarQube S138 method-length limit.
     */
    public Pointer.Builder fromFhir(DocumentReference dr) {
        Pointer.Builder builder = Pointer.builder()
                .id(dr.getIdElement().getIdPart())
                .resourceType("DocumentReference")
                .status(fromFhirStatus(dr.getStatus()));

        // entryUUID slice: urn:ietf:rfc:3986 system, value starts with urn:uuid:.
        // Missing entryUUID isn't a protocol violation — Pointer's own constructor generates one.
        dr.getIdentifier().stream()
                .filter(id -> URN_OID_SYSTEM.equals(id.getSystem())
                        && id.hasValue() && id.getValue().startsWith(URN_UUID_PREFIX))
                .findFirst()
                .ifPresent(id -> builder.entryUuid(id.getValue().substring(URN_UUID_PREFIX.length())));

        extractPhn(dr, builder);
        extractContent(dr, builder);
        extractContext(dr, builder);
        extractExtensions(dr, builder);
        builder.consent(extractConsent(dr));

        return builder;
    }

    /** Strips "Patient/" prefix via lastIndexOf to avoid mangling a PHN that contains that substring. */
    private void extractPhn(DocumentReference dr, Pointer.Builder builder) {
        if (dr.hasSubject() && dr.getSubject().hasReference()) {
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
    }

    /** Reads contentType and contentUrl from the first content element. */
    private void extractContent(DocumentReference dr, Pointer.Builder builder) {
        if (dr.hasContent() && !dr.getContent().isEmpty()) {
            Attachment attachment = dr.getContentFirstRep().getAttachment();
            builder.contentType(attachment.getContentType());
            builder.contentUrl(attachment.getUrl());
        }
    }

    /**
     * Reads context.facilityType (native R4) and context.encounter.
     * Encounter URL is stored back as just the id portion (last path segment).
     */
    private void extractContext(DocumentReference dr, Pointer.Builder builder) {
        if (!dr.hasContext()) return;
        DocumentReference.DocumentReferenceContextComponent ctx = dr.getContext();
        if (ctx.hasFacilityType() && !ctx.getFacilityType().getCoding().isEmpty()) {
            builder.facilityHin(ctx.getFacilityType().getCodingFirstRep().getCode());
        }
        if (ctx.hasEncounter() && !ctx.getEncounter().isEmpty()) {
            String encounterRef = ctx.getEncounterFirstRep().getReference();
            if (encounterRef != null) {
                int lastSlash = encounterRef.lastIndexOf('/');
                builder.nehrEncounterRef(lastSlash >= 0 ? encounterRef.substring(lastSlash + 1) : encounterRef);
            }
        }
    }

    /**
     * Reads the four custom DHP extensions: consent-accessible, location-excluded,
     * submission-set-id, and source-resource-type, applying each to the builder.
     * Does NOT read consent here — that is handled by extractConsent() so it can
     * validate presence and throw.
     */
    private void extractExtensions(DocumentReference dr, Pointer.Builder builder) {
        for (Extension ext : dr.getExtension()) {
            String url = ext.getUrl();
            if (EXT_SUBMISSION_SET.equals(url) && ext.getValue() != null) {
                builder.submissionSetId(ext.getValue().primitiveValue());
            } else if (EXT_SOURCE_RESOURCE_TYPE.equals(url) && ext.getValue() != null) {
                builder.resourceType(ext.getValue().primitiveValue());
            }
        }
    }

    /**
     * Reads consent-accessible, location-excluded extensions and securityLabel purpose-of-use
     * codings, assembles a Consent, and throws if consent-accessible is absent (protocol violation).
     */
    private Consent extractConsent(DocumentReference dr) {
        Boolean accessible = null;
        boolean locationExcluded = false;
        for (Extension ext : dr.getExtension()) {
            String url = ext.getUrl();
            if (EXT_CONSENT_ACCESSIBLE.equals(url) && ext.getValue() instanceof BooleanType bool) {
                accessible = bool.getValue();
            } else if (EXT_LOCATION_EXCLUDED.equals(url) && ext.getValue() instanceof BooleanType bool) {
                locationExcluded = bool.getValue();
            }
        }
        if (accessible == null) {
            throw new IllegalArgumentException(
                    "DocumentReference " + dr.getIdElement().getIdPart()
                            + " has no consent-accessible extension - refusing to default it");
        }
        // purpose-of-use travels in securityLabel (not a custom extension).
        Set<String> purposes = dr.getSecurityLabel().stream()
                .flatMap(cc -> cc.getCoding().stream())
                .filter(c -> PURPOSE_OF_USE_SYSTEM.equals(c.getSystem()))
                .map(Coding::getCode)
                .collect(java.util.stream.Collectors.toSet());
        // M-9/M-10: fromAccessible() avoids the confusing !accessible inversion.
        return Consent.fromAccessible(accessible, locationExcluded, purposes);
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
