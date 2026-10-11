package lk.gov.moh.dhp.rl.pointer;

import lk.gov.moh.dhp.rl.consent.Consent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for Pointer entity — builder, state transitions, consent application.
 * No Spring context; pure domain logic.
 */
class PointerTest {

    // ── builder ───────────────────────────────────────────────────────────────

    @Test
    void builder_setsAllFields() {
        Pointer p = Pointer.builder()
                .id("doc-001")
                .phn("PHN001")
                .resourceType("DocumentReference")
                .contentUrl("http://example.com/doc-001")
                .facilityHin("HIN001")
                .facilityName("General Hospital")
                .docTypeLoinc("11488-4")
                .contentType("application/pdf")
                .submissionSetId("ss-001")
                .nehrEncounterRef("enc-001")
                .consent(Consent.defaultAccessible())
                .nationalSynced(false)
                .build();

        assertThat(p.id()).isEqualTo("doc-001");
        assertThat(p.phn()).isEqualTo("PHN001");
        assertThat(p.resourceType()).isEqualTo("DocumentReference");
        assertThat(p.contentUrl()).isEqualTo("http://example.com/doc-001");
        assertThat(p.facilityHin()).isEqualTo("HIN001");
        assertThat(p.facilityName()).isEqualTo("General Hospital");
        assertThat(p.docTypeLoinc()).isEqualTo("11488-4");
        assertThat(p.contentType()).isEqualTo("application/pdf");
        assertThat(p.submissionSetId()).isEqualTo("ss-001");
        assertThat(p.nehrEncounterRef()).isEqualTo("enc-001");
        assertThat(p.isNationalSynced()).isFalse();
        assertThat(p.consent().isAccessible()).isTrue();
        assertThat(p.status()).isEqualTo(Pointer.Status.CURRENT);
        assertThat(p.indexedAt()).isNotNull();
        assertThat(p.updatedAt()).isNotNull();
    }

    @Test
    void builder_generatesEntryUuid_whenNotSet() {
        Pointer p = minimalPointer("doc-001");
        assertThat(p.entryUuid()).isNotNull();
        assertThat(p.entryUuid()).hasSize(36); // UUID format
    }

    @Test
    void builder_usesProvidedEntryUuid() {
        String uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        Pointer p = Pointer.builder()
                .id("doc-001").phn("PHN001")
                .resourceType("DocumentReference")
                .consent(Consent.defaultAccessible())
                .entryUuid(uuid)
                .build();
        assertThat(p.entryUuid()).isEqualTo(uuid);
    }

    @Test
    void builder_defaultsStatusToCurrent() {
        Pointer p = minimalPointer("doc-001");
        assertThat(p.status()).isEqualTo(Pointer.Status.CURRENT);
    }

    @Test
    void builder_throwsWhenIdMissing() {
        assertThatThrownBy(() -> Pointer.builder()
                .phn("PHN001")
                .resourceType("DocumentReference")
                .consent(Consent.defaultAccessible())
                .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id");
    }

    @Test
    void builder_throwsWhenPhnMissing() {
        assertThatThrownBy(() -> Pointer.builder()
                .id("doc-001")
                .resourceType("DocumentReference")
                .consent(Consent.defaultAccessible())
                .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("phn");
    }

    @Test
    void builder_throwsWhenConsentMissing() {
        assertThatThrownBy(() -> Pointer.builder()
                .id("doc-001")
                .phn("PHN001")
                .resourceType("DocumentReference")
                .build())
                .isInstanceOf(NullPointerException.class);
    }

    // ── locationExcluded strips facility ─────────────────────────────────────

    @Test
    void builder_locationExcluded_nullsFacilityFields() {
        Consent excluded = Consent.from(false, true, (String) null); // inaccessible+locationExcluded
        Pointer p = Pointer.builder()
                .id("doc-001").phn("PHN001")
                .resourceType("DocumentReference")
                .facilityHin("HIN001")
                .facilityName("General Hospital")
                .consent(excluded)
                .build();

        assertThat(p.facilityHin()).isNull();
        assertThat(p.facilityName()).isNull();
    }

    @Test
    void builder_locationNotExcluded_keepsFacilityFields() {
        Pointer p = Pointer.builder()
                .id("doc-001").phn("PHN001")
                .resourceType("DocumentReference")
                .facilityHin("HIN001")
                .facilityName("General Hospital")
                .consent(Consent.defaultAccessible())
                .build();

        assertThat(p.facilityHin()).isEqualTo("HIN001");
        assertThat(p.facilityName()).isEqualTo("General Hospital");
    }

    // ── applyConsent ──────────────────────────────────────────────────────────

    @Test
    void applyConsent_changesConsent() {
        Pointer p = minimalPointerWithFacility("doc-001");
        assertThat(p.consent().isAccessible()).isTrue();

        p.applyConsent(Consent.unknown()); // unknown = inaccessible

        assertThat(p.consent().isAccessible()).isFalse();
    }

    @Test
    void applyConsent_locationExcluded_nullsFacility() {
        Pointer p = minimalPointerWithFacility("doc-001");
        assertThat(p.facilityHin()).isEqualTo("HIN001");

        Consent excluded = Consent.from(false, true, (String) null);
        p.applyConsent(excluded);

        assertThat(p.facilityHin()).isNull();
        assertThat(p.facilityName()).isNull();
    }

    @Test
    void applyConsent_updatesTimestamp() throws InterruptedException {
        Pointer p = minimalPointer("doc-001");
        Instant before = p.updatedAt();
        Thread.sleep(5);

        p.applyConsent(Consent.unknown());

        assertThat(p.updatedAt()).isAfter(before);
    }

    // ── updateContentUrl ──────────────────────────────────────────────────────

    @Test
    void updateContentUrl_changesUrlAndClearsSynced() {
        Pointer p = Pointer.builder()
                .id("doc-001").phn("PHN001")
                .resourceType("ImagingStudy")
                .contentUrl("http://old.url")
                .consent(Consent.defaultAccessible())
                .nationalSynced(true)
                .build();

        p.updateContentUrl("http://new.url");

        assertThat(p.contentUrl()).isEqualTo("http://new.url");
        assertThat(p.isNationalSynced()).isFalse();
    }

    // ── touch ─────────────────────────────────────────────────────────────────

    @Test
    void touch_updatesTimestampOnly() throws InterruptedException {
        Pointer p = minimalPointer("doc-001");
        Instant before = p.updatedAt();
        String urlBefore = p.contentUrl();
        boolean syncedBefore = p.isNationalSynced();
        Thread.sleep(5);

        p.touch();

        assertThat(p.updatedAt()).isAfter(before);
        assertThat(p.contentUrl()).isEqualTo(urlBefore);
        assertThat(p.isNationalSynced()).isEqualTo(syncedBefore);
    }

    // ── markSuperseded / markSynced ───────────────────────────────────────────

    @Test
    void markSuperseded_changesStatus() {
        Pointer p = minimalPointer("doc-001");
        assertThat(p.status()).isEqualTo(Pointer.Status.CURRENT);

        p.markSuperseded();

        assertThat(p.status()).isEqualTo(Pointer.Status.SUPERSEDED);
    }

    @Test
    void markSynced_setsFlag() {
        Pointer p = minimalPointer("doc-001");
        assertThat(p.isNationalSynced()).isFalse();

        p.markSynced();

        assertThat(p.isNationalSynced()).isTrue();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Pointer minimalPointer(String id) {
        return Pointer.builder()
                .id(id).phn("PHN001")
                .resourceType("DocumentReference")
                .contentUrl("http://example.com/" + id)
                .consent(Consent.defaultAccessible())
                .build();
    }

    private Pointer minimalPointerWithFacility(String id) {
        return Pointer.builder()
                .id(id).phn("PHN001")
                .resourceType("DocumentReference")
                .contentUrl("http://example.com/" + id)
                .facilityHin("HIN001")
                .facilityName("Test Hospital")
                .consent(Consent.defaultAccessible())
                .build();
    }
}
