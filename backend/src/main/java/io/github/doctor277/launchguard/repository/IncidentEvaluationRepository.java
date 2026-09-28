package io.github.doctor277.launchguard.repository;

import io.github.doctor277.launchguard.domain.ServiceStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IncidentEvaluationRepository {

    private final JdbcClient jdbcClient;

    public IncidentEvaluationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public DeploymentAtEvaluation lockService(UUID serviceId) {
        // Unlike FOR UPDATE, this is compatible with parent-key locks acquired by health-check inserts.
        return jdbcClient.sql("SELECT current_deployment_id FROM monitored_services WHERE id = :id FOR NO KEY UPDATE")
                .param("id", serviceId)
                .query((rs, row) -> new DeploymentAtEvaluation(rs.getObject("current_deployment_id", UUID.class)))
                .single();
    }

    public List<RecentCheck> recentChecks(UUID serviceId, int limit) {
        return jdbcClient.sql("""
                        SELECT id, status, checked_at FROM health_checks
                        WHERE service_id = :id ORDER BY checked_at DESC, id DESC LIMIT :limit
                        """)
                .param("id", serviceId).param("limit", limit)
                .query((rs, row) -> new RecentCheck(rs.getObject("id", UUID.class),
                        ServiceStatus.valueOf(rs.getString("status")), rs.getTimestamp("checked_at").toInstant()))
                .list();
    }

    public record DeploymentAtEvaluation(UUID deploymentId) {
    }

    public record RecentCheck(UUID id, ServiceStatus status, Instant checkedAt) {
    }
}
