package io.github.doctor277.launchguard.repository;

import io.github.doctor277.launchguard.domain.Incident;
import io.github.doctor277.launchguard.domain.IncidentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    @EntityGraph(attributePaths = {"service", "deployment"})
    Page<Incident> findAllByServiceId(UUID serviceId, Pageable pageable);

    @EntityGraph(attributePaths = {"service", "deployment"})
    Page<Incident> findAllByServiceIdAndStatus(UUID serviceId, IncidentStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"service", "deployment"})
    Optional<Incident> findByIdAndServiceId(UUID id, UUID serviceId);

    @EntityGraph(attributePaths = {"service", "deployment"})
    Optional<Incident> findByServiceIdAndStatus(UUID serviceId, IncidentStatus status);

    boolean existsByServiceIdAndStatus(UUID serviceId, IncidentStatus status);

    @Query("select i.service.id from Incident i where i.status = io.github.doctor277.launchguard.domain.IncidentStatus.OPEN")
    List<UUID> findOpenServiceIds();
}
