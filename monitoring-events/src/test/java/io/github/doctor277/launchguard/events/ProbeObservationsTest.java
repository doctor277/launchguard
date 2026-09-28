package io.github.doctor277.launchguard.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProbeObservationsTest {
    @Test
    void identifiersStayOutOfMetersAcrossManyDifferentServicesAndRequests() {
        var meters = new SimpleMeterRegistry();
        var observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        for (int i = 0; i < 100; i++) {
            var observation = ProbeObservations.start("launchguard.test.probe", observations,
                    UUID.randomUUID(), UUID.randomUUID());
            observation.lowCardinalityKeyValue("status", "HEALTHY");
            observation.stop();
        }
        assertThat(meters.get("launchguard.test.probe").timer().count()).isEqualTo(100);
        assertThat(meters.getMeters()).hasSizeLessThanOrEqualTo(2);
        meters.getMeters().forEach(meter -> assertThat(meter.getId().getTags())
                .allMatch(tag -> tag.getKey().equals("status") || tag.getKey().equals("error")));
    }
}
