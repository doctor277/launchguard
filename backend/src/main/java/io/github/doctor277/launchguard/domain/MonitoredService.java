package io.github.doctor277.launchguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@DynamicUpdate
@Table(name = "monitored_services")
public class MonitoredService {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "base_url", nullable = false, length = 2048)
    private String baseUrl;

    @Column(name = "health_path", nullable = false, length = 1024)
    private String healthPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ServiceStatus status;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_deployment_id",
            foreignKey = @ForeignKey(name = "fk_monitored_services_current_deployment"))
    private Deployment currentDeployment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MonitoredService() {
    }

    private MonitoredService(UUID id, String name, String baseUrl, String healthPath) {
        this.id = id;
        this.name = name;
        this.baseUrl = baseUrl;
        this.healthPath = healthPath;
        this.status = ServiceStatus.UNKNOWN;
    }

    public static MonitoredService register(String name, String baseUrl, String healthPath) {
        return new MonitoredService(UUID.randomUUID(), name, baseUrl, healthPath);
    }

    public void recordStatus(ServiceStatus newStatus, Instant checkedAt) {
        status = newStatus;
        lastCheckedAt = checkedAt;
    }

    public void setCurrentDeployment(Deployment deployment) {
        if (!id.equals(deployment.getService().getId())) {
            throw new IllegalArgumentException("Deployment belongs to another service");
        }
        currentDeployment = deployment;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getHealthPath() {
        return healthPath;
    }

    public ServiceStatus getStatus() {
        return status;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public Deployment getCurrentDeployment() {
        return currentDeployment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
