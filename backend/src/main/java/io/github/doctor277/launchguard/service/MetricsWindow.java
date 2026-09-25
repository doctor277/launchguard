package io.github.doctor277.launchguard.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum MetricsWindow {
    ONE_HOUR("1h", Duration.ofHours(1)),
    TWENTY_FOUR_HOURS("24h", Duration.ofHours(24)),
    SEVEN_DAYS("7d", Duration.ofDays(7)),
    THIRTY_DAYS("30d", Duration.ofDays(30)),
    ALL("all", null);

    private static final String SUPPORTED_VALUES = "1h, 24h, 7d, 30d, all";

    private final String value;
    private final Duration duration;

    MetricsWindow(String value, Duration duration) {
        this.value = value;
        this.duration = duration;
    }

    public static MetricsWindow parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(window -> window.value.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid metrics window '" + value + "'. Supported values: " + SUPPORTED_VALUES));
    }

    public Optional<Instant> startInclusive(Instant now) {
        return duration == null ? Optional.empty() : Optional.of(now.minus(duration));
    }

    public String value() {
        return value;
    }
}
