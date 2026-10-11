package lk.gov.moh.dhp.rl.fhir;

import lk.gov.moh.dhp.rl.consent.Consent;
import lk.gov.moh.dhp.rl.pointer.Pointer;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Enumerations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PointerFhirMapper — no Spring context, manual property injection.
 */
class PointerFhirMapperTest {

    private static final String HOME_COMMUNITY_ID = "urn:oid:2.16.840.1.113883.3.1";
    private static final String NEHR_BASE = "https://nehr.example.lk/fhir";

    PointerFhirMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new PointerFhirMapper();
        ReflectionTestUtils.setField(mapper, "homeCommunityId", HOME_COMMUNITY_ID);
        ReflectionTestUtils.setField(mapper, "nehrBaseUrl", NEHR_BASE);
    }

    // ── toFhir ────────────────────────────────────────────────────────────────

    @Test
    void toFhir_mapsId() {
        Pointer pointer = buildPointer("doc-001", "PHN001", Pointer.Status.CURRENT);
        DocumentReference dr = mapper.toFhir(pointer);
        assertThat(dr.getId()).isEqualTo("doc-001");
    }

    @Test
    void toFhir_mapsPatientReference() {
        Pointer pointer = buildPointer("doc-001", "PHN001", Pointer.Status.CURRENT);
        DocumentReference dr = mapper.toFhir(pointer);
        assertThat(dr.getSubject().getReference()).isEqualTo("Patient/PHN001");
    }

    @Test
    void toFhir_statusCurrent() {
        Pointer pointer = buildPointer("doc-001", "PHN001", Pointer.Status.CURRENT);
        assertThat(mapper.toFhir(pointer).getStatus())
                .isEqualTo(Enumerations.DocumentReferenceStatus.CURRENT);
    }

    @Test
    void toFhir_statusSuperseded() {
        Pointer pointer = buildPointer("doc-001", "PHN001", Pointer.Status.SUPERSEDED);
        assertThat(mapper.toFhir(pointer).getStatus())
                .isEqualTo(Enumerations.DocumentReferenceStatus.SUPERSEDED);
    }

    @Test
    void toFhir_statusEnteredInError() {
        Pointer pointer = buildPointer("doc-001", "PHN001", Pointer.Status.ENTERED_IN_ERROR);
        assertThat(mapper.toFhir(pointer).getStatus())
                .isEqualTo(Enumerations.DocumentReferenceStatus.ENTEREDINERROR);
    }

    @Test
    void toFhir_consentAccessibleExtension_true() {
        Pointer pointer = buildPointerWithConsent("doc-001", Consent.defaultAccessible());
        DocumentReference dr = mapper.toFhir(pointer);

        boolean found = dr.getExtension().stream()
                .filter(e -> e.getUrl().endsWith("consent-accessible"))
                .anyMatch(e -> e.getValue() instanceof BooleanType b && b.booleanValue());
        assertThat(found).isTrue();
    }

    @Test
    void toFhir_consentAccessibleExtension_false() {
        Pointer pointer = buildPointerWithConsent("doc-001", Consent.unknown());
        DocumentReference dr = mapper.toFhir(pointer);

        boolean found = dr.getExtension().stream()
                .filter(e -> e.getUrl().endsWith("consent-accessible"))
                .anyMatch(e -> e.getValue() instanceof BooleanType b && !b.booleanValue());
        assertThat(found).isTrue();
    }

    @Test
    void toFhir_purposeOfUse_appearsInSecurityLabel() {
        Pointer pointer = buildPointerWithConsent("doc-001",
                Consent.from(false, false, Set.of("TREAT")));
        DocumentReference dr = mapper.toFhir(pointer);

        boolean foundTreat = dr.getSecurityLabel().stream()
                .flatMap(cc -> cc.getCoding().stream())
                .anyMatch(c -> "TREAT".equals(c.getCode()));
        assertThat(foundTreat).isTrue();
    }

    @Test
    void toFhir_entryUuidSlice_hasUrnPrefix() {
        Pointer pointer = buildPointer("doc-001", "PHN001", Pointer.Status.CURRENT);
        DocumentReference dr = mapper.toFhir(pointer);

        boolean hasUrnUuid = dr.getIdentifier().stream()
                .anyMatch(id -> id.getValue() != null && id.getValue().startsWith("urn:uuid:"));
        assertThat(hasUrnUuid).isTrue();
    }

    @Test
    void toFhir_imagingStudy_addsdicomUidIdentifier() {
        Pointer pointer = Pointer.builder()
                .id("1.2.3.4.5")
                .phn("PHN001")
                .resourceType("ImagingStudy")
                .docTypeLoinc("18747-6")
                .contentType("application/dicom+json")
                .contentUrl("http://orthanc/wado")
                .facilityHin("HIN001")
                .facilityName("Test Hospital")
                .consent(Consent.defaultAccessible())
                .nationalSynced(false)
                .build();
        DocumentReference dr = mapper.toFhir(pointer);

        boolean hasDicom = dr.getIdentifier().stream()
                .anyMatch(id -> "urn:dicom:uid".equals(id.getSystem()));
        assertThat(hasDicom).isTrue();
    }

    @Test
    void toFhir_nehrEncounterRef_addedToContext() {
        Pointer pointer = Pointer.builder()
                .id("doc-001")
                .phn("PHN001")
                .resourceType("DocumentReference")
                .contentUrl("http://example.com/doc")
                .facilityHin("HIN001")
                .facilityName("Test Hospital")
                .consent(Consent.defaultAccessible())
                .nationalSynced(false)
                .nehrEncounterRef("ENC-001")
                .build();
        DocumentReference dr = mapper.toFhir(pointer);

        assertThat(dr.getContext().getEncounter()).hasSize(1);
        assertThat(dr.getContext().getEncounterFirstRep().getReference())
                .contains("ENC-001");
    }

    // ── fromFhir ──────────────────────────────────────────────────────────────

    @Test
    void fromFhir_missingConsentExtension_throws() {
        DocumentReference dr = new DocumentReference();
        dr.setId("doc-001");
        dr.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);

        assertThatThrownBy(() -> mapper.fromFhir(dr))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no consent-accessible extension");
    }

    @Test
    void fromFhir_roundTrip_preservesPhn() {
        Pointer original = buildPointer("doc-001", "PHN999", Pointer.Status.CURRENT);
        DocumentReference dr = mapper.toFhir(original);

        Pointer.Builder rebuilt = mapper.fromFhir(dr);
        assertThat(rebuilt.build().phn()).isEqualTo("PHN999");
    }

    @Test
    void fromFhir_roundTrip_preservesConsent() {
        Pointer original = buildPointerWithConsent("doc-001", Consent.defaultAccessible());
        DocumentReference dr = mapper.toFhir(original);

        Pointer rebuilt = mapper.fromFhir(dr).build();
        assertThat(rebuilt.consent().isAccessible()).isTrue();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Pointer buildPointer(String id, String phn, Pointer.Status status) {
        return Pointer.builder()
                .id(id)
                .phn(phn)
                .resourceType("DocumentReference")
                .docTypeLoinc("11502-2")
                .contentType("application/fhir+json")
                .contentUrl("http://example.com/" + id)
                .facilityHin("HIN001")
                .facilityName("Test Hospital")
                .status(status)
                .consent(Consent.defaultAccessible())
                .nationalSynced(false)
                .build();
    }

    private Pointer buildPointerWithConsent(String id, Consent consent) {
        return Pointer.builder()
                .id(id)
                .phn("PHN001")
                .resourceType("DocumentReference")
                .contentUrl("http://example.com/" + id)
                .facilityHin("HIN001")
                .facilityName("Test Hospital")
                .consent(consent)
                .nationalSynced(false)
                .build();
    }
}
