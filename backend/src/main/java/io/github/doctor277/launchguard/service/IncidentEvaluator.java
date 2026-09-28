package io.github.doctor277.launchguard.service;

import io.github.doctor277.launchguard.config.IncidentProperties;
import io.github.doctor277.launchguard.domain.Deployment;
import io.github.doctor277.launchguard.domain.HealthCheck;
import io.github.doctor277.launchguard.domain.Incident;
import io.github.doctor277.launchguard.domain.IncidentStatus;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import io.github.doctor277.launchguard.repository.DeploymentRepository;
import io.github.doctor277.launchguard.repository.IncidentEvaluationRepository;
import io.github.doctor277.launchguard.repository.IncidentRepository;
import io.github.doctor277.launchguard.observability.BackendTelemetry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentEvaluator {

    private static final Logger log = LoggerFactory.getLogger(IncidentEvaluator.class);
    private final IncidentRepository incidentRepository;
    private final IncidentEvaluationRepository evaluationRepository;
    private final DeploymentRepository deploymentRepository;
    private final IncidentProperties properties;
    private final BackendTelemetry telemetry;
    private final ObservationRegistry observations;

    public IncidentEvaluator(IncidentRepository incidentRepository, IncidentEvaluationRepository evaluationRepository,
                             DeploymentRepository deploymentRepository, IncidentProperties properties,
                             BackendTelemetry telemetry, ObservationRegistry observations) {
        this.incidentRepository = incidentRepository;
        this.evaluationRepository = evaluationRepository;
        this.deploymentRepository = deploymentRepository;
        this.properties = properties;
        this.telemetry = telemetry;
        this.observations = observations;
    }

    // Runs after the check has been flushed, in the same transaction; the row lock lives until commit.
    @Transactional(propagation = Propagation.MANDATORY)
    public void evaluate(HealthCheck check) {
        Observation.createNotStarted("launchguard.incident.evaluate", observations)
                .highCardinalityKeyValue("launchguard.service.id", check.getService().getId().toString())
                .observe(() -> evaluateIncident(check));
    }

    private void evaluateIncident(HealthCheck check) {
        var serviceId = check.getService().getId();
        var deploymentAtEvaluation = evaluationRepository.lockService(serviceId);
        var open = incidentRepository.findByServiceIdAndStatus(serviceId, IncidentStatus.OPEN);
        int threshold = open.isPresent() ? properties.recoveryThreshold() : properties.failureThreshold();
        ServiceStatus required = open.isPresent() ? ServiceStatus.HEALTHY : ServiceStatus.DOWN;
        var recent = evaluationRepository.recentChecks(serviceId, threshold);
        if (recent.size() != threshold || recent.stream().anyMatch(item -> item.status() != required)) {
            return;
        }
        // Persisted recent history is authoritative, including when evaluations arrive close together.
        if (open.isPresent()) {
            Incident incident = open.orElseThrow();
            if (recent.stream().anyMatch(item -> item.checkedAt().isBefore(incident.getStartedAt()))) {
                return;
            }
            incident.resolve(recent.getFirst().checkedAt());
            incidentRepository.save(incident);
            telemetry.incidentResolved();
            log.atInfo().addKeyValue("event", "incident_resolved").addKeyValue("serviceId", serviceId)
                    .addKeyValue("incidentId", incident.getId()).addKeyValue("status", "RESOLVED").log("incident_resolved");
        } else {
            Deployment deployment = deploymentAtEvaluation.deploymentId() == null ? null
                    : deploymentRepository.getReferenceById(deploymentAtEvaluation.deploymentId());
            Incident incident = Incident.open(check.getService(), deployment,
                    threshold + " consecutive failed health checks", recent.getFirst().checkedAt());
            incidentRepository.save(incident);
            telemetry.incidentOpened();
            log.atInfo().addKeyValue("event", "incident_opened").addKeyValue("serviceId", serviceId)
                    .addKeyValue("incidentId", incident.getId()).addKeyValue("deploymentId", deploymentAtEvaluation.deploymentId())
                    .addKeyValue("status", "OPEN").log("incident_opened");
        }
    }
}
