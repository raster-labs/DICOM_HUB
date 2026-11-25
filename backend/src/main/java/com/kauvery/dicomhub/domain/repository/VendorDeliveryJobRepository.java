package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface VendorDeliveryJobRepository extends JpaRepository<VendorDeliveryJob, UUID> {
    List<VendorDeliveryJob> findBySiteIdOrderByCreatedAtDesc(UUID siteId);
    List<VendorDeliveryJob> findBySiteIdAndStudyId(UUID siteId, UUID studyId);
    Optional<VendorDeliveryJob> findBySiteIdAndStudyIdAndVendorId(UUID siteId, UUID studyId, UUID vendorId);
    Optional<VendorDeliveryJob> findBySiteIdAndVendorJobId(UUID siteId, String vendorJobId);
    List<VendorDeliveryJob> findBySiteIdAndVendorId(UUID siteId, UUID vendorId);
    List<VendorDeliveryJob> findByStatusOrderByCreatedAtAsc(String status);
    long countBySiteIdAndStatus(UUID siteId, String status);
    long countBySiteIdAndVendorIdAndStatus(UUID siteId, UUID vendorId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM VendorDeliveryJob j WHERE j.id = :jobId AND j.siteId = :siteId")
    Optional<VendorDeliveryJob> findByIdAndSiteIdForUpdate(@Param("jobId") UUID jobId, @Param("siteId") UUID siteId);

    /**
     * Durable PostgreSQL state-machine queue claim using SELECT ... FOR UPDATE SKIP LOCKED
     * (jakarta.persistence.lock.timeout = -2 maps to Hibernate LockOptions.SKIP_LOCKED).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT j FROM VendorDeliveryJob j WHERE j.status = 'QUEUED' "
            + "OR (j.status = 'RETRY_WAIT' AND (j.nextRetryAt IS NULL OR j.nextRetryAt <= :now)) "
            + "ORDER BY j.createdAt ASC")
    List<VendorDeliveryJob> claimDueOutboundJobsSkipLocked(@Param("now") OffsetDateTime now);
}
