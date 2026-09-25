package io.github.doctor277.launchguard.service;

import io.github.doctor277.launchguard.domain.HealthCheck;
import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.dto.HealthCheckResponse;
import io.github.doctor277.launchguard.repository.HealthCheckRepository;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HealthCheckService {

    private final MonitoredServiceRepository serviceRepository;
    private final HealthCheckRepository healthCheckRepository;
    private final HealthProbe healthProbe;
    private final Clock clock;
    private final Set<UUID> checksInProgress = ConcurrentHashMap.newKeySet();

    public HealthCheckService(MonitoredServiceRepository serviceRepository,
                              HealthCheckRepository healthCheckRepository,
                              HealthProbe healthProbe,
                              Clock clock) {
        this.serviceRepository = serviceRepository;
        this.healthCheckRepository = healthCheckRepository;
        this.healthProbe = healthProbe;
        this.clock = clock;
    }

    @Transactional
    public HealthCheckResponse check(UUID serviceId) {
        if (!checksInProgress.add(serviceId)) {
            throw new CheckAlreadyInProgressException(serviceId);
        }

        try {
            MonitoredService service = serviceRepository.findById(serviceId)
                    .orElseThrow(() -> new ServiceNotFoundException(serviceId));
            ProbeResult result = healthProbe.probe(service);
            Instant checkedAt = clock.instant();

            HealthCheck healthCheck = HealthCheck.record(service, result.status(), result.httpStatus(),
                    result.responseTimeMs(), result.errorMessage(), checkedAt);
            healthCheckRepository.save(healthCheck);
            service.recordStatus(result.status(), checkedAt);
            serviceRepository.save(service);
            return HealthCheckResponse.from(healthCheck);
        } finally {
            checksInProgress.remove(serviceId);
        }
    }

    @Transactional(readOnly = true)
    public List<HealthCheckResponse> findHistory(UUID serviceId) {
        if (!serviceRepository.existsById(serviceId)) {
            throw new ServiceNotFoundException(serviceId);
        }
        return healthCheckRepository.findAllByServiceIdOrderByCheckedAtDesc(serviceId).stream()
                .map(HealthCheckResponse::from)
                .toList();
    }
}
