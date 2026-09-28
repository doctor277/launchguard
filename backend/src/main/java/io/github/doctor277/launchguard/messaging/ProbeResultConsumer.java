package io.github.doctor277.launchguard.messaging;

import io.github.doctor277.launchguard.events.EventJson;
import io.github.doctor277.launchguard.events.HealthCheckCompleted;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ProbeResultConsumer {
    private final EventJson json;
    private final ProbeResultPersistence persistence;
    private final InFlightProbeRegistry inFlight;

    public ProbeResultConsumer(EventJson json, ProbeResultPersistence persistence, InFlightProbeRegistry inFlight) {
        this.json = json;
        this.persistence = persistence;
        this.inFlight = inFlight;
    }

    @KafkaListener(id = "probeResults", topics = "${launchguard.kafka.results-topic:launchguard.health-check.results}")
    public void consume(ConsumerRecord<String, String> record) {
        HealthCheckCompleted event = json.read(record.value(), HealthCheckCompleted.class);
        if (!event.serviceId().toString().equals(record.key())) throw new IllegalArgumentException("Result key does not match serviceId");
        persistence.persist(event); // Spring transaction commits before releasing the guard/acknowledging Kafka.
        inFlight.release(event.serviceId(), event.requestId());
    }
}
