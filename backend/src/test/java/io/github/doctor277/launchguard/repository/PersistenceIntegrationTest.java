package io.github.doctor277.launchguard.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.doctor277.launchguard.domain.HealthCheck;
import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "spring.task.scheduling.enabled=false")
class PersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private MonitoredServiceRepository serviceRepository;

    @Autowired
    private HealthCheckRepository healthCheckRepository;

    @Test
    void flywaySchemaPersistsHealthCheckHistory() {
        MonitoredService service = serviceRepository.saveAndFlush(
                MonitoredService.register("integration-payment-service", "http://payment-service:8081", "/health"));
        Instant checkedAt = Instant.parse("2026-09-24T12:00:00Z");
        HealthCheck check = HealthCheck.record(service, ServiceStatus.HEALTHY, 200, 17, null, checkedAt);

        healthCheckRepository.saveAndFlush(check);

        assertThat(healthCheckRepository.findAllByServiceIdOrderByCheckedAtDesc(service.getId()))
                .singleElement()
                .satisfies(persisted -> {
                    assertThat(persisted.getStatus()).isEqualTo(ServiceStatus.HEALTHY);
                    assertThat(persisted.getHttpStatus()).isEqualTo(200);
                    assertThat(persisted.getResponseTimeMs()).isEqualTo(17);
                });
    }
}
