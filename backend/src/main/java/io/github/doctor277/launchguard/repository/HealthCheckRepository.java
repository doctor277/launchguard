package io.github.doctor277.launchguard.repository;

import io.github.doctor277.launchguard.domain.HealthCheck;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HealthCheckRepository extends JpaRepository<HealthCheck, UUID> {

    Page<HealthCheck> findAllByServiceId(UUID serviceId, Pageable pageable);

    List<HealthCheckTimelineView> findAllByServiceIdOrderByCheckedAtAsc(UUID serviceId);

    List<HealthCheckTimelineView> findAllByServiceIdAndCheckedAtGreaterThanEqualOrderByCheckedAtAsc(
            UUID serviceId, Instant startInclusive);
}
