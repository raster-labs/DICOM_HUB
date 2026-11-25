package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.AiResultRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiResultRecordRepository extends JpaRepository<AiResultRecord, UUID> {
    List<AiResultRecord> findBySiteIdOrderByReceivedAtDesc(UUID siteId);
    List<AiResultRecord> findBySiteIdAndStudyIdOrderByReceivedAtDesc(UUID siteId, UUID studyId);
    long countBySiteId(UUID siteId);
    long countBySiteIdAndPacsForwardedTrue(UUID siteId);
}
