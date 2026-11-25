package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.PseudonymMapping;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PseudonymMappingRepository extends JpaRepository<PseudonymMapping, UUID> {
    Optional<PseudonymMapping> findBySiteIdAndPseudoStudyUid(UUID siteId, String pseudoStudyUid);
    Optional<PseudonymMapping> findBySiteIdAndStudyIdAndVendorId(UUID siteId, UUID studyId, UUID vendorId);
}
