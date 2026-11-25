package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.LlmApprovedModel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmApprovedModelRepository extends JpaRepository<LlmApprovedModel, UUID> {
    List<LlmApprovedModel> findBySiteIdOrderByCreatedAtDesc(UUID siteId);
    List<LlmApprovedModel> findBySiteIdAndActiveTrue(UUID siteId);
    Optional<LlmApprovedModel> findBySiteIdAndModelIdentifierAndPinnedVersion(
            UUID siteId, String modelIdentifier, String pinnedVersion);
}
