package io.github.doctor277.probeworker;

import io.github.doctor277.launchguard.events.EventJson;
import io.github.doctor277.launchguard.events.HealthCheckRequested;
import io.github.doctor277.launchguard.events.KafkaMonitoringProperties;
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

    public ProbeRequestConsumer(EventJson json, WorkerHttpProbe probe, ThreadPoolTaskExecutor executor,
                                KafkaTemplate<String, String> template, KafkaMonitoringProperties topics) {
        this.json = json;
        this.probe = probe;
        this.executor = executor;
        this.template = template;
        this.topics = topics;
    }

    @KafkaListener(id = "probeRequests", topics = "${launchguard.kafka.requests-topic:launchguard.health-check.requests}")
    public CompletableFuture<Void> consume(ConsumerRecord<String, String> record) {
        try {
            HealthCheckRequested request = json.read(record.value(), HealthCheckRequested.class);
            if (!request.serviceId().toString().equals(record.key())) throw new IllegalArgumentException("Request key does not match serviceId");
            return CompletableFuture.supplyAsync(() -> probe.probe(request), executor)
                    .thenCompose(result -> {
                        log.info("health_probe_completed requestId={} serviceId={} status={} latencyMs={}",
                                result.requestId(), result.serviceId(), result.status(), result.responseTimeMs());
                        return template.send(topics.resultsTopic(), record.partition(), result.serviceId().toString(), json.write(result));
                    }).thenApply(sent -> null);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}
