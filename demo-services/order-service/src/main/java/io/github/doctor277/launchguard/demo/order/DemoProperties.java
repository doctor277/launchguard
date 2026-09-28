package io.github.doctor277.launchguard.demo.order;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("demo")
public record DemoProperties(@DefaultValue("2000") int slowDelayMs) {

    public static final int MAX_DELAY_MS = 30000;

    public DemoProperties {
        if (slowDelayMs < 1 || slowDelayMs > MAX_DELAY_MS) {
            throw new IllegalArgumentException("demo.slow-delay-ms must be between 1 and 30000");
        }
    }
}
