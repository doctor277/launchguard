package io.github.doctor277.launchguard.dto;

import io.github.doctor277.launchguard.domain.ServiceStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ServiceMetricsResponse(
        UUID serviceId,
        ServiceStatus status,
        long totalChecks,
        long healthyChecks,
        long failedChecks,
        BigDecimal availabilityPercentage,
        BigDecimal averageResponseTimeMs,
        Long minResponseTimeMs,
        Long maxResponseTimeMs,
        Instant lastFailureAt,
        Instant lastCheckedAt) {
}
