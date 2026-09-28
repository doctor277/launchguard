package io.github.doctor277.launchguard.messaging;

import io.github.doctor277.launchguard.domain.HealthCheck;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import io.github.doctor277.launchguard.events.HealthCheckCompleted;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import io.github.doctor277.launchguard.repository.HealthCheckRepository;
import io.github.doctor277.launchguard.repository.DeploymentRepository;
import io.github.doctor277.launchguard.service.IncidentEvaluator;
import io.github.doctor277.launchguard.observability.BackendTelemetry;
import io.github.doctor277.launchguard.events.ProbeObservations;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ProbeResultPersistence {
    private static final Logger log = LoggerFactory.getLogger(ProbeResultPersistence.class);
    private final MonitoredServiceRepository services;
    private final HealthCheckRepository checks;
    private final DeploymentRepository deployments;
    private final IncidentEvaluator incidents;
    private final JdbcClient jdbc;
    private final BackendTelemetry telemetry;
    private final ObservationRegistry observations;

    public ProbeResultPersistence(MonitoredServiceRepository services, HealthCheckRepository checks,
                                 DeploymentRepository deployments, IncidentEvaluator incidents, JdbcClient jdbc,
                                 BackendTelemetry telemetry, ObservationRegistry observations) {
        this.services = services;
        this.checks = checks;
        this.deployments = deployments;
        this.incidents = incidents;
        this.jdbc = jdbc;
        this.telemetry = telemetry;
        this.observations = observations;
    }

    @Transactional
    public void persist(HealthCheckCompleted event) {
        Observation observation = ProbeObservations.start("launchguard.result.persistence", observations,
                event.requestId(), event.serviceId());
        try (Observation.Scope scope = observation.openScope()) {
            persistResult(event);
        } catch (RuntimeException exception) {
            observation.error(exception);
            throw exception;
        } finally { observation.stop(); }
    }

    private void persistResult(HealthCheckCompleted event) {
        // NO KEY UPDATE remains compatible with the parent-key locks acquired by check inserts.
        var locked = jdbc.sql("SELECT id FROM monitored_services WHERE id=:id FOR NO KEY UPDATE")
                .param("id", event.serviceId()).query(UUID.class).optional();
        if (locked.isEmpty()) {
            telemetry.resultIgnored("service_deleted");
            log.info("health_result_service_deleted requestId={} serviceId={}", event.requestId(), event.serviceId());
            return;
        }
        if (checks.existsByProbeRequestId(event.requestId())) {
            telemetry.resultIgnored("duplicate");
            log.info("health_result_duplicate requestId={} serviceId={}", event.requestId(), event.serviceId());
            return;
        }
        var service = services.findById(event.serviceId()).orElseThrow();
        var deployment = event.deploymentId() == null ? null : deployments.findById(event.deploymentId())
                .orElseThrow(() -> new IllegalArgumentException("Captured deployment no longer exists"));
        var status = ServiceStatus.valueOf(event.status().name());
        HealthCheck check = HealthCheck.recordProbe(service, deployment, status, event.httpStatus(),
                event.responseTimeMs(), event.errorMessage(), event.checkedAt(), event.requestId());
        checks.saveAndFlush(check);
        if (service.getLastCheckedAt() == null || !event.checkedAt().isBefore(service.getLastCheckedAt())) {
            service.recordStatus(status, event.checkedAt());
            services.saveAndFlush(service);
            incidents.evaluate(check);
        } else {
            log.info("health_result_historical requestId={} serviceId={}", event.requestId(), event.serviceId());
        }
        telemetry.resultPersisted(status);
        log.atInfo().addKeyValue("event", "health_result_persisted")
                .addKeyValue("requestId", event.requestId()).addKeyValue("serviceId", event.serviceId())
                .addKeyValue("deploymentId", event.deploymentId()).addKeyValue("status", event.status())
                .addKeyValue("latencyMs", event.responseTimeMs()).log("health_result_persisted");
    }
}
