package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.DicomNodeSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DicomNodeSettingsRepository extends JpaRepository<DicomNodeSettings, UUID> {
    Optional<DicomNodeSettings> findBySiteId(UUID siteId);
}
