package io.github.doctor277.launchguard.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import io.github.doctor277.launchguard.domain.*;
import io.github.doctor277.launchguard.events.EventJson;
import io.github.doctor277.launchguard.events.HealthCheckRequested;
import io.github.doctor277.launchguard.events.KafkaMonitoringProperties;
import io.github.doctor277.launchguard.config.MonitoringProperties;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import io.github.doctor277.launchguard.scheduling.HealthCheckScheduler;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class ProbeDispatcherTest {
    @Test
    void dispatchedMetricCountsOnlySuccessfulBrokerAcknowledgements() {
        var repository = mock(MonitoredServiceRepository.class);
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        var service = MonitoredService.register("payment", "http://payment-service:8081", "/health");
        when(repository.findById(service.getId())).thenReturn(Optional.of(service));
        var publication = new CompletableFuture<SendResult<String, String>>();
        when(template.send(anyString(), anyInt(), anyString(), anyString())).thenReturn(publication);
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var inFlight = new InFlightProbeRegistry(Clock.systemUTC(), new DispatchProperties(Duration.ofSeconds(120)));
        var dispatcher = new ProbeDispatcher(repository, inFlight, template, new EventJson(),
                new KafkaMonitoringProperties("requests", "results", 6, (short) 1),
                new MonitoringProperties(Duration.ofSeconds(2), Duration.ofSeconds(5)), Clock.systemUTC(),
                new io.github.doctor277.launchguard.observability.BackendTelemetry(meters),
                io.micrometer.observation.ObservationRegistry.NOOP);
        var queued = dispatcher.dispatch(service.getId());
        assertThat(meters.get("launchguard.probe.requests.dispatched").counter().count()).isZero();
        publication.complete(mock(SendResult.class));
        assertThat(meters.get("launchguard.probe.requests.dispatched").counter().count()).isEqualTo(1);
        assertThat(publication.complete(mock(SendResult.class))).isFalse();
        assertThat(meters.get("launchguard.probe.requests.dispatched").counter().count()).isEqualTo(1);
        inFlight.release(service.getId(), queued.requestId());
        var failed = new CompletableFuture<SendResult<String, String>>();
        when(template.send(anyString(), anyInt(), anyString(), anyString())).thenReturn(failed);
        dispatcher.dispatch(service.getId());
        failed.completeExceptionally(new IllegalStateException("Broker unavailable"));
        assertThat(meters.get("launchguard.probe.requests.dispatched").counter().count()).isEqualTo(1);
        assertThat(meters.get("launchguard.probe.dispatch.failures").counter().count()).isEqualTo(1);
        assertThat(inFlight.size()).isZero();
    }

    @Test
    void publishesServiceKeyAndDispatchTimeDeploymentSnapshot() {
        var repository = mock(MonitoredServiceRepository.class);
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        var service = MonitoredService.register("payment", "http://payment-service:8081", "/health");
        var deployment = Deployment.register(service, "v1", null, null, Instant.now());
        service.setCurrentDeployment(deployment);
        when(repository.findById(service.getId())).thenReturn(Optional.of(service));
        when(template.send(anyString(), anyInt(), anyString(), anyString())).thenReturn(new CompletableFuture<SendResult<String, String>>());
        var json = new EventJson();
        var registry = new InFlightProbeRegistry(Clock.systemUTC(), new DispatchProperties(Duration.ofSeconds(120)));
        var dispatcher = new ProbeDispatcher(repository, registry, template, json,
                new KafkaMonitoringProperties("requests", "results", 6, (short) 1),
                new MonitoringProperties(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                Clock.systemUTC(), new io.github.doctor277.launchguard.observability.BackendTelemetry(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), io.micrometer.observation.ObservationRegistry.NOOP);
        var queued = dispatcher.dispatch(service.getId());
        var payload = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("requests"), eq(0), eq(service.getId().toString()), payload.capture());
        var event = json.read(payload.getValue(), HealthCheckRequested.class);
        assertThat(event.requestId()).isEqualTo(queued.requestId());
        assertThat(event.deploymentId()).isEqualTo(deployment.getId());
        assertThat(event.targetUrl()).isEqualTo("http://payment-service:8081/health");
        assertThatThrownBy(() -> dispatcher.dispatch(service.getId()))
                .isInstanceOf(io.github.doctor277.launchguard.service.CheckAlreadyInProgressException.class);
    }

    @Test
    void expiredRequestCannotReleaseItsReplacementToken() {
        UUID serviceId = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-28T12:00:00Z");
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now);
        var registry = new InFlightProbeRegistry(clock, new DispatchProperties(Duration.ofSeconds(10)));
        assertThat(registry.acquire(serviceId, first)).isTrue();
        assertThat(registry.acquire(serviceId, second)).isFalse();
        when(clock.instant()).thenReturn(now.plusSeconds(11));
        assertThat(registry.acquire(serviceId, second)).isTrue();
        registry.release(serviceId, first);
        assertThat(registry.isInFlight(serviceId)).isTrue();
        registry.release(serviceId, second);
        assertThat(registry.isInFlight(serviceId)).isFalse();
    }

    @Test
    void asynchronousPublicationFailureReleasesGuard() {
        var repository = mock(MonitoredServiceRepository.class);
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        var service = MonitoredService.register("payment", "http://payment-service:8081", "/health");
        when(repository.findById(service.getId())).thenReturn(Optional.of(service));
        var publication = new CompletableFuture<SendResult<String, String>>();
        when(template.send(anyString(), anyInt(), anyString(), anyString())).thenReturn(publication);
        var registry = new InFlightProbeRegistry(Clock.systemUTC(), new DispatchProperties(Duration.ofSeconds(120)));
        var dispatcher = new ProbeDispatcher(repository, registry, template, new EventJson(),
                new KafkaMonitoringProperties("requests", "results", 6, (short) 1),
                new MonitoringProperties(Duration.ofSeconds(2), Duration.ofSeconds(5)), Clock.systemUTC(), new io.github.doctor277.launchguard.observability.BackendTelemetry(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), io.micrometer.observation.ObservationRegistry.NOOP);
        dispatcher.dispatch(service.getId());
        assertThat(registry.isInFlight(service.getId())).isTrue();
        publication.completeExceptionally(new IllegalStateException("Broker unavailable"));
        assertThat(registry.isInFlight(service.getId())).isFalse();
    }

    @Test
    void immediatePublicationFailureReturnsDispatchErrorAndReleasesGuard() {
        var repository = mock(MonitoredServiceRepository.class);
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        var service = MonitoredService.register("payment", "http://payment-service:8081", "/health");
        when(repository.findById(service.getId())).thenReturn(Optional.of(service));
        when(template.send(anyString(), anyInt(), anyString(), anyString())).thenThrow(new IllegalStateException("Broker unavailable"));
        var registry = new InFlightProbeRegistry(Clock.systemUTC(), new DispatchProperties(Duration.ofSeconds(120)));
        var dispatcher = new ProbeDispatcher(repository, registry, template, new EventJson(),
                new KafkaMonitoringProperties("requests", "results", 6, (short) 1),
                new MonitoringProperties(Duration.ofSeconds(2), Duration.ofSeconds(5)), Clock.systemUTC(), new io.github.doctor277.launchguard.observability.BackendTelemetry(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), io.micrometer.observation.ObservationRegistry.NOOP);
        assertThatThrownBy(() -> dispatcher.dispatch(service.getId())).isInstanceOf(ProbeDispatchException.class);
        assertThat(registry.isInFlight(service.getId())).isFalse();
    }

    @Test
    void schedulerOnlyDispatchesAndContinuesAfterOneDispatchFails() {
        var repository = mock(MonitoredServiceRepository.class);
        var dispatcher = mock(ProbeDispatcher.class);
        var first = MonitoredService.register("first", "http://first", "/health");
        var second = MonitoredService.register("second", "http://second", "/health");
        when(repository.findAll()).thenReturn(List.of(first, second));
        when(dispatcher.dispatch(first.getId())).thenThrow(new ProbeDispatchException());
        new HealthCheckScheduler(repository, dispatcher).checkRegisteredServices();
        verify(dispatcher).dispatch(first.getId());
        verify(dispatcher).dispatch(second.getId());
    }
}
