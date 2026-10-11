CREATE TABLE pointer (
    id                        VARCHAR(200)  NOT NULL,
    entry_uuid                VARCHAR(36)   NOT NULL,
    -- Named sludi for consistency with the RFP's eventual national
    -- identifier, but holds PHN values today - SLUDI has no real
    -- values until the national DPI is production-ready. See
    -- Pointer.java's javadoc on this field for the full explanation.
    sludi                     VARCHAR(64)   NOT NULL,
    resource_type             VARCHAR(20)   NOT NULL,
    status                    VARCHAR(20)   NOT NULL DEFAULT 'CURRENT',
    doc_type_loinc            VARCHAR(20),
    content_type              VARCHAR(100),
    content_url               VARCHAR(500),
    facility_hin              VARCHAR(100),
    facility_name             VARCHAR(200),
    submission_set_id         VARCHAR(200),
    nehr_encounter_ref        VARCHAR(100),
    consent_accessible        BOOLEAN       NOT NULL DEFAULT FALSE,
    consent_location_excluded BOOLEAN       NOT NULL DEFAULT FALSE,
    consent_purpose_of_use    VARCHAR(200),
    consent_source            VARCHAR(20)   NOT NULL DEFAULT 'UNKNOWN',
    consent_as_of             TIMESTAMP     NOT NULL,
    indexed_at                TIMESTAMP     NOT NULL,
    updated_at                TIMESTAMP     NOT NULL,
    national_synced           BOOLEAN       NOT NULL DEFAULT FALSE,

    PRIMARY KEY (id)
);

CREATE INDEX idx_pointer_sludi ON pointer (sludi);
CREATE INDEX idx_pointer_sludi_status ON pointer (sludi, status);
CREATE INDEX idx_pointer_facility ON pointer (facility_hin);
CREATE INDEX idx_pointer_updated_at ON pointer (updated_at);

CREATE TABLE audit_event (
    id            VARCHAR(36)   NOT NULL,
    event_type    VARCHAR(50)   NOT NULL,
    sludi         VARCHAR(64),
    resource_id   VARCHAR(200),
    outcome       VARCHAR(20)   NOT NULL,
    recorded_at   TIMESTAMP     NOT NULL,

    PRIMARY KEY (id)
);

CREATE INDEX idx_audit_event_sludi ON audit_event (sludi);
CREATE INDEX idx_audit_event_recorded_at ON audit_event (recorded_at);
