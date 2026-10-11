package lk.gov.moh.dhp.rl.pointer;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lk.gov.moh.dhp.rl.consent.Consent;

import java.time.Instant;
import java.util.Objects;

/**
 * A pointer to one clinical document or imaging study. This is what
 * ITI-66/67 search against and what ITI-68 resolves to a retrieval
 * URL. We never store the document itself here, only where to find it.
 */
@Entity
@Table(
        name = "pointer",
        indexes = {
                @Index(name = "idx_pointer_phn", columnList = "phn"),
                @Index(name = "idx_pointer_phn_status", columnList = "phn, status"),
                @Index(name = "idx_pointer_facility", columnList = "facility_hin")
        }
)
public class Pointer {

    @Id
    @Column(name = "id", length = 200)
    private String id; // FHIR resource id - DocumentReference.id or StudyInstanceUID for imaging

    /**
     * A random UUID generated once at creation, distinct from id.
     * The MHD entryUUID identifier slice requires urn:uuid:{value}
     * identifying the DocumentReference RESOURCE itself, which the
     * real IHE profile treats as a genuinely different thing from
     * the document the pointer points at (masterIdentifier/uniqueId,
     * which uses id). Confirmed by checking the actual IHE.MHD.
     * EntryUUID.Identifier structure definition: fixed
     * system=urn:ietf:rfc:3986, value must start with urn:uuid: -
     * this is not satisfiable by reusing id, since id isn't
     * constrained to be a UUID at all (it's whatever Dolu/NEHR/
     * Orthanc assigned).
     */
    @Column(name = "entry_uuid", nullable = false, length = 36)
    private String entryUuid;

    /**
     * Patient Health Number (PHN) — the national patient identifier
     * assigned and resolved by B1's Client Registry. Every upstream
     * system (HHIMS, NHDX, Orthanc via DICOM PatientID) sends the PHN.
     *
     * Earlier versions of this field were named "sludi" after the RFP's
     * eventual national identifier, but SLUDI has no production values yet.
     * The column was renamed phn in V4 migration to remove that confusion.
     */
    @Column(name = "phn", nullable = false, length = 64)
    private String phn;

    @Column(name = "resource_type", nullable = false, length = 20)
    private String resourceType; // DocumentReference | ImagingStudy

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "doc_type_loinc", length = 20)
    private String docTypeLoinc;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "content_url", length = 500)
    private String contentUrl;

    @Column(name = "facility_hin", length = 100)
    private String facilityHin;

    @Column(name = "facility_name", length = 200)
    private String facilityName;

    @Column(name = "submission_set_id", length = 200)
    private String submissionSetId;

    /**
     * The NEHR-assigned Encounter id this document/pointer relates
     * to, when known - just the id, not a full URL (the base URL is
     * config, not data, so it belongs in the mapper, not stored per
     * row). Populated when NHDX embeds context.encounter into the
     * DocumentReference it sends to Dolu, or directly for a
     * NEHR-originated pointer. Null for anything not tied to a
     * specific encounter (a standalone lab result, for instance).
     */
    @Column(name = "nehr_encounter_ref", length = 100)
    private String nehrEncounterRef;

    @Embedded
    private Consent consent;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "national_synced", nullable = false)
    private boolean nationalSynced;

    protected Pointer() {
        // JPA
    }

    private Pointer(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.entryUuid = b.entryUuid != null ? b.entryUuid : java.util.UUID.randomUUID().toString();
        this.phn = Objects.requireNonNull(b.phn, "phn");
        this.resourceType = Objects.requireNonNull(b.resourceType, "resourceType");
        this.status = b.status == null ? Status.CURRENT : b.status;
        this.docTypeLoinc = b.docTypeLoinc;
        this.contentType = b.contentType;
        this.contentUrl = b.contentUrl;
        this.facilityHin = b.facilityHin;
        this.facilityName = b.facilityName;
        this.submissionSetId = b.submissionSetId;
        this.nehrEncounterRef = b.nehrEncounterRef;
        this.consent = Objects.requireNonNull(b.consent, "consent - use ConsentResolver, don't default this");
        this.indexedAt = Instant.now();
        this.updatedAt = Instant.now();
        this.nationalSynced = b.nationalSynced;

        if (consent.isLocationExcluded()) {
            this.facilityHin = null;
            this.facilityName = null;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public void applyConsent(Consent newConsent) {
        this.consent = newConsent;
        this.updatedAt = Instant.now();
        if (newConsent.isLocationExcluded()) {
            this.facilityHin = null;
            this.facilityName = null;
        }
    }

    public void updateContentUrl(String url) {
        this.contentUrl = url;
        this.updatedAt = Instant.now();
        this.nationalSynced = false;
    }

    /**
     * For a type-scoped query pointer (e.g. "this patient has
     * DiagnosticReport data, query here") - a repeat signal that a
     * new resource of that type has landed doesn't need a new
     * pointer, since the existing one already covers it as a query,
     * not a snapshot of one instance. This just moves updatedAt
     * forward so "most recently updated" stays meaningful, without
     * churning nationalSynced/contentUrl the way a real content
     * change would.
     */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    public void markSuperseded() {
        this.status = Status.SUPERSEDED;
        this.updatedAt = Instant.now();
    }

    public void markSynced() {
        this.nationalSynced = true;
    }

    public String id() {
        return id;
    }

    public String entryUuid() {
        return entryUuid;
    }

    public String phn() {
        return phn;
    }

    public String resourceType() {
        return resourceType;
    }

    public Status status() {
        return status;
    }

    public String docTypeLoinc() {
        return docTypeLoinc;
    }

    public String contentType() {
        return contentType;
    }

    public String contentUrl() {
        return contentUrl;
    }

    public String facilityHin() {
        return facilityHin;
    }

    public String facilityName() {
        return facilityName;
    }

    public String submissionSetId() {
        return submissionSetId;
    }

    public String nehrEncounterRef() {
        return nehrEncounterRef;
    }

    public Consent consent() {
        return consent;
    }

    public Instant indexedAt() {
        return indexedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isNationalSynced() {
        return nationalSynced;
    }

    public enum Status {
        CURRENT,
        SUPERSEDED,
        ENTERED_IN_ERROR
    }

    public static final class Builder {
        private String id;
        private String entryUuid;
        private String phn;
        private String resourceType;
        private Status status;
        private String docTypeLoinc;
        private String contentType;
        private String contentUrl;
        private String facilityHin;
        private String facilityName;
        private String submissionSetId;
        private String nehrEncounterRef;
        private Consent consent;
        private boolean nationalSynced;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder entryUuid(String entryUuid) {
            this.entryUuid = entryUuid;
            return this;
        }

        public Builder phn(String phn) {
            this.phn = phn;
            return this;
        }

        public Builder resourceType(String resourceType) {
            this.resourceType = resourceType;
            return this;
        }

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public Builder docTypeLoinc(String docTypeLoinc) {
            this.docTypeLoinc = docTypeLoinc;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder contentUrl(String contentUrl) {
            this.contentUrl = contentUrl;
            return this;
        }

        public Builder facilityHin(String facilityHin) {
            this.facilityHin = facilityHin;
            return this;
        }

        public Builder facilityName(String facilityName) {
            this.facilityName = facilityName;
            return this;
        }

        public Builder submissionSetId(String submissionSetId) {
            this.submissionSetId = submissionSetId;
            return this;
        }

        public Builder nehrEncounterRef(String nehrEncounterRef) {
            this.nehrEncounterRef = nehrEncounterRef;
            return this;
        }

        public Builder consent(Consent consent) {
            this.consent = consent;
            return this;
        }

        public Builder nationalSynced(boolean nationalSynced) {
            this.nationalSynced = nationalSynced;
            return this;
        }

        public Pointer build() {
            return new Pointer(this);
        }
    }
}
