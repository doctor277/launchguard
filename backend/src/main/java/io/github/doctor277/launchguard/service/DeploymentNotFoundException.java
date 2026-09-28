package io.github.doctor277.launchguard.service;

import java.util.UUID;

public class DeploymentNotFoundException extends RuntimeException {

    public DeploymentNotFoundException(UUID serviceId, UUID deploymentId) {
        super("Deployment not found for service " + serviceId + ": " + deploymentId);
    }
}
