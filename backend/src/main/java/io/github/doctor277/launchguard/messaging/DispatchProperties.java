package io.github.doctor277.launchguard.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("launchguard.dispatch")
public record DispatchProperties(@DefaultValue("120s") Duration inFlightTtl) {
    public DispatchProperties {
        if (inFlightTtl == null || inFlightTtl.compareTo(Duration.ofSeconds(10)) < 0
                || inFlightTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("in-flight-ttl must be 10s..1h");
        }
    }
}
