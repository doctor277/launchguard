CREATE TABLE monitored_services (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    health_path VARCHAR(1024) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    last_checked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_monitored_services_name UNIQUE (name),
    CONSTRAINT ck_monitored_services_status CHECK (status IN ('UNKNOWN', 'HEALTHY', 'DOWN'))
);

CREATE TABLE health_checks (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    http_status INTEGER,
    response_time_ms BIGINT NOT NULL,
    error_message VARCHAR(2048),
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_health_checks_service
        FOREIGN KEY (service_id) REFERENCES monitored_services (id) ON DELETE CASCADE,
    CONSTRAINT ck_health_checks_status CHECK (status IN ('HEALTHY', 'DOWN')),
    CONSTRAINT ck_health_checks_http_status CHECK (http_status IS NULL OR (http_status >= 100 AND http_status <= 599)),
    CONSTRAINT ck_health_checks_response_time CHECK (response_time_ms >= 0)
);

CREATE INDEX idx_health_checks_service_checked_at
    ON health_checks (service_id, checked_at DESC);
