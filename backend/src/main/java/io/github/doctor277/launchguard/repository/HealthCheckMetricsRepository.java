package io.github.doctor277.launchguard.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HealthCheckMetricsRepository {

    private static final String SELECT_AGGREGATE = """
            SELECT COUNT(*) AS total_checks,
                   COUNT(*) FILTER (WHERE status = 'HEALTHY') AS healthy_checks,
                   COUNT(*) FILTER (WHERE status = 'DOWN') AS failed_checks,
                   AVG(response_time_ms) AS average_response_time_ms,
                   MIN(response_time_ms) AS min_response_time_ms,
                   MAX(response_time_ms) AS max_response_time_ms,
                   MAX(checked_at) FILTER (WHERE status = 'DOWN') AS last_failure_at
            FROM health_checks
            WHERE service_id = :serviceId
            """;

    private final JdbcClient jdbcClient;

    public HealthCheckMetricsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public MetricsAggregate summarize(UUID serviceId) {
        return query(SELECT_AGGREGATE, serviceId, null);
    }

    public MetricsAggregate summarizeSince(UUID serviceId, Instant startInclusive) {
        return query(SELECT_AGGREGATE + " AND checked_at >= :startInclusive", serviceId, startInclusive);
    }

    private MetricsAggregate query(String sql, UUID serviceId, Instant startInclusive) {
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql).param("serviceId", serviceId);
        if (startInclusive != null) {
            statement = statement.param("startInclusive", Timestamp.from(startInclusive));
        }

        return statement.query((resultSet, rowNumber) -> {
            Timestamp lastFailure = resultSet.getTimestamp("last_failure_at");
            return new MetricsAggregate(
                    resultSet.getLong("total_checks"),
                    resultSet.getLong("healthy_checks"),
                    resultSet.getLong("failed_checks"),
                    resultSet.getBigDecimal("average_response_time_ms"),
                    resultSet.getObject("min_response_time_ms", Long.class),
                    resultSet.getObject("max_response_time_ms", Long.class),
                    lastFailure == null ? null : lastFailure.toInstant());
        }).single();
    }
}
