package io.github.doctor277.launchguard.service;

import io.github.doctor277.launchguard.domain.ServiceStatus;

public record ProbeResult(
        ServiceStatus status,
        Integer httpStatus,
        long responseTimeMs,
        String errorMessage) {
}
