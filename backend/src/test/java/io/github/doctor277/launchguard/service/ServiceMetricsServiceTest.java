package io.github.doctor277.launchguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import io.github.doctor277.launchguard.repository.HealthCheckMetricsRepository;
import io.github.doctor277.launchguard.repository.HealthCheckRepository;
import io.github.doctor277.launchguard.repository.HealthCheckTimelineView;
import io.github.doctor277.launchguard.repository.MetricsAggregate;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServiceMetricsServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final Instant LAST_FAILURE = Instant.parse("2026-09-24T10:30:00Z");

    @Mock
    private MonitoredServiceRepository serviceRepository;

    @Mock
    private HealthCheckRepository healthCheckRepository;

    @Mock
    private HealthCheckMetricsRepository metricsRepository;

    private ServiceMetricsService metricsService;
    private MonitoredService service;

    @BeforeEach
    void setUp() {
        service = MonitoredService.register("payment-service", "http://localhost:8081", "/health");
        service.recordStatus(ServiceStatus.HEALTHY, NOW.minusSeconds(30));
        when(serviceRepository.findById(service.getId())).thenReturn(Optional.of(service));
        metricsService = new ServiceMetricsService(serviceRepository, healthCheckRepository, metricsRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void calculatesOneHundredPercentAvailabilityForHealthyHistory() {
        when(metricsRepository.summarize(service.getId()))
                .thenReturn(aggregate(12, 12, 0, "45", 31L, 62L, null));

        var metrics = metricsService.getMetrics(service.getId(), MetricsWindow.ALL);

        assertThat(metrics.availabilityPercentage()).isEqualByComparingTo("100.00");
        assertThat(metrics.healthyChecks()).isEqualTo(12);
        assertThat(metrics.failedChecks()).isZero();
    }

    @Test
    void calculatesAvailabilityForPartialOutageHistory() {
        when(metricsRepository.summarize(service.getId()))
                .thenReturn(aggregate(120, 116, 4, "72.4", 31L, 410L, LAST_FAILURE));

        var metrics = metricsService.getMetrics(service.getId(), MetricsWindow.ALL);

        assertThat(metrics.availabilityPercentage()).isEqualByComparingTo("96.67");
        assertThat(metrics.totalChecks()).isEqualTo(120);
        assertThat(metrics.failedChecks()).isEqualTo(4);
    }

    @Test
    void returnsDefinedEmptyMetricsWhenThereAreNoChecks() {
        when(metricsRepository.summarize(service.getId()))
                .thenReturn(aggregate(0, 0, 0, null, null, null, null));

        var metrics = metricsService.getMetrics(service.getId(), MetricsWindow.ALL);

        assertThat(metrics.availabilityPercentage()).isEqualByComparingTo("0.00");
        assertThat(metrics.averageResponseTimeMs()).isNull();
        assertThat(metrics.minResponseTimeMs()).isNull();
        assertThat(metrics.maxResponseTimeMs()).isNull();
        assertThat(metrics.lastFailureAt()).isNull();
    }

    @Test
    void returnsRoundedAverageLatency() {
        when(metricsRepository.summarize(service.getId()))
                .thenReturn(aggregate(3, 3, 0, "72.416", 40L, 100L, null));

        var metrics = metricsService.getMetrics(service.getId(), MetricsWindow.ALL);

        assertThat(metrics.averageResponseTimeMs()).isEqualByComparingTo("72.42");
    }

    @Test
    void returnsMinimumLatency() {
        when(metricsRepository.summarize(service.getId()))
                .thenReturn(aggregate(3, 3, 0, "72", 31L, 100L, null));

        assertThat(metricsService.getMetrics(service.getId(), MetricsWindow.ALL).minResponseTimeMs())
                .isEqualTo(31);
    }

    @Test
    void returnsMaximumLatency() {
        when(metricsRepository.summarize(service.getId()))
                .thenReturn(aggregate(3, 3, 0, "72", 31L, 410L, null));

        assertThat(metricsService.getMetrics(service.getId(), MetricsWindow.ALL).maxResponseTimeMs())
                .isEqualTo(410);
    }

    @Test
    void returnsLastFailureTimestamp() {
        when(metricsRepository.summarize(service.getId()))
                .thenReturn(aggregate(3, 2, 1, "72", 31L, 410L, LAST_FAILURE));

        assertThat(metricsService.getMetrics(service.getId(), MetricsWindow.ALL).lastFailureAt())
                .isEqualTo(LAST_FAILURE);
    }

    @Test
    void appliesOneHourWindowBoundary() {
        Instant expectedStart = NOW.minusSeconds(3_600);
        when(metricsRepository.summarizeSince(service.getId(), expectedStart))
                .thenReturn(aggregate(0, 0, 0, null, null, null, null));

        metricsService.getMetrics(service.getId(), MetricsWindow.ONE_HOUR);

        verify(metricsRepository).summarizeSince(service.getId(), expectedStart);
    }

    @Test
    void appliesTwentyFourHourWindowBoundary() {
        Instant expectedStart = NOW.minusSeconds(86_400);
        when(metricsRepository.summarizeSince(service.getId(), expectedStart))
                .thenReturn(aggregate(0, 0, 0, null, null, null, null));

        metricsService.getMetrics(service.getId(), MetricsWindow.TWENTY_FOUR_HOURS);

        verify(metricsRepository).summarizeSince(service.getId(), expectedStart);
    }

    @Test
    void returnsTimelineInRepositoryOrder() {
        HealthCheckTimelineView point = mock(HealthCheckTimelineView.class);
        when(point.getCheckedAt()).thenReturn(NOW.minusSeconds(60));
        when(point.getStatus()).thenReturn(ServiceStatus.DOWN);
        when(point.getResponseTimeMs()).thenReturn(2_100L);
        when(healthCheckRepository.findAllByServiceIdOrderByCheckedAtAsc(service.getId()))
                .thenReturn(List.of(point));

        var timeline = metricsService.getTimeline(service.getId(), MetricsWindow.ALL);

        assertThat(timeline).singleElement().satisfies(item -> {
            assertThat(item.timestamp()).isEqualTo(NOW.minusSeconds(60));
            assertThat(item.status()).isEqualTo(ServiceStatus.DOWN);
            assertThat(item.responseTimeMs()).isEqualTo(2_100);
        });
    }

    @Test
    void rejectsMetricsForNonexistentService() {
        when(serviceRepository.findById(service.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> metricsService.getMetrics(service.getId(), MetricsWindow.ALL))
                .isInstanceOf(ServiceNotFoundException.class)
                .hasMessageContaining(service.getId().toString());
        verifyNoInteractions(metricsRepository);
    }

    private static MetricsAggregate aggregate(long total, long healthy, long failed, String average,
                                                Long minimum, Long maximum, Instant lastFailure) {
        return new MetricsAggregate(total, healthy, failed,
                average == null ? null : new BigDecimal(average), minimum, maximum, lastFailure);
    }
}
