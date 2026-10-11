-- Fix 1: Use TIMESTAMP WITH TIME ZONE so UTC storage is enforced by the DB
--         rather than relying on JVM timezone being set correctly.
--         PostgreSQL TIMESTAMP (without tz) stores local time; TIMESTAMP WITH
--         TIME ZONE always normalises to UTC regardless of the JVM's default
--         timezone.
--
--         ALTER COLUMN ... SET DATA TYPE ... is ANSI SQL 2003, supported by
--         both PostgreSQL (≥ 9.x) and H2 in PostgreSQL compatibility mode.
--         This avoids the PostgreSQL-specific TIMESTAMPTZ alias and the
--         USING clause (PostgreSQL accepts the implicit cast; H2 stores
--         TIMESTAMP as UTC internally and needs no cast).  Each column is
--         altered in its own statement because H2 does not support altering
--         multiple columns in one ALTER TABLE.
ALTER TABLE pointer
    ALTER COLUMN consent_as_of SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE pointer
    ALTER COLUMN indexed_at    SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE pointer
    ALTER COLUMN updated_at    SET DATA TYPE TIMESTAMP WITH TIME ZONE;

ALTER TABLE audit_event
    ALTER COLUMN recorded_at   SET DATA TYPE TIMESTAMP WITH TIME ZONE;

-- Fix 2: Change the default for consent_accessible to TRUE for the edge/Bundle-6
--         profile (no consent webhook — defaulting to FALSE means every newly-polled
--         study is permanently invisible until a consent event arrives, which never
--         happens in this deployment).
ALTER TABLE pointer
    ALTER COLUMN consent_accessible SET DEFAULT TRUE;

-- Fix 3: Back-fill any existing rows that were created with accessible=FALSE
--         due to the old Consent.unknown() default. On a fresh Bundle 6 deployment
--         this is a no-op; on a deployment that has been running with the old default
--         it makes previously-invisible studies visible.
UPDATE pointer
SET    consent_accessible = TRUE,
       consent_source      = 'UNKNOWN'
WHERE  consent_accessible = FALSE
  AND  consent_source = 'UNKNOWN';
