package io.github.doctor277.probeworker;

import io.github.doctor277.launchguard.events.HealthCheckRequested;
import io.github.doctor277.launchguard.events.HealthCheckCompleted;
import io.github.doctor277.launchguard.events.ServiceStatus;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.RequestReplySenderContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class WorkerHttpProbe {
    private final HttpClient client;
    private final Clock clock;
    private final WorkerTelemetry telemetry;
    private final ObservationRegistry observations;

    public WorkerHttpProbe(HttpClient client, Clock clock, WorkerTelemetry telemetry, ObservationRegistry observations) {
        this.client = client;
        this.clock = clock;
        this.telemetry = telemetry;
        this.observations = observations;
    }

    public HealthCheckCompleted probe(HealthCheckRequested request) {
        long started = System.nanoTime();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.targetUrl()))
                .timeout(Duration.ofMillis(request.timeoutMs())).GET();
        var context = new RequestReplySenderContext<HttpRequest.Builder, HttpResponse<Void>>(
                (carrier, key, value) -> carrier.setHeader(key, value));
        context.setCarrier(builder);
        context.setRemoteServiceName("monitored-service");
        Observation observation = Observation.createNotStarted("launchguard.probe.http", () -> context, observations)
                .highCardinalityKeyValue("launchguard.request.id", request.requestId().toString())
                .highCardinalityKeyValue("launchguard.service.id", request.serviceId().toString()).start();
        try (Observation.Scope scope = observation.openScope()) {
            var result = execute(request, builder);
            observation.lowCardinalityKeyValue("status", result.status().name());
            telemetry.completed(result.status().name(), System.nanoTime() - started);
            return result;
        } catch (RuntimeException exception) {
            observation.error(exception);
            telemetry.completed("ERROR", System.nanoTime() - started);
            throw exception;
        } finally { observation.stop(); }
    }

    private HealthCheckCompleted execute(HealthCheckRequested request, HttpRequest.Builder builder) {
        long start = System.nanoTime();
        Integer httpStatus = null;
        String error = null;
        java.util.concurrent.CompletableFuture<HttpResponse<Void>> response = null;
        try {
            HttpRequest httpRequest = builder.build();
            response = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.discarding());
            httpStatus = response.get(request.timeoutMs(), TimeUnit.MILLISECONDS).statusCode();
            if (httpStatus < 200 || httpStatus >= 300) error = "HTTP request returned status " + httpStatus;
        } catch (java.util.concurrent.TimeoutException exception) {
            response.cancel(true);
            error = "TimeoutException: Probe response deadline exceeded";
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception.getCause();
            error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        } catch (InterruptedException exception) {
            if (response != null) response.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Probe worker interrupted", exception);
        }
        ServiceStatus status = httpStatus != null && httpStatus >= 200 && httpStatus < 300
                ? ServiceStatus.HEALTHY : ServiceStatus.DOWN;
        if (error != null && error.length() > 2048) error = error.substring(0, 2048);
        return new HealthCheckCompleted(1, request.requestId(), request.serviceId(), request.deploymentId(), status,
                httpStatus, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), error, clock.instant());
    }
}
