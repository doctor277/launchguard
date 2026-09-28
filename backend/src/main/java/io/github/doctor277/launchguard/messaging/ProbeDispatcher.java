package io.github.doctor277.launchguard.messaging;

import io.github.doctor277.launchguard.events.EventJson;
import io.github.doctor277.launchguard.events.HealthCheckRequested;
import io.github.doctor277.launchguard.events.KafkaMonitoringProperties;
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

    public ProbeDispatcher(MonitoredServiceRepository services, InFlightProbeRegistry inFlight,
            KafkaTemplate<String, String> template, EventJson json, KafkaMonitoringProperties topics,
            MonitoringProperties monitoring, Clock clock) {
        this.services = services;
        this.inFlight = inFlight;
        this.template = template;
        this.json = json;
        this.topics = topics;
        this.monitoring = monitoring;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AsyncCheckResponse dispatch(UUID serviceId) {
        var service = services.findById(serviceId).orElseThrow(() -> new ServiceNotFoundException(serviceId));
        UUID requestId = UUID.randomUUID();
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
                        if (failure != null) {
                            inFlight.release(serviceId, requestId);
                            log.warn("health_probe_publish_failed requestId={} serviceId={} errorType={}",
                                    requestId, serviceId, failure.getClass().getSimpleName());
                        } else {
                            log.info("health_probe_dispatched requestId={} serviceId={}", requestId, serviceId);
                        }
                    });
            return new AsyncCheckResponse(requestId, serviceId, "QUEUED");
        } catch (RuntimeException exception) {
            inFlight.release(serviceId, requestId);
            log.warn("health_probe_queue_failed requestId={} serviceId={} errorType={}",
                    requestId, serviceId, exception.getClass().getSimpleName());
            throw new ProbeDispatchException();
        }
    }
}
