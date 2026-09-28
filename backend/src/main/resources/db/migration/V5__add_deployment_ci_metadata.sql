-- Additive, forward-only expansion. Legacy rows default to MANUAL; no history is rewritten.
ALTER TABLE deployments
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN environment VARCHAR(64),
    ADD COLUMN image_tag VARCHAR(512),
    ADD COLUMN external_id VARCHAR(200),
    ADD CONSTRAINT ck_deployments_source CHECK (source IN ('MANUAL', 'CI'));

-- One CI run can deploy several services. Null external IDs retain manual create-always behavior.
CREATE UNIQUE INDEX CONCURRENTLY uk_deployments_service_external_id
    ON deployments (service_id, external_id) WHERE external_id IS NOT NULL;
