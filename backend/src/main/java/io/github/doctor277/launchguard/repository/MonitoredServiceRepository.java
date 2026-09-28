package io.github.doctor277.launchguard.repository;

import io.github.doctor277.launchguard.domain.MonitoredService;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, UUID> {

    @Override
    @EntityGraph(attributePaths = "currentDeployment")
    Optional<MonitoredService> findById(UUID id);

    @EntityGraph(attributePaths = "currentDeployment")
    List<MonitoredService> findAllByOrderByCreatedAtAsc();

    boolean existsByNameIgnoreCase(String name);
}
