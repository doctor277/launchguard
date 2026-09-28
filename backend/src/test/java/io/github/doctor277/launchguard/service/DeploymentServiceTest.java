package io.github.doctor277.launchguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;

import io.github.doctor277.launchguard.domain.Deployment;
import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.dto.CreateDeploymentRequest;
import io.github.doctor277.launchguard.repository.DeploymentMetricsAggregate;
import io.github.doctor277.launchguard.repository.DeploymentMetricsRepository;
import io.github.doctor277.launchguard.repository.DeploymentRepository;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class DeploymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Mock
    private MonitoredServiceRepository serviceRepository;

    @Mock
    private DeploymentRepository deploymentRepository;

    @Mock
    private DeploymentMetricsRepository metricsRepository;

    private MonitoredService service;
    private DeploymentService deploymentService;

    @BeforeEach
    void setUp() {
        service = MonitoredService.register("payment-service", "http://localhost:8081", "/health");
        deploymentService = new DeploymentService(serviceRepository, deploymentRepository, metricsRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void firstDeploymentBecomesCurrent() {
        when(serviceRepository.findById(service.getId())).thenReturn(Optional.of(service));
        AtomicReference<Deployment> managed = new AtomicReference<>();
        when(deploymentRepository.save(any(Deployment.class)))
                .thenAnswer(invocation -> {
                    Deployment persisted = spy(invocation.getArgument(0, Deployment.class));
                    managed.set(persisted);
                    return persisted;
                });

        var response = deploymentService.create(service.getId(),
                new CreateDeploymentRequest(" v1.0.0 ", "a921fc7", "First release"));

        assertThat(response.version()).isEqualTo("v1.0.0");
        assertThat(response.commitSha()).isEqualTo("a921fc7");
        assertThat(response.deployedAt()).isEqualTo(NOW);
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.current()).isTrue();
        assertThat(service.getCurrentDeployment().getId()).isEqualTo(response.id());
        assertThat(service.getCurrentDeployment()).isSameAs(managed.get());
        verify(deploymentRepository).save(any(Deployment.class));
        verify(serviceRepository).save(service);
    }

    @Test
    void secondDeploymentReplacesCurrentWithoutChangingFirst() {
        when(serviceRepository.findById(service.getId())).thenReturn(Optional.of(service));
        when(deploymentRepository.save(any(Deployment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        deploymentService.create(service.getId(), new CreateDeploymentRequest("v1.0.0", null, null));
        Deployment first = service.getCurrentDeployment();

        var second = deploymentService.create(service.getId(),
                new CreateDeploymentRequest("v1.1.0", "a921fc7", null));

        assertThat(second.current()).isTrue();
        assertThat(service.getCurrentDeployment().getId()).isEqualTo(second.id());
        assertThat(first.getId()).isNotEqualTo(second.id());
        assertThat(first.getVersion()).isEqualTo("v1.0.0");
        assertThat(first.getService()).isSameAs(service);
    }

    @Test
    void reportsAllHealthyDeploymentMetrics() {
        Deployment deployment = currentDeployment();
        when(metricsRepository.summarize(deployment.getId())).thenReturn(
                aggregate(3, 3, 0, "50", 20L, 90L, null, NOW));

        var metrics = deploymentService.getMetrics(service.getId(), deployment.getId());

        assertThat(metrics.current()).isTrue();
        assertThat(metrics.availabilityPercentage()).isEqualByComparingTo("100.00");
        assertThat(metrics.averageResponseTimeMs()).isEqualByComparingTo("50");
        assertThat(metrics.firstFailureAt()).isNull();
        assertThat(metrics.lastCheckedAt()).isEqualTo(NOW);
    }

    @Test
    void reportsFailuresAndFirstFailureForDeployment() {
        Deployment deployment = currentDeployment();
        Instant firstFailure = NOW.minusSeconds(60);
        when(metricsRepository.summarize(deployment.getId())).thenReturn(
                aggregate(5, 3, 2, "143.75", 31L, 920L, firstFailure, NOW));

        var metrics = deploymentService.getMetrics(service.getId(), deployment.getId());

        assertThat(metrics.totalChecks()).isEqualTo(5);
        assertThat(metrics.failedChecks()).isEqualTo(2);
        assertThat(metrics.availabilityPercentage()).isEqualByComparingTo("60.00");
        assertThat(metrics.averageResponseTimeMs()).isEqualByComparingTo("143.75");
        assertThat(metrics.minResponseTimeMs()).isEqualTo(31);
        assertThat(metrics.maxResponseTimeMs()).isEqualTo(920);
        assertThat(metrics.firstFailureAt()).isEqualTo(firstFailure);
    }

    @Test
    void reportsDefinedValuesForZeroChecks() {
        Deployment deployment = currentDeployment();
        when(metricsRepository.summarize(deployment.getId())).thenReturn(
                aggregate(0, 0, 0, null, null, null, null, null));

        var metrics = deploymentService.getMetrics(service.getId(), deployment.getId());

        assertThat(metrics.availabilityPercentage()).isEqualByComparingTo("0.00");
        assertThat(metrics.averageResponseTimeMs()).isNull();
        assertThat(metrics.minResponseTimeMs()).isNull();
        assertThat(metrics.maxResponseTimeMs()).isNull();
        assertThat(metrics.firstFailureAt()).isNull();
        assertThat(metrics.lastCheckedAt()).isNull();
    }

    @Test
    void returnsPaginatedDeploymentsNewestFirst() {
        when(serviceRepository.findById(service.getId())).thenReturn(Optional.of(service));
        Deployment deployment = Deployment.register(service, "v1.0.0", null, null, NOW);
        PageRequest expected = PageRequest.of(1, 2,
                Sort.by(Sort.Order.desc("deployedAt"), Sort.Order.desc("id")));
        when(deploymentRepository.findAllByServiceId(service.getId(), expected))
                .thenReturn(new PageImpl<>(List.of(deployment), expected, 5));

        var page = deploymentService.findAll(service.getId(), 1, 2);

        assertThat(page.content()).singleElement().satisfies(item ->
                assertThat(item.version()).isEqualTo("v1.0.0"));
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.first()).isFalse();
    }

    @Test
    void rejectsOversizedPage() {
        assertThatThrownBy(() -> deploymentService.findAll(service.getId(), 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be between 1 and 100");
        verifyNoInteractions(deploymentRepository);
    }

    @Test
    void rejectsMissingService() {
        when(serviceRepository.findById(service.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deploymentService.create(service.getId(),
                new CreateDeploymentRequest("v1.0.0", null, null)))
                .isInstanceOf(ServiceNotFoundException.class);
        verifyNoInteractions(deploymentRepository);
    }

    @Test
    void rejectsMissingDeployment() {
        UUID deploymentId = UUID.randomUUID();
        when(serviceRepository.findById(service.getId())).thenReturn(Optional.of(service));
        when(deploymentRepository.findByIdAndServiceId(deploymentId, service.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> deploymentService.findById(service.getId(), deploymentId))
                .isInstanceOf(DeploymentNotFoundException.class);
    }

    @Test
    void rejectsDeploymentBelongingToAnotherService() {
        MonitoredService other = MonitoredService.register("other", "http://localhost:8082", "/health");
        Deployment otherDeployment = Deployment.register(other, "v2.0.0", null, null, NOW);
        when(serviceRepository.findById(service.getId())).thenReturn(Optional.of(service));
        when(deploymentRepository.findByIdAndServiceId(otherDeployment.getId(), service.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> deploymentService.getMetrics(service.getId(), otherDeployment.getId()))
                .isInstanceOf(DeploymentNotFoundException.class);
        verifyNoInteractions(metricsRepository);
    }

    private Deployment currentDeployment() {
        Deployment deployment = Deployment.register(service, "v1.0.0", "a921fc7", null, NOW);
        service.setCurrentDeployment(deployment);
        when(serviceRepository.findById(service.getId())).thenReturn(Optional.of(service));
        when(deploymentRepository.findByIdAndServiceId(deployment.getId(), service.getId()))
                .thenReturn(Optional.of(deployment));
        return deployment;
    }

    private static DeploymentMetricsAggregate aggregate(long total, long healthy, long failed, String average,
                                                        Long minimum, Long maximum, Instant firstFailure,
                                                        Instant lastChecked) {
        return new DeploymentMetricsAggregate(total, healthy, failed,
                average == null ? null : new BigDecimal(average),
                minimum, maximum, firstFailure, lastChecked);
    }
}
