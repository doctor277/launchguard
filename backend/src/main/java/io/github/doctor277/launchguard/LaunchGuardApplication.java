package io.github.doctor277.launchguard;

import io.github.doctor277.launchguard.config.MonitoringProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties(MonitoringProperties.class)
@SpringBootApplication
public class LaunchGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(LaunchGuardApplication.class, args);
    }
}
