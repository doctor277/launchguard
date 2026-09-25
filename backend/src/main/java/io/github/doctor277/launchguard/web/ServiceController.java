package io.github.doctor277.launchguard.web;

import io.github.doctor277.launchguard.dto.CreateServiceRequest;
import io.github.doctor277.launchguard.dto.HealthCheckResponse;
import io.github.doctor277.launchguard.dto.PageResponse;
import io.github.doctor277.launchguard.dto.ServiceMetricsResponse;
import io.github.doctor277.launchguard.dto.ServiceResponse;
import io.github.doctor277.launchguard.dto.TimelinePointResponse;
import io.github.doctor277.launchguard.service.HealthCheckService;
import io.github.doctor277.launchguard.service.MetricsWindow;
import io.github.doctor277.launchguard.service.ServiceManager;
import io.github.doctor277.launchguard.service.ServiceMetricsService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceManager serviceManager;
    private final HealthCheckService healthCheckService;
    private final ServiceMetricsService metricsService;

    public ServiceController(ServiceManager serviceManager,
                             HealthCheckService healthCheckService,
                             ServiceMetricsService metricsService) {
        this.serviceManager = serviceManager;
        this.healthCheckService = healthCheckService;
        this.metricsService = metricsService;
    }

    @PostMapping
    public ResponseEntity<ServiceResponse> create(@Valid @RequestBody CreateServiceRequest request) {
        ServiceResponse created = serviceManager.create(request);
        return ResponseEntity.created(URI.create("/api/services/" + created.id())).body(created);
    }

    @GetMapping
    public List<ServiceResponse> findAll() {
        return serviceManager.findAll();
    }

    @GetMapping("/{id}")
    public ServiceResponse findById(@PathVariable UUID id) {
        return serviceManager.findById(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        serviceManager.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/check")
    public HealthCheckResponse check(@PathVariable UUID id) {
        return healthCheckService.check(id);
    }

    @GetMapping("/{id}/checks")
    public PageResponse<HealthCheckResponse> findChecks(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return healthCheckService.findHistory(id, page, size);
    }

    @GetMapping("/{id}/metrics")
    public ServiceMetricsResponse metrics(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "24h") String window) {
        return metricsService.getMetrics(id, MetricsWindow.parse(window));
    }

    @GetMapping("/{id}/metrics/timeline")
    public List<TimelinePointResponse> timeline(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "24h") String window) {
        return metricsService.getTimeline(id, MetricsWindow.parse(window));
    }
}
