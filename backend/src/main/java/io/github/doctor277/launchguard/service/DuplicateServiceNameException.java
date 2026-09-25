package io.github.doctor277.launchguard.service;

public class DuplicateServiceNameException extends RuntimeException {

    public DuplicateServiceNameException(String name) {
        super("A monitored service named '" + name + "' already exists");
    }
}
