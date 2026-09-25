package io.github.doctor277.launchguard.repository;

import io.github.doctor277.launchguard.domain.HealthCheck;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HealthCheckRepository extends JpaRepository<HealthCheck, UUID> {

    List<HealthCheck> findAllByServiceIdOrderByCheckedAtDesc(UUID serviceId);
}
