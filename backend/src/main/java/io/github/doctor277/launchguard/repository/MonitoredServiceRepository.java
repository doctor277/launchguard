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

    @Query(value = "SELECT COUNT(*) FROM monitored_services WHERE (created_at, id) < (:createdAt, :id)", nativeQuery = true)
    long countPreceding(@Param("createdAt") Instant createdAt, @Param("id") UUID id);
}
