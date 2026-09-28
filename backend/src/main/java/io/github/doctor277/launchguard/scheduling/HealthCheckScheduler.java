package io.github.doctor277.launchguard.scheduling;

import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import io.github.doctor277.launchguard.service.CheckAlreadyInProgressException;
import io.github.doctor277.launchguard.messaging.ProbeDispatcher;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HealthCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckScheduler.class);

    private final MonitoredServiceRepository serviceRepository;
    private final ProbeDispatcher dispatcher;

    public HealthCheckScheduler(MonitoredServiceRepository serviceRepository,
                                ProbeDispatcher dispatcher) {
        this.serviceRepository = serviceRepository;
        this.dispatcher = dispatcher;
    }

    @Scheduled(
            fixedDelayString = "${launchguard.monitoring.interval:30s}",
            initialDelayString = "${launchguard.monitoring.initial-delay:30s}")
    public void checkRegisteredServices() {
        serviceRepository.findAll().forEach(service -> checkSafely(service.getId()));
    }

    private void checkSafely(UUID serviceId) {
        try {
            dispatcher.dispatch(serviceId);
        } catch (CheckAlreadyInProgressException exception) {
            log.debug("scheduled_health_check_skipped service_id={} reason=already_in_progress", serviceId);
        } catch (RuntimeException exception) {
            log.error("scheduled_health_check_failed service_id={}", serviceId, exception);
        }
    }
}
