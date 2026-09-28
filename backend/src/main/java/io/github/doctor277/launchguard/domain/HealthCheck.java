package io.github.doctor277.launchguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "health_checks")
public class HealthCheck {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_health_checks_service"))
    private MonitoredService service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deployment_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_health_checks_deployment"))
    private Deployment deployment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ServiceStatus status;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_time_ms", nullable = false)
    private long responseTimeMs;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    protected HealthCheck() {
    }

    private HealthCheck(UUID id, MonitoredService service, Deployment deployment, ServiceStatus status, Integer httpStatus,
                        long responseTimeMs, String errorMessage, Instant checkedAt) {
        this.id = id;
        this.service = service;
        this.deployment = deployment;
        this.status = status;
        this.httpStatus = httpStatus;
        this.responseTimeMs = responseTimeMs;
        this.errorMessage = errorMessage;
        this.checkedAt = checkedAt;
    }

    public static HealthCheck record(MonitoredService service, ServiceStatus status, Integer httpStatus,
                                     long responseTimeMs, String errorMessage, Instant checkedAt) {
        return record(service, null, status, httpStatus, responseTimeMs, errorMessage, checkedAt);
    }

    public static HealthCheck record(MonitoredService service, Deployment deployment, ServiceStatus status,
                                     Integer httpStatus, long responseTimeMs, String errorMessage, Instant checkedAt) {
        if (deployment != null && !service.getId().equals(deployment.getService().getId())) {
            throw new IllegalArgumentException("Deployment belongs to another service");
        }
        return new HealthCheck(UUID.randomUUID(), service, deployment, status, httpStatus,
                responseTimeMs, errorMessage, checkedAt);
    }

    public UUID getId() {
        return id;
    }

    public MonitoredService getService() {
        return service;
    }

    public Deployment getDeployment() {
        return deployment;
    }

    public ServiceStatus getStatus() {
        return status;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public long getResponseTimeMs() {
        return responseTimeMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }
}
