package io.github.doctor277.launchguard.dto;

import io.github.doctor277.launchguard.domain.Incident;
import io.github.doctor277.launchguard.domain.IncidentStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(UUID id, UUID serviceId, String serviceName, IncidentStatus status,
        IncidentDeploymentResponse deployment, String triggerReason, Instant startedAt, Instant resolvedAt,
        Long durationSeconds) {

    public static IncidentResponse from(Incident incident) {
        var deployment = incident.getDeployment();
        Long duration = incident.getResolvedAt() == null ? null
                : Duration.between(incident.getStartedAt(), incident.getResolvedAt()).getSeconds();
        return new IncidentResponse(incident.getId(), incident.getService().getId(), incident.getService().getName(),
                incident.getStatus(), deployment == null ? null
                : new IncidentDeploymentResponse(deployment.getId(), deployment.getVersion(), deployment.getCommitSha()),
                incident.getTriggerReason(), incident.getStartedAt(), incident.getResolvedAt(), duration);
    }
}
