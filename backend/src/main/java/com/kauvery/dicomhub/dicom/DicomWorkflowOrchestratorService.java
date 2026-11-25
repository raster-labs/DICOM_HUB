package com.kauvery.dicomhub.dicom;

import com.kauvery.dicomhub.audit.AuditService;
import com.kauvery.dicomhub.config.HubProperties;
import com.kauvery.dicomhub.deid.DicomPs315DeidentifierService;
import com.kauvery.dicomhub.deid.DicomPs315DeidentifierService.DeidentificationOutcome;
import com.kauvery.dicomhub.domain.entity.AiResultRecord;
import com.kauvery.dicomhub.domain.entity.DeidProfile;
import com.kauvery.dicomhub.domain.entity.DicomSource;
import com.kauvery.dicomhub.domain.entity.InstanceRecord;
import com.kauvery.dicomhub.domain.entity.PacsDestination;
import com.kauvery.dicomhub.domain.entity.PseudonymMapping;
import com.kauvery.dicomhub.domain.entity.RoutingRule;
import com.kauvery.dicomhub.domain.entity.Site;
import com.kauvery.dicomhub.domain.entity.StudyRecord;
import com.kauvery.dicomhub.domain.entity.VendorConfig;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.domain.entity.VendorTransferAttempt;
import com.kauvery.dicomhub.domain.repository.AiResultRecordRepository;
import com.kauvery.dicomhub.domain.repository.CleanupPolicyRepository;
import com.kauvery.dicomhub.domain.repository.DeidProfileRepository;
import com.kauvery.dicomhub.domain.repository.DicomSourceRepository;
import com.kauvery.dicomhub.domain.repository.InstanceRecordRepository;
import com.kauvery.dicomhub.domain.repository.PacsDestinationRepository;
import com.kauvery.dicomhub.domain.repository.PseudonymMappingRepository;
import com.kauvery.dicomhub.domain.repository.RoutingRuleRepository;
import com.kauvery.dicomhub.domain.repository.SiteRepository;
import com.kauvery.dicomhub.domain.repository.StudyRecordRepository;
import com.kauvery.dicomhub.domain.repository.VendorConfigRepository;
import com.kauvery.dicomhub.domain.repository.VendorDeliveryJobRepository;
import com.kauvery.dicomhub.domain.repository.VendorTransferAttemptRepository;
import com.kauvery.dicomhub.llm.LlmPromptGatewayService;
import com.kauvery.dicomhub.report.ClinicalPdfReportGenerator;
import com.kauvery.dicomhub.report.ClinicalPdfReportGenerator.GeneratedPdfReport;
import com.kauvery.dicomhub.report.ReportIntegrationDispatcher;
import com.kauvery.dicomhub.report.RisTeleradUploadReportService;
import com.kauvery.dicomhub.report.RisTeleradUploadReportService.UploadReportServletResult;
import com.kauvery.dicomhub.routing.RoutingEngineService;
import com.kauvery.dicomhub.security.PseudonymCryptoService;
import com.kauvery.dicomhub.vendor.DicomCStoreVendorConnector;
import com.kauvery.dicomhub.vendor.DicomXrayToPngConverter;
import com.kauvery.dicomhub.vendor.DicomXrayToPngConverter.ReidentifiedDicomImageResult;
import com.kauvery.dicomhub.vendor.GrokAiXrayVendorConnector;
import com.kauvery.dicomhub.vendor.GrokAiXrayVendorConnector.GrokVisionAnalysisOutput;
import com.kauvery.dicomhub.vendor.VendorConnectorRegistry;
import com.kauvery.dicomhub.vendor.spi.VendorUploadReceipt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates the complete DICOM Hub lifecycle:
 * 1. Original Repository storage with SHA-256 provenance commit
 * 2. Study completion & FR-07 validation
 * 3. Rule-based routing, FR-08 series selection & PS3.15 working copy de-identification
 * 4. Resilient outbound delivery with attempts & exponential backoff
 * 5. AI result validation, re-identification, "AI-generated – not verified" labeling & PACS return
 * 6. Independent per-vendor working copy cleanup
 */
@Service
public class DicomWorkflowOrchestratorService {

    public static final String AI_UNVERIFIED_LABEL = "AI-generated – not verified";

    private final HubProperties hubProperties;
    private final SiteRepository siteRepository;
    private final DicomSourceRepository dicomSourceRepository;
    private final PacsDestinationRepository pacsDestinationRepository;
    private final StudyRecordRepository studyRecordRepository;
    private final InstanceRecordRepository instanceRecordRepository;
    private final DeidProfileRepository deidProfileRepository;
    private final VendorConfigRepository vendorConfigRepository;
    private final RoutingRuleRepository routingRuleRepository;
    private final VendorDeliveryJobRepository jobRepository;
    private final VendorTransferAttemptRepository attemptRepository;
    private final PseudonymMappingRepository pseudonymMappingRepository;
    private final AiResultRecordRepository aiResultRecordRepository;
    private final CleanupPolicyRepository cleanupPolicyRepository;
    private final PseudonymCryptoService cryptoService;
    private final DicomPs315DeidentifierService deidentifierService;
    private final RoutingEngineService routingEngineService;
    private final VendorConnectorRegistry vendorConnectorRegistry;
    private final DicomCStoreVendorConnector dicomCStoreConnector;
    private final DicomXrayToPngConverter xrayToPngConverter;
    private final GrokAiXrayVendorConnector grokAiXrayVendorConnector;
    private final ClinicalPdfReportGenerator clinicalPdfReportGenerator;
    private final RisTeleradUploadReportService risUploadService;
    private final ReportIntegrationDispatcher reportDispatcher;
    private final AuditService auditService;
    private final LiveStudyEventStreamService liveStreamService;
    private final TransactionTemplate transactionTemplate;
    private final Set<UUID> activeJobUploads = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> studyIngestLocks = new ConcurrentHashMap<>();

    public DicomWorkflowOrchestratorService(
            HubProperties hubProperties,
            SiteRepository siteRepository,
            DicomSourceRepository dicomSourceRepository,
            PacsDestinationRepository pacsDestinationRepository,
            StudyRecordRepository studyRecordRepository,
            InstanceRecordRepository instanceRecordRepository,
            DeidProfileRepository deidProfileRepository,
            VendorConfigRepository vendorConfigRepository,
            RoutingRuleRepository routingRuleRepository,
            VendorDeliveryJobRepository jobRepository,
            VendorTransferAttemptRepository attemptRepository,
            PseudonymMappingRepository pseudonymMappingRepository,
            AiResultRecordRepository aiResultRecordRepository,
            CleanupPolicyRepository cleanupPolicyRepository,
            PseudonymCryptoService cryptoService,
            DicomPs315DeidentifierService deidentifierService,
            RoutingEngineService routingEngineService,
            VendorConnectorRegistry vendorConnectorRegistry,
            DicomCStoreVendorConnector dicomCStoreConnector,
            DicomXrayToPngConverter xrayToPngConverter,
            GrokAiXrayVendorConnector grokAiXrayVendorConnector,
            ClinicalPdfReportGenerator clinicalPdfReportGenerator,
            RisTeleradUploadReportService risUploadService,
            ReportIntegrationDispatcher reportDispatcher,
            AuditService auditService,
            LiveStudyEventStreamService liveStreamService,
            PlatformTransactionManager transactionManager) {
        this.hubProperties = hubProperties;
        this.siteRepository = siteRepository;
        this.dicomSourceRepository = dicomSourceRepository;
        this.pacsDestinationRepository = pacsDestinationRepository;
        this.studyRecordRepository = studyRecordRepository;
        this.instanceRecordRepository = instanceRecordRepository;
        this.deidProfileRepository = deidProfileRepository;
        this.vendorConfigRepository = vendorConfigRepository;
        this.routingRuleRepository = routingRuleRepository;
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
        this.pseudonymMappingRepository = pseudonymMappingRepository;
        this.aiResultRecordRepository = aiResultRecordRepository;
        this.cleanupPolicyRepository = cleanupPolicyRepository;
        this.cryptoService = cryptoService;
        this.deidentifierService = deidentifierService;
        this.routingEngineService = routingEngineService;
        this.vendorConnectorRegistry = vendorConnectorRegistry;
        this.dicomCStoreConnector = dicomCStoreConnector;
        this.xrayToPngConverter = xrayToPngConverter;
        this.grokAiXrayVendorConnector = grokAiXrayVendorConnector;
        this.clinicalPdfReportGenerator = clinicalPdfReportGenerator;
        this.risUploadService = risUploadService;
        this.reportDispatcher = reportDispatcher;
        this.auditService = auditService;
        this.liveStreamService = liveStreamService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private String inferSourceTypeFromAeTitle(String callingAeTitle) {
        if (callingAeTitle == null || callingAeTitle.isBlank()) {
            return "MODALITY";
        }
        String upper = callingAeTitle.trim().toUpperCase();
        if (upper.contains("PACS")
                || upper.contains("RIS")
                || upper.contains("ARCHIVE")
                || upper.contains("ROUTER")
                || upper.contains("DCM4CHE")
                || upper.contains("ORTHANC")
                || upper.contains("RASTER")) {
            return "RIS_PACS";
        }
        return "MODALITY";
    }

    public InstanceRecord ingestDicomInstance(
            UUID siteId,
            String callingAeTitle,
            String calledAeTitle,
            Attributes fmi,
            Attributes dataset) throws IOException {

        String studyUid = dataset.getString(Tag.StudyInstanceUID);
        String seriesUid = dataset.getString(Tag.SeriesInstanceUID);
        String sopUid = dataset.getString(Tag.SOPInstanceUID);
        if (studyUid == null || seriesUid == null || sopUid == null) {
            throw new IllegalArgumentException("Missing mandatory Study/Series/SOP Instance UID");
        }

        Object studyLock = studyIngestLocks.computeIfAbsent(siteId + ":" + studyUid, k -> new Object());
        synchronized (studyLock) {
            try {
                return transactionTemplate.execute(status -> {
                    try {
                        return ingestDicomInstanceInTransaction(
                                siteId, callingAeTitle, calledAeTitle, fmi, dataset, studyUid, seriesUid, sopUid);
                    } catch (IOException ioEx) {
                        throw new java.io.UncheckedIOException(ioEx);
                    }
                });
            } catch (java.io.UncheckedIOException uio) {
                throw uio.getCause();
            }
        }
    }

    private InstanceRecord ingestDicomInstanceInTransaction(
            UUID siteId,
            String callingAeTitle,
            String calledAeTitle,
            Attributes fmi,
            Attributes dataset,
            String studyUid,
            String seriesUid,
            String sopUid) throws IOException {

        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown siteId: " + siteId));

        Optional<DicomSource> sourceOpt = dicomSourceRepository
                .findBySiteIdAndCallingAeTitleAndActiveTrue(siteId, callingAeTitle);
        String sourceType = sourceOpt.map(DicomSource::getSourceType)
                .orElseGet(() -> inferSourceTypeFromAeTitle(callingAeTitle));
        UUID sourceId = sourceOpt.map(DicomSource::getId).orElse(null);

        String sopClassUid = dataset.getString(Tag.SOPClassUID, UID.CTImageStorage);
        String transferSyntax = fmi != null
                ? fmi.getString(Tag.TransferSyntaxUID, UID.ExplicitVRLittleEndian)
                : UID.ExplicitVRLittleEndian;

        // Serialize unmodified DICOM object to compute SHA-256 before committing to Original Repository
        Attributes effectiveFmi = fmi != null
                ? fmi
                : dataset.createFileMetaInformation(transferSyntax);
        byte[] rawBytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DicomOutputStream dos = new DicomOutputStream(baos, UID.ExplicitVRLittleEndian)) {
            dos.writeDataset(effectiveFmi, dataset);
            rawBytes = baos.toByteArray();
        }
        String sha256 = cryptoService.sha256Hex(rawBytes);

        // Check duplicate SOPInstanceUID within site (Specification Section 3.2 & 4.3 & FR-46 deduplication)
        Optional<InstanceRecord> existingInstance = instanceRecordRepository
                .findBySiteIdAndSopInstanceUid(siteId, sopUid);
        if (existingInstance.isPresent()) {
            InstanceRecord dup = existingInstance.get();
            dup.setDuplicateCount(dup.getDuplicateCount() + 1);
            if (!dup.getSha256Checksum().equals(sha256)) {
                auditService.record(
                        siteId, callingAeTitle, "DICOM_SOURCE", "STUDY_LIFECYCLE",
                        "DUPLICATE_SOP_CHECKSUM_CONFLICT", "InstanceRecord", dup.getId().toString(),
                        "BLOCKED", "{\"sopInstanceUid\":\"" + sopUid + "\"}");
                throw new IllegalStateException("Conflicting duplicate SOPInstanceUID with different SHA-256 checksum: " + sopUid);
            }
            InstanceRecord savedDup = instanceRecordRepository.save(dup);
            studyRecordRepository.findById(dup.getStudyId()).ifPresent(existingStudy -> {
                existingStudy.setReceivedTransferEvents(
                        Math.max(existingStudy.getInstanceCount(), existingStudy.getReceivedTransferEvents()) + 1);
                existingStudy.setDuplicateInstancesCount(existingStudy.getDuplicateInstancesCount() + 1);
                StudyRecord updatedStudy = studyRecordRepository.save(existingStudy);
                liveStreamService.publishStudyEvent(
                        siteId,
                        "DUPLICATE_INSTANCE_SKIPPED",
                        updatedStudy,
                        sopUid,
                        "Duplicate/retransmitted SOPInstanceUID " + sopUid + " detected from "
                                + callingAeTitle + " (" + updatedStudy.getSourceType()
                                + ") — skipped double-counting; unique stored instances remain "
                                + updatedStudy.getInstanceCount());
            });
            return savedDup;
        }

        // Write unmodified DICOM file to Original DICOM Repository and verify durable commit before incrementing count
        Path originalRepoFile = Path.of(
                hubProperties.storage().rootDir(),
                "original-repo",
                site.getSiteCode(),
                studyUid,
                seriesUid,
                sopUid + ".dcm");
        Files.createDirectories(originalRepoFile.getParent());
        Files.write(originalRepoFile, rawBytes);
        if (!Files.exists(originalRepoFile) || Files.size(originalRepoFile) != rawBytes.length) {
            throw new IOException("Original DICOM Repository commit verification failed for " + originalRepoFile);
        }

        // Upsert StudyRecord
        String rawPatientId = dataset.getString(Tag.PatientID, "UNKNOWN");
        String rawPatientName = dataset.getString(Tag.PatientName, "UNKNOWN");
        String rawPatientAge = dataset.getString(Tag.PatientAge, "");
        String rawPatientSex = dataset.getString(Tag.PatientSex, "");
        String rawAccession = dataset.getString(Tag.AccessionNumber, "");
        String rawModality = dataset.getString(Tag.Modality, "OT");
        String rawBodyPart = dataset.getString(Tag.BodyPartExamined, "").trim();
        String rawProtocolName = dataset.getString(Tag.ProtocolName, "").trim();
        String rawSeriesDesc = dataset.getString(Tag.SeriesDescription, "").trim();
        String rawStudyDesc = dataset.getString(Tag.StudyDescription, "").trim();
        if (rawStudyDesc.isBlank()) {
            if (!rawBodyPart.isBlank()) {
                rawStudyDesc = rawBodyPart;
            } else if (!rawProtocolName.isBlank()) {
                rawStudyDesc = rawProtocolName;
            } else if (!rawSeriesDesc.isBlank()) {
                rawStudyDesc = rawSeriesDesc;
            }
        }
        String effectiveStudyDesc = rawStudyDesc;
        String resolvedCanonicalRegion = routingEngineService.resolveCanonicalRegion(
                siteId, rawModality, effectiveStudyDesc, rawBodyPart, rawProtocolName);

        StudyRecord study = studyRecordRepository
                .findBySiteIdAndStudyInstanceUid(siteId, studyUid)
                .orElseGet(() -> {
                    StudyRecord sr = new StudyRecord();
                    sr.setId(UUID.randomUUID());
                    sr.setSiteId(siteId);
                    sr.setSourceId(sourceId);
                    sr.setSourceType(sourceType);
                    sr.setCallingAeTitle(callingAeTitle);
                    sr.setCalledAeTitle(calledAeTitle);
                    sr.setStudyInstanceUid(studyUid);
                    sr.setPatientIdHash(cryptoService.sha256Hex(rawPatientId));
                    sr.setAccessionHash(cryptoService.sha256Hex(rawAccession));
                    sr.setPatientId(rawPatientId);
                    sr.setPatientName(rawPatientName);
                    sr.setPatientAge(rawPatientAge);
                    sr.setPatientSex(rawPatientSex);
                    sr.setAccessionNumber(rawAccession);
                    sr.setCanonicalRegion(resolvedCanonicalRegion);
                    sr.setModality(rawModality);
                    sr.setStudyDescription(effectiveStudyDesc);
                    sr.setBodyPartExamined(rawBodyPart);
                    sr.setProtocolName(rawProtocolName);
                    sr.setLifecycleState("WAITING_FOR_COMPLETION");
                    sr.setReceivingStatus("RECEIVING");
                    sr.setCreatedAt(OffsetDateTime.now());
                    return sr;
                });

        if (study.getPatientId() == null || study.getPatientId().isBlank()) {
            study.setPatientId(rawPatientId);
        }
        if (study.getPatientName() == null || study.getPatientName().isBlank()) {
            study.setPatientName(rawPatientName);
        }
        if ((study.getStudyDescription() == null || study.getStudyDescription().isBlank())
                && !effectiveStudyDesc.isBlank()) {
            study.setStudyDescription(effectiveStudyDesc);
        }
        if ((study.getBodyPartExamined() == null || study.getBodyPartExamined().isBlank())
                && !rawBodyPart.isBlank()) {
            study.setBodyPartExamined(rawBodyPart);
        }
        if ((study.getProtocolName() == null || study.getProtocolName().isBlank())
                && !rawProtocolName.isBlank()) {
            study.setProtocolName(rawProtocolName);
        }
        if (study.getCanonicalRegion() == null
                || study.getCanonicalRegion().isBlank()
                || study.getCanonicalRegion().endsWith(" Study")) {
            study.setCanonicalRegion(resolvedCanonicalRegion);
        }

        boolean isLateArrival = "ROUTED".equals(study.getLifecycleState())
                || "UNMATCHED_NO_RULE".equals(study.getLifecycleState());
        study.setLastInstanceReceivedAt(OffsetDateTime.now());
        study.setInstanceCount(study.getInstanceCount() + 1);
        study.setReceivedTransferEvents(
                Math.max(study.getInstanceCount(), study.getReceivedTransferEvents() + 1));
        if (!isLateArrival) {
            study.setReceivingStatus("RECEIVING");
        }
        study.setTotalBytes(study.getTotalBytes() + rawBytes.length);
        study = studyRecordRepository.save(study);

        InstanceRecord instance = new InstanceRecord();
        instance.setId(UUID.randomUUID());
        instance.setSiteId(siteId);
        instance.setStudyId(study.getId());
        instance.setSeriesInstanceUid(seriesUid);
        instance.setSopInstanceUid(sopUid);
        instance.setSopClassUid(sopClassUid);
        instance.setTransferSyntaxUid(transferSyntax);
        instance.setSeriesDescription(dataset.getString(Tag.SeriesDescription, ""));
        if (dataset.containsValue(Tag.SliceThickness)) {
            instance.setSliceThicknessMm(dataset.getDouble(Tag.SliceThickness, 0.0));
        }
        instance.setBurnedInAnnotation(dataset.getString(Tag.BurnedInAnnotation, "NO"));
        instance.setOriginalRepoPath(originalRepoFile.toAbsolutePath().toString());
        instance.setSha256Checksum(sha256);
        instance.setFileSizeBytes(rawBytes.length);
        instance.setDuplicateCount(0);
        instance.setLateArrival(isLateArrival);
        instance.setReceivedAt(OffsetDateTime.now());
        instance = instanceRecordRepository.save(instance);

        if (isLateArrival) {
            auditService.record(
                    siteId, callingAeTitle, "DICOM_SOURCE", "STUDY_LIFECYCLE",
                    "LATE_ARRIVAL_INSTANCE_RECORDED", "InstanceRecord", instance.getId().toString(),
                    "SUCCESS", "{\"studyId\":\"" + study.getId() + "\",\"sopInstanceUid\":\"" + sopUid + "\"}");
        }

        // Update distinct series count on study
        List<InstanceRecord> allStudyInstances = instanceRecordRepository
                .findBySiteIdAndStudyIdOrderByReceivedAtAsc(siteId, study.getId());
        Set<String> seriesSet = allStudyInstances.stream()
                .map(InstanceRecord::getSeriesInstanceUid)
                .collect(Collectors.toSet());
        study.setSeriesCount(seriesSet.size());
        study = studyRecordRepository.save(study);

        auditService.record(
                siteId,
                callingAeTitle != null && !callingAeTitle.isBlank() ? callingAeTitle : "MODALITY_SCU",
                study.getSourceType(),
                "STUDY_LIFECYCLE",
                "STUDY_INSTANCE_COMMITTED",
                "StudyRecord",
                study.getId().toString(),
                "SUCCESS",
                "{\"studyId\":\"" + study.getId()
                        + "\",\"studyInstanceUid\":\"" + study.getStudyInstanceUid()
                        + "\",\"sopInstanceUid\":\"" + sopUid
                        + "\",\"instanceNumber\":" + study.getInstanceCount()
                        + ",\"sha256\":\"" + sha256
                        + "\",\"fileSizeBytes\":" + rawBytes.length + "}");

        liveStreamService.publishStudyEvent(
                siteId,
                "INSTANCE_COMMITTED",
                study,
                sopUid,
                "Committed DICOM instance #" + study.getInstanceCount() + " from "
                        + study.getSourceType() + " (" + callingAeTitle + ") to Original Repository [SHA-256: "
                        + sha256.substring(0, 12) + "…]");

        return instance;
    }

    @Transactional
    public StudyRecord updateStudyReceivingStatus(
            UUID siteId,
            UUID studyId,
            String receivingStatus,
            String reason) {
        StudyRecord study = studyRecordRepository.findById(studyId)
                .filter(s -> s.getSiteId().equals(siteId))
                .orElseThrow(() -> new IllegalArgumentException("Study not found for site"));
        String normalized = receivingStatus != null ? receivingStatus.trim().toUpperCase() : "COMPLETED";
        if (!Set.of("RECEIVING", "COMPLETED", "ON_HOLD", "FAILED", "INCOMPLETE").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported receivingStatus: " + receivingStatus);
        }
        study.setReceivingStatus(normalized);
        if ("ON_HOLD".equals(normalized)) {
            study.setLifecycleState("UNMATCHED_NO_RULE");
            study.setValidationError(reason != null && !reason.isBlank()
                    ? reason
                    : "Study placed On Hold (routing rules not met — awaiting manual AI Agent selection)");
        } else if ("INCOMPLETE".equals(normalized)) {
            study.setLifecycleState("INCOMPLETE");
            study.setValidationError(reason != null && !reason.isBlank()
                    ? reason
                    : "Study reception incomplete (fewer instances received than expected or association aborted)");
        } else if ("FAILED".equals(normalized)) {
            study.setLifecycleState("VALIDATION_FAILED");
            study.setValidationError(reason != null && !reason.isBlank()
                    ? reason
                    : "Study reception failed validation");
        } else if ("RECEIVING".equals(normalized)) {
            study.setLifecycleState("WAITING_FOR_COMPLETION");
        }
        StudyRecord saved = studyRecordRepository.save(study);
        liveStreamService.publishStudyEvent(
                siteId,
                "STUDY_RECEIVING_STATUS_CHANGED",
                saved,
                null,
                "Study receiving status updated to " + normalized
                        + " (" + saved.getInstanceCount() + " unique stored instances)");
        return saved;
    }

    @Transactional
    public List<VendorDeliveryJob> completeValidateAndRouteStudy(
            UUID siteId,
            UUID studyId,
            boolean forceDeliberateResend) {

        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown siteId: " + siteId));
        StudyRecord study = studyRecordRepository.findById(studyId)
                .filter(s -> s.getSiteId().equals(siteId))
                .orElseThrow(() -> new IllegalArgumentException("Study not found for site"));
        List<InstanceRecord> instances = instanceRecordRepository
                .findBySiteIdAndStudyIdOrderByReceivedAtAsc(siteId, studyId);

        study.setLifecycleState("VALIDATING");
        Optional<String> validationFailure = validateStudy(study, instances);
        if (validationFailure.isPresent()) {
            study.setLifecycleState("VALIDATION_FAILED");
            study.setReceivingStatus("FAILED");
            study.setValidationError(validationFailure.get());
            StudyRecord failedStudy = studyRecordRepository.save(study);
            liveStreamService.publishStudyEvent(
                    siteId,
                    "STUDY_RECEIVING_STATUS_CHANGED",
                    failedStudy,
                    null,
                    "Study reception FAILED validation: " + validationFailure.get());
            auditService.record(
                    siteId, "DICOM_VALIDATOR", "SYSTEM", "STUDY_LIFECYCLE",
                    "STUDY_VALIDATION_FAILED", "StudyRecord", failedStudy.getId().toString(),
                    "BLOCKED", "{\"reason\":\"" + validationFailure.get() + "\"}");
            return List.of();
        }

        study.setValidationError(null);
        study.setCompletedAt(OffsetDateTime.now());
        if (study.getStudyDescription() == null || study.getStudyDescription().isBlank()) {
            if (study.getBodyPartExamined() != null && !study.getBodyPartExamined().isBlank()) {
                study.setStudyDescription(study.getBodyPartExamined().trim());
            } else if (study.getProtocolName() != null && !study.getProtocolName().isBlank()) {
                study.setStudyDescription(study.getProtocolName().trim());
            } else {
                instances.stream()
                        .map(InstanceRecord::getSeriesDescription)
                        .filter(sd -> sd != null && !sd.isBlank())
                        .findFirst()
                        .ifPresent(sd -> study.setStudyDescription(sd.trim()));
            }
        }
        study.setCanonicalRegion(routingEngineService.resolveCanonicalRegion(
                siteId,
                study.getModality(),
                study.getStudyDescription(),
                study.getBodyPartExamined(),
                study.getProtocolName()));
        study.setLifecycleState("ROUTING_PENDING");
        study.setReceivingStatus("COMPLETED");
        studyRecordRepository.save(study);

        List<VendorDeliveryJob> jobs = routingEngineService
                .evaluateAndCreateJobs(study, instances, forceDeliberateResend);

        for (VendorDeliveryJob job : jobs) {
            VendorConfig vendor = vendorConfigRepository.findById(job.getVendorId()).orElseThrow();
            DeidProfile profile = deidProfileRepository.findById(job.getDeidProfileId()).orElseThrow();
            RoutingRule rule = job.getRoutingRuleId() != null
                    ? routingRuleRepository.findById(job.getRoutingRuleId()).orElse(null)
                    : null;

            // FR-08: select only the series/instances required by the routing rule
            List<InstanceRecord> selectedInstances = routingEngineService
                    .selectEligibleInstancesForRule(instances, rule);
            job.setSelectedInstances(selectedInstances.size());

            DeidentificationOutcome deidOutcome = deidentifierService.createDeidentifiedWorkingCopy(
                    site.getSiteCode(), study, selectedInstances, vendor, profile, job);

            if (!deidOutcome.success()) {
                job.setStatus("DEIDENTIFICATION_FAILED");
                job.setLastError(deidOutcome.errorReason());
                job.setWorkingCopyDir(null);
                job.setTransferredInstances(0);
                job.setUpdatedAt(OffsetDateTime.now());
                jobRepository.save(job);
            } else {
                job.setStatus("QUEUED");
                job.setWorkingCopyDir(deidOutcome.workingCopyDirectory().toAbsolutePath().toString());
                job.setUpdatedAt(OffsetDateTime.now());
                jobRepository.save(job);
            }
        }

        boolean hasAnyJob = !jobs.isEmpty()
                || jobRepository.findBySiteIdAndStudyId(siteId, studyId).stream()
                        .anyMatch(j -> j.getRoutingRuleId() != null
                                || "VENDOR_ACCEPTED".equalsIgnoreCase(j.getStatus())
                                || "COMPLETED".equalsIgnoreCase(j.getStatus())
                                || "QUEUED".equalsIgnoreCase(j.getStatus())
                                || "UPLOADING".equalsIgnoreCase(j.getStatus()));
        study.setLifecycleState(hasAnyJob ? "ROUTED" : "UNMATCHED_NO_RULE");
        study.setReceivingStatus(hasAnyJob ? "COMPLETED" : "ON_HOLD");
        StudyRecord completedStudy = studyRecordRepository.save(study);
        auditService.record(
                siteId,
                "ROUTING_ENGINE",
                "SYSTEM",
                "STUDY_LIFECYCLE",
                !hasAnyJob ? "STUDY_COMPLETED_UNMATCHED" : "STUDY_VALIDATED_AND_ROUTED",
                "StudyRecord",
                completedStudy.getId().toString(),
                "SUCCESS",
                "{\"studyId\":\"" + completedStudy.getId()
                        + "\",\"studyInstanceUid\":\"" + completedStudy.getStudyInstanceUid()
                        + "\",\"modality\":\"" + completedStudy.getModality()
                        + "\",\"canonicalRegion\":\"" + completedStudy.getCanonicalRegion()
                        + "\",\"receivingStatus\":\"" + completedStudy.getReceivingStatus()
                        + "\",\"jobsCreated\":" + jobs.size() + "}");
        liveStreamService.publishStudyEvent(
                siteId,
                "STUDY_RECEIVING_STATUS_CHANGED",
                completedStudy,
                null,
                hasAnyJob
                        ? "Study reception COMPLETED (" + completedStudy.getInstanceCount()
                                + " unique stored instances from " + completedStudy.getSourceType() + " · "
                                + completedStudy.getCallingAeTitle() + ")"
                        : "Study reception ON HOLD — rules not met (" + completedStudy.getInstanceCount()
                                + " unique stored instances from " + completedStudy.getSourceType() + " · "
                                + completedStudy.getCallingAeTitle() + ")");
        if (!jobs.isEmpty()) {
            liveStreamService.publishJobOrResultEvent(
                    siteId,
                    completedStudy,
                    jobs.getFirst(),
                    null,
                    "Routed study " + completedStudy.getStudyInstanceUid() + " to " + jobs.size() + " outbound job(s)");
        }
        return jobs;
    }

    /**
     * Manually routes an unmatched / On-Hold study (or any study) to a specific selected AI Agent or
     * On-Premise DICOM Router chosen from the dashboard dropdown when routing rules are not met.
     */
    @Transactional
    public VendorDeliveryJob routeStudyToSpecificVendor(
            UUID siteId,
            UUID studyId,
            UUID vendorId) {
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown siteId: " + siteId));
        StudyRecord study = studyRecordRepository.findById(studyId)
                .filter(s -> s.getSiteId().equals(siteId))
                .orElseThrow(() -> new IllegalArgumentException("Study not found for site"));
        VendorConfig vendor = vendorConfigRepository.findById(vendorId)
                .filter(v -> v.getSiteId().equals(siteId))
                .orElseThrow(() -> new IllegalArgumentException("Target AI Agent / Vendor not found for site"));
        List<InstanceRecord> instances = instanceRecordRepository
                .findBySiteIdAndStudyIdOrderByReceivedAtAsc(siteId, studyId);

        Optional<String> validationFailure = validateStudy(study, instances);
        if (validationFailure.isPresent()) {
            study.setLifecycleState("VALIDATION_FAILED");
            study.setReceivingStatus("FAILED");
            study.setValidationError(validationFailure.get());
            studyRecordRepository.save(study);
            throw new IllegalArgumentException(validationFailure.get());
        }

        DeidProfile profile = (vendor.getDeidProfileId() != null
                ? deidProfileRepository.findById(vendor.getDeidProfileId())
                : Optional.<DeidProfile>empty())
                .or(() -> deidProfileRepository.findBySiteIdOrderByProfileCodeAsc(siteId).stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("No DeidProfile configured for site"));

        Optional<VendorDeliveryJob> existingJob = jobRepository
                .findBySiteIdAndStudyIdAndVendorId(siteId, studyId, vendorId);
        VendorDeliveryJob job = existingJob.orElseGet(VendorDeliveryJob::new);
        if (job.getId() == null) {
            job.setId(UUID.randomUUID());
            job.setCreatedAt(OffsetDateTime.now());
        } else {
            job.setAttemptCount(0);
            job.setNextRetryAt(null);
            job.setLastError(null);
            job.setVendorJobId(null);
            job.setUploadedBytes(0L);
            job.setSelectedInstances(0);
            job.setTransferredInstances(0);
            job.setVendorAcceptedAt(null);
            job.setResultReceivedAt(null);
            job.setPacsReturnedAt(null);
            job.setWorkingCopyCleanedAt(null);
        }
        job.setSiteId(siteId);
        job.setStudyId(studyId);
        job.setVendorId(vendorId);
        job.setDeidProfileId(profile.getId());
        job.setStatus("DEIDENTIFICATION_PENDING");
        job.setMaxAttempts(vendor.getMaxRetryAttempts());
        job.setUpdatedAt(OffsetDateTime.now());
        job = jobRepository.save(job);

        List<InstanceRecord> selectedInstances = routingEngineService.selectEligibleInstancesForRule(instances, null);
        if (selectedInstances.isEmpty()) {
            selectedInstances = instances;
        }
        job.setSelectedInstances(selectedInstances.size());

        DeidentificationOutcome deidOutcome = deidentifierService.createDeidentifiedWorkingCopy(
                site.getSiteCode(), study, selectedInstances, vendor, profile, job);
        if (!deidOutcome.success()) {
            job.setStatus("DEIDENTIFICATION_FAILED");
            job.setLastError(deidOutcome.errorReason());
            job.setWorkingCopyDir(null);
            job.setTransferredInstances(0);
        } else {
            job.setStatus("QUEUED");
            job.setWorkingCopyDir(deidOutcome.workingCopyDirectory().toAbsolutePath().toString());
        }
        job.setUpdatedAt(OffsetDateTime.now());
        job = jobRepository.save(job);

        study.setValidationError(null);
        study.setCompletedAt(OffsetDateTime.now());
        study.setLifecycleState("ROUTED");
        study.setReceivingStatus("COMPLETED");
        StudyRecord savedStudy = studyRecordRepository.save(study);

        auditService.record(
                siteId,
                "COMMAND_CENTRE_USER",
                "OPERATIONS",
                "STUDY_LIFECYCLE",
                "MANUAL_AGENT_ROUTING_TRIGGERED",
                "VendorDeliveryJob",
                job.getId().toString(),
                "SUCCESS",
                "{\"studyId\":\"" + savedStudy.getId()
                        + "\",\"vendorId\":\"" + vendor.getId()
                        + "\",\"vendorCode\":\"" + vendor.getVendorCode()
                        + "\",\"agentName\":\"" + vendor.resolveEffectiveAgentName() + "\"}");
        liveStreamService.publishStudyEvent(
                siteId,
                "STUDY_RECEIVING_STATUS_CHANGED",
                savedStudy,
                null,
                "Study manually routed from On Hold to " + vendor.resolveEffectiveAgentName()
                        + " (" + vendor.getVendorCode() + ")");
        liveStreamService.publishJobOrResultEvent(
                siteId,
                savedStudy,
                job,
                null,
                "Manual routing job created for " + vendor.resolveEffectiveAgentName());
        return job;
    }

    private Optional<String> validateStudy(StudyRecord study, List<InstanceRecord> instances) {
        if (instances == null || instances.isEmpty()) {
            return Optional.of("FR-07 Validation Failed: No DICOM instances in study");
        }
        if (study.getModality() == null || study.getModality().isBlank()
                || "INVALID".equalsIgnoreCase(study.getModality())) {
            return Optional.of("FR-07 Validation Failed: Invalid or unsupported Modality (" + study.getModality() + ")");
        }
        for (InstanceRecord inst : instances) {
            if (inst.getSliceThicknessMm() != null && inst.getSliceThicknessMm() <= 0.0) {
                return Optional.of("FR-07 Validation Failed: Non-positive SliceThickness ("
                        + inst.getSliceThicknessMm() + " mm) on SOPInstanceUID " + inst.getSopInstanceUid());
            }
            if (inst.getTransferSyntaxUid() == null || inst.getTransferSyntaxUid().startsWith("INVALID")) {
                return Optional.of("FR-07 Validation Failed: Unsupported TransferSyntaxUID ("
                        + inst.getTransferSyntaxUid() + ")");
            }
        }
        return Optional.empty();
    }

    @Transactional
    public VendorDeliveryJob processOutboundJob(UUID siteId, UUID jobId, boolean simulateTransientFailure) {
        if (!activeJobUploads.add(jobId)) {
            return jobRepository.findById(jobId)
                    .filter(j -> j.getSiteId().equals(siteId))
                    .orElseThrow(() -> new IllegalArgumentException("Job not found for site"));
        }
        try {
            VendorDeliveryJob job = jobRepository.findByIdAndSiteIdForUpdate(jobId, siteId)
                    .orElseThrow(() -> new IllegalArgumentException("Job not found for site"));
            VendorConfig vendor = vendorConfigRepository.findById(job.getVendorId()).orElseThrow();

            // Guard against missing workingCopyDir or retry of a DEIDENTIFICATION_FAILED / DEIDENTIFICATION_PENDING job
            if (job.getWorkingCopyDir() == null
                    || !Files.exists(Path.of(job.getWorkingCopyDir()))
                    || "DEIDENTIFICATION_FAILED".equals(job.getStatus())
                    || "DEIDENTIFICATION_PENDING".equals(job.getStatus())) {
                Site site = siteRepository.findById(siteId).orElseThrow();
                StudyRecord study = studyRecordRepository.findById(job.getStudyId()).orElseThrow();
                DeidProfile profile = deidProfileRepository.findById(job.getDeidProfileId()).orElseThrow();
                RoutingRule rule = job.getRoutingRuleId() != null
                        ? routingRuleRepository.findById(job.getRoutingRuleId()).orElse(null)
                        : null;
                List<InstanceRecord> instances = instanceRecordRepository
                        .findBySiteIdAndStudyIdOrderByReceivedAtAsc(siteId, study.getId());
                List<InstanceRecord> selectedInstances = routingEngineService
                        .selectEligibleInstancesForRule(instances, rule);
                DeidentificationOutcome deidOutcome = deidentifierService.createDeidentifiedWorkingCopy(
                        site.getSiteCode(), study, selectedInstances, vendor, profile, job);
                if (!deidOutcome.success()) {
                    job.setStatus("DEIDENTIFICATION_FAILED");
                    job.setLastError(deidOutcome.errorReason());
                    job.setWorkingCopyDir(null);
                    job.setUpdatedAt(OffsetDateTime.now());
                    VendorDeliveryJob blockedJob = jobRepository.save(job);
                    liveStreamService.publishJobOrResultEvent(
                            siteId,
                            study,
                            blockedJob,
                            null,
                            "Outbound job de-identification blocked: " + blockedJob.getLastError());
                    return blockedJob;
                }
                job.setSelectedInstances(selectedInstances.size());
                job.setWorkingCopyCleanedAt(null);
                job.setWorkingCopyDir(deidOutcome.workingCopyDirectory().toAbsolutePath().toString());
            }

            OffsetDateTime startedAt = OffsetDateTime.now();
            int attemptNum = job.getAttemptCount() + 1;
            int historyAttemptNum = Math.max(
                    attemptNum,
                    (int) attemptRepository.countBySiteIdAndJobId(siteId, job.getId()) + 1);
            job.setAttemptCount(attemptNum);
            job.setStatus("UPLOADING");
            String idempotencyKey = job.getId() + "-ATTEMPT-" + historyAttemptNum;

            VendorTransferAttempt attempt = new VendorTransferAttempt();
            attempt.setId(UUID.randomUUID());
            attempt.setSiteId(siteId);
            attempt.setJobId(job.getId());
            attempt.setAttemptNumber(historyAttemptNum);
            attempt.setIdempotencyKey(idempotencyKey);
            attempt.setStartedAt(startedAt);

            if (simulateTransientFailure) {
                attempt.setOutcome("TRANSIENT_ERROR");
                attempt.setStatusCode("503");
                attempt.setResponseSummary("Simulated vendor 503 outage");
                attempt.setFinishedAt(OffsetDateTime.now());
                attemptRepository.save(attempt);

                if (attemptNum >= job.getMaxAttempts()) {
                    job.setStatus("FAILED");
                    job.setLastError("Retry limit reached after " + attemptNum + " attempts");
                } else {
                    long backoffSeconds = (long) Math.min(300, Math.pow(2, attemptNum) * 5);
                    job.setStatus("RETRY_WAIT");
                    job.setNextRetryAt(OffsetDateTime.now().plusSeconds(backoffSeconds));
                    job.setLastError("Transient 503 error; scheduled exponential backoff retry in " + backoffSeconds + "s");
                }
                job.setUpdatedAt(OffsetDateTime.now());
                job = jobRepository.save(job);
                auditService.record(
                        siteId, vendor.getVendorCode(), "VENDOR_CONNECTOR", "VENDOR_TRANSFER",
                        "OUTBOUND_TRANSFER_TRANSIENT_ERROR", "VendorDeliveryJob", job.getId().toString(),
                        "FAILURE", "{\"attempt\":" + attemptNum + ",\"status\":\"" + job.getStatus() + "\"}");
                liveStreamService.publishJobOrResultEvent(
                        siteId,
                        null,
                        job,
                        null,
                        "Outbound transfer " + job.getStatus() + " (Attempt " + attemptNum + "): " + job.getLastError());
                return job;
            }

            List<Path> workingFiles;
            try (Stream<Path> stream = Files.list(Path.of(job.getWorkingCopyDir()))) {
                workingFiles = stream.filter(p -> p.toString().endsWith(".dcm")).toList();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read working copy directory: " + job.getWorkingCopyDir(), e);
            }

            VendorUploadReceipt receipt = vendorConnectorRegistry
                    .uploadWorkingCopy(vendor, job, workingFiles, idempotencyKey);
            attempt.setFinishedAt(OffsetDateTime.now());

            if (receipt.accepted()) {
                long bytes = workingFiles.stream().mapToLong(p -> p.toFile().length()).sum();
                int transferredCount = receipt.confirmedInstancesCount() > 0
                        ? receipt.confirmedInstancesCount()
                        : workingFiles.size();
                attempt.setOutcome("SUCCESS");
                attempt.setStatusCode("202");
                attempt.setResponseSummary(receipt.message());
                attemptRepository.save(attempt);

                job.setStatus("VENDOR_ACCEPTED");
                job.setVendorJobId(receipt.vendorJobId());
                job.setUploadedBytes(bytes);
                if (job.getSelectedInstances() <= 0) {
                    job.setSelectedInstances(workingFiles.size());
                }
                job.setTransferredInstances(transferredCount);
                job.setVendorAcceptedAt(OffsetDateTime.now());
                job.setLastError(null);
                job.setUpdatedAt(OffsetDateTime.now());
                job = jobRepository.save(job);
                auditService.record(
                        siteId, vendor.getVendorCode(), "VENDOR_CONNECTOR", "VENDOR_TRANSFER",
                        "VENDOR_UPLOAD_CONFIRMED", "VendorDeliveryJob", job.getId().toString(),
                        "SUCCESS", "{\"vendorJobId\":\"" + receipt.vendorJobId()
                                + "\",\"transferredInstances\":" + transferredCount
                                + ",\"destinationType\":\"" + vendor.getDestinationType()
                                + "\",\"destinationAeTitle\":\"" + vendor.getDestinationAeTitle()
                                + "\",\"bytes\":" + bytes + "}");
                liveStreamService.publishJobOrResultEvent(
                        siteId,
                        null,
                        job,
                        null,
                        "Vendor " + vendor.getVendorCode() + " accepted outbound transfer (" + transferredCount + " instances)");

                // For synchronous Grok AI Vision X-Ray jobs, immediately re-identify, generate PDF, and upload to RIS
                if (!"DICOM_ROUTER".equalsIgnoreCase(vendor.getDestinationType())
                        && "GROK_AI_VISION".equalsIgnoreCase(vendor.getConnectorType())) {
                    GrokVisionAnalysisOutput grokOut = grokAiXrayVendorConnector.consumeJobAnalysis(job.getId());
                    if (grokOut != null) {
                        UUID currentVendorId = vendor.getId();
                        UUID currentJobId = job.getId();
                        Optional<PseudonymMapping> pmOpt = pseudonymMappingRepository
                                .findBySiteIdAndStudyIdAndVendorId(siteId, job.getStudyId(), currentVendorId);
                        if (pmOpt.isPresent()) {
                            try {
                                String retMode = grokOut.returnDeliveryMode() != null
                                        ? grokOut.returnDeliveryMode().toUpperCase(Locale.ROOT)
                                        : "PDF_VIA_RIS_API";
                                String outFormat = switch (retMode) {
                                    case "BASE64_PNG_VIA_DICOM_AE" -> "BASE64_PNG";
                                    case "BASE64_JPEG_VIA_DICOM_AE" -> "BASE64_JPEG";
                                    case "BOTH_PDF_API_AND_IMAGE_AE" -> "BOTH_PDF_AND_BASE64_IMAGE";
                                    case "DICOM_VIA_DICOM_AE" -> "DICOM_FILE_VIA_DICOM_AE";
                                    case "DICOM_VIA_RIS_API" -> "DICOM_FILE_VIA_RIS_API";
                                    case "BOTH_PDF_API_AND_DICOM_AE" -> "BOTH_PDF_AND_DICOM_FILE";
                                    default -> (vendor.getOutputReportFormat() != null
                                            && !vendor.getOutputReportFormat().isBlank())
                                            ? vendor.getOutputReportFormat().trim()
                                            : "ENCAPSULATED_PDF";
                                };
                                receiveAndReturnAiResult(
                                        siteId,
                                        currentJobId,
                                        pmOpt.get().getPseudoStudyUid(),
                                        outFormat,
                                        grokOut.modelName(),
                                        grokOut.modelVersion(),
                                        grokOut.findingsJson(),
                                        grokOut.returnedBase64Image(),
                                        grokOut.returnedDicomBytes());
                                job = jobRepository.findById(currentJobId).orElse(job);
                            } catch (IOException ioEx) {
                                job.setLastError("Grok PDF / DICOM AE return failed: " + ioEx.getMessage());
                                job = jobRepository.save(job);
                            }
                        }
                    }
                }
                return job;
            } else {
                attempt.setOutcome("PERMANENT_ERROR");
                attempt.setStatusCode("422");
                attempt.setResponseSummary(receipt.message());
                attemptRepository.save(attempt);

                job.setStatus("FAILED");
                job.setLastError(receipt.message());
                job.setUpdatedAt(OffsetDateTime.now());
                VendorDeliveryJob failedJob = jobRepository.save(job);
                liveStreamService.publishJobOrResultEvent(
                        siteId,
                        null,
                        failedJob,
                        null,
                        "Outbound transfer FAILED: " + failedJob.getLastError());
                return failedJob;
            }
        } finally {
            activeJobUploads.remove(jobId);
        }
    }

    @Transactional(noRollbackFor = {IllegalArgumentException.class, IllegalStateException.class})
    public AiResultRecord receiveAndReturnAiResult(
            UUID siteId,
            UUID jobId,
            String pseudoStudyUidFromVendor,
            String resultType,
            String modelName,
            String modelVersion,
            String findingsJson) throws IOException {
        return receiveAndReturnAiResult(
                siteId, jobId, pseudoStudyUidFromVendor, resultType, modelName, modelVersion, findingsJson, null, null);
    }

    @Transactional(noRollbackFor = {IllegalArgumentException.class, IllegalStateException.class})
    public AiResultRecord receiveAndReturnAiResult(
            UUID siteId,
            UUID jobId,
            String pseudoStudyUidFromVendor,
            String resultType,
            String modelName,
            String modelVersion,
            String findingsJson,
            String returnedBase64Image) throws IOException {
        return receiveAndReturnAiResult(
                siteId, jobId, pseudoStudyUidFromVendor, resultType, modelName, modelVersion, findingsJson, returnedBase64Image, null);
    }

    /**
     * Receives an AI result from a vendor (PDF Report, DICOM .dcm File, Base64 PNG/JPEG Image, or DICOM SR),
     * re-identifies via the on-premise encrypted pseudonym mapping (FR-20), stamps
     * "AI-generated – not verified" (FR-21) or LLM draft label (FR-42), and routes to the destination:
     * - When receiving a PDF file: dispatches via RIS / PACS UploadReportServlet API.
     * - When receiving a DICOM (.dcm) file from the AI vendor: re-identifies all patient & study tags on-premise
     *   and delivers the re-identified DICOM file to RIS / PACS via DICOM C-STORE (AE Title, IP, Port) and RIS UploadReportServlet API.
     * - When receiving a Base64 PNG or Base64 JPEG image: converts to a re-identified DICOM image
     *   and transmits via DICOM C-STORE using the PACS / RIS Destination AE Title, IP Address, and Port Number.
     */
    @Transactional(noRollbackFor = {IllegalArgumentException.class, IllegalStateException.class})
    public AiResultRecord receiveAndReturnAiResult(
            UUID siteId,
            UUID jobId,
            String pseudoStudyUidFromVendor,
            String resultType,
            String modelName,
            String modelVersion,
            String findingsJson,
            String returnedBase64Image,
            byte[] returnedDicomBytes) throws IOException {

        VendorDeliveryJob job = jobRepository.findById(jobId)
                .filter(j -> j.getSiteId().equals(siteId))
                .orElseThrow(() -> new IllegalArgumentException("Job not found for site"));

        // FR-19: A PDF alone without structured findings does not meet structured result requirements
        if ("PDF_ONLY".equalsIgnoreCase(resultType)) {
            job.setStatus("RESULT_VALIDATION_FAILED");
            job.setLastError("FR-19 Violation: Standalone PDF rejected; DICOM SR (TID 1500) + overlays required");
            job.setUpdatedAt(OffsetDateTime.now());
            job = jobRepository.save(job);
            auditService.record(
                    siteId, "RESULT_RECEIVER", "SYSTEM", "RESULT_RETURN",
                    "STANDALONE_PDF_REJECTED_FR19", "VendorDeliveryJob", job.getId().toString(),
                    "BLOCKED", "{\"resultType\":\"" + resultType + "\"}");
            liveStreamService.publishJobOrResultEvent(
                    siteId, null, job, null, job.getLastError());
            throw new IllegalArgumentException(job.getLastError());
        }

        // Validate pseudonym mapping (Specification Section 4.8: quarantine if ambiguous or mismatched)
        Optional<PseudonymMapping> mappingOpt = pseudonymMappingRepository
                .findBySiteIdAndPseudoStudyUid(siteId, pseudoStudyUidFromVendor);
        if (mappingOpt.isEmpty() || !mappingOpt.get().getStudyId().equals(job.getStudyId())) {
            job.setStatus("MANUAL_REVIEW");
            job.setLastError("Quarantined: Returned pseudoStudyUid does not match on-premise mapping for job");
            job.setUpdatedAt(OffsetDateTime.now());
            job = jobRepository.save(job);
            auditService.record(
                    siteId, "RESULT_RECEIVER", "SYSTEM", "RESULT_RETURN",
                    "PSEUDONYM_MISMATCH_QUARANTINED", "VendorDeliveryJob", job.getId().toString(),
                    "BLOCKED", "{\"pseudoStudyUid\":\"" + pseudoStudyUidFromVendor + "\"}");
            liveStreamService.publishJobOrResultEvent(
                    siteId, null, job, null, job.getLastError());
            throw new IllegalStateException(job.getLastError());
        }

        // Idempotency guard for duplicate webhook callbacks (Specification Section 11)
        List<AiResultRecord> existingForStudy = aiResultRecordRepository
                .findBySiteIdAndStudyIdOrderByReceivedAtDesc(siteId, job.getStudyId());
        Optional<AiResultRecord> existingForJob = existingForStudy.stream()
                .filter(r -> r.getJobId().equals(jobId) && r.getResultType().equalsIgnoreCase(resultType))
                .findFirst();
        if (existingForJob.isPresent() && "COMPLETED".equals(job.getStatus())) {
            return existingForJob.get();
        }

        PseudonymMapping mapping = mappingOpt.get();
        String restoredPatientId = cryptoService.decrypt(mapping.getEncryptedRealPatientId());
        String restoredAccession = cryptoService.decrypt(mapping.getEncryptedRealAccession());
        String restoredStudyUid = mapping.getOriginalStudyUid();

        Site site = siteRepository.findById(siteId).orElseThrow();
        StudyRecord study = studyRecordRepository.findById(job.getStudyId()).orElseThrow();
        VendorConfig vendor = vendorConfigRepository.findById(job.getVendorId()).orElseThrow();
        PacsDestination pacs = (vendor.getPacsDestinationId() != null
                ? pacsDestinationRepository.findById(vendor.getPacsDestinationId())
                : pacsDestinationRepository.findFirstBySiteIdAndDefaultForSiteTrueAndActiveTrue(siteId))
                .orElseThrow(() -> new IllegalStateException("No designated PACS destination configured for site"));

        String seriesLabel = ("LLM_MULTIMODAL".equalsIgnoreCase(vendor.getConnectorType())
                || "GROK_AI_VISION".equalsIgnoreCase(vendor.getConnectorType())
                || (resultType != null && resultType.toUpperCase(Locale.ROOT).startsWith("LLM_")))
                ? LlmPromptGatewayService.formatLlmDraftSeriesLabel(modelName, modelVersion)
                : AI_UNVERIFIED_LABEL;

        Path resultDir = Path.of(
                hubProperties.storage().rootDir(),
                "ai-results",
                site.getSiteCode(),
                vendor.getVendorCode(),
                job.getId().toString());
        Files.createDirectories(resultDir);

        String normType = resultType != null ? resultType.trim().toUpperCase(Locale.ROOT) : "ENCAPSULATED_PDF";
        String vendorReturnMode = vendor.getReturnDeliveryMode() != null
                ? vendor.getReturnDeliveryMode().trim().toUpperCase(Locale.ROOT)
                : "PDF_VIA_RIS_API";

        byte[] effectiveVendorDicomBytes = returnedDicomBytes;
        if ((effectiveVendorDicomBytes == null || effectiveVendorDicomBytes.length == 0)
                && returnedBase64Image != null
                && returnedBase64Image.toLowerCase(Locale.ROOT).startsWith("data:application/dicom")) {
            effectiveVendorDicomBytes = xrayToPngConverter.decodeBase64DicomPayload(returnedBase64Image);
        }

        boolean isDicomFileReturn = (effectiveVendorDicomBytes != null && effectiveVendorDicomBytes.length > 0)
                || normType.contains("DICOM_FILE")
                || normType.equals("DICOM_NATIVE")
                || normType.equals("BOTH_PDF_AND_DICOM")
                || ("GROK_AI_VISION".equalsIgnoreCase(vendor.getConnectorType())
                        && (vendorReturnMode.equals("DICOM_VIA_DICOM_AE")
                                || vendorReturnMode.equals("DICOM_VIA_RIS_API")
                                || vendorReturnMode.equals("BOTH_PDF_API_AND_DICOM_AE")
                                || vendorReturnMode.contains("DICOM_FILE")));

        boolean isBase64ImageReturn = !isDicomFileReturn
                && (normType.contains("BASE64_PNG")
                        || normType.contains("BASE64_JPEG")
                        || normType.contains("BOTH_PDF_AND_BASE64")
                        || (returnedBase64Image != null && !returnedBase64Image.isBlank())
                        || ("GROK_AI_VISION".equalsIgnoreCase(vendor.getConnectorType())
                                && (vendorReturnMode.contains("IMAGE")
                                        || vendorReturnMode.contains("BASE64")
                                        || vendorReturnMode.equals("BOTH_PDF_API_AND_IMAGE_AE"))));

        boolean isPdfReturn = normType.contains("PDF")
                || ("GROK_AI_VISION".equalsIgnoreCase(vendor.getConnectorType())
                        && (vendorReturnMode.contains("PDF") || vendorReturnMode.contains("BOTH"))
                        && !normType.equals("BASE64_PNG")
                        && !normType.equals("BASE64_JPEG")
                        && !normType.equals("DICOM_FILE_VIA_DICOM_AE")
                        && !normType.equals("DICOM_FILE_VIA_RIS_API"));

        String agentGeneratedBy = vendor.resolveEffectiveAgentName();
        var risEndpoint = risUploadService.getOrCreateSiteEndpoint(siteId);
        String nowLocalTime = RisTeleradUploadReportService.formatCurrentLocalTeleradTime();
        String configuredReportType = (risEndpoint.getDefaultReportType() != null && !risEndpoint.getDefaultReportType().isBlank())
                ? risEndpoint.getDefaultReportType().trim()
                : RisTeleradUploadReportService.HARDCODED_RIS_REPORT_TYPE;
        GeneratedPdfReport generatedPdf = clinicalPdfReportGenerator.generateReidentifiedXrayPdfReport(
                resultDir,
                study,
                vendor,
                restoredPatientId,
                study.getPatientName(),
                restoredAccession,
                restoredStudyUid,
                modelName,
                modelVersion,
                seriesLabel,
                findingsJson,
                configuredReportType,
                risEndpoint.getAuthUsername(),
                nowLocalTime,
                nowLocalTime,
                agentGeneratedBy);
        String preparedReportDataJson = RisTeleradUploadReportService.buildUploadReportJsonPayload(
                configuredReportType,
                restoredStudyUid,
                nowLocalTime,
                risEndpoint.getAuthUsername(),
                nowLocalTime,
                risEndpoint.getAuthUsername(),
                agentGeneratedBy);

        auditService.record(
                siteId,
                agentGeneratedBy,
                "AI_AGENT_PDF_GENERATOR",
                "RESULT_RETURN",
                "CLINICAL_PDF_REPORT_GENERATED",
                "StudyRecord",
                study.getId().toString(),
                "SUCCESS",
                "{\"studyId\":\"" + study.getId()
                        + "\",\"studyInstanceUid\":\"" + restoredStudyUid
                        + "\",\"jobId\":\"" + job.getId()
                        + "\",\"generatedBy\":\"" + agentGeneratedBy.replace("\\", "\\\\").replace("\"", "\\\"")
                        + "\",\"pdfFileName\":\"" + generatedPdf.pdfFileName()
                        + "\",\"pdfBytes\":" + generatedPdf.pdfBytes().length + "}");

        ReidentifiedDicomImageResult reidentifiedImage = null;
        if (isBase64ImageReturn) {
            String effectiveBase64 = returnedBase64Image;
            if (effectiveBase64 == null || effectiveBase64.isBlank()) {
                // Generate lossless Base64 PNG or JPEG from study working copy or phantom
                String targetImgFmt = (normType.contains("JPEG") || vendorReturnMode.contains("JPEG"))
                        ? "BASE64_JPEG_LOSSLESS_100"
                        : "BASE64_PNG_LOSSLESS";
                List<InstanceRecord> studyInsts = instanceRecordRepository
                        .findBySiteIdAndStudyIdOrderByReceivedAtAsc(siteId, study.getId());
                if (!studyInsts.isEmpty() && Files.exists(Path.of(studyInsts.getFirst().getOriginalRepoPath()))) {
                    var conv = xrayToPngConverter.convertDeidentifiedDicom(
                            Path.of(studyInsts.getFirst().getOriginalRepoPath()), targetImgFmt);
                    effectiveBase64 = xrayToPngConverter.generateAnnotatedAiResponseBase64Image(
                            conv, targetImgFmt, modelName, study.getStudyDescription(), "ROUTINE");
                }
            }
            reidentifiedImage = xrayToPngConverter.convertReturnedBase64ImageToReidentifiedDicom(
                    resultDir,
                    effectiveBase64,
                    study,
                    vendor,
                    restoredPatientId,
                    study.getPatientName(),
                    restoredAccession,
                    restoredStudyUid,
                    modelName,
                    modelVersion,
                    seriesLabel);
        }

        // Option B.2: Receive DICOM (.dcm) file returned by AI Vendor -> Re-identify Patient & Study UID tags on-premise
        DicomXrayToPngConverter.ReidentifiedDicomImageResult reidentifiedVendorDicom = null;
        if (isDicomFileReturn) {
            byte[] effectiveDicomBytes = effectiveVendorDicomBytes;
            if (effectiveDicomBytes == null || effectiveDicomBytes.length == 0) {
                Path sourceDicomForDemo = resolvePrimaryStudyDicomFile(study);
                effectiveDicomBytes = xrayToPngConverter.generateAnnotatedAiResponseDicomBytes(
                        sourceDicomForDemo,
                        study,
                        vendor,
                        findingsJson,
                        modelName,
                        modelVersion);
            }
            reidentifiedVendorDicom = xrayToPngConverter.reidentifyVendorReturnedDicomFile(
                    resultDir,
                    effectiveDicomBytes,
                    study,
                    vendor,
                    restoredPatientId,
                    study.getPatientName(),
                    restoredAccession,
                    restoredStudyUid,
                    modelName,
                    modelVersion,
                    seriesLabel,
                    findingsJson);
        }

        Path primaryDicomResultFile;
        if (reidentifiedVendorDicom != null) {
            primaryDicomResultFile = reidentifiedVendorDicom.reidentifiedDicomPath();
        } else if (reidentifiedImage != null && !isPdfReturn) {
            primaryDicomResultFile = reidentifiedImage.dicomFilePath();
        } else {
            String srSopUid = org.dcm4che3.util.UIDUtils.createUID();
            Path srFile = resultDir.resolve(srSopUid + (isPdfReturn ? "-ENCAPSULATED-PDF.dcm" : "-SR.dcm"));
            Attributes srDataset = new Attributes();
            srDataset.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192");
            srDataset.setString(
                    Tag.SOPClassUID,
                    VR.UI,
                    isPdfReturn ? UID.EncapsulatedPDFStorage : UID.ComprehensiveSRStorage);
            srDataset.setString(Tag.SOPInstanceUID, VR.UI, srSopUid);
            srDataset.setString(Tag.StudyInstanceUID, VR.UI, restoredStudyUid);
            srDataset.setString(Tag.SeriesInstanceUID, VR.UI, org.dcm4che3.util.UIDUtils.createUID());
            srDataset.setString(Tag.Modality, VR.CS, isPdfReturn ? "DOC" : "SR");
            srDataset.setString(Tag.PatientID, VR.LO, restoredPatientId);
            if (study.getPatientName() != null && !study.getPatientName().isBlank()) {
                srDataset.setString(Tag.PatientName, VR.PN, study.getPatientName());
            }
            srDataset.setString(Tag.AccessionNumber, VR.SH, restoredAccession);
            srDataset.setString(Tag.SeriesDescription, VR.LO, seriesLabel);
            srDataset.setString(Tag.Manufacturer, VR.LO, vendor.getVendorName());
            srDataset.setString(Tag.ManufacturerModelName, VR.LO, modelName);
            srDataset.setString(Tag.SoftwareVersions, VR.LO, modelVersion);
            if (generatedPdf != null) {
                srDataset.setString(Tag.MIMETypeOfEncapsulatedDocument, VR.LO, "application/pdf");
                srDataset.setString(Tag.DocumentTitle, VR.ST, seriesLabel);
                srDataset.setString(Tag.BurnedInAnnotation, VR.CS, "YES");
                srDataset.setBytes(Tag.EncapsulatedDocument, VR.OB, generatedPdf.pdfBytes());
            }

            Attributes srFmi = srDataset.createFileMetaInformation(UID.ExplicitVRLittleEndian);
            try (DicomOutputStream dos = new DicomOutputStream(srFile.toFile())) {
                dos.writeDataset(srFmi, srDataset);
            }
            primaryDicomResultFile = srFile;
        }

        AiResultRecord resultRecord = new AiResultRecord();
        resultRecord.setId(UUID.randomUUID());
        resultRecord.setSiteId(siteId);
        resultRecord.setJobId(job.getId());
        resultRecord.setStudyId(study.getId());
        resultRecord.setVendorId(vendor.getId());
        resultRecord.setResultType(resultType);
        resultRecord.setModelName(modelName);
        resultRecord.setModelVersion(modelVersion);
        resultRecord.setSeriesDescriptionLabel(seriesLabel);
        resultRecord.setStoragePath(primaryDicomResultFile.toAbsolutePath().toString());
        if (generatedPdf != null) {
            resultRecord.setPdfStoragePath(generatedPdf.pdfFilePath().toAbsolutePath().toString());
        }
        resultRecord.setGeneratedBy(agentGeneratedBy);
        resultRecord.setReportData(preparedReportDataJson);
        if (reidentifiedVendorDicom != null) {
            resultRecord.setDicomStoragePath(reidentifiedVendorDicom.reidentifiedDicomPath().toAbsolutePath().toString());
            resultRecord.setDicomFileName(reidentifiedVendorDicom.dicomFileName());
            resultRecord.setDicomFileSizeBytes(reidentifiedVendorDicom.fileSizeBytes());
            if (reidentifiedVendorDicom.previewPngPath() != null) {
                resultRecord.setImageStoragePath(reidentifiedVendorDicom.previewPngPath().toAbsolutePath().toString());
            }
        } else if (reidentifiedImage != null) {
            resultRecord.setImageStoragePath(reidentifiedImage.rawImageFilePath().toAbsolutePath().toString());
        }
        resultRecord.setFindingsJson(findingsJson);
        resultRecord.setReidentified(true);
        String strategyUsed = (isPdfReturn && isDicomFileReturn)
                ? "DUAL_PDF_AND_DICOM_FILE_TO_RIS_AND_AE"
                : isDicomFileReturn
                        ? ("DICOM_VIA_RIS_API".equals(vendorReturnMode)
                                ? "DICOM_FILE_TO_RIS_API_AND_AE"
                                : "DICOM_FILE_CSTORE_TO_RIS_PACS_AE")
                        : (isPdfReturn && isBase64ImageReturn)
                                ? "DUAL_PDF_RIS_API_AND_DICOM_AE"
                                : isPdfReturn
                                        ? "TELERAD_UPLOAD_REPORT_SERVLET"
                                        : isBase64ImageReturn
                                                ? "DICOM_CSTORE_TO_PACS_AE"
                                                : pacs.getReportIntegrationStrategy();
        resultRecord.setReportPrefillStrategy(strategyUsed);

        // FR-36: Shadow Mode hides results from clinical PACS worklist & RIS until live activation
        if (vendor.isShadowMode()) {
            resultRecord.setPacsForwarded(false);
            resultRecord.setReportPrefillStatus("SKIPPED_SHADOW_MODE");
            if (generatedPdf != null) {
                resultRecord.setRisUploadStatus("SKIPPED_SHADOW_MODE");
                resultRecord.setRisUploadResponse("{\"status\":\"skipped\",\"reason\":\"Vendor is in Shadow Mode\"}");
            }
            if (reidentifiedImage != null || reidentifiedVendorDicom != null) {
                resultRecord.setDicomAeReturnStatus("SKIPPED_SHADOW_MODE");
            }
            if (reidentifiedVendorDicom != null) {
                resultRecord.setRisDicomUploadStatus("SKIPPED_SHADOW_MODE");
            }
        } else {
            String draftStatus = reportDispatcher.dispatchDraft(
                    pacs, study, resultRecord, restoredPatientId, restoredAccession);
            resultRecord.setPacsForwarded(true);
            resultRecord.setReportPrefillStatus(draftStatus);

            // Destination Path 1: Dispatch generated PDF report + reportData + generatedBy to RIS / PACS UploadReportServlet API
            // (Skip standalone PDF upload when BOTH PDF + DICOM are being uploaded together in Destination Path 3 below)
            if (generatedPdf != null && reidentifiedVendorDicom == null) {
                UploadReportServletResult risRes = risUploadService.uploadPdfReportToRis(
                        siteId,
                        job.getId(),
                        study.getId(),
                        restoredStudyUid,
                        null,
                        generatedPdf.draftedTimeLocal(),
                        generatedPdf.reviewedTimeLocal(),
                        agentGeneratedBy,
                        generatedPdf.pdfFileName(),
                        generatedPdf.pdfFilePath(),
                        generatedPdf.pdfBytes(),
                        false);
                boolean risSuccess = risRes.httpStatus() >= 200
                        && risRes.httpStatus() < 300
                        && !"failure".equalsIgnoreCase(risRes.status());
                resultRecord.setRisUploadStatus(
                        risSuccess ? "UPLOADED_HTTP_200" : "FAILED_HTTP_" + risRes.httpStatus());
                resultRecord.setRisUploadResponse(risRes.rawResponseJson());
                if (risRes.reportData() != null) {
                    resultRecord.setReportData(risRes.reportData());
                }
                if (risRes.generatedBy() != null) {
                    resultRecord.setGeneratedBy(risRes.generatedBy());
                }
                if (isPdfReturn) {
                    resultRecord.setReportPrefillStatus(
                            risSuccess
                                    ? "RIS_TELERAD_PDF_UPLOADED"
                                    : "RIS_TELERAD_UPLOAD_FAILED");
                }
                if (!risSuccess) {
                    String errSummary = "RIS Server Error (HTTP " + risRes.httpStatus()
                            + " at " + risEndpoint.getTargetUrl() + "): "
                            + (risRes.reason() != null && !risRes.reason().isBlank()
                                    ? risRes.reason()
                                    : risRes.message());
                    job.setLastError(errSummary);
                }
            }

            // Destination Path 2: When receiving a Base64 PNG/JPEG image -> convert to DICOM & send via PACS/RIS AE Title, IP & Port
            if (reidentifiedImage != null) {
                VendorConfig pacsDicomTarget = new VendorConfig();
                pacsDicomTarget.setSiteId(siteId);
                pacsDicomTarget.setVendorCode(pacs.getAeTitle());
                pacsDicomTarget.setVendorName(pacs.getDestinationName());
                pacsDicomTarget.setConnectorType("DICOM_CSTORE");
                pacsDicomTarget.setDestinationType("DICOM_ROUTER");
                pacsDicomTarget.setDestinationAeTitle(pacs.getAeTitle());
                pacsDicomTarget.setDestinationHost(pacs.getHostAddress());
                pacsDicomTarget.setDestinationPort(pacs.getDicomPort());

                VendorUploadReceipt cstoreReceipt = dicomCStoreConnector.uploadWorkingCopy(
                        pacsDicomTarget,
                        job,
                        List.of(reidentifiedImage.dicomFilePath()),
                        "AI-IMAGE-RETURN-" + job.getId());
                String aeStatus = (cstoreReceipt.accepted() ? "SENT_TO_AE_" : "FAILED_AE_")
                        + pacs.getAeTitle() + "@" + pacs.getHostAddress() + ":" + pacs.getDicomPort();
                if (aeStatus.length() > 120) {
                    aeStatus = aeStatus.substring(0, 120);
                }
                resultRecord.setDicomAeReturnStatus(aeStatus);
                if (!isPdfReturn) {
                    String shortStatus = "DICOM_AE_SENT:" + pacs.getAeTitle();
                    resultRecord.setReportPrefillStatus(
                            shortStatus.length() > 64 ? shortStatus.substring(0, 64) : shortStatus);
                } else if ("RIS_TELERAD_PDF_UPLOADED".equals(resultRecord.getReportPrefillStatus())) {
                    resultRecord.setReportPrefillStatus("PDF_API_AND_DICOM_AE_SENT");
                }
                auditService.record(
                        siteId,
                        vendor.getVendorCode(),
                        "PACS_DICOM_RETURN_SCU",
                        "RESULT_RETURN",
                        "BASE64_IMAGE_CONVERTED_AND_SENT_TO_PACS_AE",
                        "AiResultRecord",
                        resultRecord.getId().toString(),
                        cstoreReceipt.accepted() ? "SUCCESS" : "FAILURE",
                        "{\"imageFormat\":\"" + reidentifiedImage.imageFormat()
                                + "\",\"pacsAeTitle\":\"" + pacs.getAeTitle()
                                + "\",\"pacsHost\":\"" + pacs.getHostAddress()
                                + "\",\"pacsPort\":" + pacs.getDicomPort()
                                + ",\"sopInstanceUid\":\"" + reidentifiedImage.sopInstanceUid() + "\"}");
            }

            // Destination Path 3: When receiving a DICOM (.dcm) file from AI Vendor -> Re-identify & deliver to RIS (UploadReportServlet API + PACS/RIS DICOM AE)
            if (reidentifiedVendorDicom != null) {
                VendorConfig pacsDicomTarget = new VendorConfig();
                pacsDicomTarget.setSiteId(siteId);
                pacsDicomTarget.setVendorCode(pacs.getAeTitle());
                pacsDicomTarget.setVendorName(pacs.getDestinationName());
                pacsDicomTarget.setConnectorType("DICOM_CSTORE");
                pacsDicomTarget.setDestinationType("DICOM_ROUTER");
                pacsDicomTarget.setDestinationAeTitle(pacs.getAeTitle());
                pacsDicomTarget.setDestinationHost(pacs.getHostAddress());
                pacsDicomTarget.setDestinationPort(pacs.getDicomPort());

                VendorUploadReceipt cstoreReceipt = dicomCStoreConnector.uploadWorkingCopy(
                        pacsDicomTarget,
                        job,
                        List.of(reidentifiedVendorDicom.reidentifiedDicomPath()),
                        "AI-DICOM-RETURN-" + job.getId());
                String aeStatus = (cstoreReceipt.accepted() ? "SENT_DICOM_TO_AE_" : "FAILED_DICOM_AE_")
                        + pacs.getAeTitle() + "@" + pacs.getHostAddress() + ":" + pacs.getDicomPort();
                if (aeStatus.length() > 120) {
                    aeStatus = aeStatus.substring(0, 120);
                }
                resultRecord.setDicomAeReturnStatus(aeStatus);

                // Also deliver the re-identified DICOM file to RIS UploadReportServlet if RIS supports DICOM or mode requests RIS API
                boolean sendDicomToRisApi = risEndpoint.isSupportDicomUpload()
                        || "DICOM_VIA_RIS_API".equals(vendorReturnMode)
                        || "BOTH_PDF_API_AND_DICOM_AE".equals(vendorReturnMode)
                        || isPdfReturn;
                if (sendDicomToRisApi) {
                    UploadReportServletResult risDicomRes = risUploadService.uploadDicomFileToRis(
                            siteId,
                            job.getId(),
                            study.getId(),
                            restoredStudyUid,
                            null,
                            generatedPdf != null ? generatedPdf.draftedTimeLocal() : null,
                            generatedPdf != null ? generatedPdf.reviewedTimeLocal() : null,
                            agentGeneratedBy,
                            reidentifiedVendorDicom.dicomFileName(),
                            reidentifiedVendorDicom.reidentifiedDicomPath(),
                            reidentifiedVendorDicom.reidentifiedDicomBytes(),
                            generatedPdf != null ? generatedPdf.pdfFileName() : null,
                            generatedPdf != null ? generatedPdf.pdfFilePath() : null,
                            generatedPdf != null ? generatedPdf.pdfBytes() : null,
                            aeStatus,
                            false);
                    boolean risDicomSuccess = risDicomRes.httpStatus() >= 200
                            && risDicomRes.httpStatus() < 300
                            && !"failure".equalsIgnoreCase(risDicomRes.status());
                    resultRecord.setRisDicomUploadStatus(
                            risDicomSuccess
                                    ? "DICOM_UPLOADED_HTTP_200"
                                    : "DICOM_FAILED_HTTP_" + risDicomRes.httpStatus());
                    resultRecord.setRisUploadStatus(
                            risDicomSuccess
                                    ? (generatedPdf != null ? "PDF_AND_DICOM_UPLOADED_HTTP_200" : "DICOM_UPLOADED_HTTP_200")
                                    : "FAILED_HTTP_" + risDicomRes.httpStatus());
                    resultRecord.setRisUploadResponse(risDicomRes.rawResponseJson());
                    if (risDicomRes.reportData() != null) {
                        resultRecord.setReportData(risDicomRes.reportData());
                    }
                    if (risDicomRes.generatedBy() != null) {
                        resultRecord.setGeneratedBy(risDicomRes.generatedBy());
                    }
                    resultRecord.setReportPrefillStatus(
                            risDicomSuccess
                                    ? (generatedPdf != null
                                            ? "RIS_PDF_AND_DICOM_UPLOADED"
                                            : "RIS_DICOM_UPLOADED_AND_AE_SENT")
                                    : "RIS_DICOM_UPLOAD_FAILED");
                } else {
                    String shortStatus = "DICOM_FILE_AE_SENT:" + pacs.getAeTitle();
                    resultRecord.setReportPrefillStatus(
                            shortStatus.length() > 64 ? shortStatus.substring(0, 64) : shortStatus);
                }

                auditService.record(
                        siteId,
                        vendor.getVendorCode(),
                        "AI_VENDOR_DICOM_RETURN",
                        "RESULT_RETURN",
                        "VENDOR_DICOM_FILE_REIDENTIFIED_AND_SENT_TO_RIS_AND_AE",
                        "AiResultRecord",
                        resultRecord.getId().toString(),
                        cstoreReceipt.accepted() ? "SUCCESS" : "FAILURE",
                        "{\"dicomFileName\":\"" + reidentifiedVendorDicom.dicomFileName()
                                + "\",\"fileSizeBytes\":" + reidentifiedVendorDicom.fileSizeBytes()
                                + ",\"pacsAeTitle\":\"" + pacs.getAeTitle()
                                + "\",\"pacsHost\":\"" + pacs.getHostAddress()
                                + "\",\"pacsPort\":" + pacs.getDicomPort()
                                + ",\"risDicomUploadStatus\":\"" + resultRecord.getRisDicomUploadStatus()
                                + "\",\"sopInstanceUid\":\"" + reidentifiedVendorDicom.sopInstanceUid() + "\"}");
            }
            job.setPacsReturnedAt(OffsetDateTime.now());
        }
        resultRecord.setReceivedAt(OffsetDateTime.now());
        resultRecord = aiResultRecordRepository.save(resultRecord);

        job.setResultReceivedAt(OffsetDateTime.now());
        job.setStatus("COMPLETED");
        job.setUpdatedAt(OffsetDateTime.now());
        job = jobRepository.save(job);

        auditService.record(
                siteId,
                vendor.getVendorCode(),
                "AI_VENDOR",
                "RESULT_RETURN",
                "AI_RESULT_REIDENTIFIED_AND_RETURNED",
                "AiResultRecord",
                resultRecord.getId().toString(),
                "SUCCESS",
                "{\"pacsDestination\":\"" + pacs.getAeTitle()
                        + "\",\"reportStrategy\":\"" + pacs.getReportIntegrationStrategy()
                        + "\",\"shadowMode\":" + vendor.isShadowMode() + "}");

        liveStreamService.publishJobOrResultEvent(
                siteId,
                study,
                job,
                resultRecord,
                "AI result re-identified and returned (" + resultRecord.getReportPrefillStatus() + ")");

        return resultRecord;
    }

    private Path resolvePrimaryStudyDicomFile(StudyRecord study) {
        if (study == null || study.getId() == null) {
            return null;
        }
        List<InstanceRecord> instances = instanceRecordRepository
                .findBySiteIdAndStudyIdOrderByReceivedAtAsc(study.getSiteId(), study.getId());
        for (InstanceRecord inst : instances) {
            if (inst.getOriginalRepoPath() != null && !inst.getOriginalRepoPath().isBlank()) {
                Path p = Path.of(inst.getOriginalRepoPath());
                if (Files.exists(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * Receives an AI vendor returned DICOM (.dcm) file for a study (or generates a simulated
     * AI vendor annotated DICOM file if none is provided), re-identifies all patient & study
     * tags on-premise, uploads the DICOM file to the RIS UploadReportServlet endpoint, and
     * forwards it to the site's PACS/RIS DICOM AE destination.
     */
    @Transactional
    public AiResultRecord receiveAndDispatchVendorDicomForStudy(
            UUID siteId,
            UUID studyId,
            String vendorCode,
            byte[] vendorDicomBytes,
            String findingsJson,
            String modelName,
            String modelVersion) throws Exception {

        StudyRecord study = studyRecordRepository.findById(studyId)
                .filter(s -> s.getSiteId().equals(siteId))
                .orElseThrow(() -> new IllegalArgumentException("Study not found for site"));

        String resolvedVendorCode = (vendorCode != null && !vendorCode.isBlank()) ? vendorCode : "GROK-AI-XRAY";
        VendorConfig vendor = vendorConfigRepository.findBySiteIdAndVendorCode(siteId, resolvedVendorCode)
                .orElseGet(() -> vendorConfigRepository.findBySiteIdOrderByVendorCodeAsc(siteId).stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No AI vendor configured for site")));

        List<VendorDeliveryJob> studyJobs = jobRepository.findBySiteIdAndStudyId(siteId, studyId);
        VendorDeliveryJob job = studyJobs.stream()
                .filter(j -> j.getVendorId().equals(vendor.getId()))
                .findFirst()
                .orElseGet(() -> studyJobs.isEmpty() ? null : studyJobs.get(0));

        PseudonymMapping mapping = pseudonymMappingRepository
                .findBySiteIdAndStudyIdAndVendorId(siteId, study.getId(), vendor.getId())
                .orElseGet(() -> {
                    PseudonymMapping pm = new PseudonymMapping();
                    pm.setId(UUID.randomUUID());
                    pm.setSiteId(siteId);
                    pm.setStudyId(study.getId());
                    pm.setVendorId(vendor.getId());
                    pm.setOriginalStudyUid(study.getStudyInstanceUid());
                    String pseudoUid = "2.25." + Math.abs(UUID.randomUUID().getMostSignificantBits());
                    pm.setPseudoStudyUid(pseudoUid);
                    pm.setPseudoPatientId("ANON-" + study.getId().toString().substring(0, 8).toUpperCase(Locale.ROOT));
                    pm.setPseudoAccessionNumber("ACC-ANON-" + study.getId().toString().substring(0, 6).toUpperCase(Locale.ROOT));
                    pm.setEncryptedRealPatientId(cryptoService.encrypt(
                            study.getPatientId() != null ? study.getPatientId() : "PAT-UNKNOWN"));
                    pm.setEncryptedRealAccession(cryptoService.encrypt(
                            study.getAccessionNumber() != null ? study.getAccessionNumber() : "ACC-UNKNOWN"));
                    pm.setCreatedAt(OffsetDateTime.now());
                    return pseudonymMappingRepository.save(pm);
                });

        if (job == null) {
            DeidProfile defaultProfile = (vendor.getDeidProfileId() != null
                    ? deidProfileRepository.findById(vendor.getDeidProfileId())
                    : Optional.<DeidProfile>empty())
                    .or(() -> deidProfileRepository.findBySiteIdOrderByProfileCodeAsc(siteId).stream().findFirst())
                    .orElseThrow(() -> new IllegalStateException("No DeidProfile configured for site"));
            job = new VendorDeliveryJob();
            job.setId(UUID.randomUUID());
            job.setSiteId(siteId);
            job.setStudyId(study.getId());
            job.setVendorId(vendor.getId());
            job.setDeidProfileId(defaultProfile.getId());
            job.setStatus("VENDOR_ACCEPTED");
            job.setVendorJobId("VJOB-DICOM-" + UUID.randomUUID().toString().substring(0, 8));
            job.setAttemptCount(1);
            job.setMaxAttempts(vendor.getMaxRetryAttempts());
            job.setCreatedAt(OffsetDateTime.now());
            job.setUpdatedAt(OffsetDateTime.now());
            job = jobRepository.save(job);
        }

        // Allow re-receiving a DICOM result on an already COMPLETED job by resetting status to VENDOR_ACCEPTED
        if ("COMPLETED".equals(job.getStatus())) {
            job.setStatus("VENDOR_ACCEPTED");
            job = jobRepository.save(job);
        }

        String effectiveModelName = (modelName != null && !modelName.isBlank())
                ? modelName
                : (vendor.getAiModelName() != null && !vendor.getAiModelName().isBlank()
                        ? vendor.getAiModelName()
                        : "grok-2-vision-1212");
        String effectiveModelVersion = (modelVersion != null && !modelVersion.isBlank())
                ? modelVersion
                : "2026.03-xray";
        String effectiveFindings = (findingsJson != null && !findingsJson.isBlank())
                ? findingsJson
                : "{\"studyUid\":\"" + study.getStudyInstanceUid()
                        + "\",\"modality\":\"" + study.getModality()
                        + "\",\"impression\":\"AI Vendor DICOM (.dcm) Diagnostic Result received and re-identified for RIS & PACS.\",\"recommendation\":\"Correlate clinically.\"}";

        byte[] effectiveDicomBytes = vendorDicomBytes;
        if (effectiveDicomBytes == null || effectiveDicomBytes.length == 0) {
            Path sourceDicom = resolvePrimaryStudyDicomFile(study);
            effectiveDicomBytes = xrayToPngConverter.generateAnnotatedAiResponseDicomBytes(
                    sourceDicom,
                    study,
                    vendor,
                    effectiveFindings,
                    effectiveModelName,
                    effectiveModelVersion);
        }

        String resultType = ("BOTH_PDF_API_AND_DICOM_AE".equalsIgnoreCase(vendor.getReturnDeliveryMode()))
                ? "BOTH_PDF_AND_DICOM"
                : "DICOM_FILE_VIA_RIS_API";

        return receiveAndReturnAiResult(
                siteId,
                job.getId(),
                mapping.getPseudoStudyUid(),
                resultType,
                effectiveModelName,
                effectiveModelVersion,
                effectiveFindings,
                null,
                effectiveDicomBytes);
    }

    @Transactional
    public VendorDeliveryJob cancelJob(UUID siteId, UUID jobId, String actor) {
        VendorDeliveryJob job = jobRepository.findById(jobId)
                .filter(j -> j.getSiteId().equals(siteId))
                .orElseThrow(() -> new IllegalArgumentException("Job not found for site"));
        job.setStatus("CANCELLED");
        job.setLastError("Cancelled by administrator (" + actor + ")");
        job.setUpdatedAt(OffsetDateTime.now());
        job = jobRepository.save(job);
        auditService.record(
                siteId, actor, "COMMAND_CENTRE_ADMIN", "VENDOR_TRANSFER",
                "JOB_CANCELLED", "VendorDeliveryJob", job.getId().toString(),
                "SUCCESS", "{\"status\":\"CANCELLED\"}");
        liveStreamService.publishJobOrResultEvent(
                siteId, null, job, null, "Delivery job cancelled by " + actor);
        return job;
    }

    @Transactional
    public VendorDeliveryJob quarantineJob(UUID siteId, UUID jobId, String reason, String actor) {
        VendorDeliveryJob job = jobRepository.findById(jobId)
                .filter(j -> j.getSiteId().equals(siteId))
                .orElseThrow(() -> new IllegalArgumentException("Job not found for site"));
        job.setStatus("MANUAL_REVIEW");
        job.setLastError(reason == null || reason.isBlank() ? "Placed in quarantine for manual review" : reason);
        job.setUpdatedAt(OffsetDateTime.now());
        job = jobRepository.save(job);
        auditService.record(
                siteId, actor, "COMMAND_CENTRE_ADMIN", "VENDOR_TRANSFER",
                "JOB_QUARANTINED_MANUAL_REVIEW", "VendorDeliveryJob", job.getId().toString(),
                "BLOCKED", "{\"reason\":\"" + job.getLastError() + "\"}");
        liveStreamService.publishJobOrResultEvent(
                siteId, null, job, null, "Job quarantined for manual review: " + job.getLastError());
        return job;
    }

    @Transactional
    public VendorDeliveryJob toggleJobLegalHold(UUID siteId, UUID jobId, String actor) {
        VendorDeliveryJob job = jobRepository.findById(jobId)
                .filter(j -> j.getSiteId().equals(siteId))
                .orElseThrow(() -> new IllegalArgumentException("Job not found for site"));
        job.setLegalHold(!job.isLegalHold());
        job.setUpdatedAt(OffsetDateTime.now());
        job = jobRepository.save(job);
        auditService.record(
                siteId, actor, "COMMAND_CENTRE_ADMIN", "CLEANUP",
                "JOB_LEGAL_HOLD_TOGGLED", "VendorDeliveryJob", job.getId().toString(),
                "SUCCESS", "{\"legalHold\":" + job.isLegalHold() + "}");
        liveStreamService.publishJobOrResultEvent(
                siteId, null, job, null, "Job legal hold toggled to " + job.isLegalHold());
        return job;
    }

    @Transactional
    public AiResultRecord updateRadiologistDisposition(
            UUID siteId, UUID resultId, String disposition, String actor) {
        AiResultRecord result = aiResultRecordRepository.findById(resultId)
                .filter(r -> r.getSiteId().equals(siteId))
                .orElseThrow(() -> new IllegalArgumentException("AI result not found for site"));
        String normalized = disposition == null ? "PENDING_REVIEW" : disposition.toUpperCase();
        if (!Set.of("PENDING_REVIEW", "ACCEPTED", "EDITED", "REJECTED").contains(normalized)) {
            throw new IllegalArgumentException("Invalid radiologist disposition: " + disposition);
        }
        result.setRadiologistDisposition(normalized);
        result = aiResultRecordRepository.save(result);
        auditService.record(
                siteId, actor, "RADIOLOGIST", "RESULT_RETURN",
                "RADIOLOGIST_DISPOSITION_" + normalized, "AiResultRecord", result.getId().toString(),
                "SUCCESS", "{\"disposition\":\"" + normalized + "\",\"model\":\"" + result.getModelName() + "\"}");
        VendorDeliveryJob job = jobRepository.findById(result.getJobId()).orElse(null);
        liveStreamService.publishJobOrResultEvent(
                siteId, null, job, result, "Radiologist disposition updated to " + normalized + " by " + actor);
        return result;
    }

    /**
     * Enforces Section 5.1 & 5.2 independent per-vendor working-copy cleanup.
     * Never deletes the Original DICOM Repository, another vendor's working copy,
     * pseudonym mappings, or audit records.
     */
    @Transactional
    public int cleanupEligibleVendorWorkingCopies(UUID siteId, UUID vendorId, boolean dryRun) throws IOException {
        List<VendorDeliveryJob> vendorJobs = jobRepository.findBySiteIdAndVendorId(siteId, vendorId);
        int cleanedCount = 0;

        for (VendorDeliveryJob job : vendorJobs) {
            if (job.isLegalHold() || job.getWorkingCopyCleanedAt() != null || job.getWorkingCopyDir() == null) {
                continue;
            }
            // Eligibility gate: must have positive vendor acknowledgement and completed delivery
            boolean eligibleState = "COMPLETED".equals(job.getStatus()) || "VENDOR_ACCEPTED".equals(job.getStatus());
            if (!eligibleState || job.getVendorJobId() == null) {
                continue;
            }

            Path dir = Path.of(job.getWorkingCopyDir());
            if (Files.exists(dir)) {
                if (!dryRun) {
                    try (Stream<Path> walk = Files.walk(dir)) {
                        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(p);
                        }
                    }
                    job.setWorkingCopyCleanedAt(OffsetDateTime.now());
                    job.setUpdatedAt(OffsetDateTime.now());
                    jobRepository.save(job);
                }
                cleanedCount++;
            }
        }

        auditService.record(
                siteId, "CLEANUP_WORKER", "SYSTEM", "CLEANUP",
                dryRun ? "VENDOR_WORKING_COPY_CLEANUP_DRY_RUN" : "VENDOR_WORKING_COPY_CLEANUP_EXECUTED",
                "VendorConfig", vendorId.toString(),
                "SUCCESS", "{\"eligibleOrCleanedCount\":" + cleanedCount + ",\"dryRun\":" + dryRun + "}");
        if (!dryRun && cleanedCount > 0) {
            liveStreamService.publishJobOrResultEvent(
                    siteId, null, null, null, "Cleaned " + cleanedCount + " eligible vendor working copies");
        }
        return cleanedCount;
    }
}
