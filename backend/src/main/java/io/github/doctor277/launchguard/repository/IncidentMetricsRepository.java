package io.github.doctor277.launchguard.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IncidentMetricsRepository {

    private static final String AGGREGATE_SQL = """
            SELECT COUNT(*) AS total_incidents,
                   COUNT(*) FILTER (WHERE status = 'RESOLVED') AS resolved_incidents,
                   COUNT(*) FILTER (WHERE status = 'OPEN') AS open_incidents,
                   AVG(EXTRACT(EPOCH FROM resolved_at - started_at))
                       FILTER (WHERE status = 'RESOLVED') AS average_resolution_seconds,
                   FLOOR(MAX(GREATEST(0, EXTRACT(EPOCH FROM COALESCE(resolved_at, :now) - started_at))))::BIGINT
                       AS longest_incident_seconds
            FROM incidents WHERE service_id = :serviceId
            """;

    private final JdbcClient jdbcClient;

    public IncidentMetricsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public IncidentMetricsAggregate summarize(UUID serviceId, Instant since, Instant now) {
        String sql = AGGREGATE_SQL + (since == null ? "" : " AND started_at >= :since");
        var query = jdbcClient.sql(sql).param("serviceId", serviceId).param("now", Timestamp.from(now));
        if (since != null) {
            query = query.param("since", Timestamp.from(since));
        }
        return query.query((rs, row) -> new IncidentMetricsAggregate(rs.getLong("total_incidents"),
                rs.getLong("resolved_incidents"), rs.getLong("open_incidents"),
                rs.getBigDecimal("average_resolution_seconds"),
                rs.getObject("longest_incident_seconds", Long.class))).single();
    }
}
