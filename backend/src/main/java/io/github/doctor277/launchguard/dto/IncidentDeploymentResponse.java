package io.github.doctor277.launchguard.dto;

import java.util.UUID;

public record IncidentDeploymentResponse(UUID id, String version, String commitSha) {
}
