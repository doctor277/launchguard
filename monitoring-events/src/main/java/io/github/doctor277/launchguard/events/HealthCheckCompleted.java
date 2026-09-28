package io.github.doctor277.launchguard.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record HealthCheckCompleted(int eventVersion, UUID requestId, UUID serviceId, UUID deploymentId,
                                   ServiceStatus status, Integer httpStatus, long responseTimeMs,
                                   String errorMessage, Instant checkedAt) {
    public HealthCheckCompleted {
        if (eventVersion != 1) throw new IllegalArgumentException("Unsupported result eventVersion: " + eventVersion);
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(checkedAt, "checkedAt");
        if (responseTimeMs < 0) throw new IllegalArgumentException("responseTimeMs must not be negative");
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) throw new IllegalArgumentException("Invalid httpStatus");
        boolean success = httpStatus != null && httpStatus >= 200 && httpStatus < 300;
        if ((status == ServiceStatus.HEALTHY) != success) throw new IllegalArgumentException("status does not match httpStatus");
        if (errorMessage != null && errorMessage.length() > 2048) throw new IllegalArgumentException("errorMessage too long");
    }
}
