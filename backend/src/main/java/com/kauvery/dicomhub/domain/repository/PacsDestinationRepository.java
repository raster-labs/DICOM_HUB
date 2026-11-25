package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.PacsDestination;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PacsDestinationRepository extends JpaRepository<PacsDestination, UUID> {
    List<PacsDestination> findBySiteIdOrderByDestinationNameAsc(UUID siteId);
    Optional<PacsDestination> findFirstBySiteIdAndDefaultForSiteTrueAndActiveTrue(UUID siteId);
}
