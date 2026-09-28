package io.github.doctor277.launchguard.events;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;

/** Identifiers are trace-only high-cardinality attributes, never metric labels. */
public final class ProbeObservations {
    private ProbeObservations() {
    }

    public static Observation start(String name, ObservationRegistry registry, UUID requestId, UUID serviceId) {
        return Observation.createNotStarted(name, registry)
                .highCardinalityKeyValue("launchguard.request.id", requestId.toString())
                .highCardinalityKeyValue("launchguard.service.id", serviceId.toString()).start();
    }
}
