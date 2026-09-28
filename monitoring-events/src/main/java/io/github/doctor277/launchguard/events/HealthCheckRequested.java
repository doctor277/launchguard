package io.github.doctor277.launchguard.events;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record HealthCheckRequested(int eventVersion, UUID requestId, UUID serviceId, UUID deploymentId,
                                   String targetUrl, Instant requestedAt, long timeoutMs) {
    public HealthCheckRequested {
        if (eventVersion != 1) throw new IllegalArgumentException("Unsupported request eventVersion: " + eventVersion);
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(requestedAt, "requestedAt");
        URI uri = URI.create(Objects.requireNonNull(targetUrl, "targetUrl"));
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || targetUrl.length() > 3072) {
            throw new IllegalArgumentException("targetUrl must be an absolute HTTP(S) URL without credentials");
        }
        if (timeoutMs < 1 || timeoutMs > 60000) throw new IllegalArgumentException("timeoutMs must be 1..60000");
    }
}
