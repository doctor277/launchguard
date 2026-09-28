package io.github.doctor277.launchguard.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
public class InFlightProbeRegistry {
    private record Pending(UUID requestId, Instant expiresAt) {}
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Clock clock;
    private final DispatchProperties properties;

    public InFlightProbeRegistry(Clock clock, DispatchProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    public boolean acquire(UUID serviceId, UUID requestId) {
        Instant now = clock.instant();
        Pending selected = pending.compute(serviceId, (id, current) ->
                current == null || !current.expiresAt().isAfter(now)
                        ? new Pending(requestId, now.plus(properties.inFlightTtl())) : current);
        return selected.requestId().equals(requestId);
    }

    public void release(UUID serviceId, UUID requestId) {
        pending.computeIfPresent(serviceId, (id, current) -> current.requestId().equals(requestId) ? null : current);
    }

    public boolean isInFlight(UUID serviceId) {
        return pending.containsKey(serviceId);
    }

    public int size() {
        return pending.size();
    }

    @Scheduled(fixedDelay = 5000)
    public void removeExpired() {
        Instant now = clock.instant();
        pending.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }
}
