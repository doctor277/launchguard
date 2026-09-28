package io.github.doctor277.launchguard.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.doctor277.launchguard.domain.IncidentStatus;
import io.github.doctor277.launchguard.dto.IncidentResponse;
import io.github.doctor277.launchguard.dto.IncidentMetricsResponse;
import io.github.doctor277.launchguard.dto.PageResponse;
import io.github.doctor277.launchguard.service.IncidentService;
import io.github.doctor277.launchguard.service.IncidentNotFoundException;
import io.github.doctor277.launchguard.service.MetricsWindow;
import io.github.doctor277.launchguard.service.ServiceNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class IncidentControllerTest {

    @Mock private IncidentService incidentService;
    private MockMvc mockMvc;
    private UUID serviceId;

    @BeforeEach
    void setUp() {
        serviceId = UUID.randomUUID();
        mockMvc = MockMvcBuilders.standaloneSetup(new IncidentController(incidentService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void currentReturnsOpenIncidentOr204WithoutBody() throws Exception {
        IncidentResponse incident = new IncidentResponse(UUID.randomUUID(), serviceId, "payment", IncidentStatus.OPEN,
                null, "3 consecutive failed health checks", Instant.parse("2026-09-28T12:00:00Z"), null, null);
        when(incidentService.current(serviceId)).thenReturn(Optional.of(incident)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/services/{id}/incidents/current", serviceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(get("/api/services/{id}/incidents/current", serviceId))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
    }

    @Test
    void passesPaginationAndFilterAndReturnsOwnedMetadata() throws Exception {
        when(incidentService.findAll(serviceId, 1, 5, "RESOLVED"))
                .thenReturn(new PageResponse<>(List.of(), 1, 5, 0, 0, false, true));

        mockMvc.perform(get("/api/services/{id}/incidents", serviceId)
                        .param("page", "1").param("size", "5").param("status", "RESOLVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5)).andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void metricsDefaultsTo30DaysAndRejectsInvalidWindow() throws Exception {
        when(incidentService.metrics(serviceId, MetricsWindow.THIRTY_DAYS))
                .thenReturn(new IncidentMetricsResponse(serviceId, "30d", 0, 0, 0, null, null));

        mockMvc.perform(get("/api/services/{id}/incident-metrics", serviceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.window").value("30d"));
        mockMvc.perform(get("/api/services/{id}/incident-metrics", serviceId).param("window", "yesterday"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void returnsStructured404ForMissingServiceOrWrongServiceIncident() throws Exception {
        UUID incidentId = UUID.randomUUID();
        when(incidentService.current(serviceId)).thenThrow(new ServiceNotFoundException(serviceId));
        when(incidentService.findById(serviceId, incidentId)).thenThrow(new IncidentNotFoundException(serviceId, incidentId));

        mockMvc.perform(get("/api/services/{id}/incidents/current", serviceId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(get("/api/services/{id}/incidents/{incidentId}", serviceId, incidentId))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.path").exists());
    }
}
