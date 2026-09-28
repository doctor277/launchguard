package io.github.doctor277.launchguard.repository;

import java.math.BigDecimal;

public record IncidentMetricsAggregate(long totalIncidents, long resolvedIncidents, long openIncidents,
                                       BigDecimal averageResolutionTimeSeconds, Long longestIncidentSeconds) {
}
