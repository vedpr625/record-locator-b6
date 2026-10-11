-- Rename the patient identifier column from "sludi" to "phn".
--
-- Background: the column was originally named "sludi" after the RFP's
-- eventual national identifier (Sri Lanka Unique Digital Identifier), but
-- SLUDI has no production values — what every upstream system (HHIMS, NHDX,
-- Orthanc) actually sends is the PHN (Personal Health Number) assigned by
-- B1's Client Registry. Renaming removes that confusion for all future
-- readers of the schema.
--
-- The application code was also updated (phn field, phn() accessor) in the
-- same change that added this migration, so V4 and the jar must be deployed
-- together — Flyway's validate step will fail against the old jar because
-- Pointer.java's @Column(name="sludi") annotation will no longer match this
-- column name.

-- pointer table
ALTER TABLE pointer RENAME COLUMN sludi TO phn;
DROP INDEX IF EXISTS idx_pointer_sludi;
DROP INDEX IF EXISTS idx_pointer_sludi_status;
CREATE INDEX idx_pointer_phn        ON pointer (phn);
CREATE INDEX idx_pointer_phn_status ON pointer (phn, status);

-- audit_event table
ALTER TABLE audit_event RENAME COLUMN sludi TO phn;
DROP INDEX IF EXISTS idx_audit_event_sludi;
CREATE INDEX idx_audit_event_phn ON audit_event (phn);

-- -----------------------------------------------------------------------
-- OpenSearch patient index
-- -----------------------------------------------------------------------
-- The OpenSearch index "rl-patients" is NOT managed by Flyway (it's not a
-- relational table). Create it via the OpenSearch REST API before starting
-- the application with rl.opensearch.enabled=true:
--
--   PUT /rl-patients
--   {
--     "settings": { "number_of_shards": 1, "number_of_replicas": 1 },
--     "mappings": {
--       "properties": {
--         "phn":         { "type": "keyword" },
--         "givenName":   { "type": "text", "analyzer": "standard" },
--         "familyName":  { "type": "text", "analyzer": "standard" },
--         "fullName":    { "type": "text", "analyzer": "standard" },
--         "facilityHin": { "type": "keyword" }
--       }
--     }
--   }
--
-- Document id = phn (so upserts are idempotent and the index stays at
-- one-document-per-patient). The app uses doc_as_upsert=true on every
-- index call so no separate "create if absent" logic is needed.
