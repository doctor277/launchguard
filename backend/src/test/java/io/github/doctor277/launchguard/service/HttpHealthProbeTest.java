package io.github.doctor277.launchguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class HttpHealthProbeTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "health-probe-test-server");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/healthy", exchange -> respond(exchange, 200, "{\"status\":\"healthy\"}"));
        server.createContext("/failure", exchange -> respond(exchange, 500, "{\"status\":\"unhealthy\"}"));
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(300);
                respond(exchange, 200, "{}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The client is expected to close the connection after timing out.
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void reportsHealthyForTwoHundredResponse() {
        ProbeResult result = probeWithTimeout(Duration.ofSeconds(1)).probe(service("/healthy"));

        assertThat(result.status()).isEqualTo(ServiceStatus.HEALTHY);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.errorMessage()).isNull();
        assertThat(result.responseTimeMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void reportsDownForFiveHundredResponse() {
        ProbeResult result = probeWithTimeout(Duration.ofSeconds(1)).probe(service("/failure"));

        assertThat(result.status()).isEqualTo(ServiceStatus.DOWN);
        assertThat(result.httpStatus()).isEqualTo(500);
        assertThat(result.errorMessage()).contains("500");
    }

    @Test
    void reportsDownWhenResponseTimesOut() {
        ProbeResult result = probeWithTimeout(Duration.ofMillis(50)).probe(service("/slow"));

        assertThat(result.status()).isEqualTo(ServiceStatus.DOWN);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.errorMessage()).isNotBlank();
    }

    private HttpHealthProbe probeWithTimeout(Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(timeout);
        return new HttpHealthProbe(RestClient.builder().requestFactory(requestFactory).build());
    }

    private MonitoredService service(String healthPath) {
        return MonitoredService.register("test-service", baseUrl, healthPath);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
