package io.github.doctor277.launchguard.service;

import io.github.doctor277.launchguard.domain.Deployment;
import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.dto.CreateDeploymentRequest;
import io.github.doctor277.launchguard.dto.DeploymentMetricsResponse;
import io.github.doctor277.launchguard.dto.DeploymentResponse;
import io.github.doctor277.launchguard.dto.PageResponse;
import io.github.doctor277.launchguard.repository.DeploymentMetricsAggregate;
import io.github.doctor277.launchguard.repository.DeploymentMetricsRepository;
import io.github.doctor277.launchguard.repository.DeploymentRepository;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);

    private final MonitoredServiceRepository serviceRepository;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentMetricsRepository metricsRepository;
    private final Clock clock;

    public DeploymentService(MonitoredServiceRepository serviceRepository,
                             DeploymentRepository deploymentRepository,
                             DeploymentMetricsRepository metricsRepository,
                             Clock clock) {
        this.serviceRepository = serviceRepository;
        this.deploymentRepository = deploymentRepository;
        this.metricsRepository = metricsRepository;
        this.clock = clock;
    }

    @Transactional
    public DeploymentResponse create(UUID serviceId, CreateDeploymentRequest request) {
        MonitoredService service = getRequiredService(serviceId);
        Deployment deployment = deploymentRepository.save(Deployment.register(service, request.version().trim(),
                request.commitSha(), request.description(), clock.instant()));
        service.setCurrentDeployment(deployment);
        serviceRepository.save(service);
        log.info("deployment_registered serviceId={} deploymentId={} version={}",
                serviceId, deployment.getId(), deployment.getVersion());
        return DeploymentResponse.from(deployment, service);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeploymentResponse> findAll(UUID serviceId, int page, int size) {
        PaginationPolicy.validate(page, size);
        MonitoredService service = getRequiredService(serviceId);
        PageRequest request = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("deployedAt"), Sort.Order.desc("id")));
        Page<DeploymentResponse> deployments = deploymentRepository.findAllByServiceId(serviceId, request)
                .map(deployment -> DeploymentResponse.from(deployment, service));
        return new PageResponse<>(deployments.getContent(), deployments.getNumber(), deployments.getSize(),
                deployments.getTotalElements(), deployments.getTotalPages(),
                deployments.isFirst(), deployments.isLast());
    }

    @Transactional(readOnly = true)
    public DeploymentResponse findById(UUID serviceId, UUID deploymentId) {
        MonitoredService service = getRequiredService(serviceId);
        return DeploymentResponse.from(getRequiredDeployment(serviceId, deploymentId), service);
    }

    @Transactional(readOnly = true)
    public DeploymentMetricsResponse getMetrics(UUID serviceId, UUID deploymentId) {
        MonitoredService service = getRequiredService(serviceId);
        Deployment deployment = getRequiredDeployment(serviceId, deploymentId);
        DeploymentMetricsAggregate aggregate = metricsRepository.summarize(deploymentId);
        Deployment current = service.getCurrentDeployment();
        return new DeploymentMetricsResponse(
                deploymentId, serviceId, deployment.getVersion(), deployment.getCommitSha(),
                current != null && current.getId().equals(deploymentId), deployment.getDeployedAt(),
                aggregate.totalChecks(), aggregate.healthyChecks(), aggregate.failedChecks(),
                MetricsMath.availability(aggregate.healthyChecks(), aggregate.totalChecks()),
                MetricsMath.roundedLatency(aggregate.averageResponseTimeMs()),
                aggregate.minResponseTimeMs(), aggregate.maxResponseTimeMs(),
                aggregate.firstFailureAt(), aggregate.lastCheckedAt());
    }

    private MonitoredService getRequiredService(UUID serviceId) {
        return serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));
    }

    private Deployment getRequiredDeployment(UUID serviceId, UUID deploymentId) {
        return deploymentRepository.findByIdAndServiceId(deploymentId, serviceId)
                .orElseThrow(() -> new DeploymentNotFoundException(serviceId, deploymentId));
    }
}
