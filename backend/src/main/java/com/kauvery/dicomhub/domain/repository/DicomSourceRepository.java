package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.DicomSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DicomSourceRepository extends JpaRepository<DicomSource, UUID> {
    List<DicomSource> findBySiteIdOrderBySourceNameAsc(UUID siteId);
    Optional<DicomSource> findBySiteIdAndCallingAeTitleAndActiveTrue(UUID siteId, String callingAeTitle);
    Optional<DicomSource> findFirstByCallingAeTitleAndActiveTrue(String callingAeTitle);
}
