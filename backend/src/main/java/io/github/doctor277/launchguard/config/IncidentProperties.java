package io.github.doctor277.launchguard.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("launchguard.incidents")
public record IncidentProperties(
        @DefaultValue("3") @Min(1) @Max(1000) int failureThreshold,
        @DefaultValue("2") @Min(1) @Max(1000) int recoveryThreshold) {
}
