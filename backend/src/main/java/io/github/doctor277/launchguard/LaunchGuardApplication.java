package io.github.doctor277.launchguard;

import io.github.doctor277.launchguard.config.MonitoringProperties;
import io.github.doctor277.launchguard.config.IncidentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Import;
import io.github.doctor277.launchguard.events.MonitoringKafkaConfiguration;
import io.github.doctor277.launchguard.messaging.DispatchProperties;

@EnableScheduling
@EnableConfigurationProperties({MonitoringProperties.class, IncidentProperties.class, DispatchProperties.class})
@Import(MonitoringKafkaConfiguration.class)
@SpringBootApplication
public class LaunchGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(LaunchGuardApplication.class, args);
    }
}
