package io.github.doctor277.launchguard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateDeploymentRequest(
        @NotBlank @Size(max = 100) String version,
        @Pattern(regexp = "[0-9a-fA-F]{7,64}", message = "must be a 7 to 64 character hexadecimal SHA")
        String commitSha,
        @Size(max = 1000) String description) {
}
