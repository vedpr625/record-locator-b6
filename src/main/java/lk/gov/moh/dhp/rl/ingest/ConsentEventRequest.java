package lk.gov.moh.dhp.rl.ingest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload that Bundle 2a POSTs to POST /consent-events to record a patient's
 * consent decision at the National Record Locator.
 *
 * When received, the RL bulk-updates all Pointer rows for this PHN:
 *   - consent.accessible  → from this payload
 *   - consent.locationExcluded → from this payload
 *   - consent.purposeOfUse → from this payload (comma-separated purposes)
 *   - consent.source     → "BUNDLE_2A"
 *   - nationalSynced     → false (changed rows need re-sync)
 *
 * Auth: JWT OAuth2 resource server (always active) + mTLS header filter
 * (enabled via rl.mtls.enabled=true in production).
 *
 * TODO: route to domain node when domain affinity federation is implemented.
 */
public record ConsentEventRequest(

        /** Patient Health Number whose consent changed. */
        @NotBlank String phn,

        /**
         * True = patient has opted in (data accessible).
         * False = patient has opted out (data must be withheld on search).
         */
        boolean accessible,

        /**
         * True = patient has requested location data be excluded from results
         * (facility name and HIN must be cleared).
         */
        boolean locationExcluded,

        /**
         * Comma-separated list of allowed purpose-of-use codes
         * (e.g. "TREAT,HPAYMT"). Null or empty = unrestricted.
         *
         * <p>M-8: constrained to at most 200 characters and to the
         * pattern {@code [A-Z,]*} (uppercase purpose-of-use codes separated
         * by commas) so a malformed payload is rejected early at the binding
         * layer rather than silently stored and later causing a query anomaly.
         */
        @Size(max = 200)
        @Pattern(regexp = "[A-Z,]*", message = "purposeOfUse must be comma-separated uppercase codes, e.g. TREAT,HPAYMT")
        String purposeOfUse
) {}
