package io.github.doctor277.launchguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.doctor277.launchguard.domain.HealthCheck;
import io.github.doctor277.launchguard.domain.Deployment;
import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import io.github.doctor277.launchguard.repository.HealthCheckRepository;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class HealthCheckServiceTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-09-24T12:00:00Z");

    @Mock
    private MonitoredServiceRepository serviceRepository;

    @Mock
    private HealthCheckRepository healthCheckRepository;

    @Mock
    private HealthProbe healthProbe;

    @Mock
    private IncidentEvaluator incidentEvaluator;

    private HealthCheckService healthCheckService;
    private MonitoredService service;

    @BeforeEach
    void setUp() {
        service = MonitoredService.register("payment-service", "http://localhost:8081", "/health");
        healthCheckService = new HealthCheckService(serviceRepository, healthCheckRepository, healthProbe,
                Clock.fixed(CHECKED_AT, ZoneOffset.UTC), incidentEvaluator,
                new io.github.doctor277.launchguard.observability.BackendTelemetry(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @Test
    void transitionsUnknownToHealthyAndPersistsCheck() {
        prepareCheck();
        when(healthProbe.probe(service)).thenReturn(new ProbeResult(ServiceStatus.HEALTHY, 200, 12, null));

        var response = healthCheckService.check(service.getId());

        assertThat(service.getStatus()).isEqualTo(ServiceStatus.HEALTHY);
        assertThat(service.getLastCheckedAt()).isEqualTo(CHECKED_AT);
        assertThat(response.status()).isEqualTo(ServiceStatus.HEALTHY);
        assertThat(response.deploymentId()).isNull();
        assertThat(response.httpStatus()).isEqualTo(200);
        ArgumentCaptor<HealthCheck> captor = ArgumentCaptor.forClass(HealthCheck.class);
        verify(healthCheckRepository).save(captor.capture());
        assertThat(captor.getValue().getService()).isSameAs(service);
        assertThat(captor.getValue().getResponseTimeMs()).isEqualTo(12);
        verify(serviceRepository).save(service);
        var order = org.mockito.Mockito.inOrder(healthCheckRepository, incidentEvaluator);
        order.verify(healthCheckRepository).save(any(HealthCheck.class));
        order.verify(healthCheckRepository).flush();
        order.verify(incidentEvaluator).evaluate(captor.getValue());
    }

    @Test
    void transitionsHealthyToDown() {
        prepareCheck();
        service.recordStatus(ServiceStatus.HEALTHY, CHECKED_AT.minusSeconds(30));
        when(healthProbe.probe(service))
                .thenReturn(new ProbeResult(ServiceStatus.DOWN, 500, 8, "HTTP request returned status 500"));

        var response = healthCheckService.check(service.getId());

        assertThat(service.getStatus()).isEqualTo(ServiceStatus.DOWN);
        assertThat(response.status()).isEqualTo(ServiceStatus.DOWN);
        assertThat(response.errorMessage()).contains("500");
    }

    @Test
    void linksCheckToCurrentDeployment() {
        prepareCheck();
        Deployment deployment = Deployment.register(service, "v1.0.0", null, null, CHECKED_AT);
        service.setCurrentDeployment(deployment);
        when(healthProbe.probe(service)).thenReturn(new ProbeResult(ServiceStatus.HEALTHY, 200, 12, null));

        var response = healthCheckService.check(service.getId());

        ArgumentCaptor<HealthCheck> captor = ArgumentCaptor.forClass(HealthCheck.class);
        verify(healthCheckRepository).save(captor.capture());
        assertThat(captor.getValue().getDeployment()).isSameAs(deployment);
        assertThat(response.deploymentId()).isEqualTo(deployment.getId());
    }

    @Test
    void leavesEarlierCheckLinkedToOriginalDeploymentAfterReplacement() {
        prepareCheck();
        Deployment first = Deployment.register(service, "v1.0.0", null, null, CHECKED_AT.minusSeconds(60));
        service.setCurrentDeployment(first);
        when(healthProbe.probe(service)).thenReturn(new ProbeResult(ServiceStatus.HEALTHY, 200, 12, null));
        healthCheckService.check(service.getId());
        Deployment second = Deployment.register(service, "v1.1.0", null, null, CHECKED_AT);
        service.setCurrentDeployment(second);

        var secondResponse = healthCheckService.check(service.getId());

        ArgumentCaptor<HealthCheck> captor = ArgumentCaptor.forClass(HealthCheck.class);
        verify(healthCheckRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getDeployment()).isSameAs(first);
        assertThat(captor.getAllValues().get(1).getDeployment()).isSameAs(second);
        assertThat(secondResponse.deploymentId()).isEqualTo(second.getId());
    }

    @Test
    void returnsPaginatedHistoryNewestFirst() {
        HealthCheck check = HealthCheck.record(service, ServiceStatus.HEALTHY, 200, 14, null, CHECKED_AT);
        PageRequest expectedPage = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "checkedAt"));
        when(serviceRepository.existsById(service.getId())).thenReturn(true);
        when(healthCheckRepository.findAllByServiceId(service.getId(), expectedPage))
                .thenReturn(new PageImpl<>(List.of(check), expectedPage, 5));

        var response = healthCheckService.findHistory(service.getId(), 1, 2);

        assertThat(response.content()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(ServiceStatus.HEALTHY);
            assertThat(item.responseTimeMs()).isEqualTo(14);
        });
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.first()).isFalse();
        assertThat(response.last()).isFalse();
    }

    @Test
    void rejectsPageSizeAboveMaximum() {
        assertThatThrownBy(() -> healthCheckService.findHistory(service.getId(), 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be between 1 and 100");

        verifyNoInteractions(healthCheckRepository);
    }

    private void prepareCheck() {
        when(serviceRepository.findById(service.getId())).thenReturn(Optional.of(service));
        when(healthCheckRepository.save(any(HealthCheck.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }
}
