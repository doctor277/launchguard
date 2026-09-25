package io.github.doctor277.launchguard.dto;

import io.github.doctor277.launchguard.domain.ServiceStatus;
import java.time.Instant;

public record TimelinePointResponse(
        Instant timestamp,
        ServiceStatus status,
        long responseTimeMs) {
}
