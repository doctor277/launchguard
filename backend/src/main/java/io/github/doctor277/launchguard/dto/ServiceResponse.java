package io.github.doctor277.launchguard.dto;

import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import java.time.Instant;
import java.util.UUID;

public record ServiceResponse(
        UUID id,
        String name,
        String baseUrl,
        String healthPath,
        ServiceStatus status,
        Instant lastCheckedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static ServiceResponse from(MonitoredService service) {
        return new ServiceResponse(service.getId(), service.getName(), service.getBaseUrl(),
                service.getHealthPath(), service.getStatus(), service.getLastCheckedAt(),
                service.getCreatedAt(), service.getUpdatedAt());
    }
}
