package io.github.doctor277.launchguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MetricsWindowTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Test
    void parsesEverySupportedWindow() {
        assertThat(MetricsWindow.parse("1h")).isEqualTo(MetricsWindow.ONE_HOUR);
        assertThat(MetricsWindow.parse("24h")).isEqualTo(MetricsWindow.TWENTY_FOUR_HOURS);
        assertThat(MetricsWindow.parse("7d")).isEqualTo(MetricsWindow.SEVEN_DAYS);
        assertThat(MetricsWindow.parse("30d")).isEqualTo(MetricsWindow.THIRTY_DAYS);
        assertThat(MetricsWindow.parse("all")).isEqualTo(MetricsWindow.ALL);
    }

    @Test
    void calculatesWindowStartAndLeavesAllUnbounded() {
        assertThat(MetricsWindow.ONE_HOUR.startInclusive(NOW)).contains(NOW.minusSeconds(3_600));
        assertThat(MetricsWindow.TWENTY_FOUR_HOURS.startInclusive(NOW)).contains(NOW.minusSeconds(86_400));
        assertThat(MetricsWindow.ALL.startInclusive(NOW)).isEmpty();
    }

    @Test
    void rejectsInvalidWindow() {
        assertThatThrownBy(() -> MetricsWindow.parse("yesterday"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Supported values: 1h, 24h, 7d, 30d, all");
    }
}
