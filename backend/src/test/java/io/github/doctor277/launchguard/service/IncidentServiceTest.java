package io.github.doctor277.launchguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.doctor277.launchguard.domain.Incident;
import io.github.doctor277.launchguard.domain.IncidentStatus;
import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.repository.IncidentMetricsAggregate;
import io.github.doctor277.launchguard.repository.IncidentMetricsRepository;
import io.github.doctor277.launchguard.repository.IncidentRepository;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    @Mock private MonitoredServiceRepository serviceRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private IncidentMetricsRepository metricsRepository;
    private IncidentService incidentService;
    private MonitoredService service;

    @BeforeEach
    void setUp() {
        service = MonitoredService.register("payment", "http://localhost:8081", "/health");
        incidentService = new IncidentService(serviceRepository, incidentRepository, metricsRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "resolved"})
    void paginatesAndFiltersHistory(String status) {
        when(serviceRepository.existsById(service.getId())).thenReturn(true);
        Incident incident = Incident.open(service, null, "outage", NOW.minusSeconds(30));
        IncidentStatus filter = IncidentStatus.valueOf(status.toUpperCase(java.util.Locale.ROOT));
        if (filter == IncidentStatus.RESOLVED) {
            incident.resolve(NOW);
        }
        PageRequest request = PageRequest.of(0, 1,
                Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id")));
        when(incidentRepository.findAllByServiceIdAndStatus(service.getId(), filter, request))
                .thenReturn(new PageImpl<>(List.of(incident), request, 2));

        var page = incidentService.findAll(service.getId(), 0, 1, status);

        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(filter);
            assertThat(item.durationSeconds()).isEqualTo(filter == IncidentStatus.OPEN ? null : 30L);
        });
    }

    @Test
    void paginatesUnfilteredHistory() {
        when(serviceRepository.existsById(service.getId())).thenReturn(true);
        PageRequest request = PageRequest.of(1, 20,
                Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id")));
        when(incidentRepository.findAllByServiceId(service.getId(), request))
                .thenReturn(new PageImpl<>(List.of(), request, 0));

        assertThat(incidentService.findAll(service.getId(), 1, 20, null).content()).isEmpty();
    }

    @Test
    void currentIsEmptyWhenNoOpenIncidentExists() {
        when(serviceRepository.existsById(service.getId())).thenReturn(true);
        when(incidentRepository.findByServiceIdAndStatus(service.getId(), IncidentStatus.OPEN)).thenReturn(Optional.empty());

        assertThat(incidentService.current(service.getId())).isEmpty();
    }

    @Test
    void rejectsMissingServiceAndWrongServiceIncident() {
        UUID incidentId = UUID.randomUUID();
        assertThatThrownBy(() -> incidentService.current(service.getId())).isInstanceOf(ServiceNotFoundException.class);
        when(serviceRepository.existsById(service.getId())).thenReturn(true);
        when(incidentRepository.findByIdAndServiceId(incidentId, service.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> incidentService.findById(service.getId(), incidentId))
                .isInstanceOf(IncidentNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void rejectsInvalidPageSize(int size) {
        assertThatThrownBy(() -> incidentService.findAll(service.getId(), 0, size, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidStatusAndNegativePage() {
        assertThatThrownBy(() -> incidentService.findAll(service.getId(), 0, 20, "BROKEN"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OPEN, RESOLVED");
        assertThatThrownBy(() -> incidentService.findAll(service.getId(), -1, 20, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(MetricsWindow.class)
    void metricsUsesExistingWindowsAndDatabaseAggregate(MetricsWindow window) {
        when(serviceRepository.existsById(service.getId())).thenReturn(true);
        when(metricsRepository.summarize(service.getId(), window.startInclusive(NOW).orElse(null), NOW))
                .thenReturn(new IncidentMetricsAggregate(4, 3, 1, new BigDecimal("218.505"), 512L));

        var metrics = incidentService.metrics(service.getId(), window);

        assertThat(metrics.window()).isEqualTo(window.value());
        assertThat(metrics.totalIncidents()).isEqualTo(4);
        assertThat(metrics.resolvedIncidents()).isEqualTo(3);
        assertThat(metrics.openIncidents()).isEqualTo(1);
        assertThat(metrics.averageResolutionTimeSeconds()).isEqualByComparingTo("218.51");
        assertThat(metrics.longestIncidentSeconds()).isEqualTo(512);
    }

    @Test
    void emptyMetricsHasNullDurations() {
        when(serviceRepository.existsById(service.getId())).thenReturn(true);
        when(metricsRepository.summarize(service.getId(), null, NOW))
                .thenReturn(new IncidentMetricsAggregate(0, 0, 0, null, null));

        var metrics = incidentService.metrics(service.getId(), MetricsWindow.ALL);

        assertThat(metrics.totalIncidents()).isZero();
        assertThat(metrics.averageResolutionTimeSeconds()).isNull();
        assertThat(metrics.longestIncidentSeconds()).isNull();
    }
}
