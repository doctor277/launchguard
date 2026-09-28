package io.github.doctor277.launchguard.repository;

import java.math.BigDecimal;
import java.time.Instant;

public record DeploymentMetricsAggregate(
        long totalChecks,
        long healthyChecks,
        long failedChecks,
        BigDecimal averageResponseTimeMs,
        Long minResponseTimeMs,
        Long maxResponseTimeMs,
        Instant firstFailureAt,
        Instant lastCheckedAt) {
}
