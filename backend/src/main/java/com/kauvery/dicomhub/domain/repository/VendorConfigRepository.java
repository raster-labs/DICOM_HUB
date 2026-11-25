package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.VendorConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorConfigRepository extends JpaRepository<VendorConfig, UUID> {
    List<VendorConfig> findBySiteIdOrderByVendorCodeAsc(UUID siteId);
    Optional<VendorConfig> findBySiteIdAndVendorCode(UUID siteId, String vendorCode);
}
