package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.StudyRegionMapping;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudyRegionMappingRepository extends JpaRepository<StudyRegionMapping, UUID> {
    List<StudyRegionMapping> findBySiteIdOrderByCanonicalRegionAsc(UUID siteId);
    List<StudyRegionMapping> findBySiteIdAndActiveTrueOrderByCanonicalRegionAsc(UUID siteId);
}
