package io.github.doctor277.launchguard.repository;

import io.github.doctor277.launchguard.domain.MonitoredService;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, UUID> {

    List<MonitoredService> findAllByOrderByCreatedAtAsc();

    boolean existsByNameIgnoreCase(String name);
}
