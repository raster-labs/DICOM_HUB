package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.RisReportEndpoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RisReportEndpointRepository extends JpaRepository<RisReportEndpoint, UUID> {
    List<RisReportEndpoint> findBySiteIdOrderByCreatedAtAsc(UUID siteId);
    Optional<RisReportEndpoint> findFirstBySiteIdAndActiveTrueOrderByCreatedAtAsc(UUID siteId);
}
