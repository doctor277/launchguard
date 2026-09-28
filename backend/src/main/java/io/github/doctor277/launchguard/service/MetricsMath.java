package io.github.doctor277.launchguard.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class MetricsMath {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private MetricsMath() {
    }

    static BigDecimal availability(long healthyChecks, long totalChecks) {
        if (totalChecks == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(healthyChecks)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(totalChecks), 2, RoundingMode.HALF_UP);
    }

    static BigDecimal roundedLatency(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
    }
}
