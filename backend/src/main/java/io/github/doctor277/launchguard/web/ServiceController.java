package io.github.doctor277.launchguard.web;

import io.github.doctor277.launchguard.dto.CreateServiceRequest;
import io.github.doctor277.launchguard.dto.HealthCheckResponse;
import io.github.doctor277.launchguard.dto.ServiceResponse;
import io.github.doctor277.launchguard.service.HealthCheckService;
import io.github.doctor277.launchguard.service.ServiceManager;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceManager serviceManager;
    private final HealthCheckService healthCheckService;

    public ServiceController(ServiceManager serviceManager, HealthCheckService healthCheckService) {
        this.serviceManager = serviceManager;
        this.healthCheckService = healthCheckService;
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
    public List<HealthCheckResponse> findChecks(@PathVariable UUID id) {
        return healthCheckService.findHistory(id);
    }
}
