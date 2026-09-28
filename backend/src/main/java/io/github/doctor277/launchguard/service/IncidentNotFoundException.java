package io.github.doctor277.launchguard.service;

import java.util.UUID;

public class IncidentNotFoundException extends RuntimeException {
    public IncidentNotFoundException(UUID serviceId, UUID incidentId) {
        super("Incident not found for service " + serviceId + ": " + incidentId);
    }
}
