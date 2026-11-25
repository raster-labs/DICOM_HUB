package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.LlmPromptTemplate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmPromptTemplateRepository extends JpaRepository<LlmPromptTemplate, UUID> {
    List<LlmPromptTemplate> findBySiteIdOrderByTemplateCodeAscTemplateVersionDesc(UUID siteId);
    Optional<LlmPromptTemplate> findFirstBySiteIdAndTemplateCodeOrderByTemplateVersionDesc(
            UUID siteId, String templateCode);
}
