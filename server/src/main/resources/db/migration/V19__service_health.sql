CREATE TABLE service_operational_status (
    component VARCHAR(32) PRIMARY KEY,
    last_seen TIMESTAMPTZ NOT NULL
);
