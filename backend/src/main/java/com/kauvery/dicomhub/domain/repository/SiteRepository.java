package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.Site;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteRepository extends JpaRepository<Site, UUID> {
    Optional<Site> findBySiteCode(String siteCode);
}
