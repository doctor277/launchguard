package io.github.doctor277.launchguard.service;

import io.github.doctor277.launchguard.domain.MonitoredService;
import io.github.doctor277.launchguard.dto.CreateServiceRequest;
import io.github.doctor277.launchguard.dto.ServiceResponse;
import io.github.doctor277.launchguard.repository.MonitoredServiceRepository;
import io.github.doctor277.launchguard.repository.IncidentRepository;
import io.github.doctor277.launchguard.domain.IncidentStatus;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceManager {

    private final MonitoredServiceRepository serviceRepository;
    private final IncidentRepository incidentRepository;

    public ServiceManager(MonitoredServiceRepository serviceRepository, IncidentRepository incidentRepository) {
        this.serviceRepository = serviceRepository;
        this.incidentRepository = incidentRepository;
    }

    @Transactional
    public ServiceResponse create(CreateServiceRequest request) {
        String name = request.name().trim();
        if (serviceRepository.existsByNameIgnoreCase(name)) {
            throw new DuplicateServiceNameException(name);
        }

        String baseUrl = normalizeAndValidateBaseUrl(request.baseUrl());
        String healthPath = request.healthPath().trim();
        MonitoredService service = MonitoredService.register(name, baseUrl, healthPath);
        return ServiceResponse.from(serviceRepository.save(service));
    }

    @Transactional(readOnly = true)
    public List<ServiceResponse> findAll() {
        Set<UUID> openServiceIds = Set.copyOf(incidentRepository.findOpenServiceIds());
        return serviceRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(service -> ServiceResponse.from(service, openServiceIds.contains(service.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ServiceResponse findById(UUID id) {
        return ServiceResponse.from(getRequired(id),
                incidentRepository.existsByServiceIdAndStatus(id, IncidentStatus.OPEN));
    }

    @Transactional
    public void delete(UUID id) {
        MonitoredService service = getRequired(id);
        serviceRepository.delete(service);
    }

    @Transactional(readOnly = true)
    public MonitoredService getRequired(UUID id) {
        return serviceRepository.findById(id)
                .orElseThrow(() -> new ServiceNotFoundException(id));
    }

    private static String normalizeAndValidateBaseUrl(String value) {
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("baseUrl must be a valid absolute HTTP or HTTPS URL");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new IllegalArgumentException("baseUrl must be a valid absolute HTTP or HTTPS URL");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not contain a query string or fragment");
        }
        return normalized;
    }
}
