package io.github.doctor277.launchguard.repository;

import io.github.doctor277.launchguard.domain.ServiceStatus;
import java.time.Instant;

public interface HealthCheckTimelineView {

    Instant getCheckedAt();

    ServiceStatus getStatus();

    long getResponseTimeMs();
}
