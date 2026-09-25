package io.github.doctor277.launchguard.repository;

import java.math.BigDecimal;
import java.time.Instant;

public record MetricsAggregate(
        long totalChecks,
        long healthyChecks,
        long failedChecks,
        BigDecimal averageResponseTimeMs,
        Long minResponseTimeMs,
        Long maxResponseTimeMs,
        Instant lastFailureAt) {
}
