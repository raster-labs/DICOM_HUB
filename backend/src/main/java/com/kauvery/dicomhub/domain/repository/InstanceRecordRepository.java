package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.InstanceRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstanceRecordRepository extends JpaRepository<InstanceRecord, UUID> {
    List<InstanceRecord> findBySiteIdAndStudyIdOrderByReceivedAtAsc(UUID siteId, UUID studyId);
    List<InstanceRecord> findBySiteIdOrderByReceivedAtDesc(UUID siteId);
    Optional<InstanceRecord> findBySiteIdAndSopInstanceUid(UUID siteId, String sopInstanceUid);
    long countBySiteId(UUID siteId);
    long countBySiteIdAndReceivedAtGreaterThanEqual(UUID siteId, OffsetDateTime since);
}
