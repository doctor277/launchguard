package io.github.doctor277.launchguard.dto;

import io.github.doctor277.launchguard.domain.Deployment;
import java.time.Instant;
import java.util.UUID;

public record CurrentDeploymentResponse(UUID id, String version, String commitSha, Instant deployedAt) {

    public static CurrentDeploymentResponse from(Deployment deployment) {
        return new CurrentDeploymentResponse(deployment.getId(), deployment.getVersion(),
                deployment.getCommitSha(), deployment.getDeployedAt());
    }
}
