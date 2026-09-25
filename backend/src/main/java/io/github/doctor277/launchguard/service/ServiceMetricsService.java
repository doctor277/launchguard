package io.github.doctor277.launchguard.service;

import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.dto.ServiceMetricsResponse;
import io.github.doctor277.launchguard.dto.TimelinePointResponse;
import io.github.doctor277.launchguard.repository.HealthCheckMetricsRepository;
import io.github.doctor277.launchguard.repository.HealthCheckRepository;
import io.github.doctor277.launchguard.repository.HealthCheckTimelineView;
import io.github.doctor277.launchguard.repository.MetricsAggregate;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceMetricsService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final MonitoredServiceRepository serviceRepository;
    private final HealthCheckRepository healthCheckRepository;
    private final HealthCheckMetricsRepository metricsRepository;
    private final Clock clock;

    public ServiceMetricsService(MonitoredServiceRepository serviceRepository,
                                 HealthCheckRepository healthCheckRepository,
                                 HealthCheckMetricsRepository metricsRepository,
                                 Clock clock) {
        this.serviceRepository = serviceRepository;
        this.healthCheckRepository = healthCheckRepository;
        this.metricsRepository = metricsRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ServiceMetricsResponse getMetrics(UUID serviceId, MetricsWindow window) {
        MonitoredService service = getRequiredService(serviceId);
        MetricsAggregate aggregate = summarize(serviceId, window, clock.instant());

        return new ServiceMetricsResponse(
                serviceId,
                service.getStatus(),
                aggregate.totalChecks(),
                aggregate.healthyChecks(),
                aggregate.failedChecks(),
                calculateAvailability(aggregate.healthyChecks(), aggregate.totalChecks()),
                roundLatency(aggregate.averageResponseTimeMs()),
                aggregate.minResponseTimeMs(),
                aggregate.maxResponseTimeMs(),
                aggregate.lastFailureAt(),
                service.getLastCheckedAt());
    }

    @Transactional(readOnly = true)
    public List<TimelinePointResponse> getTimeline(UUID serviceId, MetricsWindow window) {
        getRequiredService(serviceId);
        Instant now = clock.instant();
        List<HealthCheckTimelineView> points = window.startInclusive(now)
                .map(start -> healthCheckRepository
                        .findAllByServiceIdAndCheckedAtGreaterThanEqualOrderByCheckedAtAsc(serviceId, start))
                .orElseGet(() -> healthCheckRepository.findAllByServiceIdOrderByCheckedAtAsc(serviceId));

        return points.stream()
                .map(point -> new TimelinePointResponse(
                        point.getCheckedAt(), point.getStatus(), point.getResponseTimeMs()))
                .toList();
    }

    private MonitoredService getRequiredService(UUID serviceId) {
        return serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));
    }

    private MetricsAggregate summarize(UUID serviceId, MetricsWindow window, Instant now) {
        return window.startInclusive(now)
                .map(start -> metricsRepository.summarizeSince(serviceId, start))
                .orElseGet(() -> metricsRepository.summarize(serviceId));
    }

    private static BigDecimal calculateAvailability(long healthyChecks, long totalChecks) {
        if (totalChecks == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(healthyChecks)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(totalChecks), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal roundLatency(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
    }
}
