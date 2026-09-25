package io.github.doctor277.launchguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.doctor277.launchguard.domain.HealthCheck;
import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.domain.ServiceStatus;
import io.github.doctor277.launchguard.repository.HealthCheckRepository;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HealthCheckServiceTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-09-24T12:00:00Z");

    @Mock
    private MonitoredServiceRepository serviceRepository;

    @Mock
    private HealthCheckRepository healthCheckRepository;

    @Mock
    private HealthProbe healthProbe;

    private HealthCheckService healthCheckService;
    private MonitoredService service;

    @BeforeEach
    void setUp() {
        service = MonitoredService.register("payment-service", "http://localhost:8081", "/health");
        when(serviceRepository.findById(service.getId())).thenReturn(Optional.of(service));
        when(healthCheckRepository.save(any(HealthCheck.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        healthCheckService = new HealthCheckService(serviceRepository, healthCheckRepository, healthProbe,
                Clock.fixed(CHECKED_AT, ZoneOffset.UTC));
    }

    @Test
    void transitionsUnknownToHealthyAndPersistsCheck() {
        when(healthProbe.probe(service)).thenReturn(new ProbeResult(ServiceStatus.HEALTHY, 200, 12, null));

        var response = healthCheckService.check(service.getId());

        assertThat(service.getStatus()).isEqualTo(ServiceStatus.HEALTHY);
        assertThat(service.getLastCheckedAt()).isEqualTo(CHECKED_AT);
        assertThat(response.status()).isEqualTo(ServiceStatus.HEALTHY);
        assertThat(response.httpStatus()).isEqualTo(200);
        ArgumentCaptor<HealthCheck> captor = ArgumentCaptor.forClass(HealthCheck.class);
        verify(healthCheckRepository).save(captor.capture());
        assertThat(captor.getValue().getService()).isSameAs(service);
        assertThat(captor.getValue().getResponseTimeMs()).isEqualTo(12);
        verify(serviceRepository).save(service);
    }

    @Test
    void transitionsHealthyToDown() {
        service.recordStatus(ServiceStatus.HEALTHY, CHECKED_AT.minusSeconds(30));
        when(healthProbe.probe(service))
                .thenReturn(new ProbeResult(ServiceStatus.DOWN, 500, 8, "HTTP request returned status 500"));

        var response = healthCheckService.check(service.getId());

        assertThat(service.getStatus()).isEqualTo(ServiceStatus.DOWN);
        assertThat(response.status()).isEqualTo(ServiceStatus.DOWN);
        assertThat(response.errorMessage()).contains("500");
    }
}
