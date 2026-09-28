package io.github.doctor277.launchguard.observability;

import io.github.doctor277.launchguard.domain.IncidentStatus;
import io.github.doctor277.launchguard.messaging.InFlightProbeRegistry;
import io.github.doctor277.launchguard.repository.IncidentRepository;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WorkloadMetricsConfiguration {
    @Bean
    InitializingBean workloadMetrics(MeterRegistry registry, InFlightProbeRegistry inFlight,
                                    MonitoredServiceRepository services, IncidentRepository incidents) {
        return () -> {
            registry.gauge("launchguard.probe.inflight", inFlight, InFlightProbeRegistry::size);
            registry.gauge("launchguard.monitored.services", services, MonitoredServiceRepository::count);
            registry.gauge("launchguard.current.open.incidents", incidents,
                    repository -> repository.countByStatus(IncidentStatus.OPEN));
        };
    }
}
