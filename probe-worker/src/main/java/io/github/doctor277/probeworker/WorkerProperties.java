package io.github.doctor277.probeworker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("launchguard.worker")
public record WorkerProperties(@DefaultValue("4") int threads, @DefaultValue("64") int queueCapacity,
                               @DefaultValue("2s") Duration connectTimeout) {
    public WorkerProperties {
        if (threads < 1 || threads > 32 || queueCapacity < 1 || queueCapacity > 1024
                || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid worker concurrency or timeout configuration");
        }
    }
}
