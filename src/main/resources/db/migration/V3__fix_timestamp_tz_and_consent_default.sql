-- Fix 1: Use TIMESTAMP WITH TIME ZONE so UTC storage is enforced by the DB
--         rather than relying on JVM timezone being set correctly.
--         PostgreSQL TIMESTAMP (without tz) stores local time; TIMESTAMPTZ
--         always normalises to UTC regardless of the JVM's default timezone.
ALTER TABLE pointer
    ALTER COLUMN consent_as_of TYPE TIMESTAMPTZ USING consent_as_of AT TIME ZONE 'UTC',
    ALTER COLUMN indexed_at    TYPE TIMESTAMPTZ USING indexed_at    AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at    TYPE TIMESTAMPTZ USING updated_at    AT TIME ZONE 'UTC';

ALTER TABLE audit_event
    ALTER COLUMN recorded_at TYPE TIMESTAMPTZ USING recorded_at AT TIME ZONE 'UTC';

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
