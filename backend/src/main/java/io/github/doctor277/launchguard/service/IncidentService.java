package io.github.doctor277.launchguard.service;

import io.github.doctor277.launchguard.domain.Incident;
import io.github.doctor277.launchguard.domain.IncidentStatus;
import io.github.doctor277.launchguard.dto.IncidentMetricsResponse;
import io.github.doctor277.launchguard.dto.IncidentResponse;
import io.github.doctor277.launchguard.dto.PageResponse;
import io.github.doctor277.launchguard.repository.IncidentMetricsRepository;
import io.github.doctor277.launchguard.repository.IncidentRepository;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class IncidentService {

    private final MonitoredServiceRepository serviceRepository;
    private final IncidentRepository incidentRepository;
    private final IncidentMetricsRepository metricsRepository;
    private final Clock clock;

    public IncidentService(MonitoredServiceRepository serviceRepository, IncidentRepository incidentRepository,
                           IncidentMetricsRepository metricsRepository, Clock clock) {
        this.serviceRepository = serviceRepository;
        this.incidentRepository = incidentRepository;
        this.metricsRepository = metricsRepository;
        this.clock = clock;
    }

    public PageResponse<IncidentResponse> findAll(UUID serviceId, int page, int size, String status) {
        PaginationPolicy.validate(page, size);
        IncidentStatus filter = parseStatus(status);
        requireService(serviceId);
        PageRequest request = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id")));
        Page<Incident> incidents = filter == null ? incidentRepository.findAllByServiceId(serviceId, request)
                : incidentRepository.findAllByServiceIdAndStatus(serviceId, filter, request);
        var mapped = incidents.map(IncidentResponse::from);
        return new PageResponse<>(mapped.getContent(), mapped.getNumber(), mapped.getSize(),
                mapped.getTotalElements(), mapped.getTotalPages(), mapped.isFirst(), mapped.isLast());
    }

    public IncidentResponse findById(UUID serviceId, UUID incidentId) {
        requireService(serviceId);
        return incidentRepository.findByIdAndServiceId(incidentId, serviceId).map(IncidentResponse::from)
                .orElseThrow(() -> new IncidentNotFoundException(serviceId, incidentId));
    }

    public Optional<IncidentResponse> current(UUID serviceId) {
        requireService(serviceId);
        return incidentRepository.findByServiceIdAndStatus(serviceId, IncidentStatus.OPEN).map(IncidentResponse::from);
    }

    public IncidentMetricsResponse metrics(UUID serviceId, MetricsWindow window) {
        requireService(serviceId);
        var now = clock.instant();
        var aggregate = metricsRepository.summarize(serviceId, window.startInclusive(now).orElse(null), now);
        var average = aggregate.averageResolutionTimeSeconds();
        return new IncidentMetricsResponse(serviceId, window.value(), aggregate.totalIncidents(),
                aggregate.resolvedIncidents(), aggregate.openIncidents(),
                average == null ? null : average.setScale(2, RoundingMode.HALF_UP), aggregate.longestIncidentSeconds());
    }

    private void requireService(UUID serviceId) {
        if (!serviceRepository.existsById(serviceId)) {
            throw new ServiceNotFoundException(serviceId);
        }
    }

    private static IncidentStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return IncidentStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid incident status '" + status + "'. Supported values: OPEN, RESOLVED");
        }
    }
}
