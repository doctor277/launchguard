package io.github.doctor277.launchguard.dto;

import io.github.doctor277.launchguard.domain.Deployment;
import io.github.doctor277.launchguard.domain.DeploymentSource;
import io.github.doctor277.launchguard.domain.MonitoredService;
import java.time.Instant;
import java.util.UUID;

public record DeploymentResponse(
        UUID id,
        UUID serviceId,
        String version,
        String commitSha,
        String description,
        Instant deployedAt,
        Instant createdAt,
        boolean current,
        DeploymentSource source,
        String environment,
        String imageTag,
        String externalId) {

    public DeploymentResponse(UUID id, UUID serviceId, String version, String commitSha, String description,
                              Instant deployedAt, Instant createdAt, boolean current) {
        this(id, serviceId, version, commitSha, description, deployedAt, createdAt, current,
                DeploymentSource.MANUAL, null, null, null);
    }

    public static DeploymentResponse from(Deployment deployment, MonitoredService service) {
        Deployment current = service.getCurrentDeployment();
        return new DeploymentResponse(deployment.getId(), service.getId(), deployment.getVersion(),
                deployment.getCommitSha(), deployment.getDescription(), deployment.getDeployedAt(),
                deployment.getCreatedAt(), current != null && current.getId().equals(deployment.getId()),
                deployment.getSource(), deployment.getEnvironment(), deployment.getImageTag(), deployment.getExternalId());
    }
}
