package io.github.doctor277.launchguard.observability;

import io.github.doctor277.launchguard.domain.ServiceStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class BackendTelemetry {
    private final MeterRegistry registry;
    private final Counter dispatched;
    private final Counter dispatchFailures;
    private final Counter opened;
    private final Counter resolved;
    private final Map<ServiceStatus, Counter> results = new EnumMap<>(ServiceStatus.class);

    public BackendTelemetry(MeterRegistry registry) {
        this.registry = registry;
        dispatched = registry.counter("launchguard.probe.requests.dispatched");
        dispatchFailures = registry.counter("launchguard.probe.dispatch.failures");
        opened = registry.counter("launchguard.incidents.opened");
        resolved = registry.counter("launchguard.incidents.resolved");
        for (ServiceStatus status : new ServiceStatus[]{ServiceStatus.HEALTHY, ServiceStatus.DOWN}) {
            results.put(status, registry.counter("launchguard.probe.results", "status", status.name()));
        }
        for (String reason : new String[]{"duplicate", "service_deleted"}) {
            registry.counter("launchguard.probe.results.ignored", "reason", reason);
        }
    }

    public void dispatched() { dispatched.increment(); }
    public void dispatchFailed() { dispatchFailures.increment(); }
    public void resultPersisted(ServiceStatus status) { afterCommit(() -> results.get(status).increment()); }
    public void incidentOpened() { afterCommit(opened::increment); }
    public void incidentResolved() { afterCommit(resolved::increment); }
    public void resultIgnored(String reason) {
        if (!reason.equals("duplicate") && !reason.equals("service_deleted")) throw new IllegalArgumentException("Unknown ignored-result reason");
        afterCommit(() -> registry.counter("launchguard.probe.results.ignored", "reason", reason).increment());
    }
    public Timer.Sample processingStarted() { return Timer.start(registry); }
    public void processingFinished(Timer.Sample sample, boolean successful) {
        sample.stop(Timer.builder("launchguard.probe.result.processing")
                .tag("outcome", successful ? "success" : "error")
                .publishPercentileHistogram().register(registry));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            // Direct unit calls have no transaction; production transition points are transactional.
            action.run();
        }
    }
}
