package lk.gov.moh.dhp.rl.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Instant;
import java.util.Set;

/**
 * A patient's consent decision as it applies to one pointer. Every
 * {@link lk.gov.moh.dhp.rl.pointer.Pointer} carries one of these -
 * there's no code path that creates a pointer without it.
 *
 * accessible=false means the patient has opted out and this pointer
 * must not appear in any search result. purposeOfUse is empty unless
 * Bundle 2a's Consent resource actually restricts it.
 */
@Embeddable
public class Consent {

    @Column(name = "consent_accessible", nullable = false)
    private boolean accessible;

    @Column(name = "consent_location_excluded", nullable = false)
    private boolean locationExcluded;

    @Column(name = "consent_purpose_of_use")
    private String purposeOfUse; // comma separated, empty = no restriction

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_source", nullable = false, length = 20)
    private Source source;

    @Column(name = "consent_as_of", nullable = false)
    private Instant asOf;

    protected Consent() {
        // JPA
    }

    private Consent(boolean accessible, boolean locationExcluded, String purposeOfUse, Source source, Instant asOf) {
        this.accessible = accessible;
        this.locationExcluded = locationExcluded;
        this.purposeOfUse = purposeOfUse;
        this.source = source;
        this.asOf = asOf;
    }

    public static Consent from(boolean optedOut, boolean locationExcluded, Set<String> purposeOfUse) {
        String joined = purposeOfUse == null || purposeOfUse.isEmpty()
                ? null
                : String.join(",", purposeOfUse);
        return new Consent(!optedOut, locationExcluded, joined, Source.BUNDLE_2A, Instant.now());
    }

    /**
     * Used only when we genuinely have no consent information yet -
     * e.g. a brand new patient before their first Bundle 2a lookup.
     * Fails closed on purpose.
     */
    public static Consent unknown() {
        return new Consent(false, false, null, Source.UNKNOWN, Instant.now());
    }

    /**
     * Default for edge/Bundle-6 deployments that have no consent webhook.
     * Without a Bundle 2a integration, consent status is never updated, so
     * defaulting to inaccessible (unknown()) would make every newly-polled
     * study permanently invisible. Accessible-by-default matches the intended
     * Bundle 6 operational model: all studies are visible unless explicitly
     * opted out (which requires Bundle 2a to be wired in).
     */
    public static Consent defaultAccessible() {
        return new Consent(true, false, null, Source.UNKNOWN, Instant.now());
    }

    public boolean isAccessible() {
        return accessible;
    }

    public boolean isLocationExcluded() {
        return locationExcluded;
    }

    /** The raw stored form - used when persisting, not for checking a specific purpose (use allows() for that). */
    public String purposeOfUseRaw() {
        return purposeOfUse;
    }

    /**
     * Not called anywhere yet - purpose-of-use filtering at query
     * time hasn't been ported into this rewrite. Kept correct rather
     * than removed, since it's a real part of the consent model, not
     * dead weight - but worth a real set-membership check now rather
     * than leaving a String.contains() substring match sitting here
     * for something to eventually wire up and silently misbehave on
     * (e.g. a requested purpose of "TREAT" would incorrectly match a
     * stored "TREATMENT" under a plain substring check).
     */
    public boolean allows(String requestedPurpose) {
        if (purposeOfUse == null || requestedPurpose == null) {
            return true;
        }
        return Set.of(purposeOfUse.split(",")).contains(requestedPurpose);
    }

    public Source source() {
        return source;
    }

    public Instant asOf() {
        return asOf;
    }

    public enum Source {
        BUNDLE_2A,
        UNKNOWN
    }
}
