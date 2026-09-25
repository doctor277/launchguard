package io.github.doctor277.launchguard.dto;

import io.github.doctor277.launchguard.domain.HealthCheck;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import java.time.Instant;
import java.util.UUID;

public record HealthCheckResponse(
        UUID id,
        UUID serviceId,
        ServiceStatus status,
        Integer httpStatus,
        long responseTimeMs,
        String errorMessage,
        Instant checkedAt) {

    public static HealthCheckResponse from(HealthCheck check) {
        return new HealthCheckResponse(check.getId(), check.getService().getId(), check.getStatus(),
                check.getHttpStatus(), check.getResponseTimeMs(), check.getErrorMessage(), check.getCheckedAt());
    }
}
