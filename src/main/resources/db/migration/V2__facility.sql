CREATE TABLE facility (
    hin           VARCHAR(100)  NOT NULL,
    name          VARCHAR(200)  NOT NULL,
    province_code VARCHAR(20)   NOT NULL,

    PRIMARY KEY (hin)
);

CREATE INDEX idx_facility_province ON facility (province_code);
