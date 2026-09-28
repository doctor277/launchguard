package io.github.doctor277.launchguard.messaging;

import io.github.doctor277.launchguard.events.EventJson;
import io.github.doctor277.launchguard.events.HealthCheckCompleted;
import io.github.doctor277.launchguard.events.ProbeObservations;
import io.github.doctor277.launchguard.observability.BackendTelemetry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ProbeResultConsumer {
    private final EventJson json;
    private final ProbeResultPersistence persistence;
    private final InFlightProbeRegistry inFlight;
    private final BackendTelemetry telemetry;
    private final ObservationRegistry observations;

    public ProbeResultConsumer(EventJson json, ProbeResultPersistence persistence, InFlightProbeRegistry inFlight,
                               BackendTelemetry telemetry, ObservationRegistry observations) {
        this.json = json;
        this.persistence = persistence;
        this.inFlight = inFlight;
        this.telemetry = telemetry;
        this.observations = observations;
    }

    @KafkaListener(id = "probeResults", topics = "${launchguard.kafka.results-topic:launchguard.health-check.results}")
    public void consume(ConsumerRecord<String, String> record) {
        var sample = telemetry.processingStarted();
        boolean successful = false;
        try {
            HealthCheckCompleted event = json.read(record.value(), HealthCheckCompleted.class);
            if (!event.serviceId().toString().equals(record.key())) throw new IllegalArgumentException("Result key does not match serviceId");
            Observation observation = ProbeObservations.start("launchguard.result.consume", observations,
                    event.requestId(), event.serviceId());
            try (Observation.Scope scope = observation.openScope()) {
                persistence.persist(event); // Transaction commits before guard release and Kafka ack.
                inFlight.release(event.serviceId(), event.requestId());
                successful = true;
            } catch (RuntimeException exception) {
                observation.error(exception);
                throw exception;
            } finally {
                observation.stop();
            }
        } finally {
            telemetry.processingFinished(sample, successful);
        }
    }
}
