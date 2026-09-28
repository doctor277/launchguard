package io.github.doctor277.launchguard.messaging;

import io.github.doctor277.launchguard.events.EventJson;
import io.github.doctor277.launchguard.events.HealthCheckRequested;
import io.github.doctor277.launchguard.events.KafkaMonitoringProperties;
import io.github.doctor277.launchguard.events.ProbeObservations;
import io.github.doctor277.launchguard.observability.BackendTelemetry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.github.doctor277.launchguard.dto.AsyncCheckResponse;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import io.github.doctor277.launchguard.service.CheckAlreadyInProgressException;
import io.github.doctor277.launchguard.service.ServiceNotFoundException;
import io.github.doctor277.launchguard.config.MonitoringProperties;
import java.time.Clock;
import java.util.UUID;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ProbeDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ProbeDispatcher.class);
    private final MonitoredServiceRepository services;
    private final InFlightProbeRegistry inFlight;
    private final KafkaTemplate<String, String> template;
    private final EventJson json;
    private final KafkaMonitoringProperties topics;
    private final MonitoringProperties monitoring;
    private final Clock clock;
    private final BackendTelemetry telemetry;
    private final ObservationRegistry observations;

    public ProbeDispatcher(MonitoredServiceRepository services, InFlightProbeRegistry inFlight,
            KafkaTemplate<String, String> template, EventJson json, KafkaMonitoringProperties topics,
            MonitoringProperties monitoring, Clock clock, BackendTelemetry telemetry, ObservationRegistry observations) {
        this.services = services;
        this.inFlight = inFlight;
        this.template = template;
        this.json = json;
        this.topics = topics;
        this.monitoring = monitoring;
        this.clock = clock;
        this.telemetry = telemetry;
        this.observations = observations;
    }

    @Transactional(readOnly = true)
    public AsyncCheckResponse dispatch(UUID serviceId) {
        UUID requestId = UUID.randomUUID();
        Observation observation = ProbeObservations.start("launchguard.probe.dispatch", observations, requestId, serviceId);
        try (Observation.Scope scope = observation.openScope()) {
            return dispatch(serviceId, requestId, observation);
        } catch (RuntimeException exception) {
            observation.error(exception);
            throw exception;
        } finally {
            observation.stop();
        }
    }

    private AsyncCheckResponse dispatch(UUID serviceId, UUID requestId, Observation observation) {
        var service = services.findById(serviceId).orElseThrow(() -> new ServiceNotFoundException(serviceId));
        if (!inFlight.acquire(serviceId, requestId)) throw new CheckAlreadyInProgressException(serviceId);
        try {
            String base = service.getBaseUrl().replaceAll("/$", "");
            String path = service.getHealthPath();
            var deployment = service.getCurrentDeployment();
            HealthCheckRequested request = new HealthCheckRequested(1, requestId, serviceId,
                    deployment == null ? null : deployment.getId(),
                    base + (path.startsWith("/") ? path : "/" + path), clock.instant(), monitoring.responseTimeout().toMillis());
            // Stable creation-order routing avoids hash collisions for the first N registered services.
            int partition = (int) (services.countPreceding(service.getCreatedAt(), serviceId) % topics.partitions());
            template.send(topics.requestsTopic(), partition, serviceId.toString(), json.write(request))
                    .whenComplete((sent, failure) -> {
                        try (Observation.Scope scope = observation.openScope()) {
                            if (failure != null) {
                                telemetry.dispatchFailed();
                                inFlight.release(serviceId, requestId);
                                log.atWarn().addKeyValue("event", "health_probe_publish_failed")
                                        .addKeyValue("requestId", requestId).addKeyValue("serviceId", serviceId)
                                        .addKeyValue("errorType", failure.getClass().getSimpleName()).log("health_probe_publish_failed");
                            } else {
                                telemetry.dispatched();
                                log.atInfo().addKeyValue("event", "health_probe_dispatched")
                                        .addKeyValue("requestId", requestId).addKeyValue("serviceId", serviceId)
                                        .addKeyValue("deploymentId", request.deploymentId()).log("health_probe_dispatched");
                            }
                        }
                    });
            return new AsyncCheckResponse(requestId, serviceId, "QUEUED");
        } catch (RuntimeException exception) {
            telemetry.dispatchFailed();
            inFlight.release(serviceId, requestId);
            log.warn("health_probe_queue_failed requestId={} serviceId={} errorType={}",
                    requestId, serviceId, exception.getClass().getSimpleName());
            throw new ProbeDispatchException();
        }
    }
}
