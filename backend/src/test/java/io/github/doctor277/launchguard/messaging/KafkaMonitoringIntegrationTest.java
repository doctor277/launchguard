package io.github.doctor277.launchguard.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import com.sun.net.httpserver.HttpServer;
import io.github.doctor277.launchguard.LaunchGuardApplication;
import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.dto.CreateDeploymentRequest;
import io.github.doctor277.launchguard.events.EventJson;
import io.github.doctor277.launchguard.events.HealthCheckCompleted;
import io.github.doctor277.launchguard.events.HealthCheckRequested;
import io.github.doctor277.launchguard.events.ServiceStatus;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import io.github.doctor277.launchguard.service.DeploymentService;
import io.github.doctor277.launchguard.service.IncidentService;
import io.github.doctor277.probeworker.ProbeWorkerApplication;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = LaunchGuardApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"launchguard.monitoring.initial-delay=24h", "launchguard.kafka.partitions=3",
        "spring.kafka.listener.concurrency=3", "launchguard.monitoring.response-timeout=30s",
        "logging.level.org.apache.kafka=WARN", "logging.level.org.springframework.kafka=WARN",
        "management.tracing.sampling.probability=1.0"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaMonitoringIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-alpine");
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.0"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    private static final String REQUESTS = "launchguard.health-check.requests";
    private static final String RESULTS = "launchguard.health-check.results";

    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        KAFKA.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired private ProbeDispatcher dispatcher;
    @Autowired private InFlightProbeRegistry inFlight;
    @Autowired private ProbeResultPersistence persistence;
    @Autowired private MonitoredServiceRepository services;
    @Autowired private DeploymentService deployments;
    @Autowired private IncidentService incidents;
    @Autowired private KafkaTemplate<String, String> template;
    @Autowired private EventJson json;
    @Autowired private JdbcClient jdbc;
    @Autowired private KafkaListenerEndpointRegistry backendListeners;
    @Autowired private ConfigurableApplicationContext backendContext;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meters;
    @org.springframework.boot.test.web.server.LocalServerPort private int port;
    private final java.util.concurrent.atomic.AtomicReference<String> httpTraceparent = new java.util.concurrent.atomic.AtomicReference<>();
    private ConfigurableApplicationContext worker;
    private HttpServer http;
    private java.util.concurrent.ExecutorService httpExecutor;
    private CountDownLatch slowStarted;
    private CountDownLatch blockedStarted;
    private CountDownLatch releaseBlocked;

    @BeforeAll
    void startWorkerWithoutAnyDatabase() {
        worker = new SpringApplicationBuilder(ProbeWorkerApplication.class).web(WebApplicationType.NONE).run(
                "--spring.config.location=optional:classpath:/no-worker-test-config.yml",
                "--spring.application.name=probe-worker",
                "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "--spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "--spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "--spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "--spring.kafka.consumer.auto-offset-reset=earliest", "--spring.kafka.consumer.enable-auto-commit=false",
                "--spring.kafka.consumer.properties.max.poll.records=8", "--spring.kafka.listener.concurrency=3",
                "--spring.kafka.listener.ack-mode=record", "--spring.kafka.admin.fail-fast=true",
                "--spring.kafka.template.observation-enabled=true", "--spring.kafka.listener.observation-enabled=true",
                "--management.tracing.export.otlp.enabled=false", "--management.tracing.sampling.probability=1.0",
                "--management.otlp.metrics.export.enabled=false", "--management.logging.export.enabled=false",
                "--launchguard.kafka.partitions=3", "--logging.level.org.apache.kafka=WARN",
                "--logging.level.org.springframework.kafka=WARN");
        assertThat(worker.containsBean("dataSource")).isFalse();
        await().atMost(Duration.ofSeconds(30)).until(() ->
                backendListeners.getListenerContainer("probeResults").getAssignedPartitions().size() == 3
                && worker.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("probeRequests")
                    .getAssignedPartitions().size() == 3);
    }

    @BeforeEach
    void startHttpTargets() throws Exception {
        slowStarted = new CountDownLatch(1);
        blockedStarted = new CountDownLatch(1);
        releaseBlocked = new CountDownLatch(1);
        httpExecutor = Executors.newCachedThreadPool();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(httpExecutor);
        http.createContext("/healthy", exchange -> {
            httpTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        http.createContext("/failed", exchange -> { exchange.sendResponseHeaders(500, -1); exchange.close(); });
        http.createContext("/slow", exchange -> {
            slowStarted.countDown();
            try { Thread.sleep(1500); exchange.sendResponseHeaders(200, -1); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        http.createContext("/blocked", exchange -> {
            blockedStarted.countDown();
            try {
                if (releaseBlocked.await(20, TimeUnit.SECONDS)) exchange.sendResponseHeaders(200, -1);
                else exchange.sendResponseHeaders(503, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        http.start();
    }
    @AfterEach
    void stopHttp() {
        releaseBlocked.countDown();
        http.stop(0);
        httpExecutor.shutdownNow();
    }
    @AfterAll
    void stopContainers() {
        if (worker != null) worker.close();
        // Spring owns the cached backend context lifecycle. Stop its clients before the broker.
        backendListeners.stop();
        ((org.springframework.kafka.core.DefaultKafkaProducerFactory<?, ?>) template.getProducerFactory()).reset();
        backendContext.getBean(org.apache.kafka.clients.admin.Admin.class).close(Duration.ofSeconds(2));
        KAFKA.stop();
        POSTGRES.stop();
    }

    private MonitoredService register(String path) {
        return services.saveAndFlush(MonitoredService.register("kafka-" + UUID.randomUUID(),
                "http://127.0.0.1:" + http.getAddress().getPort(), path));
    }
    private long rows(UUID requestId) {
        return jdbc.sql("SELECT COUNT(*) FROM health_checks WHERE probe_request_id=:id")
                .param("id", requestId).query(Long.class).single();
    }
    private void awaitResult(UUID requestId) {
        await().atMost(Duration.ofSeconds(20)).until(() -> rows(requestId) == 1);
    }
    private HealthCheckCompleted result(MonitoredService service, ServiceStatus status, Instant checkedAt) {
        return new HealthCheckCompleted(1, UUID.randomUUID(), service.getId(), null, status,
                status == ServiceStatus.HEALTHY ? 200 : 500, 20, null, checkedAt);
    }
    private void sendResult(HealthCheckCompleted event) throws Exception {
        template.send(RESULTS, event.serviceId().toString(), json.write(event)).get(10, TimeUnit.SECONDS);
        awaitResult(event.requestId());
    }

    @Test
    void fullKafkaFlowPersistsFastServicesBeforeSlowAndCapturesDispatchDeployment() throws Exception {
        var slow = register("/blocked");
        var fastPayment = register("/healthy");
        var fastOrder = register("/healthy");
        var firstDeployment = deployments.create(slow.getId(), new CreateDeploymentRequest("v1", "a921fc7", "CI release",
                io.github.doctor277.launchguard.domain.DeploymentSource.CI, "local", "sha-a921fc7", "kafka-ci-run"));
        assertThat(firstDeployment.source()).isEqualTo(io.github.doctor277.launchguard.domain.DeploymentSource.CI);
        long started = System.nanoTime();
        var slowQueued = dispatcher.dispatch(slow.getId());
        UUID nextDeploymentId;
        long fastPersistedMs;
        try {
            assertThat(blockedStarted.await(10, TimeUnit.SECONDS)).isTrue();
            nextDeploymentId = deployments.create(slow.getId(),
                    new CreateDeploymentRequest("v2", null, null)).id();
            var paymentQueued = dispatcher.dispatch(fastPayment.getId());
            var orderQueued = dispatcher.dispatch(fastOrder.getId());
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    rows(paymentQueued.requestId()) == 1 && rows(orderQueued.requestId()) == 1);
            fastPersistedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(rows(slowQueued.requestId())).isZero();
        } finally {
            releaseBlocked.countDown();
        }
        awaitResult(slowQueued.requestId());
        UUID captured = jdbc.sql("SELECT deployment_id FROM health_checks WHERE probe_request_id=:id")
                .param("id", slowQueued.requestId()).query(UUID.class).single();
        assertThat(captured).isEqualTo(firstDeployment.id());
        assertThat(services.findById(slow.getId()).orElseThrow().getCurrentDeployment().getId())
                .isEqualTo(nextDeploymentId);
        assertThat(services.findById(fastPayment.getId()).orElseThrow().getStatus().name()).isEqualTo("HEALTHY");
        System.out.println("KAFKA_FLOW_CONCURRENCY fastPersistedMs=" + fastPersistedMs
                + " slowPersistedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    @Test
    void duplicateResultsDoNotDuplicateChecksOrIncidentTransitions() throws Exception {
        double openedBefore = meters.get("launchguard.incidents.opened").counter().count();
        double resolvedBefore = meters.get("launchguard.incidents.resolved").counter().count();
        double failedBefore = meters.get("launchguard.probe.results").tag("status", "DOWN").counter().count();
        double healthyBefore = meters.get("launchguard.probe.results").tag("status", "HEALTHY").counter().count();
        var service = register("/healthy");
        Instant start = Instant.now();
        var first = result(service, ServiceStatus.DOWN, start);
        sendResult(first);
        template.send(RESULTS, service.getId().toString(), json.write(first)).get(10, TimeUnit.SECONDS);
        var second = result(service, ServiceStatus.DOWN, start.plusMillis(1));
        sendResult(second);
        assertThat(incidents.current(service.getId())).isEmpty();
        var third = result(service, ServiceStatus.DOWN, start.plusMillis(2));
        sendResult(third);
        var open = incidents.current(service.getId()).orElseThrow();
        template.send(RESULTS, service.getId().toString(), json.write(third)).get(10, TimeUnit.SECONDS);
        sendResult(result(service, ServiceStatus.HEALTHY, start.plusMillis(3)));
        sendResult(result(service, ServiceStatus.HEALTHY, start.plusMillis(4)));
        template.send(RESULTS, service.getId().toString(), json.write(third)).get(10, TimeUnit.SECONDS);
        // A later barrier record on that partition ensures preceding duplicates have been handled.
        sendResult(result(service, ServiceStatus.HEALTHY, start.plusMillis(5)));
        assertThat(rows(first.requestId())).isEqualTo(1);
        assertThat(rows(third.requestId())).isEqualTo(1);
        assertThat(incidents.current(service.getId())).isEmpty();
        assertThat(incidents.findById(service.getId(), open.id()).status().name()).isEqualTo("RESOLVED");
        assertThat(incidents.findAll(service.getId(), 0, 20, null).totalElements()).isEqualTo(1);
        assertThat(meters.get("launchguard.incidents.opened").counter().count()).isEqualTo(openedBefore + 1);
        assertThat(meters.get("launchguard.incidents.resolved").counter().count()).isEqualTo(resolvedBefore + 1);
        assertThat(meters.get("launchguard.probe.results").tag("status", "DOWN").counter().count()).isEqualTo(failedBefore + 3);
        assertThat(meters.get("launchguard.probe.results").tag("status", "HEALTHY").counter().count()).isEqualTo(healthyBefore + 3);
    }

    @Test
    void managementHealthAndPrometheusAreAvailableWithoutSensitiveEndpoints() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        for (String path : java.util.List.of("/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness")) {
            var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("UP").doesNotContain("password");
        }
        var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes", "launchguard_probe_requests_dispatched_total");
        var endpoints = backendContext.getBean(org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier.class)
                .getEndpoints().stream().map(endpoint -> endpoint.getEndpointId().toString()).toList();
        assertThat(endpoints).containsExactlyInAnyOrder("health", "prometheus");
        assertThat(backendContext.getBeansOfType(io.micrometer.core.instrument.MeterRegistry.class).values())
                .noneMatch(registry -> registry.getClass().getName().contains(".otlp."));
        assertThat(worker.getBeansOfType(io.micrometer.core.instrument.MeterRegistry.class).values())
                .noneMatch(registry -> registry.getClass().getName().contains(".otlp."));
    }

    @Test
    void w3cTraceContextSurvivesBothKafkaMessagesAndWorkerHttpThreadHop() throws Exception {
        var service = register("/healthy");
        try (var requests = dltConsumer(REQUESTS); var results = dltConsumer(RESULTS)) {
            var queued = dispatcher.dispatch(service.getId());
            awaitResult(queued.requestId());
            String requestHeader = traceHeader(requests, queued.requestId());
            String resultHeader = traceHeader(results, queued.requestId());
            assertThat(requestHeader).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
            // W3C Level 2 permits additional flags (e.g. random trace ID); sampled is bit zero.
            assertThat(Integer.parseInt(requestHeader.split("-")[3], 16) & 1).isEqualTo(1);
            assertThat(resultHeader.split("-")[1]).isEqualTo(requestHeader.split("-")[1]);
            assertThat(httpTraceparent.get().split("-")[1]).isEqualTo(requestHeader.split("-")[1]);
        }
    }

    private String traceHeader(KafkaConsumer<String, String> consumer, UUID requestId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            for (var record : consumer.poll(Duration.ofMillis(100))) {
                if (record.value().contains(requestId.toString())) {
                    var header = record.headers().lastHeader("traceparent");
                    assertThat(header).isNotNull();
                    return new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Missing traced Kafka record " + requestId);
    }

    @Test
    void lateResultForDeletedServiceIsAcknowledgedSafely() throws Exception {
        var service = register("/slow");
        var queued = dispatcher.dispatch(service.getId());
        assertThat(slowStarted.await(10, TimeUnit.SECONDS)).isTrue();
        services.deleteById(service.getId());
        await().atMost(Duration.ofSeconds(20)).until(() -> !inFlight.isInFlight(service.getId()));
        assertThat(rows(queued.requestId())).isZero();
        var other = register("/healthy");
        var otherRequest = dispatcher.dispatch(other.getId());
        awaitResult(otherRequest.requestId());
    }

    @Test
    void malformedAndUnsupportedEventsGoToDltButHttp500DoesNot() throws Exception {
        var service = register("/failed");
        var poison = "{";
        var unsupported = "{\"eventVersion\":99}";
        try (var requestDlt = dltConsumer(REQUESTS + ".dlt"); var resultDlt = dltConsumer(RESULTS + ".dlt")) {
            template.send(REQUESTS, service.getId().toString(), poison).get(10, TimeUnit.SECONDS);
            template.send(RESULTS, service.getId().toString(), unsupported).get(10, TimeUnit.SECONDS);
            awaitDlt(requestDlt, poison);
            awaitDlt(resultDlt, unsupported);
            var queued = dispatcher.dispatch(service.getId());
            awaitResult(queued.requestId());
            assertThat(jdbc.sql("SELECT status FROM health_checks WHERE probe_request_id=:id")
                    .param("id", queued.requestId()).query(String.class).single()).isEqualTo("DOWN");
            assertThat(requestDlt.poll(Duration.ofMillis(300)).isEmpty()).isTrue();
        }
    }

    @Test
    void historicalResultIsStoredWithoutRegressingCurrentStatus() throws Exception {
        var service = register("/healthy");
        Instant now = Instant.now();
        var healthy = result(service, ServiceStatus.HEALTHY, now);
        sendResult(healthy);
        var olderFailure = result(service, ServiceStatus.DOWN, now.minusSeconds(5));
        sendResult(olderFailure);
        assertThat(rows(olderFailure.requestId())).isEqualTo(1);
        assertThat(services.findById(service.getId()).orElseThrow().getStatus().name()).isEqualTo("HEALTHY");
        assertThat(incidents.current(service.getId())).isEmpty();
    }

    @Test
    void concurrentDuplicatePersistenceIsProtectedByDatabaseAndServiceLock() throws Exception {
        var service = register("/healthy");
        var event = result(service, ServiceStatus.DOWN, Instant.now());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> persistence.persist(event));
            var second = executor.submit(() -> persistence.persist(event));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(rows(event.requestId())).isEqualTo(1);
        assertThat(incidents.current(service.getId())).isEmpty();
    }

    private KafkaConsumer<String, String> dltConsumer(String topic) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("group.id", "dlt-test-" + UUID.randomUUID());
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", false);
        properties.put("key.deserializer", StringDeserializer.class);
        properties.put("value.deserializer", StringDeserializer.class);
        var consumer = new KafkaConsumer<String, String>(properties);
        consumer.subscribe(java.util.List.of(topic));
        return consumer;
    }
    private void awaitDlt(KafkaConsumer<String, String> consumer, String expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            for (var record : consumer.poll(Duration.ofMillis(100))) {
                if (expected.equals(record.value())) return;
            }
        }
        throw new AssertionError("Poison event was not recovered to DLT");
    }
}
