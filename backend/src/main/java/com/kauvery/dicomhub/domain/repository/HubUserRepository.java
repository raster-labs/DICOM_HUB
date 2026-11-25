package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.HubUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubUserRepository extends JpaRepository<HubUser, UUID> {
    List<HubUser> findBySiteIdOrderByCreatedAtAsc(UUID siteId);
    Optional<HubUser> findFirstBySiteIdAndUsernameIgnoreCase(UUID siteId, String username);
    Optional<HubUser> findFirstByUsernameIgnoreCase(String username);
}
