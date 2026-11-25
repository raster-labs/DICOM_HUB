package com.kauvery.dicomhub.dicom;

import com.kauvery.dicomhub.api.CommandCentreController.DashboardOverviewDto;
import com.kauvery.dicomhub.api.CommandCentreController.ForwardedPatientTransferDto;
import com.kauvery.dicomhub.api.CommandCentreController.OperationalAlertDto;
import com.kauvery.dicomhub.api.CommandCentreController.VendorMetricsRowDto;
import com.kauvery.dicomhub.domain.entity.AiResultRecord;
import com.kauvery.dicomhub.domain.entity.AuditEvent;
import com.kauvery.dicomhub.domain.entity.PseudonymMapping;
import com.kauvery.dicomhub.domain.entity.RisUploadedReport;
import com.kauvery.dicomhub.domain.entity.StudyRecord;
import com.kauvery.dicomhub.domain.entity.VendorConfig;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.domain.repository.AiResultRecordRepository;
import com.kauvery.dicomhub.domain.repository.InstanceRecordRepository;
import com.kauvery.dicomhub.domain.repository.PseudonymMappingRepository;
import com.kauvery.dicomhub.domain.repository.RisUploadedReportRepository;
import com.kauvery.dicomhub.domain.repository.StudyRecordRepository;
import com.kauvery.dicomhub.domain.repository.VendorConfigRepository;
import com.kauvery.dicomhub.domain.repository.VendorDeliveryJobRepository;
import com.kauvery.dicomhub.domain.repository.VendorTransferAttemptRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Real-time Server-Sent Events (SSE) broadcaster for Live Incoming Study, Image-Count,
 * Outbound Delivery Job, AI Result, Operational Alert, and Audit Trail updates (FR-46).
 *
 * Emits self-contained delta/snapshot payloads so the Angular Command Centre can patch
 * Signals directly in memory across all pages with zero periodic setInterval polling.
 */
@Service
public class LiveStudyEventStreamService {

    private static final long COALESCE_WINDOW_MS = 100L;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15L;

    public record LiveIncomingStudyEventDto(
            String eventId,
            UUID siteId,
            String eventType, // INSTANCE_COMMITTED, DUPLICATE_INSTANCE_SKIPPED, STUDY_RECEIVING_STATUS_CHANGED, JOB_OR_RESULT_UPDATED, ALERT_OR_AUDIT_CREATED
            UUID studyId,
            String studyInstanceUid,
            String patientId,
            String patientName,
            String modality,
            String studyDescription,
            String sourceType, // RIS_PACS or MODALITY
            String callingAeTitle,
            String receivingStatus, // RECEIVING, COMPLETED, FAILED, INCOMPLETE
            int uniqueStoredInstances,
            int receivedTransferEvents,
            int duplicateInstancesSkipped,
            String sopInstanceUid,
            String message,
            OffsetDateTime timestamp,
            StudyRecord updatedStudy,
            DashboardOverviewDto overview,
            VendorDeliveryJob updatedJob,
            List<VendorDeliveryJob> jobs,
            AiResultRecord updatedResult,
            List<AiResultRecord> aiResults,
            List<ForwardedPatientTransferDto> forwardedPatients,
            List<VendorMetricsRowDto> vendorMetrics,
            List<RisUploadedReport> risUploadedReports,
            List<OperationalAlertDto> alerts,
            AuditEvent latestAuditEvent
    ) {
        public LiveIncomingStudyEventDto(
                String eventId,
                UUID siteId,
                String eventType,
                UUID studyId,
                String studyInstanceUid,
                String patientId,
                String patientName,
                String modality,
                String studyDescription,
                String sourceType,
                String callingAeTitle,
                String receivingStatus,
                int uniqueStoredInstances,
                int receivedTransferEvents,
                int duplicateInstancesSkipped,
                String sopInstanceUid,
                String message,
                OffsetDateTime timestamp) {
            this(
                    eventId,
                    siteId,
                    eventType,
                    studyId,
                    studyInstanceUid,
                    patientId,
                    patientName,
                    modality,
                    studyDescription,
                    sourceType,
                    callingAeTitle,
                    receivingStatus,
                    uniqueStoredInstances,
                    receivedTransferEvents,
                    duplicateInstancesSkipped,
                    sopInstanceUid,
                    message,
                    timestamp,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        public LiveIncomingStudyEventDto withOverview(DashboardOverviewDto refreshedOverview) {
            return new LiveIncomingStudyEventDto(
                    eventId,
                    siteId,
                    eventType,
                    studyId,
                    studyInstanceUid,
                    patientId,
                    patientName,
                    modality,
                    studyDescription,
                    sourceType,
                    callingAeTitle,
                    receivingStatus,
                    uniqueStoredInstances,
                    receivedTransferEvents,
                    duplicateInstancesSkipped,
                    sopInstanceUid,
                    message,
                    timestamp,
                    updatedStudy,
                    refreshedOverview,
                    updatedJob,
                    jobs,
                    updatedResult,
                    aiResults,
                    forwardedPatients,
                    vendorMetrics,
                    risUploadedReports,
                    alerts,
                    latestAuditEvent);
        }

        public LiveIncomingStudyEventDto withVendorMetrics(List<VendorMetricsRowDto> replacedMetrics) {
            return new LiveIncomingStudyEventDto(
                    eventId,
                    siteId,
                    eventType,
                    studyId,
                    studyInstanceUid,
                    patientId,
                    patientName,
                    modality,
                    studyDescription,
                    sourceType,
                    callingAeTitle,
                    receivingStatus,
                    uniqueStoredInstances,
                    receivedTransferEvents,
                    duplicateInstancesSkipped,
                    sopInstanceUid,
                    message,
                    timestamp,
                    updatedStudy,
                    overview,
                    updatedJob,
                    jobs,
                    updatedResult,
                    aiResults,
                    forwardedPatients,
                    replacedMetrics,
                    risUploadedReports,
                    alerts,
                    latestAuditEvent);
        }
    }

    private record CachedOverview(long computedAtMillis, DashboardOverviewDto overview) {}
    private record SubscriberRegistration(SseEmitter emitter, boolean canViewConfig) {}

    private final StudyRecordRepository studyRecordRepository;
    private final InstanceRecordRepository instanceRecordRepository;
    private final VendorConfigRepository vendorConfigRepository;
    private final VendorDeliveryJobRepository jobRepository;
    private final VendorTransferAttemptRepository attemptRepository;
    private final AiResultRecordRepository aiResultRecordRepository;
    private final PseudonymMappingRepository pseudonymMappingRepository;
    private final RisUploadedReportRepository risUploadedReportRepository;

    private final Map<UUID, CopyOnWriteArrayList<SubscriberRegistration>> siteEmitters = new ConcurrentHashMap<>();
    private final Map<UUID, CopyOnWriteArrayList<LiveIncomingStudyEventDto>> recentEventsBySite = new ConcurrentHashMap<>();
    private final Map<UUID, CachedOverview> overviewCacheBySite = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cachedStudyBytesByStudyId = new ConcurrentHashMap<>();
    private final Map<UUID, Object> siteOverviewLocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSsePushMillisBySite = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, LiveIncomingStudyEventDto>> pendingCoalescedEventsBySite = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> pendingFlushFutureBySite = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dicomhub-sse-broadcaster");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public LiveStudyEventStreamService(
            StudyRecordRepository studyRecordRepository,
            InstanceRecordRepository instanceRecordRepository,
            VendorConfigRepository vendorConfigRepository,
            VendorDeliveryJobRepository jobRepository,
            VendorTransferAttemptRepository attemptRepository,
            AiResultRecordRepository aiResultRecordRepository,
            PseudonymMappingRepository pseudonymMappingRepository,
            RisUploadedReportRepository risUploadedReportRepository) {
        this.studyRecordRepository = studyRecordRepository;
        this.instanceRecordRepository = instanceRecordRepository;
        this.vendorConfigRepository = vendorConfigRepository;
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
        this.aiResultRecordRepository = aiResultRecordRepository;
        this.pseudonymMappingRepository = pseudonymMappingRepository;
        this.risUploadedReportRepository = risUploadedReportRepository;

        this.scheduler.scheduleAtFixedRate(
                this::sendHeartbeatToAllEmitters,
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    public boolean hasActiveSubscribers(UUID siteId) {
        if (siteId == null) {
            return false;
        }
        CopyOnWriteArrayList<SubscriberRegistration> list = siteEmitters.get(siteId);
        return list != null && !list.isEmpty();
    }

    public SseEmitter subscribe(UUID siteId) {
        return subscribe(siteId, true);
    }

    public SseEmitter subscribe(UUID siteId, boolean canViewConfig) {
        SseEmitter emitter = new SseEmitter(0L); // No timeout for continuous dashboard stream
        SubscriberRegistration registration = new SubscriberRegistration(emitter, canViewConfig);
        CopyOnWriteArrayList<SubscriberRegistration> list =
                siteEmitters.computeIfAbsent(siteId, k -> new CopyOnWriteArrayList<>());
        list.add(registration);

        emitter.onCompletion(() -> list.remove(registration));
        emitter.onTimeout(() -> list.remove(registration));
        emitter.onError(e -> list.remove(registration));

        try {
            DashboardOverviewDto overview = computeOverviewSafe(siteId);
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data(Map.of(
                            "siteId", siteId.toString(),
                            "status", "LIVE_STREAM_ACTIVE",
                            "timestamp", OffsetDateTime.now().toString(),
                            "overview", overview != null ? overview : Map.of())));
        } catch (Exception e) {
            list.remove(registration);
        }
        return emitter;
    }

    /**
     * Periodic 15-second SSE heartbeat comment (: heartbeat) to keep reverse-proxy / LAN
     * connections alive and evict any disconnected client emitters.
     */
    public void sendHeartbeatToAllEmitters() {
        for (Map.Entry<UUID, CopyOnWriteArrayList<SubscriberRegistration>> entry : siteEmitters.entrySet()) {
            CopyOnWriteArrayList<SubscriberRegistration> registrations = entry.getValue();
            if (registrations == null || registrations.isEmpty()) {
                continue;
            }
            List<SubscriberRegistration> dead = new ArrayList<>();
            for (SubscriberRegistration reg : registrations) {
                try {
                    reg.emitter().send(SseEmitter.event()
                            .comment("heartbeat")
                            .name("HEARTBEAT")
                            .data(Map.of("timestamp", OffsetDateTime.now().toString())));
                } catch (Exception e) {
                    dead.add(reg);
                }
            }
            if (!dead.isEmpty()) {
                registrations.removeAll(dead);
            }
        }
    }

    public void publishStudyEvent(
            UUID siteId,
            String eventType,
            StudyRecord study,
            String sopInstanceUid,
            String message) {
        if (study == null || siteId == null) {
            return;
        }
        try {
            boolean isStatusChange = "STUDY_RECEIVING_STATUS_CHANGED".equals(eventType);
            DashboardOverviewDto overview;
            List<VendorDeliveryJob> jobs = null;
            List<ForwardedPatientTransferDto> forwardedPatients = null;
            List<VendorMetricsRowDto> vendorMetrics = null;
            List<OperationalAlertDto> alerts = null;

            if (isStatusChange) {
                overviewCacheBySite.remove(siteId);
                overview = computeOverviewSafe(siteId);
                jobs = jobRepository != null ? jobRepository.findBySiteIdOrderByCreatedAtDesc(siteId) : null;
                forwardedPatients = computeForwardedPatientsSafe(siteId);
                vendorMetrics = computeVendorMetricsSafe(siteId, true);
                alerts = computeActiveAlertsSafe(siteId);
            } else {
                overview = getOrUpdateCoalescedOverview(siteId, eventType, study);
            }

            LiveIncomingStudyEventDto dto = new LiveIncomingStudyEventDto(
                    UUID.randomUUID().toString(),
                    siteId,
                    eventType,
                    study.getId(),
                    study.getStudyInstanceUid(),
                    study.getPatientId() != null ? study.getPatientId() : "UNKNOWN",
                    formatDicomPersonName(study.getPatientName()),
                    study.getModality(),
                    study.getStudyDescription() != null ? study.getStudyDescription() : study.getModality(),
                    study.getSourceType(),
                    study.getCallingAeTitle(),
                    study.getReceivingStatus() != null ? study.getReceivingStatus() : "RECEIVING",
                    study.getInstanceCount(),
                    study.getReceivedTransferEvents(),
                    study.getDuplicateInstancesCount(),
                    sopInstanceUid != null ? sopInstanceUid : "",
                    message,
                    OffsetDateTime.now(),
                    study,
                    overview,
                    null,
                    jobs,
                    null,
                    null,
                    forwardedPatients,
                    vendorMetrics,
                    null,
                    alerts,
                    null
            );

            recordInHistory(siteId, dto);

            if (isStatusChange) {
                Map<UUID, LiveIncomingStudyEventDto> sitePending = pendingCoalescedEventsBySite.get(siteId);
                if (sitePending != null && study.getId() != null) {
                    sitePending.remove(study.getId());
                }
                flushPendingCoalescedEventsForSite(siteId);
                cancelPendingFlush(siteId);
                lastSsePushMillisBySite.put(siteId, System.currentTimeMillis());
                broadcastToSiteEmitters(siteId, eventType, dto);
            } else {
                broadcastCoalesced(siteId, eventType, dto);
            }
        } catch (Exception ignored) {
        }
    }

    public void publishJobOrResultEvent(
            UUID siteId,
            StudyRecord study,
            VendorDeliveryJob updatedJob,
            AiResultRecord updatedResult,
            String message) {
        if (siteId == null) {
            return;
        }
        try {
            overviewCacheBySite.remove(siteId);
            StudyRecord effectiveStudy = study;
            if (effectiveStudy == null && updatedJob != null && updatedJob.getStudyId() != null && studyRecordRepository != null) {
                effectiveStudy = studyRecordRepository.findById(updatedJob.getStudyId()).orElse(null);
            } else if (effectiveStudy == null && updatedResult != null && updatedResult.getStudyId() != null && studyRecordRepository != null) {
                effectiveStudy = studyRecordRepository.findById(updatedResult.getStudyId()).orElse(null);
            }

            DashboardOverviewDto overview = computeOverviewSafe(siteId);
            List<VendorDeliveryJob> jobs = jobRepository != null
                    ? jobRepository.findBySiteIdOrderByCreatedAtDesc(siteId)
                    : null;
            List<AiResultRecord> aiResults = aiResultRecordRepository != null
                    ? aiResultRecordRepository.findBySiteIdOrderByReceivedAtDesc(siteId)
                    : null;
            List<ForwardedPatientTransferDto> forwardedPatients = computeForwardedPatientsSafe(siteId);
            List<VendorMetricsRowDto> vendorMetrics = computeVendorMetricsSafe(siteId, true);
            List<RisUploadedReport> risReports = risUploadedReportRepository != null
                    ? risUploadedReportRepository.findBySiteIdOrderByUploadedAtDesc(siteId)
                    : null;
            List<OperationalAlertDto> alerts = computeActiveAlertsSafe(siteId);

            LiveIncomingStudyEventDto dto = new LiveIncomingStudyEventDto(
                    UUID.randomUUID().toString(),
                    siteId,
                    "JOB_OR_RESULT_UPDATED",
                    effectiveStudy != null ? effectiveStudy.getId() : null,
                    effectiveStudy != null ? effectiveStudy.getStudyInstanceUid() : "",
                    effectiveStudy != null && effectiveStudy.getPatientId() != null ? effectiveStudy.getPatientId() : "UNKNOWN",
                    effectiveStudy != null ? formatDicomPersonName(effectiveStudy.getPatientName()) : "KAUVERY PATIENT",
                    effectiveStudy != null ? effectiveStudy.getModality() : "DX",
                    effectiveStudy != null && effectiveStudy.getStudyDescription() != null
                            ? effectiveStudy.getStudyDescription()
                            : "",
                    effectiveStudy != null ? effectiveStudy.getSourceType() : "RIS_PACS",
                    effectiveStudy != null ? effectiveStudy.getCallingAeTitle() : "DICOMHUB_AE",
                    effectiveStudy != null && effectiveStudy.getReceivingStatus() != null
                            ? effectiveStudy.getReceivingStatus()
                            : "COMPLETED",
                    effectiveStudy != null ? effectiveStudy.getInstanceCount() : 0,
                    effectiveStudy != null ? effectiveStudy.getReceivedTransferEvents() : 0,
                    effectiveStudy != null ? effectiveStudy.getDuplicateInstancesCount() : 0,
                    "",
                    message,
                    OffsetDateTime.now(),
                    effectiveStudy,
                    overview,
                    updatedJob,
                    jobs,
                    updatedResult,
                    aiResults,
                    forwardedPatients,
                    vendorMetrics,
                    risReports,
                    alerts,
                    null
            );

            if (effectiveStudy != null) {
                recordInHistory(siteId, dto);
                Map<UUID, LiveIncomingStudyEventDto> sitePending = pendingCoalescedEventsBySite.get(siteId);
                if (sitePending != null && effectiveStudy.getId() != null) {
                    sitePending.remove(effectiveStudy.getId());
                }
            }
            flushPendingCoalescedEventsForSite(siteId);
            cancelPendingFlush(siteId);
            lastSsePushMillisBySite.put(siteId, System.currentTimeMillis());
            broadcastToSiteEmitters(siteId, "JOB_OR_RESULT_UPDATED", dto);
        } catch (Exception ignored) {
        }
    }

    public void publishAuditOrAlertEvent(AuditEvent auditEvent) {
        if (auditEvent == null || auditEvent.getSiteId() == null) {
            return;
        }
        UUID siteId = auditEvent.getSiteId();
        overviewCacheBySite.remove(siteId);
        if (!hasActiveSubscribers(siteId)) {
            return;
        }
        try {
            DashboardOverviewDto overview = computeOverviewSafe(siteId);
            List<OperationalAlertDto> alerts = computeActiveAlertsSafe(siteId);
            List<RisUploadedReport> risReports = "RisUploadedReport".equalsIgnoreCase(auditEvent.getEntityType())
                    && risUploadedReportRepository != null
                    ? risUploadedReportRepository.findBySiteIdOrderByUploadedAtDesc(siteId)
                    : null;

            LiveIncomingStudyEventDto dto = new LiveIncomingStudyEventDto(
                    UUID.randomUUID().toString(),
                    siteId,
                    "ALERT_OR_AUDIT_CREATED",
                    null,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    0,
                    0,
                    0,
                    "",
                    auditEvent.getAction() + " (" + auditEvent.getOutcome() + ")",
                    OffsetDateTime.now(),
                    null,
                    overview,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    risReports,
                    alerts,
                    auditEvent
            );
            broadcastToSiteEmitters(siteId, "ALERT_OR_AUDIT_CREATED", dto);
        } catch (Exception ignored) {
        }
    }

    private void recordInHistory(UUID siteId, LiveIncomingStudyEventDto dto) {
        CopyOnWriteArrayList<LiveIncomingStudyEventDto> history =
                recentEventsBySite.computeIfAbsent(siteId, k -> new CopyOnWriteArrayList<>());
        history.addFirst(dto);
        while (history.size() > 60) {
            history.removeLast();
        }
    }

    public int getPendingCoalescedStudyCount(UUID siteId) {
        Map<UUID, LiveIncomingStudyEventDto> pending = pendingCoalescedEventsBySite.get(siteId);
        return pending != null ? pending.size() : 0;
    }

    private void cancelPendingFlush(UUID siteId) {
        pendingCoalescedEventsBySite.remove(siteId);
        ScheduledFuture<?> future = pendingFlushFutureBySite.remove(siteId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void flushPendingCoalescedEventsForSite(UUID siteId) {
        Map<UUID, LiveIncomingStudyEventDto> pendingByStudy = pendingCoalescedEventsBySite.remove(siteId);
        if (pendingByStudy == null || pendingByStudy.isEmpty()) {
            return;
        }
        List<LiveIncomingStudyEventDto> snapshotToFlush;
        synchronized (pendingByStudy) {
            snapshotToFlush = new ArrayList<>(pendingByStudy.values());
            pendingByStudy.clear();
        }
        if (snapshotToFlush.isEmpty()) {
            return;
        }
        overviewCacheBySite.remove(siteId);
        DashboardOverviewDto authoritativeOverview = computeOverviewSafe(siteId);
        lastSsePushMillisBySite.put(siteId, System.currentTimeMillis());
        CopyOnWriteArrayList<LiveIncomingStudyEventDto> history = recentEventsBySite.get(siteId);
        for (LiveIncomingStudyEventDto item : snapshotToFlush) {
            LiveIncomingStudyEventDto enriched = authoritativeOverview != null
                    ? item.withOverview(authoritativeOverview)
                    : item;
            if (history != null) {
                for (int i = 0; i < history.size(); i++) {
                    if (history.get(i).eventId().equals(enriched.eventId())) {
                        history.set(i, enriched);
                        break;
                    }
                }
            }
            broadcastToSiteEmitters(siteId, enriched.eventType(), enriched);
        }
    }

    private void broadcastCoalesced(UUID siteId, String eventType, LiveIncomingStudyEventDto dto) {
        if (!hasActiveSubscribers(siteId)) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastPush = lastSsePushMillisBySite.getOrDefault(siteId, 0L);
        if (now - lastPush >= COALESCE_WINDOW_MS) {
            flushPendingCoalescedEventsForSite(siteId);
            cancelPendingFlush(siteId);
            lastSsePushMillisBySite.put(siteId, now);
            broadcastToSiteEmitters(siteId, eventType, dto);
        } else {
            UUID studyKey = dto.studyId() != null ? dto.studyId() : UUID.randomUUID();
            Map<UUID, LiveIncomingStudyEventDto> sitePending = pendingCoalescedEventsBySite.computeIfAbsent(
                    siteId, k -> java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>()));
            sitePending.put(studyKey, dto);
            pendingFlushFutureBySite.computeIfAbsent(siteId, sid ->
                    scheduler.schedule(() -> {
                        pendingFlushFutureBySite.remove(sid);
                        flushPendingCoalescedEventsForSite(sid);
                    }, COALESCE_WINDOW_MS, TimeUnit.MILLISECONDS));
        }
    }

    public LiveIncomingStudyEventDto maskDtoForRestrictedSubscriber(LiveIncomingStudyEventDto dto) {
        if (dto == null || dto.vendorMetrics() == null || dto.vendorMetrics().isEmpty()) {
            return dto;
        }
        List<VendorMetricsRowDto> maskedMetrics = dto.vendorMetrics().stream()
                .map(m -> new VendorMetricsRowDto(
                        m.vendorId(),
                        m.vendorCode(),
                        m.vendorName(),
                        m.useCaseName(),
                        m.destinationType(),
                        "RESTRICTED",
                        "RESTRICTED",
                        0,
                        m.shadowMode(),
                        m.studiesRoutedToVendor(),
                        m.modalityOriginRouted(),
                        m.pacsOriginRouted(),
                        m.uploadAttempts(),
                        m.vendorConfirmedUploads(),
                        m.totalImagesTransferred(),
                        m.failedUploads(),
                        m.currentlyQueued(),
                        m.vendorProcessing(),
                        m.resultsReceived(),
                        m.resultsReturnedToPacs(),
                        m.slaMinutes(),
                        m.avgTurnaroundSeconds(),
                        m.totalBytesTransferred()))
                .toList();
        return dto.withVendorMetrics(maskedMetrics);
    }

    private void broadcastToSiteEmitters(UUID siteId, String eventName, LiveIncomingStudyEventDto dto) {
        CopyOnWriteArrayList<SubscriberRegistration> registrations = siteEmitters.get(siteId);
        if (registrations == null || registrations.isEmpty()) {
            return;
        }
        LiveIncomingStudyEventDto restrictedDto = null;
        List<SubscriberRegistration> dead = new ArrayList<>();
        for (SubscriberRegistration reg : registrations) {
            try {
                LiveIncomingStudyEventDto payloadToSend = reg.canViewConfig()
                        ? dto
                        : (restrictedDto != null ? restrictedDto : (restrictedDto = maskDtoForRestrictedSubscriber(dto)));
                reg.emitter().send(SseEmitter.event()
                        .name(eventName)
                        .data(payloadToSend));
            } catch (Exception e) {
                dead.add(reg);
            }
        }
        if (!dead.isEmpty()) {
            registrations.removeAll(dead);
        }
    }

    public List<LiveIncomingStudyEventDto> getRecentEvents(UUID siteId) {
        return recentEventsBySite.getOrDefault(siteId, new CopyOnWriteArrayList<>());
    }

    private DashboardOverviewDto getOrUpdateCoalescedOverview(
            UUID siteId,
            String eventType,
            StudyRecord study) {
        Object siteLock = siteOverviewLocks.computeIfAbsent(siteId, k -> new Object());
        synchronized (siteLock) {
            long now = System.currentTimeMillis();
            CachedOverview cached = overviewCacheBySite.get(siteId);
            if (cached == null || cached.overview() == null || (now - cached.computedAtMillis()) > COALESCE_WINDOW_MS) {
                DashboardOverviewDto fresh = computeOverviewSafe(siteId);
                if (fresh != null) {
                    overviewCacheBySite.put(siteId, new CachedOverview(now, fresh));
                }
                return fresh;
            }

            DashboardOverviewDto prev = cached.overview();
            boolean isDup = "DUPLICATE_INSTANCE_SKIPPED".equals(eventType);
            boolean isRisPacs = "RIS_PACS".equalsIgnoreCase(study.getSourceType());
            boolean isFirstSliceOfStudy = study.getInstanceCount() == 1 && !isDup;

            long totalStudies = prev.totalStudiesReceived() + (isFirstSliceOfStudy ? 1 : 0);
            long modalityStudies = prev.modalityOriginStudies() + (isFirstSliceOfStudy && !isRisPacs ? 1 : 0);
            long pacsStudies = prev.pacsOriginStudies() + (isFirstSliceOfStudy && isRisPacs ? 1 : 0);
            long awaitingCompletion = prev.studiesAwaitingCompletion() + (isFirstSliceOfStudy ? 1 : 0);
            long currentlyReceiving = prev.studiesCurrentlyReceiving() + (isFirstSliceOfStudy ? 1 : 0);

            long totalImages = prev.totalImagesReceived() + (isDup ? 0 : 1);
            long todayImages = prev.imagesReceivedToday() + (isDup ? 0 : 1);
            long transferEvents = prev.totalTransferEventsReceived() + 1;
            long dupSkipped = prev.duplicateInstancesSkipped() + (isDup ? 1 : 0);
            long risPacsImages = prev.risPacsImagesReceived() + (!isDup && isRisPacs ? 1 : 0);
            long modalityImages = prev.modalityImagesReceived() + (!isDup && !isRisPacs ? 1 : 0);

            Long prevStudyBytes = study.getId() != null
                    ? cachedStudyBytesByStudyId.put(study.getId(), study.getTotalBytes())
                    : null;
            long bytesDelta = isDup
                    ? 0L
                    : Math.max(0L, study.getTotalBytes() - (prevStudyBytes != null ? prevStudyBytes : 0L));
            long origBytes = prev.originalRepoTotalBytes() + bytesDelta;

            DashboardOverviewDto incremented = new DashboardOverviewDto(
                    siteId,
                    totalStudies,
                    modalityStudies,
                    pacsStudies,
                    awaitingCompletion,
                    prev.studiesRouted(),
                    prev.vendorConfirmedUploads(),
                    prev.vendorProcessingCount(),
                    prev.resultsReceivedCount(),
                    prev.resultsReturnedToPacsCount(),
                    prev.failedOrQuarantinedJobs(),
                    prev.validationFailedStudies(),
                    origBytes,
                    prev.vendorTransferredTotalBytes(),
                    prev.activeAlertsCount(),
                    totalImages,
                    todayImages,
                    transferEvents,
                    dupSkipped,
                    risPacsImages,
                    modalityImages,
                    currentlyReceiving,
                    prev.studiesCompletedReceiving(),
                    prev.studiesOnHoldReceiving(),
                    prev.studiesFailedReceiving(),
                    prev.studiesIncompleteReceiving()
            );
            overviewCacheBySite.put(siteId, new CachedOverview(cached.computedAtMillis(), incremented));
            return incremented;
        }
    }

    public DashboardOverviewDto computeOverviewSafe(UUID sid) {
        if (studyRecordRepository == null || jobRepository == null
                || aiResultRecordRepository == null || instanceRecordRepository == null) {
            return null;
        }
        List<StudyRecord> siteStudies = studyRecordRepository.findBySiteIdOrderByCreatedAtDesc(sid);
        for (StudyRecord s : siteStudies) {
            if (s.getId() != null) {
                cachedStudyBytesByStudyId.put(s.getId(), s.getTotalBytes());
            }
        }
        long total = siteStudies.size();
        long modalityCount = siteStudies.stream()
                .filter(s -> "MODALITY".equalsIgnoreCase(s.getSourceType())).count();
        long pacsCount = siteStudies.stream()
                .filter(s -> "RIS_PACS".equalsIgnoreCase(s.getSourceType())).count();
        long waiting = studyRecordRepository.countBySiteIdAndLifecycleState(sid, "WAITING_FOR_COMPLETION");
        long routed = studyRecordRepository.countBySiteIdAndLifecycleState(sid, "ROUTED");
        long validationFailed = studyRecordRepository.countBySiteIdAndLifecycleState(sid, "VALIDATION_FAILED");
        long vendorAccepted = jobRepository.countBySiteIdAndStatus(sid, "VENDOR_ACCEPTED")
                + jobRepository.countBySiteIdAndStatus(sid, "COMPLETED");
        long processing = jobRepository.countBySiteIdAndStatus(sid, "VENDOR_ACCEPTED")
                + jobRepository.countBySiteIdAndStatus(sid, "PROCESSING");
        long resultsReceived = aiResultRecordRepository.countBySiteId(sid);
        long resultsReturned = aiResultRecordRepository.countBySiteIdAndPacsForwardedTrue(sid);
        long failedOrQuarantined = jobRepository.countBySiteIdAndStatus(sid, "FAILED")
                + jobRepository.countBySiteIdAndStatus(sid, "DEIDENTIFICATION_FAILED")
                + jobRepository.countBySiteIdAndStatus(sid, "RESULT_VALIDATION_FAILED")
                + jobRepository.countBySiteIdAndStatus(sid, "MANUAL_REVIEW")
                + jobRepository.countBySiteIdAndStatus(sid, "RETRY_WAIT");

        long origBytes = siteStudies.stream()
                .mapToLong(StudyRecord::getTotalBytes).sum();
        long vendorBytes = jobRepository.findBySiteIdOrderByCreatedAtDesc(sid).stream()
                .mapToLong(VendorDeliveryJob::getUploadedBytes).sum();
        long alertsCount = failedOrQuarantined + validationFailed;

        long totalImagesFromStudies = siteStudies.stream().mapToLong(StudyRecord::getInstanceCount).sum();
        long totalInstanceRows = instanceRecordRepository.countBySiteId(sid);
        long totalImagesReceived = Math.max(totalImagesFromStudies, totalInstanceRows);

        OffsetDateTime startOfToday = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault())
                .toOffsetDateTime();
        long imagesReceivedToday = instanceRecordRepository
                .countBySiteIdAndReceivedAtGreaterThanEqual(sid, startOfToday);

        long totalTransferEventsReceived = siteStudies.stream()
                .mapToLong(s -> Math.max(s.getInstanceCount(), s.getReceivedTransferEvents()))
                .sum();
        long duplicateInstancesSkipped = siteStudies.stream()
                .mapToLong(StudyRecord::getDuplicateInstancesCount)
                .sum();
        long risPacsImagesReceived = siteStudies.stream()
                .filter(s -> "RIS_PACS".equalsIgnoreCase(s.getSourceType()))
                .mapToLong(StudyRecord::getInstanceCount)
                .sum();
        long modalityImagesReceived = siteStudies.stream()
                .filter(s -> "MODALITY".equalsIgnoreCase(s.getSourceType()))
                .mapToLong(StudyRecord::getInstanceCount)
                .sum();

        long studiesCurrentlyReceiving = siteStudies.stream()
                .filter(s -> "RECEIVING".equalsIgnoreCase(s.getReceivingStatus())
                        || "WAITING_FOR_COMPLETION".equalsIgnoreCase(s.getLifecycleState()))
                .count();
        long studiesOnHoldReceiving = siteStudies.stream()
                .filter(s -> "ON_HOLD".equalsIgnoreCase(s.getReceivingStatus())
                        || ("UNMATCHED_NO_RULE".equalsIgnoreCase(s.getLifecycleState())
                                && !"RECEIVING".equalsIgnoreCase(s.getReceivingStatus())
                                && !"FAILED".equalsIgnoreCase(s.getReceivingStatus())
                                && !"INCOMPLETE".equalsIgnoreCase(s.getReceivingStatus())))
                .count();
        long studiesFailedReceiving = siteStudies.stream()
                .filter(s -> "FAILED".equalsIgnoreCase(s.getReceivingStatus())
                        || "VALIDATION_FAILED".equalsIgnoreCase(s.getLifecycleState()))
                .count();
        long studiesIncompleteReceiving = siteStudies.stream()
                .filter(s -> "INCOMPLETE".equalsIgnoreCase(s.getReceivingStatus())
                        || "INCOMPLETE".equalsIgnoreCase(s.getLifecycleState()))
                .count();
        long studiesCompletedReceiving = Math.max(
                0L,
                total - studiesCurrentlyReceiving - studiesOnHoldReceiving - studiesFailedReceiving - studiesIncompleteReceiving);

        return new DashboardOverviewDto(
                sid, total, modalityCount, pacsCount, waiting, routed,
                vendorAccepted, processing, resultsReceived, resultsReturned,
                failedOrQuarantined, validationFailed, origBytes, vendorBytes, alertsCount,
                totalImagesReceived, imagesReceivedToday, totalTransferEventsReceived,
                duplicateInstancesSkipped, risPacsImagesReceived, modalityImagesReceived,
                studiesCurrentlyReceiving, studiesCompletedReceiving,
                studiesOnHoldReceiving, studiesFailedReceiving, studiesIncompleteReceiving);
    }

    public List<VendorMetricsRowDto> computeVendorMetricsSafe(UUID sid, boolean canViewConfig) {
        if (vendorConfigRepository == null || studyRecordRepository == null
                || jobRepository == null || attemptRepository == null) {
            return List.of();
        }
        List<VendorConfig> vendors = vendorConfigRepository.findBySiteIdOrderByVendorCodeAsc(sid);
        Map<UUID, StudyRecord> studyMap = studyRecordRepository.findBySiteIdOrderByCreatedAtDesc(sid).stream()
                .collect(Collectors.toMap(StudyRecord::getId, s -> s, (a, b) -> a));
        List<VendorMetricsRowDto> rows = new ArrayList<>();

        for (VendorConfig v : vendors) {
            List<VendorDeliveryJob> jobs = jobRepository.findBySiteIdAndVendorId(sid, v.getId());
            long modalityOrigin = jobs.stream()
                    .map(j -> studyMap.get(j.getStudyId()))
                    .filter(s -> s != null && "MODALITY".equalsIgnoreCase(s.getSourceType()))
                    .count();
            long pacsOrigin = jobs.stream()
                    .map(j -> studyMap.get(j.getStudyId()))
                    .filter(s -> s != null && "RIS_PACS".equalsIgnoreCase(s.getSourceType()))
                    .count();
            long attempts = jobs.stream().mapToLong(j -> attemptRepository.countBySiteIdAndJobId(sid, j.getId())).sum();
            long confirmed = jobs.stream().filter(j -> j.getVendorAcceptedAt() != null).count();
            long failed = jobs.stream().filter(j ->
                    "FAILED".equals(j.getStatus())
                            || "DEIDENTIFICATION_FAILED".equals(j.getStatus())
                            || "RESULT_VALIDATION_FAILED".equals(j.getStatus())
                            || "MANUAL_REVIEW".equals(j.getStatus())).count();
            long queued = jobs.stream().filter(j ->
                    "QUEUED".equals(j.getStatus()) || "RETRY_WAIT".equals(j.getStatus())).count();
            long processing = jobs.stream().filter(j ->
                    "VENDOR_ACCEPTED".equals(j.getStatus()) || "PROCESSING".equals(j.getStatus())).count();
            long resReceived = jobs.stream().filter(j -> j.getResultReceivedAt() != null).count();
            long resReturned = jobs.stream().filter(j -> j.getPacsReturnedAt() != null).count();
            long bytes = jobs.stream().mapToLong(VendorDeliveryJob::getUploadedBytes).sum();
            long imagesTransferred = jobs.stream().mapToLong(j -> {
                if (j.getTransferredInstances() > 0) {
                    return j.getTransferredInstances();
                }
                if (j.getVendorAcceptedAt() != null) {
                    StudyRecord sr = studyMap.get(j.getStudyId());
                    return sr != null ? Math.max(1, sr.getInstanceCount()) : 1;
                }
                return 0L;
            }).sum();

            double avgTurnaroundSec = jobs.stream()
                    .filter(j -> j.getCreatedAt() != null && j.getResultReceivedAt() != null)
                    .mapToLong(j -> Math.max(1L, Duration.between(j.getCreatedAt(), j.getResultReceivedAt()).toSeconds()))
                    .average()
                    .orElse(0.0);

            rows.add(new VendorMetricsRowDto(
                    v.getId(),
                    v.getVendorCode(),
                    v.getVendorName(),
                    v.getUseCaseName(),
                    v.getDestinationType(),
                    canViewConfig ? v.getDestinationAeTitle() : "RESTRICTED",
                    canViewConfig ? v.getDestinationHost() : "RESTRICTED",
                    canViewConfig ? v.getDestinationPort() : 0,
                    v.isShadowMode(),
                    jobs.size(),
                    modalityOrigin,
                    pacsOrigin,
                    attempts,
                    confirmed,
                    imagesTransferred,
                    failed,
                    queued,
                    processing,
                    resReceived,
                    resReturned,
                    v.getSlaTurnaroundMinutes(),
                    avgTurnaroundSec,
                    bytes));
        }
        return rows;
    }

    public List<ForwardedPatientTransferDto> computeForwardedPatientsSafe(UUID sid) {
        if (studyRecordRepository == null || vendorConfigRepository == null
                || jobRepository == null || pseudonymMappingRepository == null) {
            return List.of();
        }
        List<StudyRecord> siteStudies = studyRecordRepository.findBySiteIdOrderByCreatedAtDesc(sid);
        Map<UUID, VendorConfig> vendorMap = vendorConfigRepository.findBySiteIdOrderByVendorCodeAsc(sid).stream()
                .collect(Collectors.toMap(VendorConfig::getId, v -> v, (a, b) -> a));

        List<ForwardedPatientTransferDto> rows = new ArrayList<>();

        for (StudyRecord study : siteStudies) {
            String region = study.getCanonicalRegion() != null && !study.getCanonicalRegion().isBlank()
                    ? study.getCanonicalRegion()
                    : ((study.getModality() != null ? study.getModality() : "DICOM") + " Study");
            List<VendorDeliveryJob> studyJobs = jobRepository.findBySiteIdAndStudyId(sid, study.getId());

            if (studyJobs.isEmpty()) {
                rows.add(new ForwardedPatientTransferDto(
                        null,
                        study.getId(),
                        sid,
                        study.getPatientId() != null ? study.getPatientId() : "UNKNOWN",
                        formatDicomPersonName(study.getPatientName()),
                        study.getPatientAge() != null ? study.getPatientAge() : "",
                        study.getPatientSex() != null ? study.getPatientSex() : "",
                        study.getAccessionNumber() != null ? study.getAccessionNumber() : "",
                        "—",
                        study.getModality(),
                        study.getStudyDescription(),
                        region,
                        study.getBodyPartExamined(),
                        study.getSourceType(),
                        study.getCallingAeTitle(),
                        null,
                        "—",
                        "Not Forwarded (" + study.getLifecycleState() + ")",
                        "NOT_FORWARDED",
                        "—",
                        "—",
                        0,
                        "—",
                        study.getInstanceCount(),
                        0,
                        0,
                        0L,
                        study.getLifecycleState(),
                        null,
                        null,
                        study.getCreatedAt()));
                continue;
            }

            for (VendorDeliveryJob job : studyJobs) {
                VendorConfig vendor = vendorMap.get(job.getVendorId());
                Optional<PseudonymMapping> pseudoOpt = pseudonymMappingRepository
                        .findBySiteIdAndStudyIdAndVendorId(sid, study.getId(), job.getVendorId());
                String pseudoPatientId = pseudoOpt.map(PseudonymMapping::getPseudoPatientId).orElse("—");

                int totalStudyImgs = Math.max(1, study.getInstanceCount());
                int selectedImgs = job.getSelectedInstances() > 0 ? job.getSelectedInstances() : totalStudyImgs;
                int transferredImgs = job.getTransferredInstances();
                if (transferredImgs == 0 && (job.getVendorAcceptedAt() != null
                        || "VENDOR_ACCEPTED".equals(job.getStatus())
                        || "COMPLETED".equals(job.getStatus()))) {
                    transferredImgs = selectedImgs;
                }

                String destType = vendor != null && vendor.getDestinationType() != null
                        ? vendor.getDestinationType()
                        : "DICOM_UPLOADER";
                String destAe = vendor != null && vendor.getDestinationAeTitle() != null
                        ? vendor.getDestinationAeTitle()
                        : "KAUVERY_AI_UPL";
                String destHost = vendor != null && vendor.getDestinationHost() != null
                        ? vendor.getDestinationHost()
                        : "10.10.30.20";
                Integer destPort = vendor != null && vendor.getDestinationPort() != null
                        ? vendor.getDestinationPort()
                        : 11115;

                rows.add(new ForwardedPatientTransferDto(
                        job.getId(),
                        study.getId(),
                        sid,
                        study.getPatientId() != null ? study.getPatientId() : "UNKNOWN",
                        formatDicomPersonName(study.getPatientName()),
                        study.getPatientAge() != null ? study.getPatientAge() : "",
                        study.getPatientSex() != null ? study.getPatientSex() : "",
                        study.getAccessionNumber() != null ? study.getAccessionNumber() : "",
                        pseudoPatientId,
                        study.getModality(),
                        study.getStudyDescription(),
                        region,
                        study.getBodyPartExamined(),
                        study.getSourceType(),
                        study.getCallingAeTitle(),
                        vendor != null ? vendor.getId() : job.getVendorId(),
                        vendor != null ? vendor.getVendorCode() : "UNKNOWN",
                        vendor != null ? vendor.getVendorName() : "Unknown Vendor",
                        destType,
                        destAe,
                        destHost,
                        destPort,
                        vendor != null ? vendor.getConnectorType() : "DICOM_CSTORE",
                        totalStudyImgs,
                        selectedImgs,
                        transferredImgs,
                        job.getUploadedBytes(),
                        job.getStatus(),
                        job.getVendorJobId(),
                        job.getVendorAcceptedAt() != null ? job.getVendorAcceptedAt() : job.getUpdatedAt(),
                        job.getCreatedAt()));
            }
        }
        return rows;
    }

    public List<OperationalAlertDto> computeActiveAlertsSafe(UUID sid) {
        if (studyRecordRepository == null || jobRepository == null) {
            return List.of();
        }
        List<OperationalAlertDto> alerts = new ArrayList<>();

        for (StudyRecord s : studyRecordRepository.findBySiteIdAndLifecycleState(sid, "VALIDATION_FAILED")) {
            alerts.add(new OperationalAlertDto(
                    "VAL-" + s.getId(),
                    "CRITICAL",
                    "STUDY_VALIDATION",
                    "FR-07 Study Validation Failure (" + s.getModality() + ")",
                    s.getValidationError() != null ? s.getValidationError() : "Study failed DICOM pre-flight validation",
                    "StudyRecord",
                    s.getId().toString(),
                    s.getCreatedAt()));
        }

        for (VendorDeliveryJob j : jobRepository.findBySiteIdOrderByCreatedAtDesc(sid)) {
            if ("DEIDENTIFICATION_FAILED".equals(j.getStatus())) {
                alerts.add(new OperationalAlertDto(
                        "DEID-" + j.getId(),
                        "CRITICAL",
                        "PRIVACY_GATE_FR14",
                        "PS3.15 / FR-14 De-identification Gate Blocked Outbound Transfer",
                        j.getLastError() != null ? j.getLastError() : "Burned-in PHI or de-identification failure",
                        "VendorDeliveryJob",
                        j.getId().toString(),
                        j.getUpdatedAt()));
            } else if ("RETRY_WAIT".equals(j.getStatus()) || "FAILED".equals(j.getStatus())) {
                alerts.add(new OperationalAlertDto(
                        "RETRY-" + j.getId(),
                        "WARNING",
                        "VENDOR_OUTAGE_AC8",
                        "Vendor Delivery " + j.getStatus() + " (Attempt " + j.getAttemptCount() + "/" + j.getMaxAttempts() + ")",
                        j.getLastError() != null ? j.getLastError() : "Transient vendor transfer error",
                        "VendorDeliveryJob",
                        j.getId().toString(),
                        j.getUpdatedAt()));
            } else if ("RESULT_VALIDATION_FAILED".equals(j.getStatus()) || "MANUAL_REVIEW".equals(j.getStatus())) {
                alerts.add(new OperationalAlertDto(
                        "QUAR-" + j.getId(),
                        "CRITICAL",
                        "RESULT_QUARANTINE",
                        "Returned AI Result Quarantined (" + j.getStatus() + ")",
                        j.getLastError() != null ? j.getLastError() : "Result failed FR-19/FR-20 re-identification check",
                        "VendorDeliveryJob",
                        j.getId().toString(),
                        j.getUpdatedAt()));
            }
        }
        return alerts;
    }

    private String formatDicomPersonName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "KAUVERY PATIENT";
        }
        return rawName.replace('^', ' ').replaceAll("\\s+", " ").trim();
    }
}
