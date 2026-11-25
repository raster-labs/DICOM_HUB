package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.VendorTransferAttempt;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorTransferAttemptRepository extends JpaRepository<VendorTransferAttempt, UUID> {
    List<VendorTransferAttempt> findBySiteIdAndJobIdOrderByAttemptNumberAsc(UUID siteId, UUID jobId);
    long countBySiteIdAndJobId(UUID siteId, UUID jobId);
}
