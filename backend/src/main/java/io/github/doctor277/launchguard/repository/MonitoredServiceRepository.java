package io.github.doctor277.launchguard.repository;

import io.github.doctor277.launchguard.domain.MonitoredService;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, UUID> {

    @Override
    @EntityGraph(attributePaths = "currentDeployment")
    Optional<MonitoredService> findById(UUID id);

    @EntityGraph(attributePaths = "currentDeployment")
    List<MonitoredService> findAllByOrderByCreatedAtAsc();

    boolean existsByNameIgnoreCase(String name);

    // Serialize registration without blocking FK KEY SHARE locks used by health-check inserts.
    // No outer join: PostgreSQL cannot lock the nullable side of currentDeployment's entity graph.
    @Query(value = "SELECT * FROM monitored_services WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<MonitoredService> findByIdForDeploymentRegistration(@Param("id") UUID id);

    @Query(value = "SELECT COUNT(*) FROM monitored_services WHERE (created_at, id) < (:createdAt, :id)", nativeQuery = true)
    long countPreceding(@Param("createdAt") Instant createdAt, @Param("id") UUID id);
}
