package io.github.doctor277.launchguard.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record IncidentMetricsResponse(UUID serviceId, String window, long totalIncidents, long resolvedIncidents,
        long openIncidents, BigDecimal averageResolutionTimeSeconds, Long longestIncidentSeconds) {
}
