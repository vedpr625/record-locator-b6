package lk.gov.moh.dhp.rl.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistent record of the latest consent decision for a patient (PHN).
 *
 * <p>One row per PHN, written by {@link lk.gov.moh.dhp.rl.ingest.ConsentEventController}
 * via {@link PatientConsentRepository} regardless of whether any pointer rows exist yet.
 * This eliminates the race condition where an opt-out arriving before the patient's
 * first imaging study was silently lost because the bulk JPQL UPDATE on pointer rows
 * matched zero rows.
 *
 * <p>{@link lk.gov.moh.dhp.rl.ingest.PointerFactory} reads from this table to resolve
 * consent for new pointers instead of querying the pointer rows themselves.
 */
@Entity
@Table(name = "patient_consent")
public class PatientConsent {

    @Id
    @Column(name = "phn", nullable = false)
    private String phn;

    @Column(name = "accessible", nullable = false)
    private boolean accessible;

    @Column(name = "location_excluded", nullable = false)
    private boolean locationExcluded;

    @Column(name = "purpose_of_use", length = 200)
    private String purposeOfUse;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PatientConsent() {}

    private PatientConsent(String phn, boolean accessible, boolean locationExcluded,
                           String purposeOfUse, Instant recordedAt, Instant updatedAt) {
        this.phn = phn;
        this.accessible = accessible;
        this.locationExcluded = locationExcluded;
        this.purposeOfUse = purposeOfUse;
        this.recordedAt = recordedAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Create a new or updated consent record for {@code phn}.
     * If this record already exists in the database, it will be overwritten via
     * {@code save()} (JPA uses the @Id to decide insert vs. update).
     */
    public static PatientConsent of(String phn, boolean accessible, boolean locationExcluded,
                                    String purposeOfUse) {
        Instant now = Instant.now();
        return new PatientConsent(phn, accessible, locationExcluded, purposeOfUse, now, now);
    }

    public String phn() { return phn; }
    public boolean isAccessible() { return accessible; }
    public boolean isLocationExcluded() { return locationExcluded; }
    public String purposeOfUse() { return purposeOfUse; }
    public Instant recordedAt() { return recordedAt; }
    public Instant updatedAt() { return updatedAt; }

    /**
     * Convert to a {@link Consent} value for use in pointer creation / consent gate.
     */
    public Consent toConsent() {
        return Consent.from(accessible, locationExcluded, purposeOfUse);
    }
}
