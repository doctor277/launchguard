CREATE TABLE incidents (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL,
    deployment_id UUID,
    status VARCHAR(16) NOT NULL,
    trigger_reason VARCHAR(255) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_incidents_service
        FOREIGN KEY (service_id) REFERENCES monitored_services (id) ON DELETE CASCADE,
    CONSTRAINT fk_incidents_deployment
        FOREIGN KEY (service_id, deployment_id) REFERENCES deployments (service_id, id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_incidents_lifecycle CHECK (
        (status = 'OPEN' AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolved_at >= started_at)
    )
);

CREATE UNIQUE INDEX uk_incidents_one_open_per_service
    ON incidents (service_id) WHERE status = 'OPEN';

CREATE INDEX idx_incidents_service_started_at
    ON incidents (service_id, started_at DESC, id DESC);
