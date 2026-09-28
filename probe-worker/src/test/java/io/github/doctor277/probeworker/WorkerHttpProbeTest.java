package io.github.doctor277.probeworker;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import io.github.doctor277.launchguard.events.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;

class WorkerHttpProbeTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private WorkerHttpProbe probe;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry meters;

    @BeforeEach
    void start() throws Exception {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/healthy", exchange -> { exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.createContext("/failed", exchange -> { exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(500); exchange.sendResponseHeaders(200, -1); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.createContext("/slow-body", exchange -> {
            exchange.sendResponseHeaders(200, 1);
            try { Thread.sleep(500); exchange.getResponseBody().write(1); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        probe = new WorkerHttpProbe(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(), Clock.systemUTC(),
                new WorkerTelemetry(meters), io.micrometer.observation.ObservationRegistry.NOOP);
    }
    @AfterEach
    void stop() { server.stop(0); executor.shutdownNow(); }

    private HealthCheckRequested request(String path, long timeout) {
        return new HealthCheckRequested(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "http://127.0.0.1:" + server.getAddress().getPort() + path, Instant.now(), timeout);
    }

    @Test
    void successfulProbePreservesEveryCorrelationField() {
        var request = request("/healthy", 2000);
        var result = probe.probe(request);
        assertThat(result.status()).isEqualTo(ServiceStatus.HEALTHY);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.requestId()).isEqualTo(request.requestId());
        assertThat(result.serviceId()).isEqualTo(request.serviceId());
        assertThat(result.deploymentId()).isEqualTo(request.deploymentId());
        assertThat(result.responseTimeMs()).isNotNegative();
        assertThat(meters.get("launchguard.probe.worker.requests").tag("status", "HEALTHY").counter().count()).isEqualTo(1);
        assertThat(meters.get("launchguard.probe.worker.duration").tag("status", "HEALTHY").timer().count()).isEqualTo(1);
    }
    @Test
    void http500IsValidDownResult() {
        var result = probe.probe(request("/failed", 2000));
        assertThat(result.status()).isEqualTo(ServiceStatus.DOWN);
        assertThat(result.httpStatus()).isEqualTo(500);
        assertThat(meters.get("launchguard.probe.worker.requests").tag("status", "DOWN").counter().count()).isEqualTo(1);
    }
    @Test
    void deadlineProducesDownWithoutHttpStatus() {
        var result = probe.probe(request("/slow", 100));
        assertThat(result.status()).isEqualTo(ServiceStatus.DOWN);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.errorMessage()).contains("Timeout");
        assertThat(result.responseTimeMs()).isBetween(75L, 1500L);
    }
    @Test
    void connectionFailureProducesDown() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var request = new HealthCheckRequested(1, UUID.randomUUID(), UUID.randomUUID(), null,
                "http://127.0.0.1:" + port + "/health", Instant.now(), 1000);
        var result = probe.probe(request);
        assertThat(result.status()).isEqualTo(ServiceStatus.DOWN);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.errorMessage()).isNotBlank();
    }

    @Test
    void durationReflectsSlowProbeWithoutIdentifierTags() {
        var result = probe.probe(request("/slow", 2000));
        assertThat(result.status()).isEqualTo(ServiceStatus.HEALTHY);
        var timer = meters.get("launchguard.probe.worker.duration").tag("status", "HEALTHY").timer();
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(450);
        assertThat(meters.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("status")));
    }

    @Test
    void deadlineAlsoBoundsAStalledResponseBody() {
        var result = probe.probe(request("/slow-body", 100));
        assertThat(result.status()).isEqualTo(ServiceStatus.DOWN);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.responseTimeMs()).isBetween(75L, 1500L);
    }
}
