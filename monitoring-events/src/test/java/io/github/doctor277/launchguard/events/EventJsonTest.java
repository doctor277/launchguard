package io.github.doctor277.launchguard.events;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventJsonTest {
    private final EventJson json = new EventJson();
    @Test
    void roundTripsRequestIncludingNullableDeploymentAndIsoTime() {
        var request = new HealthCheckRequested(1, UUID.randomUUID(), UUID.randomUUID(), null,
                "http://order-service:8082/health", Instant.parse("2026-09-28T10:00:00Z"), 5000);
        assertThat(json.read(json.write(request), HealthCheckRequested.class)).isEqualTo(request);
        assertThat(json.write(request)).contains("2026-09-28T10:00:00Z");
    }
    @Test
    void roundTripsHealthyAndFailedResultsWithCorrelation() {
        for (ServiceStatus status : ServiceStatus.values()) {
            var event = new HealthCheckCompleted(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), status,
                    status == ServiceStatus.HEALTHY ? 200 : null, 42, status == ServiceStatus.DOWN ? "timeout" : null,
                    Instant.parse("2026-09-28T10:00:00Z"));
            assertThat(json.read(json.write(event), HealthCheckCompleted.class)).isEqualTo(event);
        }
    }
    @Test
    void rejectsUnsupportedMalformedAndInconsistentEvents() {
        assertThatThrownBy(() -> json.read("{", HealthCheckRequested.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthCheckRequested(2, UUID.randomUUID(), UUID.randomUUID(), null,
                "http://service/health", Instant.now(), 5000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthCheckCompleted(2, UUID.randomUUID(), UUID.randomUUID(), null,
                ServiceStatus.DOWN, null, 1, "network", Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthCheckCompleted(1, UUID.randomUUID(), UUID.randomUUID(), null,
                ServiceStatus.HEALTHY, 500, 1, null, Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }
}
