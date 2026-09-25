package io.github.doctor277.launchguard.service;

import java.util.UUID;

public class CheckAlreadyInProgressException extends RuntimeException {

    public CheckAlreadyInProgressException(UUID serviceId) {
        super("A health check is already in progress for service " + serviceId);
    }
}
