package io.github.doctor277.launchguard.service;

import io.github.doctor277.launchguard.domain.MonitoredService;

public interface HealthProbe {

    ProbeResult probe(MonitoredService service);
}
