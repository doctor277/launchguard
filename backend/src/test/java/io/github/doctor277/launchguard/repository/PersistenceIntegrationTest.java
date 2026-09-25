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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    @Autowired
    private HealthCheckMetricsRepository metricsRepository;

    @Test
    void flywaySchemaPersistsHealthCheckHistory() {
        MonitoredService service = serviceRepository.saveAndFlush(
                MonitoredService.register("integration-payment-service", "http://payment-service:8081", "/health"));
        Instant checkedAt = Instant.parse("2026-09-24T12:00:00Z");
        HealthCheck check = HealthCheck.record(service, ServiceStatus.HEALTHY, 200, 17, null, checkedAt);

        healthCheckRepository.saveAndFlush(check);

        var page = healthCheckRepository.findAllByServiceId(service.getId(),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "checkedAt")));

        assertThat(page.getContent())
                .singleElement()
                .satisfies(persisted -> {
                    assertThat(persisted.getStatus()).isEqualTo(ServiceStatus.HEALTHY);
                    assertThat(persisted.getHttpStatus()).isEqualTo(200);
                    assertThat(persisted.getResponseTimeMs()).isEqualTo(17);
                });
    }

    @Test
    void aggregatesMetricsInsidePostgresql() {
        MonitoredService service = serviceRepository.saveAndFlush(
                MonitoredService.register("metrics-payment-service", "http://payment-service:8081", "/health"));
        Instant now = Instant.parse("2026-09-24T12:00:00Z");
        healthCheckRepository.saveAllAndFlush(java.util.List.of(
                HealthCheck.record(service, ServiceStatus.HEALTHY, 200, 30, null, now.minusSeconds(120)),
                HealthCheck.record(service, ServiceStatus.DOWN, 500, 90, "HTTP 500", now.minusSeconds(60)),
                HealthCheck.record(service, ServiceStatus.HEALTHY, 200, 60, null, now)));

        MetricsAggregate aggregate = metricsRepository.summarizeSince(service.getId(), now.minusSeconds(90));

        assertThat(aggregate.totalChecks()).isEqualTo(2);
        assertThat(aggregate.healthyChecks()).isEqualTo(1);
        assertThat(aggregate.failedChecks()).isEqualTo(1);
        assertThat(aggregate.averageResponseTimeMs()).isEqualByComparingTo("75");
        assertThat(aggregate.minResponseTimeMs()).isEqualTo(60);
        assertThat(aggregate.maxResponseTimeMs()).isEqualTo(90);
        assertThat(aggregate.lastFailureAt()).isEqualTo(now.minusSeconds(60));
    }
}
