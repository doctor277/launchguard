package io.github.doctor277.launchguard.service;

public class DeploymentExternalIdConflictException extends RuntimeException {

    public DeploymentExternalIdConflictException() {
        super("externalId already identifies a deployment with different metadata for this service");
    }
}
