CREATE TABLE deployments (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL,
    version VARCHAR(100) NOT NULL,
    commit_sha VARCHAR(64),
    description VARCHAR(1000),
    deployed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_deployments_service
        FOREIGN KEY (service_id) REFERENCES monitored_services (id) ON DELETE CASCADE,
    CONSTRAINT uk_deployments_service_id UNIQUE (service_id, id)
);

ALTER TABLE monitored_services ADD COLUMN current_deployment_id UUID;
ALTER TABLE monitored_services ADD CONSTRAINT fk_monitored_services_current_deployment
    FOREIGN KEY (id, current_deployment_id) REFERENCES deployments (service_id, id)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE health_checks ADD COLUMN deployment_id UUID;
ALTER TABLE health_checks ADD CONSTRAINT fk_health_checks_deployment
    FOREIGN KEY (service_id, deployment_id) REFERENCES deployments (service_id, id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_deployments_service_deployed_at
    ON deployments (service_id, deployed_at DESC, id DESC);

CREATE INDEX idx_health_checks_deployment_checked_at
    ON health_checks (deployment_id, checked_at DESC)
    WHERE deployment_id IS NOT NULL;
