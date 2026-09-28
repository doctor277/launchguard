package io.github.doctor277.launchguard.web;

import io.github.doctor277.launchguard.dto.CreateDeploymentRequest;
import io.github.doctor277.launchguard.dto.DeploymentMetricsResponse;
import io.github.doctor277.launchguard.dto.DeploymentResponse;
import io.github.doctor277.launchguard.dto.PageResponse;
import io.github.doctor277.launchguard.service.DeploymentService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services/{serviceId}/deployments")
public class DeploymentController {

    private final DeploymentService deploymentService;

    public DeploymentController(DeploymentService deploymentService) {
        this.deploymentService = deploymentService;
    }

    @PostMapping
    public ResponseEntity<DeploymentResponse> create(@PathVariable UUID serviceId,
                                                      @Valid @RequestBody CreateDeploymentRequest request) {
        DeploymentResponse created = deploymentService.create(serviceId, request);
        URI location = URI.create("/api/services/" + serviceId + "/deployments/" + created.id());
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public PageResponse<DeploymentResponse> findAll(
            @PathVariable UUID serviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return deploymentService.findAll(serviceId, page, size);
    }

    @GetMapping("/{deploymentId}")
    public DeploymentResponse findById(@PathVariable UUID serviceId, @PathVariable UUID deploymentId) {
        return deploymentService.findById(serviceId, deploymentId);
    }

    @GetMapping("/{deploymentId}/metrics")
    public DeploymentMetricsResponse metrics(@PathVariable UUID serviceId, @PathVariable UUID deploymentId) {
        return deploymentService.getMetrics(serviceId, deploymentId);
    }
}
