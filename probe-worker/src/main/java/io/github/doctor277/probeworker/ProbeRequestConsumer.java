package io.github.doctor277.probeworker;

import io.github.doctor277.launchguard.events.EventJson;
import io.github.doctor277.launchguard.events.HealthCheckRequested;
import io.github.doctor277.launchguard.events.KafkaMonitoringProperties;
import io.github.doctor277.launchguard.events.ProbeObservations;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ProbeRequestConsumer {
    private static final Logger log = LoggerFactory.getLogger(ProbeRequestConsumer.class);
    private final EventJson json;
    private final WorkerHttpProbe probe;
    private final ThreadPoolTaskExecutor executor;
    private final KafkaTemplate<String, String> template;
    private final KafkaMonitoringProperties topics;
    private final ObservationRegistry observations;

    public ProbeRequestConsumer(EventJson json, WorkerHttpProbe probe, ThreadPoolTaskExecutor executor,
                                KafkaTemplate<String, String> template, KafkaMonitoringProperties topics,
                                ObservationRegistry observations) {
        this.json = json;
        this.probe = probe;
        this.executor = executor;
        this.template = template;
        this.topics = topics;
        this.observations = observations;
    }

    @KafkaListener(id = "probeRequests", topics = "${launchguard.kafka.requests-topic:launchguard.health-check.requests}")
    public CompletableFuture<Void> consume(ConsumerRecord<String, String> record) {
        try {
            HealthCheckRequested request = json.read(record.value(), HealthCheckRequested.class);
            if (!request.serviceId().toString().equals(record.key())) throw new IllegalArgumentException("Request key does not match serviceId");
            Observation observation = ProbeObservations.start("launchguard.worker.process", observations,
                    request.requestId(), request.serviceId());
            try {
                // Capture the Kafka receiver parent, then reopen its child scope on the pool thread.
                // Result publication also runs in scope; no ThreadLocal survives an unscoped hop.
                return CompletableFuture.supplyAsync(() -> {
                    try (Observation.Scope scope = observation.openScope()) {
                        var result = probe.probe(request);
                        log.atInfo().addKeyValue("event", "health_probe_completed")
                                .addKeyValue("requestId", result.requestId()).addKeyValue("serviceId", result.serviceId())
                                .addKeyValue("deploymentId", result.deploymentId()).addKeyValue("status", result.status())
                                .addKeyValue("latencyMs", result.responseTimeMs()).log("health_probe_completed");
                        return template.send(topics.resultsTopic(), record.partition(), result.serviceId().toString(), json.write(result));
                    }
                }, executor).thenCompose(future -> future).<Void>thenApply(sent -> null)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) observation.error(failure);
                        observation.stop();
                    });
            } catch (RuntimeException exception) {
                observation.error(exception);
                observation.stop();
                throw exception;
            }
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}
