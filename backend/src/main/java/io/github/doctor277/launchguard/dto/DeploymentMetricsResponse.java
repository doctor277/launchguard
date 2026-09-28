package io.github.doctor277.launchguard.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DeploymentMetricsResponse(
        UUID deploymentId,
        UUID serviceId,
        String version,
        String commitSha,
        boolean current,
        Instant deployedAt,
        long totalChecks,
        long healthyChecks,
        long failedChecks,
        BigDecimal availabilityPercentage,
        BigDecimal averageResponseTimeMs,
        Long minResponseTimeMs,
        Long maxResponseTimeMs,
        Instant firstFailureAt,
        Instant lastCheckedAt) {
}
