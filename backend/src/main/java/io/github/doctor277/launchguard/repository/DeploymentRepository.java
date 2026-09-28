package io.github.doctor277.launchguard.repository;

import io.github.doctor277.launchguard.domain.Deployment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    Page<Deployment> findAllByServiceId(UUID serviceId, Pageable pageable);

    Optional<Deployment> findByIdAndServiceId(UUID id, UUID serviceId);

    Optional<Deployment> findByServiceIdAndExternalId(UUID serviceId, String externalId);
}
