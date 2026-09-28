package io.github.doctor277.launchguard.messaging;

public class ProbeDispatchException extends RuntimeException {
    public ProbeDispatchException() { super("Health probe request could not be queued"); }
}
