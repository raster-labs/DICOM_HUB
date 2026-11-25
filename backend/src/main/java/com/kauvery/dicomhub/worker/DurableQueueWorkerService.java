package com.kauvery.dicomhub.worker;

import com.kauvery.dicomhub.config.HubProperties;
import com.kauvery.dicomhub.dicom.DicomWorkflowOrchestratorService;
import com.kauvery.dicomhub.domain.entity.DicomNodeSettings;
import com.kauvery.dicomhub.domain.entity.DicomSource;
import com.kauvery.dicomhub.domain.entity.StudyRecord;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.domain.repository.DicomNodeSettingsRepository;
import com.kauvery.dicomhub.domain.repository.DicomSourceRepository;
import com.kauvery.dicomhub.domain.repository.StudyRecordRepository;
import com.kauvery.dicomhub.domain.repository.VendorDeliveryJobRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL-backed durable state-machine queue worker (ADR-02 & ADR-03).
 * Uses SELECT ... FOR UPDATE SKIP LOCKED to:
 * 1. Detect study completion after the source's configured quiet period (FR-05, default 120s).
 * 2. Process queued and due retry-wait outbound vendor delivery jobs (FR-17, AC-8).
 */
@Service
public class DurableQueueWorkerService {

    private static final Logger log = LoggerFactory.getLogger(DurableQueueWorkerService.class);
    public static final int DEFAULT_QUIET_PERIOD_SECONDS = 120;

    private final HubProperties hubProperties;
    private final StudyRecordRepository studyRecordRepository;
    private final DicomSourceRepository dicomSourceRepository;
    private final DicomNodeSettingsRepository dicomNodeSettingsRepository;
    private final VendorDeliveryJobRepository jobRepository;
    private final DicomWorkflowOrchestratorService orchestratorService;

    public DurableQueueWorkerService(
            HubProperties hubProperties,
            StudyRecordRepository studyRecordRepository,
            DicomSourceRepository dicomSourceRepository,
            DicomNodeSettingsRepository dicomNodeSettingsRepository,
            VendorDeliveryJobRepository jobRepository,
            DicomWorkflowOrchestratorService orchestratorService) {
        this.hubProperties = hubProperties;
        this.studyRecordRepository = studyRecordRepository;
        this.dicomSourceRepository = dicomSourceRepository;
        this.dicomNodeSettingsRepository = dicomNodeSettingsRepository;
        this.jobRepository = jobRepository;
        this.orchestratorService = orchestratorService;
    }

    public boolean isQueueWorkerRoleActive() {
        String role = hubProperties.role();
        return "all".equalsIgnoreCase(role) || "queue-worker".equalsIgnoreCase(role);
    }

    @Scheduled(fixedDelayString = "${hub.worker.poll-interval-ms:30000}", initialDelayString = "${hub.worker.initial-delay-ms:15000}")
    @Transactional
    public void scheduledQueueTick() {
        if (!isQueueWorkerRoleActive()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        int completedStudies = processQuietPeriodCompletedStudies(now);
        int processedJobs = drainDueOutboundJobs(now);
        if (completedStudies > 0 || processedJobs > 0) {
            log.info("Queue worker tick completed: completedStudies={}, processedJobs={}",
                    completedStudies, processedJobs);
        }
    }

    @Transactional
    public int processQuietPeriodCompletedStudies(OffsetDateTime referenceTime) {
        List<StudyRecord> waitingStudies = studyRecordRepository.claimWaitingForCompletionStudiesSkipLocked();
        int completedCount = 0;

        for (StudyRecord study : waitingStudies) {
            int quietPeriodSeconds = resolveQuietPeriodSeconds(study);
            OffsetDateTime threshold = referenceTime.minusSeconds(quietPeriodSeconds);
            if (study.getLastInstanceReceivedAt() != null
                    && !study.getLastInstanceReceivedAt().isAfter(threshold)) {
                orchestratorService.completeValidateAndRouteStudy(study.getSiteId(), study.getId(), false);
                completedCount++;
            }
        }
        return completedCount;
    }

    @Transactional
    public int drainDueOutboundJobs(OffsetDateTime referenceTime) {
        List<VendorDeliveryJob> claimableJobs = jobRepository.claimDueOutboundJobsSkipLocked(referenceTime);
        int processedCount = 0;

        for (VendorDeliveryJob job : claimableJobs) {
            boolean studyStillReceiving = studyRecordRepository.findById(job.getStudyId())
                    .map(s -> "RECEIVING".equalsIgnoreCase(s.getReceivingStatus())
                            || "WAITING_FOR_COMPLETION".equalsIgnoreCase(s.getLifecycleState()))
                    .orElse(false);
            if (studyStillReceiving) {
                continue;
            }
            orchestratorService.processOutboundJob(job.getSiteId(), job.getId(), false);
            processedCount++;
        }
        return processedCount;
    }

    private int resolveQuietPeriodSeconds(StudyRecord study) {
        int siteDefaultQuietPeriod = dicomNodeSettingsRepository.findBySiteId(study.getSiteId())
                .map(DicomNodeSettings::getDefaultQuietPeriodSeconds)
                .filter(q -> q > 0)
                .orElse(DEFAULT_QUIET_PERIOD_SECONDS);
        if (study.getSourceId() != null) {
            return dicomSourceRepository.findById(study.getSourceId())
                    .map(DicomSource::getQuietPeriodSeconds)
                    .orElse(siteDefaultQuietPeriod);
        }
        return dicomSourceRepository
                .findBySiteIdAndCallingAeTitleAndActiveTrue(study.getSiteId(), study.getCallingAeTitle())
                .map(DicomSource::getQuietPeriodSeconds)
                .orElse(siteDefaultQuietPeriod);
    }
}
