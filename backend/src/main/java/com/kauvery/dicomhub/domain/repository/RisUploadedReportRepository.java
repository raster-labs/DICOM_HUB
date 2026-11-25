package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.RisUploadedReport;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RisUploadedReportRepository extends JpaRepository<RisUploadedReport, UUID> {
    List<RisUploadedReport> findBySiteIdOrderByUploadedAtDesc(UUID siteId);
    List<RisUploadedReport> findBySiteIdAndStudyIuidOrderByUploadedAtDesc(UUID siteId, String studyIuid);
    Optional<RisUploadedReport> findFirstBySiteIdAndStudyIuidAndReportTypeIgnoreCaseOrderByUploadedAtDesc(
            UUID siteId, String studyIuid, String reportType);
}
