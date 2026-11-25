package com.kauvery.dicomhub.worker;

import com.kauvery.dicomhub.audit.AuditService;
import com.kauvery.dicomhub.config.HubProperties;
import com.kauvery.dicomhub.dicom.DicomWorkflowOrchestratorService;
import com.kauvery.dicomhub.dicom.LiveStudyEventStreamService;
import com.kauvery.dicomhub.domain.entity.AiResultRecord;
import com.kauvery.dicomhub.domain.entity.AuditEvent;
import com.kauvery.dicomhub.domain.entity.CleanupPolicy;
import com.kauvery.dicomhub.domain.entity.InstanceRecord;
import com.kauvery.dicomhub.domain.entity.PseudonymMapping;
import com.kauvery.dicomhub.domain.entity.RecordRetentionSettings;
import com.kauvery.dicomhub.domain.entity.RisUploadedReport;
import com.kauvery.dicomhub.domain.entity.StudyRecord;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.domain.entity.VendorTransferAttempt;
import com.kauvery.dicomhub.domain.repository.AiResultRecordRepository;
import com.kauvery.dicomhub.domain.repository.AuditEventRepository;
import com.kauvery.dicomhub.domain.repository.CleanupPolicyRepository;
import com.kauvery.dicomhub.domain.repository.InstanceRecordRepository;
import com.kauvery.dicomhub.domain.repository.PseudonymMappingRepository;
import com.kauvery.dicomhub.domain.repository.RecordRetentionSettingsRepository;
import com.kauvery.dicomhub.domain.repository.RisUploadedReportRepository;
import com.kauvery.dicomhub.domain.repository.StudyRecordRepository;
import com.kauvery.dicomhub.domain.repository.VendorDeliveryJobRepository;
import com.kauvery.dicomhub.domain.repository.VendorTransferAttemptRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces:
 * 1. Independent per-vendor working-copy cleanup and dry-run reporting (Specification Sections 5.1, 5.2, 5.3 & ADR-02).
 * 2. Manual & frequent automatic removal of old study records along with their processing logs, RIS upload logs,
 *    transfer attempts, AI results, audit events, and disk files.
 */
@Service
public class RetentionCleanupWorkerService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupWorkerService.class);

    private final HubProperties hubProperties;
    private final CleanupPolicyRepository cleanupPolicyRepository;
    private final RecordRetentionSettingsRepository retentionSettingsRepository;
    private final StudyRecordRepository studyRecordRepository;
    private final InstanceRecordRepository instanceRecordRepository;
    private final VendorDeliveryJobRepository jobRepository;
    private final VendorTransferAttemptRepository attemptRepository;
    private final PseudonymMappingRepository pseudonymMappingRepository;
    private final AiResultRecordRepository aiResultRecordRepository;
    private final RisUploadedReportRepository risUploadedReportRepository;
    private final AuditEventRepository auditEventRepository;
    private final DicomWorkflowOrchestratorService orchestratorService;
    private final LiveStudyEventStreamService liveStreamService;
    private final AuditService auditService;

    public RetentionCleanupWorkerService(
            HubProperties hubProperties,
            CleanupPolicyRepository cleanupPolicyRepository,
            RecordRetentionSettingsRepository retentionSettingsRepository,
            StudyRecordRepository studyRecordRepository,
            InstanceRecordRepository instanceRecordRepository,
            VendorDeliveryJobRepository jobRepository,
            VendorTransferAttemptRepository attemptRepository,
            PseudonymMappingRepository pseudonymMappingRepository,
            AiResultRecordRepository aiResultRecordRepository,
            RisUploadedReportRepository risUploadedReportRepository,
            AuditEventRepository auditEventRepository,
            DicomWorkflowOrchestratorService orchestratorService,
            LiveStudyEventStreamService liveStreamService,
            AuditService auditService) {
        this.hubProperties = hubProperties;
        this.cleanupPolicyRepository = cleanupPolicyRepository;
        this.retentionSettingsRepository = retentionSettingsRepository;
        this.studyRecordRepository = studyRecordRepository;
        this.instanceRecordRepository = instanceRecordRepository;
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
        this.pseudonymMappingRepository = pseudonymMappingRepository;
        this.aiResultRecordRepository = aiResultRecordRepository;
        this.risUploadedReportRepository = risUploadedReportRepository;
        this.auditEventRepository = auditEventRepository;
        this.orchestratorService = orchestratorService;
        this.liveStreamService = liveStreamService;
        this.auditService = auditService;
    }

    public record CleanupExecutionReportDto(
            UUID policyId,
            UUID siteId,
            UUID vendorId,
            String targetCategory,
            boolean dryRun,
            boolean skippedByLegalHold,
            int affectedItemsCount,
            String summaryMessage
    ) {}

    public record RecordPurgeResultDto(
            UUID siteId,
            boolean purgeAll,
            int olderThanMinutes,
            int removedStudiesCount,
            int removedInstancesCount,
            int removedJobsCount,
            int removedAiResultsCount,
            int removedRisReportsCount,
            int removedAuditLogsCount,
            long freedDiskBytes,
            String triggerSource,
            OffsetDateTime executedAt,
            String summaryMessage
    ) {}

    public record RetentionDashboardSummaryDto(
            RecordRetentionSettings settings,
            long activeStudiesCount,
            long activeInstancesCount,
            long activeJobsCount,
            long activeTransferAttemptsCount,
            long activeAiResultsCount,
            long activeRisReportsCount,
            long activeAuditLogsCount,
            long totalOriginalRepoBytes,
            long totalWorkingCopyBytes,
            long totalRisPdfBytes
    ) {}

    public boolean isCleanupWorkerRoleActive() {
        String role = hubProperties.role();
        return "all".equalsIgnoreCase(role) || "cleanup-worker".equalsIgnoreCase(role);
    }

    @Scheduled(cron = "${hub.cleanup.schedule-cron:0 0 2 * * *}")
    @Transactional
    public void scheduledDailyCleanup() throws IOException {
        if (!isCleanupWorkerRoleActive()) {
            return;
        }
        List<CleanupPolicy> allPolicies = cleanupPolicyRepository.findAll();
        for (CleanupPolicy policy : allPolicies) {
            if (policy.isActive()) {
                executePolicy(policy.getSiteId(), policy.getId(), null);
            }
        }
    }

    /**
     * Frequent automatic background removal of old records and their logs when enabled by the administrator.
     */
    @Scheduled(
            fixedDelayString = "${hub.cleanup.auto-purge-check-ms:30000}",
            initialDelayString = "${hub.cleanup.auto-purge-initial-delay-ms:30000}"
    )
    @Transactional
    public void scheduledFrequentAutoPurge() {
        if (!isCleanupWorkerRoleActive()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<RecordRetentionSettings> allSettings = retentionSettingsRepository.findAll();
        for (RecordRetentionSettings s : allSettings) {
            if (!s.isAutoRemoveEnabled()) {
                continue;
            }
            long intervalSeconds = Math.max(30L, s.getCheckIntervalMinutes() * 60L - 5L);
            boolean due = s.getLastAutoRunAt() == null
                    || Duration.between(s.getLastAutoRunAt(), now).getSeconds() >= intervalSeconds;
            if (due) {
                try {
                    RecordPurgeResultDto res = purgeOldRecordsAndLogs(
                            s.getSiteId(),
                            s.getRetentionMaxAgeMinutes(),
                            false,
                            s.isRemoveStudyLogsAndAudits(),
                            s.isRemoveDiskFiles(),
                            "AUTO_SCHEDULER");
                    if (res.removedStudiesCount() > 0 || res.removedRisReportsCount() > 0 || res.removedAuditLogsCount() > 0) {
                        log.info("Auto-removed old records & logs for site {}: {}",
                                s.getSiteId(), res.summaryMessage());
                    }
                } catch (Exception ex) {
                    log.warn("Auto-removal execution error for site {}: {}", s.getSiteId(), ex.getMessage());
                }
            }
        }
    }

    @Transactional
    public RecordRetentionSettings getOrCreateRetentionSettings(UUID siteId) {
        return retentionSettingsRepository.findBySiteId(siteId).orElseGet(() -> {
            RecordRetentionSettings s = new RecordRetentionSettings();
            s.setId(UUID.randomUUID());
            s.setSiteId(siteId);
            s.setAutoRemoveEnabled(true);
            s.setRetentionMaxAgeMinutes(60);
            s.setCheckIntervalMinutes(5);
            s.setRemoveStudyLogsAndAudits(true);
            s.setRemoveDiskFiles(true);
            s.setProtectActiveReceivingStudies(true);
            s.setUpdatedBy("super.admin");
            s.setUpdatedAt(OffsetDateTime.now());
            return retentionSettingsRepository.save(s);
        });
    }

    @Transactional
    public RetentionDashboardSummaryDto getRetentionDashboardSummary(UUID siteId) {
        RecordRetentionSettings settings = getOrCreateRetentionSettings(siteId);
        List<StudyRecord> studies = studyRecordRepository.findBySiteIdOrderByCreatedAtDesc(siteId);
        List<VendorDeliveryJob> jobs = jobRepository.findBySiteIdOrderByCreatedAtDesc(siteId);
        List<AiResultRecord> results = aiResultRecordRepository.findBySiteIdOrderByReceivedAtDesc(siteId);
        List<RisUploadedReport> risReports = risUploadedReportRepository.findBySiteIdOrderByUploadedAtDesc(siteId);
        List<AuditEvent> audits = auditEventRepository.findTop100BySiteIdOrderByCreatedAtDesc(siteId);

        long instancesCount = instanceRecordRepository.countBySiteId(siteId);
        long attemptsCount = jobs.stream()
                .mapToLong(j -> attemptRepository.countBySiteIdAndJobId(siteId, j.getId()))
                .sum();
        long origBytes = studies.stream().mapToLong(StudyRecord::getTotalBytes).sum();
        long workBytes = jobs.stream().mapToLong(VendorDeliveryJob::getUploadedBytes).sum();
        long risBytes = risReports.stream()
                .mapToLong(r -> r.getPdfFileSizeBytes() + r.getDicomFileSizeBytes())
                .sum();

        return new RetentionDashboardSummaryDto(
                settings,
                studies.size(),
                instancesCount,
                jobs.size(),
                attemptsCount,
                results.size(),
                risReports.size(),
                audits.size(),
                origBytes,
                workBytes,
                risBytes);
    }

    @Transactional
    public RecordRetentionSettings updateRetentionSettings(
            UUID siteId,
            RecordRetentionSettings req,
            String actor) {
        RecordRetentionSettings existing = getOrCreateRetentionSettings(siteId);
        existing.setAutoRemoveEnabled(req.isAutoRemoveEnabled());
        if (req.getRetentionMaxAgeMinutes() >= 1) {
            existing.setRetentionMaxAgeMinutes(req.getRetentionMaxAgeMinutes());
        }
        if (req.getCheckIntervalMinutes() >= 1) {
            existing.setCheckIntervalMinutes(req.getCheckIntervalMinutes());
        }
        existing.setRemoveStudyLogsAndAudits(req.isRemoveStudyLogsAndAudits());
        existing.setRemoveDiskFiles(req.isRemoveDiskFiles());
        existing.setProtectActiveReceivingStudies(req.isProtectActiveReceivingStudies());
        existing.setUpdatedBy(actor != null && !actor.isBlank() ? actor : "super.admin");
        existing.setUpdatedAt(OffsetDateTime.now());
        RecordRetentionSettings saved = retentionSettingsRepository.save(existing);

        auditService.record(
                siteId,
                existing.getUpdatedBy(),
                "SUPER_USER",
                "CLEANUP",
                "RECORD_RETENTION_SETTINGS_UPDATED",
                "RecordRetentionSettings",
                saved.getId().toString(),
                "SUCCESS",
                "{\"autoRemoveEnabled\":" + saved.isAutoRemoveEnabled()
                        + ",\"retentionMaxAgeMinutes\":" + saved.getRetentionMaxAgeMinutes()
                        + ",\"checkIntervalMinutes\":" + saved.getCheckIntervalMinutes()
                        + ",\"removeStudyLogsAndAudits\":" + saved.isRemoveStudyLogsAndAudits()
                        + ",\"removeDiskFiles\":" + saved.isRemoveDiskFiles() + "}");
        return saved;
    }

    @Transactional
    public RecordPurgeResultDto purgeOldRecordsAndLogs(
            UUID siteId,
            Integer olderThanMinutes,
            boolean purgeAll,
            Boolean removeLogsOverride,
            Boolean removeFilesOverride,
            String triggerSource) {

        RecordRetentionSettings settings = getOrCreateRetentionSettings(siteId);
        int effectiveMaxAgeMins = (olderThanMinutes != null && olderThanMinutes >= 0)
                ? olderThanMinutes
                : settings.getRetentionMaxAgeMinutes();
        boolean removeLogs = removeLogsOverride != null ? removeLogsOverride : settings.isRemoveStudyLogsAndAudits();
        boolean removeFiles = removeFilesOverride != null ? removeFilesOverride : settings.isRemoveDiskFiles();

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = purgeAll ? now.plusYears(10) : now.minusMinutes(effectiveMaxAgeMins);

        List<StudyRecord> siteStudies = studyRecordRepository.findBySiteIdOrderByCreatedAtDesc(siteId);
        List<StudyRecord> targetStudies = siteStudies.stream()
                .filter(s -> {
                    if (purgeAll) {
                        return true;
                    }
                    if (settings.isProtectActiveReceivingStudies()
                            && "RECEIVING".equalsIgnoreCase(s.getReceivingStatus())
                            && s.getLastInstanceReceivedAt() != null
                            && s.getLastInstanceReceivedAt().isAfter(now.minusMinutes(Math.max(5, effectiveMaxAgeMins)))) {
                        return false;
                    }
                    OffsetDateTime refTime = s.getLastInstanceReceivedAt() != null
                            ? s.getLastInstanceReceivedAt()
                            : s.getCreatedAt();
                    return refTime == null || !refTime.isAfter(cutoff);
                })
                .toList();

        int removedStudies = 0;
        int removedInstances = 0;
        int removedJobs = 0;
        int removedResults = 0;
        int removedRisReports = 0;
        int removedAuditLogs = 0;
        long freedBytes = 0L;

        Set<String> purgedEntityIds = new HashSet<>();
        Set<String> purgedStudyIuids = new HashSet<>();

        for (StudyRecord study : targetStudies) {
            UUID studyId = study.getId();
            purgedEntityIds.add(studyId.toString());
            if (study.getStudyInstanceUid() != null) {
                purgedEntityIds.add(study.getStudyInstanceUid());
                purgedStudyIuids.add(study.getStudyInstanceUid());
            }

            // 1. Delete RisUploadedReport entries for study
            List<RisUploadedReport> studyRisReports = risUploadedReportRepository
                    .findBySiteIdAndStudyIuidOrderByUploadedAtDesc(siteId, study.getStudyInstanceUid());
            for (RisUploadedReport rep : studyRisReports) {
                purgedEntityIds.add(rep.getId().toString());
                if (removeFiles && rep.getPdfFilePath() != null && !rep.getPdfFilePath().isBlank()) {
                    freedBytes += deleteFileOrDirectoryQuietly(Path.of(rep.getPdfFilePath()));
                }
                if (removeFiles && rep.getDicomFilePath() != null && !rep.getDicomFilePath().isBlank()) {
                    freedBytes += deleteFileOrDirectoryQuietly(Path.of(rep.getDicomFilePath()));
                }
                risUploadedReportRepository.delete(rep);
                removedRisReports++;
            }

            // 2. Delete AiResultRecord entries for study
            List<AiResultRecord> studyResults = aiResultRecordRepository
                    .findBySiteIdAndStudyIdOrderByReceivedAtDesc(siteId, studyId);
            for (AiResultRecord res : studyResults) {
                purgedEntityIds.add(res.getId().toString());
                if (removeFiles) {
                    if (res.getStoragePath() != null && !res.getStoragePath().isBlank()) {
                        freedBytes += deleteFileOrDirectoryQuietly(Path.of(res.getStoragePath()).getParent());
                    }
                    if (res.getPdfStoragePath() != null && !res.getPdfStoragePath().isBlank()) {
                        freedBytes += deleteFileOrDirectoryQuietly(Path.of(res.getPdfStoragePath()));
                    }
                    if (res.getImageStoragePath() != null && !res.getImageStoragePath().isBlank()) {
                        freedBytes += deleteFileOrDirectoryQuietly(Path.of(res.getImageStoragePath()));
                    }
                    if (res.getDicomStoragePath() != null && !res.getDicomStoragePath().isBlank()) {
                        freedBytes += deleteFileOrDirectoryQuietly(Path.of(res.getDicomStoragePath()));
                    }
                }
                aiResultRecordRepository.delete(res);
                removedResults++;
            }

            // 3. Delete VendorTransferAttempt & VendorDeliveryJob entries for study
            List<VendorDeliveryJob> studyJobs = jobRepository.findBySiteIdAndStudyId(siteId, studyId);
            for (VendorDeliveryJob job : studyJobs) {
                purgedEntityIds.add(job.getId().toString());
                List<VendorTransferAttempt> attempts = attemptRepository
                        .findBySiteIdAndJobIdOrderByAttemptNumberAsc(siteId, job.getId());
                for (VendorTransferAttempt att : attempts) {
                    attemptRepository.delete(att);
                }
                if (removeFiles && job.getWorkingCopyDir() != null && !job.getWorkingCopyDir().isBlank()) {
                    freedBytes += deleteFileOrDirectoryQuietly(Path.of(job.getWorkingCopyDir()));
                }
                jobRepository.delete(job);
                removedJobs++;
            }

            // 4. Delete PseudonymMapping entries for study
            List<PseudonymMapping> mappings = pseudonymMappingRepository.findAll().stream()
                    .filter(pm -> siteId.equals(pm.getSiteId()) && studyId.equals(pm.getStudyId()))
                    .toList();
            for (PseudonymMapping pm : mappings) {
                pseudonymMappingRepository.delete(pm);
            }

            // 5. Delete InstanceRecord entries & Original Repository files for study
            List<InstanceRecord> instances = instanceRecordRepository
                    .findBySiteIdAndStudyIdOrderByReceivedAtAsc(siteId, studyId);
            for (InstanceRecord inst : instances) {
                purgedEntityIds.add(inst.getId().toString());
                if (removeFiles && inst.getOriginalRepoPath() != null && !inst.getOriginalRepoPath().isBlank()) {
                    Path instPath = Path.of(inst.getOriginalRepoPath());
                    freedBytes += deleteFileOrDirectoryQuietly(instPath);
                    if (instPath.getParent() != null && instPath.getParent().getParent() != null) {
                        deleteFileOrDirectoryQuietly(instPath.getParent().getParent());
                    }
                }
                instanceRecordRepository.delete(inst);
                removedInstances++;
            }

            // 6. Delete StudyRecord
            studyRecordRepository.delete(study);
            removedStudies++;
        }

        // Also delete any standalone/test RisUploadedReport rows matching cutoff
        List<RisUploadedReport> remainingRis = risUploadedReportRepository.findBySiteIdOrderByUploadedAtDesc(siteId);
        for (RisUploadedReport rep : remainingRis) {
            boolean shouldRemove = purgeAll
                    || (rep.getUploadedAt() != null && !rep.getUploadedAt().isAfter(cutoff))
                    || purgedStudyIuids.contains(rep.getStudyIuid());
            if (shouldRemove) {
                purgedEntityIds.add(rep.getId().toString());
                if (removeFiles && rep.getPdfFilePath() != null && !rep.getPdfFilePath().isBlank()) {
                    freedBytes += deleteFileOrDirectoryQuietly(Path.of(rep.getPdfFilePath()));
                }
                if (removeFiles && rep.getDicomFilePath() != null && !rep.getDicomFilePath().isBlank()) {
                    freedBytes += deleteFileOrDirectoryQuietly(Path.of(rep.getDicomFilePath()));
                }
                risUploadedReportRepository.delete(rep);
                removedRisReports++;
            }
        }

        // 7. Remove associated study & transfer logs from audit_events if requested
        if (removeLogs) {
            List<AuditEvent> siteAudits = auditEventRepository.findAll().stream()
                    .filter(a -> siteId.equals(a.getSiteId()))
                    .toList();
            for (AuditEvent a : siteAudits) {
                boolean isConfigOrSecurity = "CONFIG_CHANGE".equalsIgnoreCase(a.getEventCategory())
                        || "SECURITY".equalsIgnoreCase(a.getEventCategory());
                boolean matchByAll = purgeAll && !isConfigOrSecurity;
                boolean matchByEntity = a.getEntityId() != null && purgedEntityIds.contains(a.getEntityId());
                boolean matchByAge = !isConfigOrSecurity
                        && a.getCreatedAt() != null
                        && !a.getCreatedAt().isAfter(cutoff);
                if (matchByAll || matchByEntity || matchByAge) {
                    auditEventRepository.delete(a);
                    removedAuditLogs++;
                }
            }
        }

        int totalLogsRemovedThisRun = removedRisReports + removedAuditLogs + removedJobs + removedResults;
        settings.setLastAutoRunAt(now);
        settings.setLastRemovedStudiesCount(removedStudies);
        settings.setLastRemovedLogsCount(totalLogsRemovedThisRun);
        settings.setTotalRemovedStudiesCount(settings.getTotalRemovedStudiesCount() + removedStudies);
        settings.setTotalRemovedLogsCount(settings.getTotalRemovedLogsCount() + totalLogsRemovedThisRun);
        settings.setUpdatedAt(now);
        retentionSettingsRepository.save(settings);

        String summary = purgeAll
                ? String.format(
                        "Removed all study data & logs: %d study record(s), %d DICOM instance(s), %d job(s), %d AI result(s), %d RIS report log(s), and %d audit log(s) removed (Configurations preserved).",
                        removedStudies, removedInstances, removedJobs, removedResults, removedRisReports, removedAuditLogs)
                : String.format(
                        "Removed records & logs older than %d min(s): %d study record(s), %d DICOM instance(s), %d job(s), %d AI result(s), %d RIS report log(s), and %d audit log(s) removed.",
                        effectiveMaxAgeMins, removedStudies, removedInstances, removedJobs, removedResults, removedRisReports, removedAuditLogs);

        liveStreamService.publishJobOrResultEvent(siteId, null, null, null, summary);

        return new RecordPurgeResultDto(
                siteId,
                purgeAll,
                effectiveMaxAgeMins,
                removedStudies,
                removedInstances,
                removedJobs,
                removedResults,
                removedRisReports,
                removedAuditLogs,
                freedBytes,
                triggerSource != null ? triggerSource : "MANUAL_ADMIN",
                now,
                summary);
    }

    @Transactional
    public RecordPurgeResultDto deleteSingleStudyAndLogs(UUID siteId, UUID studyId, String actor) {
        StudyRecord study = studyRecordRepository.findById(studyId)
                .filter(s -> s.getSiteId().equals(siteId))
                .orElseThrow(() -> new IllegalArgumentException("Study not found for site"));

        int removedInstances = 0;
        int removedJobs = 0;
        int removedResults = 0;
        int removedRisReports = 0;
        int removedAuditLogs = 0;
        long freedBytes = 0L;
        Set<String> purgedEntityIds = new HashSet<>();
        purgedEntityIds.add(studyId.toString());
        if (study.getStudyInstanceUid() != null) {
            purgedEntityIds.add(study.getStudyInstanceUid());
        }

        List<RisUploadedReport> studyRisReports = risUploadedReportRepository
                .findBySiteIdAndStudyIuidOrderByUploadedAtDesc(siteId, study.getStudyInstanceUid());
        for (RisUploadedReport rep : studyRisReports) {
            purgedEntityIds.add(rep.getId().toString());
            if (rep.getPdfFilePath() != null && !rep.getPdfFilePath().isBlank()) {
                freedBytes += deleteFileOrDirectoryQuietly(Path.of(rep.getPdfFilePath()));
            }
            if (rep.getDicomFilePath() != null && !rep.getDicomFilePath().isBlank()) {
                freedBytes += deleteFileOrDirectoryQuietly(Path.of(rep.getDicomFilePath()));
            }
            risUploadedReportRepository.delete(rep);
            removedRisReports++;
        }

        List<AiResultRecord> studyResults = aiResultRecordRepository
                .findBySiteIdAndStudyIdOrderByReceivedAtDesc(siteId, studyId);
        for (AiResultRecord res : studyResults) {
            purgedEntityIds.add(res.getId().toString());
            if (res.getStoragePath() != null && !res.getStoragePath().isBlank()) {
                freedBytes += deleteFileOrDirectoryQuietly(Path.of(res.getStoragePath()).getParent());
            }
            if (res.getPdfStoragePath() != null && !res.getPdfStoragePath().isBlank()) {
                freedBytes += deleteFileOrDirectoryQuietly(Path.of(res.getPdfStoragePath()));
            }
            if (res.getImageStoragePath() != null && !res.getImageStoragePath().isBlank()) {
                freedBytes += deleteFileOrDirectoryQuietly(Path.of(res.getImageStoragePath()));
            }
            if (res.getDicomStoragePath() != null && !res.getDicomStoragePath().isBlank()) {
                freedBytes += deleteFileOrDirectoryQuietly(Path.of(res.getDicomStoragePath()));
            }
            aiResultRecordRepository.delete(res);
            removedResults++;
        }

        List<VendorDeliveryJob> studyJobs = jobRepository.findBySiteIdAndStudyId(siteId, studyId);
        for (VendorDeliveryJob job : studyJobs) {
            purgedEntityIds.add(job.getId().toString());
            for (VendorTransferAttempt att : attemptRepository.findBySiteIdAndJobIdOrderByAttemptNumberAsc(siteId, job.getId())) {
                attemptRepository.delete(att);
            }
            if (job.getWorkingCopyDir() != null && !job.getWorkingCopyDir().isBlank()) {
                freedBytes += deleteFileOrDirectoryQuietly(Path.of(job.getWorkingCopyDir()));
            }
            jobRepository.delete(job);
            removedJobs++;
        }

        for (PseudonymMapping pm : pseudonymMappingRepository.findAll()) {
            if (siteId.equals(pm.getSiteId()) && studyId.equals(pm.getStudyId())) {
                pseudonymMappingRepository.delete(pm);
            }
        }

        for (InstanceRecord inst : instanceRecordRepository.findBySiteIdAndStudyIdOrderByReceivedAtAsc(siteId, studyId)) {
            purgedEntityIds.add(inst.getId().toString());
            if (inst.getOriginalRepoPath() != null && !inst.getOriginalRepoPath().isBlank()) {
                Path instPath = Path.of(inst.getOriginalRepoPath());
                freedBytes += deleteFileOrDirectoryQuietly(instPath);
                if (instPath.getParent() != null && instPath.getParent().getParent() != null) {
                    deleteFileOrDirectoryQuietly(instPath.getParent().getParent());
                }
            }
            instanceRecordRepository.delete(inst);
            removedInstances++;
        }

        for (AuditEvent a : auditEventRepository.findAll()) {
            if (siteId.equals(a.getSiteId()) && a.getEntityId() != null && purgedEntityIds.contains(a.getEntityId())) {
                auditEventRepository.delete(a);
                removedAuditLogs++;
            }
        }

        studyRecordRepository.delete(study);

        OffsetDateTime now = OffsetDateTime.now();
        RecordRetentionSettings settings = getOrCreateRetentionSettings(siteId);
        int logsRemoved = removedRisReports + removedAuditLogs + removedJobs + removedResults;
        settings.setLastRemovedStudiesCount(1);
        settings.setLastRemovedLogsCount(logsRemoved);
        settings.setTotalRemovedStudiesCount(settings.getTotalRemovedStudiesCount() + 1);
        settings.setTotalRemovedLogsCount(settings.getTotalRemovedLogsCount() + logsRemoved);
        settings.setUpdatedAt(now);
        retentionSettingsRepository.save(settings);

        String summary = String.format(
                "Removed study %s (%s) with %d instance(s), %d job(s), %d AI result(s), %d RIS report log(s), and %d audit log(s).",
                study.getPatientName() != null ? study.getPatientName() : study.getStudyInstanceUid(),
                study.getStudyInstanceUid(),
                removedInstances,
                removedJobs,
                removedResults,
                removedRisReports,
                removedAuditLogs);
        liveStreamService.publishJobOrResultEvent(siteId, null, null, null, summary);

        return new RecordPurgeResultDto(
                siteId,
                false,
                0,
                1,
                removedInstances,
                removedJobs,
                removedResults,
                removedRisReports,
                removedAuditLogs,
                freedBytes,
                actor != null ? actor : "MANUAL_SINGLE_DELETE",
                now,
                summary);
    }

    private long deleteFileOrDirectoryQuietly(Path target) {
        if (target == null || !Files.exists(target)) {
            return 0L;
        }
        long bytes = 0L;
        try {
            if (Files.isDirectory(target)) {
                try (Stream<Path> walk = Files.walk(target)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        if (Files.isRegularFile(p)) {
                            bytes += Files.size(p);
                        }
                        Files.deleteIfExists(p);
                    }
                }
            } else {
                bytes = Files.size(target);
                Files.deleteIfExists(target);
            }
        } catch (IOException ignored) {
        }
        return bytes;
    }

    @Transactional
    public CleanupExecutionReportDto executePolicy(
            UUID siteId,
            UUID policyId,
            Boolean overrideDryRun) throws IOException {

        CleanupPolicy policy = cleanupPolicyRepository.findById(policyId)
                .filter(p -> p.getSiteId().equals(siteId))
                .orElseThrow(() -> new IllegalArgumentException("Cleanup policy not found for site"));

        boolean effectiveDryRun = overrideDryRun != null ? overrideDryRun : policy.isDryRunOnly();

        if (policy.isLegalHoldActive()) {
            auditService.record(
                    siteId, "CLEANUP_WORKER", "SYSTEM", "CLEANUP",
                    "CLEANUP_SKIPPED_LEGAL_HOLD", "CleanupPolicy", policy.getId().toString(),
                    "BLOCKED", "{\"targetCategory\":\"" + policy.getTargetCategory() + "\"}");
            return new CleanupExecutionReportDto(
                    policy.getId(), siteId, policy.getVendorId(), policy.getTargetCategory(),
                    effectiveDryRun, true, 0,
                    "Skipped: Legal/Incident Hold is active on policy " + policy.getTargetCategory());
        }

        if ("VENDOR_WORKING_COPY".equalsIgnoreCase(policy.getTargetCategory()) && policy.getVendorId() != null) {
            int count = orchestratorService.cleanupEligibleVendorWorkingCopies(
                    siteId, policy.getVendorId(), effectiveDryRun);
            String msg = effectiveDryRun
                    ? "Dry-run audit identified " + count + " eligible vendor working copies (Original Repository untouched)"
                    : "Purged " + count + " eligible vendor working copies (Original Repository untouched)";
            return new CleanupExecutionReportDto(
                    policy.getId(), siteId, policy.getVendorId(), policy.getTargetCategory(),
                    effectiveDryRun, false, count, msg);
        }

        // ORIGINAL_REPOSITORY and AI_RESULT_OBJECTS are strictly protected from vendor working-copy purge
        auditService.record(
                siteId, "CLEANUP_WORKER", "SYSTEM", "CLEANUP",
                "PROTECTED_REPOSITORY_RETENTION_AUDIT", "CleanupPolicy", policy.getId().toString(),
                "SUCCESS", "{\"targetCategory\":\"" + policy.getTargetCategory() + "\",\"dryRun\":true}");

        return new CleanupExecutionReportDto(
                policy.getId(), siteId, policy.getVendorId(), policy.getTargetCategory(),
                true, false, 0,
                "Protected repository retention audit complete for " + policy.getTargetCategory()
                        + " (0 files deleted; Original Repository immutable invariant enforced)");
    }
}
