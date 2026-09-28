package io.github.doctor277.launchguard.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.doctor277.launchguard.domain.IncidentStatus;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import io.github.doctor277.launchguard.messaging.DispatchProperties;
import io.github.doctor277.launchguard.messaging.InFlightProbeRegistry;
import io.github.doctor277.launchguard.repository.IncidentRepository;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BackendTelemetryTest {
    @Test
    void outcomesAndTransitionsUseOnlyBoundedLabels() {
        var registry = new SimpleMeterRegistry();
        var telemetry = new BackendTelemetry(registry);
        telemetry.dispatched();
        telemetry.resultPersisted(ServiceStatus.HEALTHY);
        telemetry.resultPersisted(ServiceStatus.DOWN);
        telemetry.incidentOpened();
        telemetry.incidentResolved();
        telemetry.resultIgnored("duplicate");
        assertThat(registry.get("launchguard.probe.requests.dispatched").counter().count()).isEqualTo(1);
        assertThat(registry.get("launchguard.probe.results").tag("status", "HEALTHY").counter().count()).isEqualTo(1);
        assertThat(registry.get("launchguard.probe.results").tag("status", "DOWN").counter().count()).isEqualTo(1);
        assertThat(registry.get("launchguard.incidents.opened").counter().count()).isEqualTo(1);
        assertThat(registry.get("launchguard.incidents.resolved").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isIn("status", "reason")));
    }

    @Test
    void inFlightGaugeTracksAcquireReleaseAndExpiryWithoutServiceTags() throws Exception {
        var meters = new SimpleMeterRegistry();
        var clock = mock(Clock.class);
        var now = java.time.Instant.parse("2026-09-28T12:00:00Z");
        when(clock.instant()).thenReturn(now);
        var inFlight = new InFlightProbeRegistry(clock, new DispatchProperties(Duration.ofSeconds(10)));
        var services = mock(MonitoredServiceRepository.class);
        var incidents = mock(IncidentRepository.class);
        when(services.count()).thenReturn(3L);
        when(incidents.countByStatus(IncidentStatus.OPEN)).thenReturn(2L);
        new WorkloadMetricsConfiguration().workloadMetrics(meters, inFlight, services, incidents).afterPropertiesSet();
        var gauge = meters.get("launchguard.probe.inflight").gauge();
        assertThat(gauge.value()).isZero();
        var serviceId = UUID.randomUUID();
        var requestId = UUID.randomUUID();
        inFlight.acquire(serviceId, requestId);
        assertThat(gauge.value()).isEqualTo(1);
        inFlight.release(serviceId, UUID.randomUUID());
        assertThat(gauge.value()).isEqualTo(1);
        inFlight.release(serviceId, requestId);
        assertThat(gauge.value()).isZero();
        inFlight.acquire(serviceId, requestId);
        when(clock.instant()).thenReturn(now.plusSeconds(11));
        inFlight.removeExpired();
        assertThat(gauge.value()).isZero();
        assertThat(meters.get("launchguard.monitored.services").gauge().value()).isEqualTo(3);
        assertThat(meters.get("launchguard.current.open.incidents").gauge().value()).isEqualTo(2);
        assertThat(gauge.getId().getTags()).isEmpty();
    }
}
