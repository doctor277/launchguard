package io.github.doctor277.launchguard.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("launchguard.monitoring")
public record MonitoringProperties(
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration responseTimeout) {
}
