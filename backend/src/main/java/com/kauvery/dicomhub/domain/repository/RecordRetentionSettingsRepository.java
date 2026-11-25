package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.RecordRetentionSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordRetentionSettingsRepository extends JpaRepository<RecordRetentionSettings, UUID> {
    Optional<RecordRetentionSettings> findBySiteId(UUID siteId);
}
