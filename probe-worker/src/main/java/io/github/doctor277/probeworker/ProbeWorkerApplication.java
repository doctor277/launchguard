package io.github.doctor277.probeworker;

import io.github.doctor277.launchguard.events.MonitoringKafkaConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@Import(MonitoringKafkaConfiguration.class)
@EnableConfigurationProperties(WorkerProperties.class)
public class ProbeWorkerApplication {
    public static void main(String[] args) { SpringApplication.run(ProbeWorkerApplication.class, args); }
}
