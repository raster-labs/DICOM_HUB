package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.DeidProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeidProfileRepository extends JpaRepository<DeidProfile, UUID> {
    List<DeidProfile> findBySiteIdOrderByProfileCodeAsc(UUID siteId);
    Optional<DeidProfile> findBySiteIdAndProfileCode(UUID siteId, String profileCode);
}
