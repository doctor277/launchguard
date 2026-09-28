package io.github.doctor277.launchguard.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.doctor277.launchguard.domain.Deployment;
import io.github.doctor277.launchguard.domain.HealthCheck;
import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import io.github.doctor277.launchguard.dto.CreateDeploymentRequest;
import io.github.doctor277.launchguard.service.DeploymentService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

@SpringBootTest
@EnabledIf("databaseAvailable")
@TestPropertySource(properties = "launchguard.monitoring.initial-delay=24h")
class PersistenceIntegrationTest {

    private static final String EXTERNAL_DB_URL = System.getenv("LAUNCHGUARD_TEST_DB_URL");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static boolean databaseAvailable() {
        return EXTERNAL_DB_URL != null && !EXTERNAL_DB_URL.isBlank()
                || DockerClientFactory.instance().isDockerAvailable();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        if (EXTERNAL_DB_URL != null && !EXTERNAL_DB_URL.isBlank()) {
            registry.add("spring.datasource.url", () -> EXTERNAL_DB_URL);
            registry.add("spring.datasource.username", () -> "launchguard");
            registry.add("spring.datasource.password", () -> "launchguard");
        } else {
            POSTGRES.start();
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        }
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Autowired
    private MonitoredServiceRepository serviceRepository;

    @Autowired
    private HealthCheckRepository healthCheckRepository;

    @Autowired
    private HealthCheckMetricsRepository metricsRepository;

    @Autowired
    private DeploymentRepository deploymentRepository;

    @Autowired
    private DeploymentMetricsRepository deploymentMetricsRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DeploymentService deploymentService;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    @Test
    void persistsDeploymentCorrelationAndKeepsEarlierHistory() {
        MonitoredService service = serviceRepository.saveAndFlush(
                MonitoredService.register("deployment-history-service", "http://payment-service:8081", "/health"));
        Instant now = Instant.parse("2026-09-24T12:00:00Z");
        var firstResponse = deploymentService.create(service.getId(),
                new CreateDeploymentRequest("v1.0.0", null, null));
        Deployment first = deploymentRepository.findById(firstResponse.id()).orElseThrow();
        HealthCheck firstCheck = healthCheckRepository.saveAndFlush(
                HealthCheck.record(service, first, ServiceStatus.HEALTHY, 200, 20, null, now.minusSeconds(30)));

        var secondResponse = deploymentService.create(service.getId(),
                new CreateDeploymentRequest("v1.1.0", "a921fc7", null));
        Deployment second = deploymentRepository.findById(secondResponse.id()).orElseThrow();
        HealthCheck secondCheck = healthCheckRepository.saveAndFlush(
                HealthCheck.record(service, second, ServiceStatus.DOWN, 500, 100, "HTTP 500", now));

        UUID originalDeploymentId = jdbcClient.sql("SELECT deployment_id FROM health_checks WHERE id = :id")
                .param("id", firstCheck.getId()).query(UUID.class).single();
        UUID currentDeploymentId = jdbcClient.sql("SELECT current_deployment_id FROM monitored_services WHERE id = :id")
                .param("id", service.getId()).query(UUID.class).single();
        assertThat(originalDeploymentId).isEqualTo(first.getId());
        assertThat(currentDeploymentId).isEqualTo(second.getId());
        assertThat(healthCheckRepository.findById(secondCheck.getId())).isPresent();

        DeploymentMetricsAggregate firstMetrics = deploymentMetricsRepository.summarize(first.getId());
        DeploymentMetricsAggregate secondMetrics = deploymentMetricsRepository.summarize(second.getId());
        assertThat(firstMetrics.totalChecks()).isEqualTo(1);
        assertThat(firstMetrics.healthyChecks()).isEqualTo(1);
        assertThat(secondMetrics.totalChecks()).isEqualTo(1);
        assertThat(secondMetrics.failedChecks()).isEqualTo(1);
        assertThat(secondMetrics.firstFailureAt()).isEqualTo(now);
    }

    @Test
    void rejectsCrossServiceDeploymentReferenceAndCascadesServiceDeletion() {
        MonitoredService firstService = serviceRepository.saveAndFlush(
                MonitoredService.register("fk-first-service", "http://payment-service:8081", "/health"));
        MonitoredService secondService = serviceRepository.saveAndFlush(
                MonitoredService.register("fk-second-service", "http://payment-service:8082", "/health"));
        Instant now = Instant.parse("2026-09-24T12:00:00Z");
        Deployment deployment = deploymentRepository.saveAndFlush(
                Deployment.register(secondService, "v1.0.0", null, null, now));

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO health_checks (id, service_id, deployment_id, status, response_time_ms, checked_at)
                        VALUES (:id, :serviceId, :deploymentId, 'HEALTHY', 10, :checkedAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("serviceId", firstService.getId())
                .param("deploymentId", deployment.getId())
                .param("checkedAt", java.sql.Timestamp.from(now))
                .update()).hasMessageContaining("fk_health_checks_deployment");

        secondService.setCurrentDeployment(deployment);
        serviceRepository.saveAndFlush(secondService);
        healthCheckRepository.saveAndFlush(
                HealthCheck.record(secondService, deployment, ServiceStatus.HEALTHY, 200, 10, null, now));
        serviceRepository.deleteById(secondService.getId());

        assertThat(deploymentRepository.findById(deployment.getId())).isEmpty();
        assertThat(deploymentMetricsRepository.summarize(deployment.getId()).totalChecks()).isZero();
    }

    @Test
    void healthStatusUpdateDoesNotOverwriteDeploymentRegisteredDuringCheck() {
        MonitoredService service = serviceRepository.saveAndFlush(
                MonitoredService.register("concurrent-deployment-service", "http://payment-service:8081", "/health"));
        deploymentService.create(service.getId(), new CreateDeploymentRequest("v1.0.0", null, null));
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate registration = new TransactionTemplate(transactionManager);
        registration.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(status -> {
            MonitoredService checkingService = serviceRepository.findById(service.getId()).orElseThrow();
            Deployment observedDeployment = checkingService.getCurrentDeployment();
            registration.executeWithoutResult(inner -> deploymentService.create(service.getId(),
                    new CreateDeploymentRequest("v1.1.0", null, null)));
            Instant checkedAt = Instant.parse("2026-09-24T12:00:00Z");
            healthCheckRepository.save(HealthCheck.record(checkingService, observedDeployment,
                    ServiceStatus.HEALTHY, 200, 10, null, checkedAt));
            checkingService.recordStatus(ServiceStatus.HEALTHY, checkedAt);
            serviceRepository.save(checkingService);
        });

        MonitoredService currentService = serviceRepository.findById(service.getId()).orElseThrow();
        assertThat(currentService.getCurrentDeployment().getVersion()).isEqualTo("v1.1.0");
        assertThat(currentService.getStatus()).isEqualTo(ServiceStatus.HEALTHY);
    }
}
