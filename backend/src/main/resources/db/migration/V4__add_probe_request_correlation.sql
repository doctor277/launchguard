-- Forward-only expansion: old synchronous checks retain NULL request correlation.
ALTER TABLE health_checks ADD COLUMN probe_request_id UUID;

-- Non-transactional migration configuration permits online index creation.
CREATE UNIQUE INDEX CONCURRENTLY uk_health_checks_probe_request_id
    ON health_checks (probe_request_id) WHERE probe_request_id IS NOT NULL;
