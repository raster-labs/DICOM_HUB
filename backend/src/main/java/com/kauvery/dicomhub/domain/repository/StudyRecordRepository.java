package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.StudyRecord;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface StudyRecordRepository extends JpaRepository<StudyRecord, UUID> {
    List<StudyRecord> findBySiteIdOrderByCreatedAtDesc(UUID siteId);
    Optional<StudyRecord> findBySiteIdAndStudyInstanceUid(UUID siteId, String studyInstanceUid);
    List<StudyRecord> findBySiteIdAndLifecycleState(UUID siteId, String lifecycleState);
    long countBySiteId(UUID siteId);
    long countBySiteIdAndSourceType(UUID siteId, String sourceType);
    long countBySiteIdAndLifecycleState(UUID siteId, String lifecycleState);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT s FROM StudyRecord s WHERE s.lifecycleState = 'WAITING_FOR_COMPLETION' ORDER BY s.lastInstanceReceivedAt ASC")
    List<StudyRecord> claimWaitingForCompletionStudiesSkipLocked();
}
