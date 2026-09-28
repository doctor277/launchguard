package io.github.doctor277.probeworker;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class WorkerTelemetry {
    private final MeterRegistry registry;

    public WorkerTelemetry(MeterRegistry registry) {
        this.registry = registry;
        for (String status : new String[]{"HEALTHY", "DOWN", "ERROR"}) {
            registry.counter("launchguard.probe.worker.requests", "status", status);
            timer(status);
        }
    }

    public void completed(String status, long durationNanos) {
        if (!status.equals("HEALTHY") && !status.equals("DOWN") && !status.equals("ERROR")) {
            throw new IllegalArgumentException("Unknown worker outcome");
        }
        registry.counter("launchguard.probe.worker.requests", "status", status).increment();
        timer(status).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    private Timer timer(String status) {
        return Timer.builder("launchguard.probe.worker.duration").tag("status", status)
                .publishPercentileHistogram().register(registry);
    }
}
