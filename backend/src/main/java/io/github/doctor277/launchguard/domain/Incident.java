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
import org.hibernate.annotations.DynamicUpdate;

@Entity
@DynamicUpdate
@Table(name = "incidents")
public class Incident {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_incidents_service"))
    private MonitoredService service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deployment_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_incidents_deployment"))
    private Deployment deployment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IncidentStatus status;

    @Column(name = "trigger_reason", nullable = false, length = 255, updatable = false)
    private String triggerReason;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Incident() {
    }

    private Incident(MonitoredService service, Deployment deployment, String triggerReason, Instant startedAt) {
        if (deployment != null && !service.getId().equals(deployment.getService().getId())) {
            throw new IllegalArgumentException("Deployment belongs to another service");
        }
        this.id = UUID.randomUUID();
        this.service = service;
        this.deployment = deployment;
        this.status = IncidentStatus.OPEN;
        this.triggerReason = triggerReason;
        this.startedAt = startedAt;
        this.createdAt = startedAt;
        this.updatedAt = startedAt;
    }

    public static Incident open(MonitoredService service, Deployment deployment, String triggerReason, Instant startedAt) {
        return new Incident(service, deployment, triggerReason, startedAt);
    }

    public void resolve(Instant resolvedAt) {
        if (status != IncidentStatus.OPEN) {
            throw new IllegalStateException("Resolved incidents are immutable");
        }
        if (resolvedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Resolution cannot precede incident start");
        }
        this.status = IncidentStatus.RESOLVED;
        this.resolvedAt = resolvedAt;
        this.updatedAt = resolvedAt;
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

    public IncidentStatus getStatus() {
        return status;
    }

    public String getTriggerReason() {
        return triggerReason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
