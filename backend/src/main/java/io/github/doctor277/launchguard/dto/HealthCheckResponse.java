package io.github.doctor277.launchguard.dto;

import io.github.doctor277.launchguard.domain.HealthCheck;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import java.time.Instant;
import java.util.UUID;

public record HealthCheckResponse(
        UUID id,
        UUID serviceId,
        UUID deploymentId,
        ServiceStatus status,
        Integer httpStatus,
        long responseTimeMs,
        String errorMessage,
        Instant checkedAt) {

    public static HealthCheckResponse from(HealthCheck check) {
        var deployment = check.getDeployment();
        return new HealthCheckResponse(check.getId(), check.getService().getId(),
                deployment == null ? null : deployment.getId(), check.getStatus(),
                check.getHttpStatus(), check.getResponseTimeMs(), check.getErrorMessage(), check.getCheckedAt());
    }
}
