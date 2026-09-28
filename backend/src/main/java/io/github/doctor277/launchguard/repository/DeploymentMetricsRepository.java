package io.github.doctor277.launchguard.repository;

import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DeploymentMetricsRepository {

    private static final String SELECT_AGGREGATE = """
            SELECT COUNT(*) AS total_checks,
                   COUNT(*) FILTER (WHERE status = 'HEALTHY') AS healthy_checks,
                   COUNT(*) FILTER (WHERE status = 'DOWN') AS failed_checks,
                   AVG(response_time_ms) AS average_response_time_ms,
                   MIN(response_time_ms) AS min_response_time_ms,
                   MAX(response_time_ms) AS max_response_time_ms,
                   MIN(checked_at) FILTER (WHERE status = 'DOWN') AS first_failure_at,
                   MAX(checked_at) AS last_checked_at
            FROM health_checks
            WHERE deployment_id = :deploymentId
            """;

    private final JdbcClient jdbcClient;

    public DeploymentMetricsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public DeploymentMetricsAggregate summarize(UUID deploymentId) {
        return jdbcClient.sql(SELECT_AGGREGATE)
                .param("deploymentId", deploymentId)
                .query((resultSet, rowNumber) -> {
                    Timestamp firstFailure = resultSet.getTimestamp("first_failure_at");
                    Timestamp lastChecked = resultSet.getTimestamp("last_checked_at");
                    return new DeploymentMetricsAggregate(
                            resultSet.getLong("total_checks"),
                            resultSet.getLong("healthy_checks"),
                            resultSet.getLong("failed_checks"),
                            resultSet.getBigDecimal("average_response_time_ms"),
                            resultSet.getObject("min_response_time_ms", Long.class),
                            resultSet.getObject("max_response_time_ms", Long.class),
                            firstFailure == null ? null : firstFailure.toInstant(),
                            lastChecked == null ? null : lastChecked.toInstant());
                }).single();
    }
}
