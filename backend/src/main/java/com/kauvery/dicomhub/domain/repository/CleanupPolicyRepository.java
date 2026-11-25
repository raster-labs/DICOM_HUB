package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.CleanupPolicy;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CleanupPolicyRepository extends JpaRepository<CleanupPolicy, UUID> {
    List<CleanupPolicy> findBySiteIdOrderByTargetCategoryAsc(UUID siteId);
}
