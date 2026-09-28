package io.github.doctor277.launchguard.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.doctor277.launchguard.domain.Deployment;
import io.github.doctor277.launchguard.domain.HealthCheck;
import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import io.github.doctor277.launchguard.dto.CreateDeploymentRequest;
import io.github.doctor277.launchguard.service.DeploymentService;
import io.github.doctor277.launchguard.domain.Incident;
import io.github.doctor277.launchguard.domain.IncidentStatus;
import io.github.doctor277.launchguard.service.IncidentEvaluator;
import io.github.doctor277.launchguard.service.IncidentService;
import io.github.doctor277.launchguard.service.ServiceManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private IncidentEvaluator incidentEvaluator;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private IncidentMetricsRepository incidentMetricsRepository;

    @Autowired
    private ServiceManager serviceManager;

    @Autowired
    private DataSource dataSource;

    @Test
    void incidentLifecyclePreservesDeploymentAndResetsInterruptedRecovery() {
        MonitoredService service = serviceRepository.saveAndFlush(
                MonitoredService.register("incident-lifecycle-service", "http://payment-service:8081", "/health"));
        Instant now = Instant.parse("2026-09-24T12:00:00Z");
        var firstDeployment = deploymentService.create(service.getId(), new CreateDeploymentRequest("v1.0.0", null, null));
        persistAndEvaluate(service, ServiceStatus.HEALTHY, now);
        persistAndEvaluate(service, ServiceStatus.DOWN, now.plusSeconds(1));
        assertThat(incidentService.current(service.getId())).isEmpty();
        persistAndEvaluate(service, ServiceStatus.DOWN, now.plusSeconds(2));
        assertThat(incidentService.current(service.getId())).isEmpty();
        persistAndEvaluate(service, ServiceStatus.DOWN, now.plusSeconds(3));
        var first = incidentService.current(service.getId()).orElseThrow();
        assertThat(first.deployment().id()).isEqualTo(firstDeployment.id());
        assertThat(first.startedAt()).isEqualTo(now.plusSeconds(3));
        assertThat(first.durationSeconds()).isNull();

        persistAndEvaluate(service, ServiceStatus.DOWN, now.plusSeconds(4));
        var secondDeployment = deploymentService.create(service.getId(), new CreateDeploymentRequest("v1.1.0", null, null));
        persistAndEvaluate(service, ServiceStatus.DOWN, now.plusSeconds(5));
        assertThat(incidentService.current(service.getId()).orElseThrow().id()).isEqualTo(first.id());
        assertThat(incidentService.current(service.getId()).orElseThrow().deployment().id()).isEqualTo(firstDeployment.id());
        persistAndEvaluate(service, ServiceStatus.HEALTHY, now.plusSeconds(6));
        assertThat(incidentService.current(service.getId())).isPresent();
        persistAndEvaluate(service, ServiceStatus.DOWN, now.plusSeconds(7));
        persistAndEvaluate(service, ServiceStatus.HEALTHY, now.plusSeconds(8));
        assertThat(incidentService.current(service.getId())).isPresent();
        persistAndEvaluate(service, ServiceStatus.HEALTHY, now.plusSeconds(9));
        assertThat(incidentService.current(service.getId())).isEmpty();
        assertThat(serviceManager.findById(service.getId()).hasOpenIncident()).isFalse();
        var resolved = incidentService.findById(service.getId(), first.id());
        assertThat(resolved.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isEqualTo(now.plusSeconds(9));
        assertThat(resolved.durationSeconds()).isEqualTo(6);

        persistAndEvaluate(service, ServiceStatus.DOWN, now.plusSeconds(10));
        persistAndEvaluate(service, ServiceStatus.DOWN, now.plusSeconds(11));
        persistAndEvaluate(service, ServiceStatus.DOWN, now.plusSeconds(12));
        var second = incidentService.current(service.getId()).orElseThrow();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.deployment().id()).isEqualTo(secondDeployment.id());
        assertThat(incidentService.findById(service.getId(), first.id())).isEqualTo(resolved);
        assertThat(serviceManager.findById(service.getId()).hasOpenIncident()).isTrue();
        assertThat(serviceManager.findAll()).filteredOn(item -> item.id().equals(service.getId()))
                .singleElement().satisfies(item -> assertThat(item.hasOpenIncident()).isTrue());
        var history = incidentService.findAll(service.getId(), 0, 1, null);
        assertThat(history.totalElements()).isEqualTo(2);
        assertThat(history.totalPages()).isEqualTo(2);
        assertThat(history.content()).singleElement().satisfies(item -> assertThat(item.id()).isEqualTo(second.id()));
        assertThat(incidentService.findAll(service.getId(), 0, 20, "OPEN").totalElements()).isEqualTo(1);
        assertThat(incidentService.findAll(service.getId(), 0, 20, "RESOLVED").totalElements()).isEqualTo(1);
    }

    @Test
    void detectsAndResolvesIncidentsWithoutDeployment() {
        MonitoredService service = serviceRepository.saveAndFlush(
                MonitoredService.register("incident-without-deployment", "http://payment-service:8081", "/health"));
        Instant now = Instant.parse("2026-09-24T12:00:00Z");
        for (int i = 0; i < 3; i++) {
            persistAndEvaluate(service, ServiceStatus.DOWN, now.plusSeconds(i));
        }
        var incident = incidentService.current(service.getId()).orElseThrow();
        assertThat(incident.deployment()).isNull();
        persistAndEvaluate(service, ServiceStatus.HEALTHY, now.plusSeconds(3));
        persistAndEvaluate(service, ServiceStatus.HEALTHY, now.plusSeconds(4));
        assertThat(incidentService.findById(service.getId(), incident.id()).status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incidentService.current(service.getId())).isEmpty();
    }

    @Test
    void aggregatesIncidentWindowsAndExcludesOpenIncidentsFromResolutionAverage() {
        MonitoredService service = serviceRepository.saveAndFlush(
                MonitoredService.register("incident-aggregate-service", "http://payment-service:8081", "/health"));
        Instant now = Instant.parse("2026-09-24T12:00:00Z");
        Incident older = Incident.open(service, null, "older", now.minusSeconds(90000));
        older.resolve(now.minusSeconds(89400));
        Incident first = Incident.open(service, null, "recent", now.minusSeconds(200));
        first.resolve(now.minusSeconds(190));
        Incident second = Incident.open(service, null, "recent", now.minusSeconds(100));
        second.resolve(now.minusSeconds(75));
        Incident open = Incident.open(service, null, "ongoing", now.minusSeconds(50));
        incidentRepository.saveAllAndFlush(List.of(older, first, second, open));

        var aggregate = incidentMetricsRepository.summarize(service.getId(), now.minusSeconds(3600), now);
        assertThat(aggregate.totalIncidents()).isEqualTo(3);
        assertThat(aggregate.resolvedIncidents()).isEqualTo(2);
        assertThat(aggregate.openIncidents()).isEqualTo(1);
        assertThat(aggregate.averageResolutionTimeSeconds()).isEqualByComparingTo("17.5");
        assertThat(aggregate.longestIncidentSeconds()).isEqualTo(50);
        assertThat(incidentMetricsRepository.summarize(service.getId(), now.minusSeconds(86400), now).totalIncidents())
                .isEqualTo(3);
        assertThat(incidentMetricsRepository.summarize(service.getId(), null, now).totalIncidents()).isEqualTo(4);
        assertThat(incidentMetricsRepository.summarize(UUID.randomUUID(), null, now).longestIncidentSeconds()).isNull();
    }

    @Test
    void serializesConcurrentEvaluationAndEnforcesSingleOpenIncident() throws Exception {
        MonitoredService service = serviceRepository.saveAndFlush(
                MonitoredService.register("incident-concurrency-service", "http://payment-service:8081", "/health"));
        Instant now = Instant.parse("2026-09-24T12:00:00Z");
        for (int i = 0; i < 2; i++) {
            healthCheckRepository.saveAndFlush(
                    HealthCheck.record(service, ServiceStatus.DOWN, 500, 20, "HTTP 500", now.plusSeconds(i)));
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger offset = new java.util.concurrent.atomic.AtomicInteger(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Void> evaluate = () -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    HealthCheck check = healthCheckRepository.saveAndFlush(HealthCheck.record(service,
                            ServiceStatus.DOWN, 500, 20, "HTTP 500", now.plusSeconds(offset.getAndIncrement())));
                    ready.countDown();
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent evaluation did not start");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Concurrent evaluation interrupted", exception);
                    }
                    incidentEvaluator.evaluate(check);
                });
                return null;
            };
            var first = executor.submit(evaluate);
            var second = executor.submit(evaluate);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(incidentService.findAll(service.getId(), 0, 20, "OPEN").totalElements()).isEqualTo(1);
        assertThatThrownBy(() -> incidentRepository.saveAndFlush(Incident.open(service, null, "duplicate", now)))
                .hasMessageContaining("uk_incidents_one_open_per_service");
    }

    @Test
    void incidentForeignKeysRejectWrongOwnershipAndPreserveHistoryUntilServiceDeletion() {
        MonitoredService service = serviceRepository.saveAndFlush(
                MonitoredService.register("incident-fk-service", "http://payment-service:8081", "/health"));
        MonitoredService other = serviceRepository.saveAndFlush(
                MonitoredService.register("incident-fk-other", "http://payment-service:8081", "/health"));
        Instant now = Instant.parse("2026-09-24T12:00:00Z");
        Deployment deployment = deploymentRepository.saveAndFlush(Deployment.register(service, "v1.0.0", null, null, now));
        Incident incident = Incident.open(service, deployment, "outage", now);
        incident.resolve(now.plusSeconds(60));
        incidentRepository.saveAndFlush(incident);

        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO incidents (id, service_id, deployment_id, status, trigger_reason, started_at, created_at, updated_at)
                VALUES (:id, :service, :deployment, 'OPEN', 'invalid', :now, :now, :now)
                """).param("id", UUID.randomUUID()).param("service", other.getId())
                .param("deployment", deployment.getId()).param("now", java.sql.Timestamp.from(now)).update())
                .hasMessageContaining("fk_incidents_deployment");
        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM deployments WHERE id = :id")
                .param("id", deployment.getId()).update()).hasMessageContaining("fk_incidents_deployment");
        assertThat(incidentRepository.findById(incident.getId())).isPresent();

        serviceRepository.deleteById(service.getId());
        assertThat(incidentRepository.findById(incident.getId())).isEmpty();
        assertThat(deploymentRepository.findById(deployment.getId())).isEmpty();
    }

    @Test
    void upgradesV1ToV2ToV3WithoutChangingLegacyHistory() {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        var configuration = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema);
        configuration.target("1").load().migrate();
        UUID serviceId = UUID.randomUUID();
        UUID checkId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-24T12:00:00Z");
        jdbcClient.sql("INSERT INTO " + schema + ".monitored_services "
                        + "(id,name,base_url,health_path,status,created_at,updated_at) "
                        + "VALUES (:id,'legacy','http://localhost:8081','/health','HEALTHY',:now,:now)")
                .param("id", serviceId).param("now", java.sql.Timestamp.from(now)).update();
        jdbcClient.sql("INSERT INTO " + schema + ".health_checks "
                        + "(id,service_id,status,response_time_ms,checked_at) VALUES (:id,:service,'HEALTHY',42,:now)")
                .param("id", checkId).param("service", serviceId).param("now", java.sql.Timestamp.from(now)).update();
        configuration.target("2").load().migrate();
        UUID deploymentId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO " + schema + ".deployments "
                        + "(id,service_id,version,deployed_at,created_at) VALUES (:id,:service,'v1.0.0',:now,:now)")
                .param("id", deploymentId).param("service", serviceId).param("now", java.sql.Timestamp.from(now)).update();
        jdbcClient.sql("UPDATE " + schema + ".monitored_services SET current_deployment_id=:deployment WHERE id=:id")
                .param("deployment", deploymentId).param("id", serviceId).update();
        configuration.target("3").load().migrate();

        assertThat(jdbcClient.sql("SELECT response_time_ms FROM " + schema + ".health_checks WHERE id=:id")
                .param("id", checkId).query(Long.class).single()).isEqualTo(42);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM " + schema + ".health_checks WHERE deployment_id IS NULL")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT current_deployment_id FROM " + schema + ".monitored_services WHERE id=:id")
                .param("id", serviceId).query(UUID.class).single()).isEqualTo(deploymentId);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM " + schema + ".incidents").query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM " + schema + ".flyway_schema_history WHERE success AND version IS NOT NULL")
                .query(Long.class).single()).isEqualTo(3);
    }

    private HealthCheck persistAndEvaluate(MonitoredService service, ServiceStatus status, Instant checkedAt) {
        return new TransactionTemplate(transactionManager).execute(transaction -> {
            MonitoredService managed = serviceRepository.findById(service.getId()).orElseThrow();
            HealthCheck check = HealthCheck.record(managed, managed.getCurrentDeployment(), status,
                    status == ServiceStatus.HEALTHY ? 200 : 500, 20, null, checkedAt);
            healthCheckRepository.save(check);
            managed.recordStatus(status, checkedAt);
            serviceRepository.save(managed);
            healthCheckRepository.flush();
            incidentEvaluator.evaluate(check);
            return check;
        });
    }

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
