package io.github.doctor277.launchguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import io.github.doctor277.launchguard.config.IncidentProperties;
import io.github.doctor277.launchguard.domain.Deployment;
import io.github.doctor277.launchguard.domain.HealthCheck;
import io.github.doctor277.launchguard.domain.Incident;
import io.github.doctor277.launchguard.domain.IncidentStatus;
import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import io.github.doctor277.launchguard.repository.DeploymentRepository;
import io.github.doctor277.launchguard.repository.IncidentEvaluationRepository;
import io.github.doctor277.launchguard.repository.IncidentEvaluationRepository.DeploymentAtEvaluation;
import io.github.doctor277.launchguard.repository.IncidentEvaluationRepository.RecentCheck;
import io.github.doctor277.launchguard.repository.IncidentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IncidentEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    @Mock private IncidentRepository incidentRepository;
    @Mock private IncidentEvaluationRepository evaluationRepository;
    @Mock private DeploymentRepository deploymentRepository;

    private IncidentProperties properties;
    private IncidentEvaluator evaluator;
    private MonitoredService service;

    @BeforeEach
    void setUp() {
        service = MonitoredService.register("payment", "http://localhost:8081", "/health");
        properties = new IncidentProperties(3, 2);
        evaluator = new IncidentEvaluator(incidentRepository, evaluationRepository, deploymentRepository, properties);
    }

    static Stream<List<ServiceStatus>> belowFailureThreshold() {
        return Stream.of(List.of(ServiceStatus.DOWN), List.of(ServiceStatus.DOWN, ServiceStatus.DOWN),
                List.of(ServiceStatus.DOWN, ServiceStatus.HEALTHY, ServiceStatus.DOWN),
                List.of(ServiceStatus.HEALTHY, ServiceStatus.DOWN, ServiceStatus.DOWN));
    }

    @ParameterizedTest
    @MethodSource("belowFailureThreshold")
    void shortOrInterruptedFailureStreakDoesNotOpenIncident(List<ServiceStatus> statuses) {
        HealthCheck check = prepare(null, statuses);

        evaluator.evaluate(check);

        verify(incidentRepository, never()).save(any());
    }

    @Test
    void exactThresholdOpensIncidentWithoutDeployment() {
        HealthCheck check = prepare(null, List.of(ServiceStatus.DOWN, ServiceStatus.DOWN, ServiceStatus.DOWN));

        evaluator.evaluate(check);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(captor.getValue().getDeployment()).isNull();
        assertThat(captor.getValue().getStartedAt()).isEqualTo(NOW);
        assertThat(captor.getValue().getTriggerReason()).isEqualTo("3 consecutive failed health checks");
    }

    @Test
    void capturesDeploymentCurrentAtOpeningRatherThanProbeStart() {
        Deployment previous = Deployment.register(service, "v1.0.0", null, null, NOW.minusSeconds(60));
        Deployment active = Deployment.register(service, "v1.1.0", "a921fc7", null, NOW);
        HealthCheck check = HealthCheck.record(service, previous, ServiceStatus.DOWN, 500, 20, null, NOW);
        prepareHistory(check, null, List.of(ServiceStatus.DOWN, ServiceStatus.DOWN, ServiceStatus.DOWN));
        when(evaluationRepository.lockService(service.getId())).thenReturn(new DeploymentAtEvaluation(active.getId()));
        when(deploymentRepository.getReferenceById(active.getId())).thenReturn(active);

        evaluator.evaluate(check);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getDeployment()).isSameAs(active);
        assertThat(check.getDeployment()).isSameAs(previous);
    }

    @Test
    void furtherFailuresDoNotDuplicateOrReassociateOpenIncident() {
        Deployment original = Deployment.register(service, "v1.0.0", null, null, NOW.minusSeconds(60));
        Incident incident = Incident.open(service, original, "3 consecutive failed health checks", NOW.minusSeconds(10));
        HealthCheck check = prepare(incident, List.of(ServiceStatus.DOWN, ServiceStatus.DOWN));
        when(evaluationRepository.lockService(service.getId())).thenReturn(new DeploymentAtEvaluation(UUID.randomUUID()));

        evaluator.evaluate(check);

        verify(incidentRepository, never()).save(any());
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getDeployment()).isSameAs(original);
    }

    static Stream<List<ServiceStatus>> interruptedRecovery() {
        return Stream.of(List.of(ServiceStatus.HEALTHY, ServiceStatus.DOWN),
                List.of(ServiceStatus.DOWN, ServiceStatus.HEALTHY));
    }

    @ParameterizedTest
    @MethodSource("interruptedRecovery")
    void oneHealthyCheckOrFailureDuringRecoveryKeepsIncidentOpen(List<ServiceStatus> statuses) {
        Incident incident = Incident.open(service, null, "outage", NOW.minusSeconds(10));
        HealthCheck check = prepare(incident, statuses);

        evaluator.evaluate(check);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        verify(incidentRepository, never()).save(any());
    }

    @Test
    void exactRecoveryThresholdResolvesAndPreservesHistory() {
        Incident incident = Incident.open(service, null, "outage", NOW.minusSeconds(10));
        HealthCheck check = prepare(incident, List.of(ServiceStatus.HEALTHY, ServiceStatus.HEALTHY));

        evaluator.evaluate(check);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.getResolvedAt()).isEqualTo(NOW);
        assertThat(incident.getUpdatedAt()).isEqualTo(NOW);
        verify(incidentRepository).save(incident);
        assertThatThrownBy(() -> incident.resolve(NOW.plusSeconds(10))).isInstanceOf(IllegalStateException.class);
        assertThat(incident.getResolvedAt()).isEqualTo(NOW);
    }

    @Test
    void configuredThresholdsChangeDetectionBehavior() {
        properties = new IncidentProperties(2, 1);
        evaluator = new IncidentEvaluator(incidentRepository, evaluationRepository, deploymentRepository, properties);
        HealthCheck check = prepare(null, List.of(ServiceStatus.DOWN, ServiceStatus.DOWN));

        evaluator.evaluate(check);

        verify(evaluationRepository).recentChecks(service.getId(), 2);
        verify(incidentRepository).save(any(Incident.class));
    }

    @Test
    void evaluatesAuthoritativeRecentHistoryRatherThanStaleProbeTimestamp() {
        HealthCheck check = prepare(null, List.of(ServiceStatus.DOWN, ServiceStatus.DOWN, ServiceStatus.DOWN));
        when(evaluationRepository.recentChecks(service.getId(), 3)).thenReturn(List.of(
                new RecentCheck(UUID.randomUUID(), ServiceStatus.DOWN, NOW.plusSeconds(1)),
                new RecentCheck(check.getId(), ServiceStatus.DOWN, NOW),
                new RecentCheck(UUID.randomUUID(), ServiceStatus.DOWN, NOW.minusSeconds(1))));

        evaluator.evaluate(check);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getStartedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void recoveryThresholdCanBeConfiguredToOne() {
        properties = new IncidentProperties(3, 1);
        evaluator = new IncidentEvaluator(incidentRepository, evaluationRepository, deploymentRepository, properties);
        Incident incident = Incident.open(service, null, "outage", NOW.minusSeconds(10));
        HealthCheck check = prepare(incident, List.of(ServiceStatus.HEALTHY));

        evaluator.evaluate(check);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        verify(evaluationRepository).recentChecks(service.getId(), 1);
    }

    @Test
    void configurationRejectsNonPositiveOrUnboundedThresholds() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new IncidentProperties(3, 2))).isEmpty();
            assertThat(validator.validate(new IncidentProperties(0, 2))).isNotEmpty();
            assertThat(validator.validate(new IncidentProperties(3, 0))).isNotEmpty();
            assertThat(validator.validate(new IncidentProperties(1001, 2))).isNotEmpty();
            assertThat(validator.validate(new IncidentProperties(3, 1001))).isNotEmpty();
        }
    }

    private HealthCheck prepare(Incident incident, List<ServiceStatus> statuses) {
        HealthCheck check = HealthCheck.record(service, statuses.getFirst(), 500, 20, null, NOW);
        prepareHistory(check, incident, statuses);
        return check;
    }

    private void prepareHistory(HealthCheck check, Incident incident, List<ServiceStatus> statuses) {
        when(evaluationRepository.lockService(service.getId())).thenReturn(new DeploymentAtEvaluation(null));
        when(incidentRepository.findByServiceIdAndStatus(service.getId(), IncidentStatus.OPEN))
                .thenReturn(Optional.ofNullable(incident));
        int limit = incident == null ? properties.failureThreshold() : properties.recoveryThreshold();
        List<RecentCheck> recent = new ArrayList<>();
        for (int i = 0; i < statuses.size(); i++) {
            recent.add(new RecentCheck(i == 0 ? check.getId() : UUID.randomUUID(), statuses.get(i), NOW.minusSeconds(i)));
        }
        when(evaluationRepository.recentChecks(service.getId(), limit)).thenReturn(recent);
    }
}
