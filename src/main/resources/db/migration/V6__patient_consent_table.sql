-- V6: Dedicated patient_consent table for O(1) consent lookup.
--
-- Rationale: Previously consent was derived by scanning pointer rows
-- (existsInaccessibleByPhn).  This had two problems:
--   1. O(n) scan per patient with many imaging studies.
--   2. Race condition: consent arriving before the first pointer was
--      silently lost because the JPQL UPDATE on pointer rows matched 0 rows.
--
-- This table stores ONE row per PHN.  ConsentEventController upserts into it
-- regardless of whether any pointers exist yet.  PointerFactory reads from it
-- instead of from pointer rows to resolve consent for new pointers.
--
-- The pointer rows remain the authoritative store for per-pointer consent state
-- (accessible, location_excluded, purpose_of_use columns) — this table is an
-- index/projection used to determine the consent state for a PHN at the time
-- a new pointer is ingested and to guard consent-blocked searches.

CREATE TABLE patient_consent (
    phn               VARCHAR(255)             NOT NULL PRIMARY KEY,
    accessible        BOOLEAN                  NOT NULL DEFAULT TRUE,
    location_excluded BOOLEAN                  NOT NULL DEFAULT FALSE,
    purpose_of_use    VARCHAR(200),
    recorded_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Seed from existing pointer rows so historical data is not lost on migration.
-- One row per PHN, using the most-recently-updated pointer row as the source of
-- truth (same heuristic as PointerFactory.resolveKnownConsent).
--
-- ANSI SQL compatible: correlated subquery (no DISTINCT ON) + INSERT ... SELECT
-- with no-conflict guard via NOT EXISTS.  This runs correctly on both PostgreSQL
-- and H2 in PostgreSQL compatibility mode.
INSERT INTO patient_consent (phn, accessible, location_excluded, purpose_of_use, recorded_at, updated_at)
SELECT
    p.phn,
    p.consent_accessible        AS accessible,
    p.consent_location_excluded AS location_excluded,
    p.consent_purpose_of_use    AS purpose_of_use,
    p.updated_at                AS recorded_at,
    p.updated_at
FROM pointer p
WHERE p.updated_at = (
    SELECT MAX(p2.updated_at)
    FROM pointer p2
    WHERE p2.phn = p.phn
)
  AND NOT EXISTS (
    SELECT 1 FROM patient_consent pc WHERE pc.phn = p.phn
);

COMMENT ON TABLE patient_consent IS
    'One row per PHN capturing the most recent consent decision from Bundle 2a.  '
    'Written by ConsentEventController before any pointer rows exist, so consent '
    'is never silently lost on first opt-out.';
