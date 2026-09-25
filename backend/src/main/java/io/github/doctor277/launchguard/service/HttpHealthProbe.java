package io.github.doctor277.launchguard.service;

import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(HttpHealthProbe.class);
    private static final int MAX_ERROR_LENGTH = 2048;

    private final RestClient restClient;

    public HttpHealthProbe(RestClient healthCheckRestClient) {
        this.restClient = healthCheckRestClient;
    }

    @Override
    public ProbeResult probe(MonitoredService service) {
        URI uri = buildHealthUri(service.getBaseUrl(), service.getHealthPath());
        long startedAt = System.nanoTime();

        try {
            int statusCode = restClient.get()
                    .uri(uri)
                    .exchange((request, response) -> response.getStatusCode().value());
            long durationMs = elapsedMilliseconds(startedAt);
            ServiceStatus status = statusCode >= 200 && statusCode < 300
                    ? ServiceStatus.HEALTHY
                    : ServiceStatus.DOWN;
            String error = status == ServiceStatus.DOWN ? "HTTP request returned status " + statusCode : null;
            return new ProbeResult(status, statusCode, durationMs, error);
        } catch (RestClientException exception) {
            long durationMs = elapsedMilliseconds(startedAt);
            String message = safeMessage(exception);
            log.debug("health_check_request_failed service_id={} uri={} error={}",
                    service.getId(), uri, message);
            return new ProbeResult(ServiceStatus.DOWN, null, durationMs, message);
        }
    }

    static URI buildHealthUri(String baseUrl, String healthPath) {
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        String normalizedPath = healthPath.startsWith("/") ? healthPath : "/" + healthPath;
        return URI.create(normalizedBase + normalizedPath);
    }

    private static long elapsedMilliseconds(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static String safeMessage(RestClientException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
