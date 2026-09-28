package io.github.doctor277.launchguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deployments")
public class Deployment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_deployments_service"))
    private MonitoredService service;

    @Column(nullable = false, length = 100)
    private String version;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(length = 1000)
    private String description;

    @Column(name = "deployed_at", nullable = false, updatable = false)
    private Instant deployedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Deployment() {
    }

    private Deployment(UUID id, MonitoredService service, String version, String commitSha,
                       String description, Instant deployedAt) {
        this.id = id;
        this.service = service;
        this.version = version;
        this.commitSha = commitSha;
        this.description = description;
        this.deployedAt = deployedAt;
        this.createdAt = deployedAt;
    }

    public static Deployment register(MonitoredService service, String version, String commitSha,
                                      String description, Instant deployedAt) {
        return new Deployment(UUID.randomUUID(), service, version, commitSha, description, deployedAt);
    }

    public UUID getId() {
        return id;
    }

    public MonitoredService getService() {
        return service;
    }

    public String getVersion() {
        return version;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getDescription() {
        return description;
    }

    public Instant getDeployedAt() {
        return deployedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
