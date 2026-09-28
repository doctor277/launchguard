package io.github.doctor277.launchguard.web;

import io.github.doctor277.launchguard.dto.IncidentMetricsResponse;
import io.github.doctor277.launchguard.dto.IncidentResponse;
import io.github.doctor277.launchguard.dto.PageResponse;
import io.github.doctor277.launchguard.service.IncidentService;
import io.github.doctor277.launchguard.service.MetricsWindow;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services/{serviceId}")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping("/incidents")
    public PageResponse<IncidentResponse> findAll(@PathVariable UUID serviceId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return incidentService.findAll(serviceId, page, size, status);
    }

    @GetMapping("/incidents/current")
    public ResponseEntity<IncidentResponse> current(@PathVariable UUID serviceId) {
        return incidentService.current(serviceId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/incidents/{incidentId}")
    public IncidentResponse findById(@PathVariable UUID serviceId, @PathVariable UUID incidentId) {
        return incidentService.findById(serviceId, incidentId);
    }

    @GetMapping("/incident-metrics")
    public IncidentMetricsResponse metrics(@PathVariable UUID serviceId,
            @RequestParam(defaultValue = "30d") String window) {
        return incidentService.metrics(serviceId, MetricsWindow.parse(window));
    }
}
