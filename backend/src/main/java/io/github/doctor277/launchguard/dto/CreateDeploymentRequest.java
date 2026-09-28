package io.github.doctor277.launchguard.dto;

import io.github.doctor277.launchguard.domain.DeploymentSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateDeploymentRequest(
        @NotBlank @Size(max = 100) String version,
        @Pattern(regexp = "[0-9a-fA-F]{7,64}", message = "must be a 7 to 64 character hexadecimal SHA")
        String commitSha,
        @Size(max = 1000) String description,
        DeploymentSource source,
        @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}", message = "must be a 1 to 64 character environment name")
        String environment,
        @Size(max = 512) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String imageTag,
        @Size(max = 200) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String externalId) {

    public CreateDeploymentRequest {
        source = source == null ? DeploymentSource.MANUAL : source;
    }

    public CreateDeploymentRequest(String version, String commitSha, String description) {
        this(version, commitSha, description, DeploymentSource.MANUAL, null, null, null);
    }
}
