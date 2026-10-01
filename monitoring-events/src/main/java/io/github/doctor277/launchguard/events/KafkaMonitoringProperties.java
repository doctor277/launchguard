package io.github.doctor277.launchguard.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("launchguard.kafka")
public record KafkaMonitoringProperties(
        @DefaultValue("launchguard.health-check.requests") String requestsTopic,
        @DefaultValue("launchguard.health-check.results") String resultsTopic,
        @DefaultValue("6") int partitions,
        @DefaultValue("1") short replicationFactor) {
    public KafkaMonitoringProperties {
        if (requestsTopic == null || requestsTopic.isBlank() || resultsTopic == null || resultsTopic.isBlank()
                || requestsTopic.equals(resultsTopic) || partitions < 1 || partitions > 64
                || replicationFactor < 1) {
            throw new IllegalArgumentException("Invalid monitoring topic configuration");
        }
    }
}
