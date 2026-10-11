-- Persists the Orthanc /changes cursor so restarts resume from the last
-- processed change rather than replaying everything from 0.
--
-- Single-row table: there is exactly one cursor per facility node.
-- The row is created lazily on first write (INSERT … ON CONFLICT DO UPDATE).

CREATE TABLE IF NOT EXISTS orthanc_cursor (
    id      VARCHAR(32)              NOT NULL DEFAULT 'default' PRIMARY KEY,
    seq     BIGINT                   NOT NULL DEFAULT 0,
    updated TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
