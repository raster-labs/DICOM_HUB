package com.kauvery.dicomhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kauvery.dicomhub.api.CommandCentreController;
import com.kauvery.dicomhub.api.CommandCentreController.DashboardOverviewDto;
import com.kauvery.dicomhub.api.CommandCentreController.ForwardedPatientTransferDto;
import com.kauvery.dicomhub.api.CommandCentreController.LlmGateTestRequestDto;
import com.kauvery.dicomhub.api.CommandCentreController.OperationalAlertDto;
import com.kauvery.dicomhub.api.CommandCentreController.RuleTestRequestDto;
import com.kauvery.dicomhub.api.CommandCentreController.StudyDetailTimelineDto;
import com.kauvery.dicomhub.api.CommandCentreController.VendorDestinationUpdateDto;
import com.kauvery.dicomhub.api.CommandCentreController.VendorWebhookPayloadDto;
import com.kauvery.dicomhub.config.HubProperties;
import com.kauvery.dicomhub.dicom.DicomNetworkScpService;
import com.kauvery.dicomhub.dicom.DicomWorkflowOrchestratorService;
import com.kauvery.dicomhub.domain.entity.AiResultRecord;
import com.kauvery.dicomhub.domain.entity.CleanupPolicy;
import com.kauvery.dicomhub.domain.entity.InstanceRecord;
import com.kauvery.dicomhub.domain.entity.PseudonymMapping;
import com.kauvery.dicomhub.domain.entity.RoutingRule;
import com.kauvery.dicomhub.domain.entity.StudyRecord;
import com.kauvery.dicomhub.domain.entity.StudyRegionMapping;
import com.kauvery.dicomhub.domain.entity.VendorConfig;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.domain.repository.CleanupPolicyRepository;
import com.kauvery.dicomhub.domain.repository.PseudonymMappingRepository;
import com.kauvery.dicomhub.domain.repository.StudyRecordRepository;
import com.kauvery.dicomhub.domain.repository.VendorDeliveryJobRepository;
import com.kauvery.dicomhub.routing.RoutingEngineService.RuleTestSandboxResultDto;
import com.kauvery.dicomhub.worker.DurableQueueWorkerService;
import com.kauvery.dicomhub.worker.RetentionCleanupWorkerService;
import com.kauvery.dicomhub.worker.RetentionCleanupWorkerService.CleanupExecutionReportDto;
import java.io.File;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.util.UIDUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CommandCentreAndWorkersIntegrationTest {

    private static final UUID KAUVERY_SITE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID UNREGISTERED_OTHER_SITE_ID = UUID.fromString("11111111-9999-9999-9999-999999999999");
    private static final UUID GROK_XRAY_VENDOR_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Autowired
    private HubProperties hubProperties;

    @Autowired
    private DicomWorkflowOrchestratorService orchestratorService;

    @Autowired
    private DurableQueueWorkerService queueWorkerService;

    @Autowired
    private RetentionCleanupWorkerService cleanupWorkerService;

    @Autowired
    private CommandCentreController controller;

    @Autowired
    private StudyRecordRepository studyRecordRepository;

    @Autowired
    private VendorDeliveryJobRepository jobRepository;

    @Autowired
    private PseudonymMappingRepository pseudonymMappingRepository;

    @Autowired
    private CleanupPolicyRepository cleanupPolicyRepository;

    @Autowired
    private DicomNetworkScpService dicomNetworkScpService;

    @Autowired
    private com.kauvery.dicomhub.dicom.LiveStudyEventStreamService liveStreamService;

    @Test
    void shouldEnforceMultiTenantSiteIsolationAndSeparatePacsReportStrategies() throws Exception {
        // Ingest an X-Ray study at Kauvery Hospital site
        String studyUid = UIDUtils.createUID();
        Attributes dataset = new Attributes();
        dataset.setString(Tag.PatientName, VR.PN, "KAUVERY^XRAY^PATIENT");
        dataset.setString(Tag.PatientID, VR.LO, "KH-PAT-1001");
        dataset.setString(Tag.AccessionNumber, VR.SH, "KH-ACC-2002");
        dataset.setString(Tag.Modality, VR.CS, "DX");
        dataset.setString(Tag.StudyDescription, VR.LO, "CHEST X-RAY PA");
        dataset.setString(Tag.BodyPartExamined, VR.CS, "CHEST");
        dataset.setString(Tag.StudyDate, VR.DA, "20261123");
        dataset.setDouble(Tag.SliceThickness, VR.DS, 1.5);
        dataset.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
        dataset.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
        dataset.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
        dataset.setString(Tag.SOPClassUID, VR.UI, UID.DigitalXRayImageStorageForPresentation);

        Attributes fmi = dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian);
        InstanceRecord inst = orchestratorService.ingestDicomInstance(
                KAUVERY_SITE_ID, "KAUVERY_DX01", "DICOMHUB_AE", fmi, dataset);

        // Verify study is visible only under KAUVERY_SITE_ID and never under an unregistered/other siteId
        assertThat(controller.listStudies(KAUVERY_SITE_ID, null, null, null))
                .extracting(StudyRecord::getId)
                .contains(inst.getStudyId());
        assertThat(controller.listStudies(UNREGISTERED_OTHER_SITE_ID, null, null, null))
                .extracting(StudyRecord::getId)
                .doesNotContain(inst.getStudyId());

        // Complete, route, and verify date shift (-42 days for Kauvery profile: 20261123 -> 20261012)
        List<VendorDeliveryJob> jobs = orchestratorService.completeValidateAndRouteStudy(
                KAUVERY_SITE_ID, inst.getStudyId(), false);
        assertThat(jobs).hasSize(1);
        VendorDeliveryJob job = jobs.getFirst();

        PseudonymMapping mapping = pseudonymMappingRepository
                .findBySiteIdAndStudyIdAndVendorId(KAUVERY_SITE_ID, inst.getStudyId(), job.getVendorId())
                .orElseThrow();

        File workingFile = new File(job.getWorkingCopyDir()).listFiles((d, name) -> name.endsWith(".dcm"))[0];
        try (DicomInputStream dis = new DicomInputStream(workingFile)) {
            Attributes deidAttrs = dis.readDataset();
            assertThat(deidAttrs.getString(Tag.StudyDate)).isEqualTo("20261012");
        }

        // Process outbound job and verify idempotent webhook reception & radiologist disposition update
        job = orchestratorService.processOutboundJob(KAUVERY_SITE_ID, job.getId(), false);
        assertThat(job.getStatus()).isEqualTo("COMPLETED");

        AiResultRecord result = controller.receiveVendorWebhookResult(
                "VENDOR-GROK-XRAY",
                KAUVERY_SITE_ID,
                new VendorWebhookPayloadDto(
                        job.getId(),
                        job.getVendorJobId(),
                        mapping.getPseudoStudyUid(),
                        "ENCAPSULATED_PDF",
                        "grok-2-vision-1212",
                        "xAI-Grok-Vision-v1",
                        "{\"findings\":[{\"type\":\"clear_lungs\",\"confidence\":0.96}]}"));
        assertThat(result).isNotNull();

        // Duplicate webhook delivery must be idempotent
        AiResultRecord duplicateWebhookResult = controller.receiveVendorWebhookResult(
                "VENDOR-GROK-XRAY",
                KAUVERY_SITE_ID,
                new VendorWebhookPayloadDto(
                        null,
                        job.getVendorJobId(),
                        mapping.getPseudoStudyUid(),
                        "ENCAPSULATED_PDF",
                        "grok-2-vision-1212",
                        "xAI-Grok-Vision-v1",
                        "{\"findings\":[{\"type\":\"clear_lungs\",\"confidence\":0.96}]}"));
        assertThat(duplicateWebhookResult.getId()).isEqualTo(result.getId());

        // Radiologist disposition update (FR-25, FR-27)
        AiResultRecord edited = controller.updateResultDisposition(
                result.getId(), KAUVERY_SITE_ID, "EDITED", "dr.sundar");
        assertThat(edited.getRadiologistDisposition()).isEqualTo("EDITED");
    }

    @Test
    void shouldProcessQuietPeriodAndDrainQueueViaDurableQueueWorkerSkipLocked() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();
        Attributes dataset = new Attributes();
        dataset.setString(Tag.PatientName, VR.PN, "QUIET^PERIOD^TEST");
        dataset.setString(Tag.PatientID, VR.LO, "KH-QP-9001");
        dataset.setString(Tag.Modality, VR.CS, "DX");
        dataset.setString(Tag.StudyDescription, VR.LO, "CHEST X-RAY PA");
        dataset.setString(Tag.BodyPartExamined, VR.CS, "CHEST");
        dataset.setDouble(Tag.SliceThickness, VR.DS, 1.5);
        dataset.setString(Tag.StudyInstanceUID, VR.UI, UIDUtils.createUID());
        dataset.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
        dataset.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
        dataset.setString(Tag.SOPClassUID, VR.UI, UID.DigitalXRayImageStorageForPresentation);

        Attributes fmi = dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian);
        InstanceRecord inst = orchestratorService.ingestDicomInstance(
                siteId, "KAUVERY_DX01", "DICOMHUB_AE", fmi, dataset);

        // Before 120s quiet period elapses, study should not be completed
        int prematureCompleted = queueWorkerService.processQuietPeriodCompletedStudies(
                OffsetDateTime.now().plusSeconds(30));
        assertThat(prematureCompleted).isZero();

        // After 125s quiet period elapses, worker completes, validates, routes, and queues job
        int completed = queueWorkerService.processQuietPeriodCompletedStudies(
                OffsetDateTime.now().plusSeconds(125));
        assertThat(completed).isGreaterThanOrEqualTo(1);

        StudyRecord routedStudy = studyRecordRepository.findById(inst.getStudyId()).orElseThrow();
        assertThat(routedStudy.getLifecycleState()).isEqualTo("ROUTED");

        // Drain outbound queue via SELECT ... FOR UPDATE SKIP LOCKED
        int drained = queueWorkerService.drainDueOutboundJobs(OffsetDateTime.now().plusSeconds(130));
        assertThat(drained).isGreaterThanOrEqualTo(1);

        List<VendorDeliveryJob> jobs = jobRepository.findBySiteIdAndStudyId(siteId, inst.getStudyId());
        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().getStatus()).isIn("VENDOR_ACCEPTED", "COMPLETED");
    }

    @Test
    void shouldEnforceShadowModeValidationFailurePseudonymQuarantineAndLlmAc9Gate() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();

        // 1. FR-36 Shadow Mode (toggle Grok vendor into shadow mode, run X-Ray, then restore live mode)
        controller.toggleVendorShadowMode(GROK_XRAY_VENDOR_ID, siteId);
        UUID shadowJobId;
        try {
            Map<String, Object> shadowRun = controller.runSimulatorScenario(siteId, "grok-xray");
            UUID shadowStudyId = (UUID) shadowRun.get("studyId");
            StudyDetailTimelineDto shadowDetails = controller.getStudyDetails(shadowStudyId, siteId);
            assertThat(shadowDetails.results()).hasSize(1);
            assertThat(shadowDetails.results().getFirst().isPacsForwarded()).isFalse();
            assertThat(shadowDetails.results().getFirst().getReportPrefillStatus()).isEqualTo("SKIPPED_SHADOW_MODE");
            shadowJobId = shadowDetails.jobs().getFirst().getId();
        } finally {
            controller.toggleVendorShadowMode(GROK_XRAY_VENDOR_ID, siteId);
        }

        // 2. FR-07 / AC-3 Invalid study (negative slice thickness) -> VALIDATION_FAILED
        Map<String, Object> invalidRun = controller.runSimulatorScenario(siteId, "invalid-study");
        UUID invalidStudyId = (UUID) invalidRun.get("studyId");
        StudyRecord invalidStudy = studyRecordRepository.findById(invalidStudyId).orElseThrow();
        assertThat(invalidStudy.getLifecycleState()).isEqualTo("VALIDATION_FAILED");
        assertThat(invalidStudy.getValidationError()).contains("FR-07 Validation Failed");
        assertThat(jobRepository.findBySiteIdAndStudyId(siteId, invalidStudyId)).isEmpty();

        // 3. Pseudonym Mismatch Quarantine (Section 4.8) — must persist MANUAL_REVIEW status and emit SSE delta
        assertThatThrownBy(() -> orchestratorService.receiveAndReturnAiResult(
                siteId, shadowJobId, "2.25.9999999999999999", "DICOM_SR_TID1500", "GrokVision", "v1.0", "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Quarantined");
        assertThat(jobRepository.findById(shadowJobId).orElseThrow().getStatus()).isEqualTo("MANUAL_REVIEW");

        // 4. Verify active alerts are generated for validation failure AND quarantined job
        List<OperationalAlertDto> alerts = controller.listActiveAlerts(siteId);
        assertThat(alerts).anyMatch(a -> "STUDY_VALIDATION".equals(a.category()));
        assertThat(alerts).anyMatch(a -> "RESULT_QUARANTINE".equals(a.category()) && shadowJobId.toString().equals(a.entityId()));

        // 5. AC-9 Phase 2 LLM Prompt & Reply Sanitization Gate
        Map<String, Object> cleanGate = controller.testLlmPromptAndReplyGate(
                siteId,
                new LlmGateTestRequestDto("DX", "CHEST", "045Y", "F", "1.5", "",
                        "{\"findings\":[{\"type\":\"clear_lung_fields\",\"confidence\":0.96}],\"impression_draft\":\"Draft\"}"));
        assertThat(cleanGate.get("promptApproved")).isEqualTo(true);
        assertThat(cleanGate.get("replyValid")).isEqualTo(true);

        // Injecting a real patient ID into prompt variables or reply must be blocked by AC-9
        Map<String, Object> leakedGate = controller.testLlmPromptAndReplyGate(
                siteId,
                new LlmGateTestRequestDto("DX", "CHEST KH-PAT-9988", "045Y", "F", "1.5", "KH-PAT-9988",
                        "{\"findings\":[],\"patient_name\":\"RAMESH\"}"));
        assertThat(leakedGate.get("promptApproved")).isEqualTo(false);
        assertThat(leakedGate.get("replyValid")).isEqualTo(false);
    }

    @Test
    void shouldSupportDynamicVendorOnboardingRuleDryRunAndCleanupLegalHold() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();

        // 1. Onboard a new AI vendor without code changes (FR-34)
        VendorConfig newVendor = new VendorConfig();
        newVendor.setVendorCode("VENDOR-CHEST-XR-TEMP");
        newVendor.setVendorName("ThoraxSight AI");
        newVendor.setUseCaseName("Chest X-Ray Pneumothorax & Nodule Triage");
        newVendor.setConnectorType("DICOMWEB_STOW");
        newVendor.setApiBaseUrl("https://stow.thoraxsight.example.com/dicomweb");
        newVendor.setAuthType("OAUTH2_CLIENT_CREDENTIALS");
        newVendor.setOutboundImageFormat("DICOM_NATIVE");
        newVendor.setReturnDeliveryMode("PDF_VIA_RIS_API");
        newVendor.setSlaTurnaroundMinutes(10);
        newVendor.setShadowMode(true);
        VendorConfig savedVendor = controller.createOrOnboardVendor(siteId, newVendor);
        assertThat(savedVendor.getId()).isNotNull();

        // 2. Create a routing rule for the newly onboarded vendor
        RoutingRule rule = new RoutingRule();
        rule.setRuleName("Rule Temp – Chest X-Ray Triage");
        rule.setPriorityOrder(4);
        rule.setModality("CR");
        rule.setStudyDescriptionPattern("PNEUMOTHORAX TRIAGE");
        rule.setBodyPartPattern("CHEST");
        rule.setMatchOperator("CONTAINS");
        rule.setVendorId(savedVendor.getId());
        rule.setAllowMultiVendor(true);
        RoutingRule savedRule = controller.createRoutingRule(siteId, rule);

        try {
            Map<String, Object> connTest = controller.testVendorConnection(savedVendor.getId(), siteId);
            assertThat(connTest.get("connected")).isEqualTo(true);

            // 3. Dry-run test sample study metadata before clinical traffic (Section 4.4)
            RuleTestSandboxResultDto sandbox = controller.testRoutingRulesAgainstSample(
                    siteId,
                    new RuleTestRequestDto("CR", "PNEUMOTHORAX TRIAGE", "CHEST", "PA", null));
            assertThat(sandbox.matchedRulesCount()).isGreaterThanOrEqualTo(1);
            assertThat(sandbox.matchedRules())
                    .extracting(m -> m.vendorCode())
                    .contains("VENDOR-CHEST-XR-TEMP");

            // 4. Verify independent per-vendor cleanup policy was auto-created and test legal hold
            CleanupPolicy vendorPolicy = cleanupPolicyRepository.findBySiteIdOrderByTargetCategoryAsc(siteId).stream()
                    .filter(p -> savedVendor.getId().equals(p.getVendorId()))
                    .findFirst()
                    .orElseThrow();

            controller.toggleCleanupPolicyLegalHold(vendorPolicy.getId(), siteId);
            CleanupExecutionReportDto holdReport = controller.executeCleanupPolicy(vendorPolicy.getId(), siteId, false);
            assertThat(holdReport.skippedByLegalHold()).isTrue();
        } finally {
            controller.deleteRoutingRule(savedRule.getId(), siteId);
            controller.deleteVendor(savedVendor.getId(), siteId);
        }
        DashboardOverviewDto overview = controller.getOverview(siteId);
        assertThat(overview.totalStudiesReceived()).isGreaterThan(0);
    }

    @Test
    void shouldRejectMultiSeriesViolationsCleanUpPartialWorkingCopiesAndSupportMultiSiteLlmAndResend() throws Exception {
        // 1. Verify DicomNetworkScpService resolves tenant siteId from callingAeTitle for Kauvery Hospital
        assertThat(dicomNetworkScpService.resolveSiteIdForCallingAe("KAUVERY_DX01")).isEqualTo(KAUVERY_SITE_ID);
        assertThat(dicomNetworkScpService.resolveSiteIdForCallingAe("TEAMPACS")).isEqualTo(KAUVERY_SITE_ID);

        // 2. Burned-in PHI simulation and manual retry on DEIDENTIFICATION_FAILED job
        Map<String, Object> burnedInSim = controller.runSimulatorScenario(KAUVERY_SITE_ID, "burned-in");
        UUID burnedStudyId = (UUID) burnedInSim.get("studyId");
        StudyRecord burnedStudy = studyRecordRepository.findById(burnedStudyId).orElseThrow();
        assertThat(burnedStudy.getCallingAeTitle()).isIn("TEAMPACS", "RASTERIPACS", "KAUVERY_PACS");

        List<VendorDeliveryJob> burnedJobs = jobRepository.findBySiteIdAndStudyId(KAUVERY_SITE_ID, burnedStudyId);
        assertThat(burnedJobs).hasSize(1);
        VendorDeliveryJob blockedJob = burnedJobs.getFirst();
        assertThat(blockedJob.getStatus()).isEqualTo("DEIDENTIFICATION_FAILED");
        assertThat(blockedJob.getWorkingCopyDir()).isNull();

        VendorDeliveryJob retriedBlockedJob = controller.manualRetryJob(blockedJob.getId(), KAUVERY_SITE_ID);
        assertThat(retriedBlockedJob.getStatus()).isEqualTo("DEIDENTIFICATION_FAILED");
        assertThat(retriedBlockedJob.getWorkingCopyDir()).isNull();

        // 3. Multi-series study where Series 1 violates requiredSeriesPattern ("SCOUT", 1.5mm)
        // and Series 2 violates maxSliceThicknessMm ("AXIAL", 25.0mm > 10.0mm for X-Ray rule)
        // must NOT match the rule or fall back to sending the SCOUT series!
        String multiSeriesStudyUid = UIDUtils.createUID();
        Attributes scoutDs = new Attributes();
        scoutDs.setString(Tag.PatientName, VR.PN, "MULTI^SERIES^TEST");
        scoutDs.setString(Tag.PatientID, VR.LO, "KH-MS-001");
        scoutDs.setString(Tag.Modality, VR.CS, "DX");
        scoutDs.setString(Tag.StudyDescription, VR.LO, "CHEST X-RAY MULTI SERIES");
        scoutDs.setString(Tag.BodyPartExamined, VR.CS, "CHEST");
        scoutDs.setString(Tag.SeriesDescription, VR.LO, "SCOUT LOCALIZER");
        scoutDs.setDouble(Tag.SliceThickness, VR.DS, 1.5);
        scoutDs.setString(Tag.StudyInstanceUID, VR.UI, multiSeriesStudyUid);
        scoutDs.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
        scoutDs.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
        scoutDs.setString(Tag.SOPClassUID, VR.UI, UID.DigitalXRayImageStorageForPresentation);
        orchestratorService.ingestDicomInstance(
                KAUVERY_SITE_ID, "KAUVERY_DX01", "DICOMHUB_AE",
                scoutDs.createFileMetaInformation(UID.ExplicitVRLittleEndian), scoutDs);

        Attributes thickAxialDs = new Attributes(scoutDs);
        thickAxialDs.setString(Tag.SeriesDescription, VR.LO, "PA THICK 25MM");
        thickAxialDs.setDouble(Tag.SliceThickness, VR.DS, 25.0);
        thickAxialDs.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
        thickAxialDs.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
        InstanceRecord msInst2 = orchestratorService.ingestDicomInstance(
                KAUVERY_SITE_ID, "KAUVERY_DX01", "DICOMHUB_AE",
                thickAxialDs.createFileMetaInformation(UID.ExplicitVRLittleEndian), thickAxialDs);

        List<VendorDeliveryJob> msJobs = orchestratorService.completeValidateAndRouteStudy(
                KAUVERY_SITE_ID, msInst2.getStudyId(), false);
        assertThat(msJobs).isEmpty();
        StudyRecord msStudy = studyRecordRepository.findById(msInst2.getStudyId()).orElseThrow();
        assertThat(msStudy.getLifecycleState()).isEqualTo("UNMATCHED_NO_RULE");

        // 4. Multi-instance study where Instance 1 is clean and Instance 2 has RecognizableVisualFeatures=YES
        // (detected during deep dataset inspection) must clean up Instance 1's partial working copy file!
        String partialDeidStudyUid = UIDUtils.createUID();
        Attributes cleanSlice = new Attributes();
        cleanSlice.setString(Tag.PatientName, VR.PN, "PARTIAL^DEID^TEST");
        cleanSlice.setString(Tag.PatientID, VR.LO, "KH-PD-001");
        cleanSlice.setString(Tag.Modality, VR.CS, "DX");
        cleanSlice.setString(Tag.StudyDescription, VR.LO, "CHEST X-RAY PARTIAL DEID");
        cleanSlice.setString(Tag.BodyPartExamined, VR.CS, "CHEST");
        cleanSlice.setString(Tag.SeriesDescription, VR.LO, "CHEST PA");
        cleanSlice.setDouble(Tag.SliceThickness, VR.DS, 1.5);
        cleanSlice.setString(Tag.StudyInstanceUID, VR.UI, partialDeidStudyUid);
        cleanSlice.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
        cleanSlice.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
        cleanSlice.setString(Tag.SOPClassUID, VR.UI, UID.DigitalXRayImageStorageForPresentation);
        orchestratorService.ingestDicomInstance(
                KAUVERY_SITE_ID, "KAUVERY_DX01", "DICOMHUB_AE",
                cleanSlice.createFileMetaInformation(UID.ExplicitVRLittleEndian), cleanSlice);

        Attributes unsafeSlice2 = new Attributes(cleanSlice);
        unsafeSlice2.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
        unsafeSlice2.setString(Tag.RecognizableVisualFeatures, VR.CS, "YES");
        InstanceRecord pdInst2 = orchestratorService.ingestDicomInstance(
                KAUVERY_SITE_ID, "KAUVERY_DX01", "DICOMHUB_AE",
                unsafeSlice2.createFileMetaInformation(UID.ExplicitVRLittleEndian), unsafeSlice2);

        List<VendorDeliveryJob> pdJobs = orchestratorService.completeValidateAndRouteStudy(
                KAUVERY_SITE_ID, pdInst2.getStudyId(), false);
        assertThat(pdJobs).hasSize(1);
        assertThat(pdJobs.getFirst().getStatus()).isEqualTo("DEIDENTIFICATION_FAILED");
        assertThat(pdJobs.getFirst().getWorkingCopyDir()).isNull();

        // 5. Deliberate on-demand resend (FR-04/FR-06) after completion and cleanup resets job state cleanly
        Map<String, Object> grokSim = controller.runSimulatorScenario(KAUVERY_SITE_ID, "grok-xray");
        UUID grokStudyId = (UUID) grokSim.get("studyId");
        VendorDeliveryJob grokJob = jobRepository.findBySiteIdAndStudyId(KAUVERY_SITE_ID, grokStudyId).getFirst();
        assertThat(grokJob.getStatus()).isEqualTo("COMPLETED");
        orchestratorService.cleanupEligibleVendorWorkingCopies(KAUVERY_SITE_ID, grokJob.getVendorId(), false);

        List<VendorDeliveryJob> resentJobs = controller.routeStudyOnDemand(grokStudyId, KAUVERY_SITE_ID, true);
        assertThat(resentJobs).hasSize(1);
        VendorDeliveryJob resentJob = resentJobs.getFirst();
        assertThat(resentJob.getStatus()).isIn("VENDOR_ACCEPTED", "COMPLETED");
        assertThat(resentJob.getWorkingCopyCleanedAt()).isNull();
        assertThat(resentJob.getAttemptCount()).isEqualTo(1);

        int resentCleaned = orchestratorService.cleanupEligibleVendorWorkingCopies(
                KAUVERY_SITE_ID, resentJob.getVendorId(), false);
        assertThat(resentCleaned).isEqualTo(1);
    }

    @Test
    void shouldConfigureOnPremDicomUploaderRouterDestinationsTrackForwardedPatientImagesAndMapBrainProtocolAliases() throws Exception {
        // 1. Dynamically add a custom X-Ray study description alias ("PORTABLE ICU CXR PROTOCOL")
        //    to X-Ray Study region mapping, verify resolution, then remove it and verify pruning!
        List<StudyRegionMapping> mappings = controller.listRegionMappings(KAUVERY_SITE_ID);
        int initialMappingCount = mappings.size();
        StudyRegionMapping xrayMapping = mappings.stream()
                .filter(m -> "X-Ray Study".equalsIgnoreCase(m.getCanonicalRegion()))
                .findFirst()
                .orElseThrow();
        String originalXrayDescriptions = xrayMapping.getStudyDescriptions();
        try {
            xrayMapping.setStudyDescriptions(originalXrayDescriptions + ", PORTABLE ICU CXR PROTOCOL");
            controller.updateRegionMapping(xrayMapping.getId(), KAUVERY_SITE_ID, xrayMapping);

            RuleTestSandboxResultDto customAliasMatch = controller.testRoutingRulesAgainstSample(
                    KAUVERY_SITE_ID,
                    new RuleTestRequestDto("DX", "PORTABLE ICU CXR PROTOCOL", "CHEST", "PA", 1.0));
            assertThat(customAliasMatch.resolvedCanonicalRegion()).isEqualTo("X-Ray Study");
            assertThat(customAliasMatch.matchedRulesCount()).isEqualTo(1);
            assertThat(customAliasMatch.matchedRules().getFirst().vendorCode()).isEqualTo("VENDOR-GROK-XRAY");
        } finally {
            xrayMapping.setStudyDescriptions(originalXrayDescriptions);
            controller.updateRegionMapping(xrayMapping.getId(), KAUVERY_SITE_ID, xrayMapping);
        }

        // Calling createOrUpdateRegionMapping without ID for existing (X-Ray Study, CR,DX,RF) must update in place without duplicates
        StudyRegionMapping upsertMapping = new StudyRegionMapping();
        upsertMapping.setCanonicalRegion("X-Ray Study");
        upsertMapping.setModality(xrayMapping.getModality());
        upsertMapping.setBodyPart("CHEST");
        upsertMapping.setStudyDescriptions(originalXrayDescriptions);
        upsertMapping.setTargetVendorId(GROK_XRAY_VENDOR_ID);
        controller.createOrUpdateRegionMapping(KAUVERY_SITE_ID, upsertMapping);
        assertThat(controller.listRegionMappings(KAUVERY_SITE_ID)).hasSize(initialMappingCount);

        // 2. Simulate Grok X-Ray studies (DX, CR, RF) and verify Forwarded Patient Details & Image Transfer Counts
        Map<String, Object> dxSim = controller.runSimulatorScenario(KAUVERY_SITE_ID, "grok-xray");
        Map<String, Object> crSim = controller.runSimulatorScenario(KAUVERY_SITE_ID, "grok-xray-cr");
        UUID dxStudyId = (UUID) dxSim.get("studyId");
        UUID crStudyId = (UUID) crSim.get("studyId");

        List<ForwardedPatientTransferDto> forwarded = controller.listForwardedPatients(KAUVERY_SITE_ID, null, null);
        assertThat(forwarded).isNotEmpty();

        ForwardedPatientTransferDto dxRow = forwarded.stream()
                .filter(r -> dxStudyId.equals(r.studyId()))
                .findFirst()
                .orElseThrow();
        assertThat(dxRow.modality()).isEqualTo("DX");
        assertThat(dxRow.vendorCode()).isEqualTo("VENDOR-GROK-XRAY");
        assertThat(dxRow.transferredInstances()).isEqualTo(1);

        ForwardedPatientTransferDto crRow = forwarded.stream()
                .filter(r -> crStudyId.equals(r.studyId()))
                .findFirst()
                .orElseThrow();
        assertThat(crRow.modality()).isEqualTo("CR");
        assertThat(crRow.vendorCode()).isEqualTo("VENDOR-GROK-XRAY");
        assertThat(crRow.transferredInstances()).isEqualTo(1);
    }

    @Test
    void shouldSupportUniversalFuzzySearchLiveIncomingStudyCountsRbacAccessControlCEchoAndUserManagement() throws Exception {
        try {
            // 1. Live Incoming Study & Image-Count Updates (FR-46) from RIS-PACS (TEAMPACS)
            DashboardOverviewDto beforeOverview = controller.getOverview(KAUVERY_SITE_ID);
            long initialTotalImages = beforeOverview.totalImagesReceived();
            long initialTodayImages = beforeOverview.imagesReceivedToday();
            long initialTransferEvents = beforeOverview.totalTransferEventsReceived();
            long initialDuplicates = beforeOverview.duplicateInstancesSkipped();

            // Start a live incoming RIS-PACS study in RECEIVING status (3 unique slices)
            Map<String, Object> receivingSim = controller.simulateLiveIncomingStudy(
                    KAUVERY_SITE_ID,
                    new CommandCentreController.LiveIncomingStreamScenarioRequestDto(
                            "RIS_PACS",
                            "TEAMPACS",
                            "MR998811",
                            "AMUTHA^KAUVERY",
                            "DX",
                            "CHEST X-RAY PA",
                            "CHEST",
                            3,
                            false,
                            "RECEIVING",
                            null));
            UUID liveStudyId = (UUID) receivingSim.get("studyId");
            assertThat(receivingSim.get("sourceType")).isEqualTo("RIS_PACS");
            assertThat(receivingSim.get("receivingStatus")).isEqualTo("RECEIVING");
            assertThat(receivingSim.get("uniqueStoredInstances")).isEqualTo(3);

            DashboardOverviewDto duringReceiving = controller.getOverview(KAUVERY_SITE_ID);
            assertThat(duringReceiving.totalImagesReceived()).isEqualTo(initialTotalImages + 3);
            assertThat(duringReceiving.imagesReceivedToday()).isEqualTo(initialTodayImages + 3);
            assertThat(duringReceiving.studiesCurrentlyReceiving()).isGreaterThanOrEqualTo(1);
            // Verify coalesced INSTANCE_COMMITTED overview snapshot has byte-exact originalRepoTotalBytes
            var latestReceivingEvent = controller.listRecentLiveEvents(KAUVERY_SITE_ID).getFirst();
            assertThat(latestReceivingEvent.eventType()).isEqualTo("INSTANCE_COMMITTED");
            assertThat(latestReceivingEvent.overview()).isNotNull();
            assertThat(latestReceivingEvent.overview().originalRepoTotalBytes())
                    .isEqualTo(duringReceiving.originalRepoTotalBytes());

            // Retransmit a duplicate SOPInstanceUID on that study:
            // receivedTransferEvents and duplicateInstancesSkipped must increment, while uniqueStoredInstances must NOT double-count!
            Map<String, Object> dupRes = controller.simulateDuplicateRetransmissionForStudy(liveStudyId, KAUVERY_SITE_ID);
            assertThat(dupRes.get("uniqueStoredInstances")).isEqualTo(3);
            assertThat(dupRes.get("receivedTransferEvents")).isEqualTo(4);
            assertThat(dupRes.get("duplicateInstancesSkipped")).isEqualTo(1);

            DashboardOverviewDto afterDupOverview = controller.getOverview(KAUVERY_SITE_ID);
            assertThat(afterDupOverview.totalImagesReceived()).isEqualTo(initialTotalImages + 3);
            assertThat(afterDupOverview.totalTransferEventsReceived()).isEqualTo(initialTransferEvents + 4);
            assertThat(afterDupOverview.duplicateInstancesSkipped()).isEqualTo(initialDuplicates + 1);

            // Transition study receiving status to INCOMPLETE, then COMPLETED via routing
            StudyRecord incompleteStudy = controller.updateStudyReceivingStatus(
                    liveStudyId, KAUVERY_SITE_ID, "INCOMPLETE", "Partial transfer interrupted");
            assertThat(incompleteStudy.getReceivingStatus()).isEqualTo("INCOMPLETE");

            controller.routeStudyOnDemand(liveStudyId, KAUVERY_SITE_ID, true);
            StudyRecord completedStudy = studyRecordRepository.findById(liveStudyId).orElseThrow();
            assertThat(completedStudy.getReceivingStatus()).isEqualTo("COMPLETED");
            var recentLiveEvents = controller.listRecentLiveEvents(KAUVERY_SITE_ID);
            assertThat(recentLiveEvents).isNotEmpty();
            var latestLiveEvent = recentLiveEvents.getFirst();
            assertThat(latestLiveEvent.updatedStudy()).isNotNull();
            assertThat(latestLiveEvent.overview()).isNotNull();
            assertThat(latestLiveEvent.overview().totalImagesReceived()).isGreaterThanOrEqualTo(3);
            assertThat(latestLiveEvent.jobs()).isNotNull();
            assertThat(latestLiveEvent.forwardedPatients()).isNotNull();
            assertThat(latestLiveEvent.vendorMetrics()).isNotNull();
            var maskedLiveEvent = liveStreamService.maskDtoForRestrictedSubscriber(latestLiveEvent);
            assertThat(maskedLiveEvent.vendorMetrics())
                    .isNotEmpty()
                    .allMatch(m -> "RESTRICTED".equals(m.destinationAeTitle())
                            && "RESTRICTED".equals(m.destinationHost())
                            && m.destinationPort() == 0);
            assertThat(controller.streamLiveIncomingStudies(KAUVERY_SITE_ID)).isNotNull();

            // Verify multi-study coalescing within 100ms preserves both distinct studies (Study A and Study B)
            StudyRecord studyB = new StudyRecord();
            studyB.setId(UUID.randomUUID());
            studyB.setSiteId(KAUVERY_SITE_ID);
            studyB.setStudyInstanceUid(UIDUtils.createUID());
            studyB.setPatientId("MR-COALESCE-B");
            studyB.setPatientName("COALESCE^STUDY^B");
            studyB.setModality("DX");
            studyB.setStudyDescription("CHEST X-RAY PA");
            studyB.setSourceType("RIS_PACS");
            studyB.setCallingAeTitle("TEAMPACS");
            studyB.setReceivingStatus("RECEIVING");
            studyB.setInstanceCount(1);
            studyB.setReceivedTransferEvents(1);
            studyB.setTotalBytes(1024L);
            liveStreamService.publishStudyEvent(
                    KAUVERY_SITE_ID, "INSTANCE_COMMITTED", completedStudy, "1.2.3.1", "Leading or coalesced A1");
            liveStreamService.publishStudyEvent(
                    KAUVERY_SITE_ID, "INSTANCE_COMMITTED", studyB, "1.2.3.2", "Coalesced B1");
            liveStreamService.publishStudyEvent(
                    KAUVERY_SITE_ID, "INSTANCE_COMMITTED", completedStudy, "1.2.3.3", "Coalesced A2");
            assertThat(liveStreamService.getPendingCoalescedStudyCount(KAUVERY_SITE_ID)).isEqualTo(2);

            // 2. Universal Search with Exact vs Fuzzy Matching (Req 1 / FR-48)
            // Exact search ("AMUTA") with fuzzy=false should not match "AMUTHA KAUVERY"
            var exactMiss = controller.universalSearch(KAUVERY_SITE_ID, "AMUTA", false, null);
            assertThat(exactMiss.hits()).noneMatch(h -> h.title().contains("AMUTHA"));

            // Fuzzy search ("AMUTA") with fuzzy=true must fuzzy-match "AMUTHA KAUVERY"
            var fuzzyHit = controller.universalSearch(KAUVERY_SITE_ID, "AMUTA", true, 0.55);
            assertThat(fuzzyHit.hits()).anyMatch(h -> h.title().contains("AMUTHA"));

            // Fuzzy search for typo "CHST X-RAY" must match X-Ray region mappings & rules
            var fuzzyXrayHit = controller.universalSearch(KAUVERY_SITE_ID, "CHST X-RAY", true, 0.55);
            assertThat(fuzzyXrayHit.totalHits()).isGreaterThan(0);

            // 3. AE Title Echo & Test Connections (Req 5 & Req 6)
            var localEcho = controller.testLocalScpCEcho(KAUVERY_SITE_ID);
            assertThat(localEcho.success()).isTrue();
            assertThat(localEcho.dimseStatus()).contains("0x0000");

            var adHocValidEcho = controller.runAdHocCEcho(
                    KAUVERY_SITE_ID,
                    new CommandCentreController.AdHocCEchoRequestDto(
                            "DICOMHUB_AE", "TEAMPACS", "172.17.0.155", 11112, "TEAMPACS"));
            assertThat(adHocValidEcho.success()).isTrue();

            var adHocInvalidAeEcho = controller.runAdHocCEcho(
                    KAUVERY_SITE_ID,
                    new CommandCentreController.AdHocCEchoRequestDto(
                            "DICOMHUB_AE", "THIS_AE_TITLE_IS_WAY_TOO_LONG_FOR_DICOM", "127.0.0.1", 11112, "BadNode"));
            assertThat(adHocInvalidAeEcho.success()).isFalse();

            var sources = controller.listSources(KAUVERY_SITE_ID);
            assertThat(sources).isNotEmpty();
            Map<String, Object> sourceTest = controller.testSourceAssociation(sources.getFirst().getId(), KAUVERY_SITE_ID);
            assertThat(sourceTest.get("cEchoSuccess")).isEqualTo(true);

            var pacsList = controller.listPacsDestinations(KAUVERY_SITE_ID);
            assertThat(pacsList).isNotEmpty();
            Map<String, Object> pacsTest = controller.testPacsDestinationConnection(pacsList.getFirst().getId(), KAUVERY_SITE_ID);
            assertThat(pacsTest.get("cEchoSuccess")).isEqualTo(true);

            // 4. User Management & DICOM Configuration Access Control (Req 2.2 / FR-47 & Req 7)
            var users = controller.listUsers(KAUVERY_SITE_ID);
            assertThat(users).hasSizeGreaterThanOrEqualTo(6);

            var superUser = users.stream().filter(u -> "SUPER_USER".equals(u.getUserRole())).findFirst().orElseThrow();
            var restrictedAdmin = users.stream().filter(u -> "ops.admin".equals(u.getUsername())).findFirst().orElseThrow();
            var radiologist = users.stream().filter(u -> "RADIOLOGIST".equals(u.getUserRole())).findFirst().orElseThrow();

            var localSettings = controller.getDicomNodeSettings(KAUVERY_SITE_ID);

            // 4a. Radiologist ("Other users") has NO access to DICOM configuration settings (blocked from VIEW & EDIT)
            controller.setTestActingUser(radiologist);
            assertThatThrownBy(() -> controller.getDicomNodeSettings(KAUVERY_SITE_ID))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("DICOM Configuration Access Denied");
            assertThatThrownBy(() -> controller.listSources(KAUVERY_SITE_ID))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("DICOM Configuration Access Denied");
            assertThatThrownBy(() -> controller.listVendors(KAUVERY_SITE_ID))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("DICOM Configuration Access Denied");
            assertThatThrownBy(() -> controller.listRoutingRules(KAUVERY_SITE_ID))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("DICOM Configuration Access Denied");
            assertThatThrownBy(() -> controller.updateDicomNodeSettings(KAUVERY_SITE_ID, localSettings))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("DICOM Configuration Access Denied");

            // Universal search as Radiologist must NOT leak DICOM configuration items, Destination AE on jobs, or CONFIG_CHANGE audits
            var radSearch = controller.universalSearch(KAUVERY_SITE_ID, "TEAMPACS", true, 0.55);
            assertThat(radSearch.hits()).noneMatch(h ->
                    "DICOM_SOURCE".equals(h.category())
                            || "DESTINATION_VENDOR".equals(h.category())
                            || "ROUTING_RULE".equals(h.category())
                            || ("AUDIT_EVENT".equals(h.category()) && "CONFIG_CHANGE".equals(h.badge())));

            var radDestAeSearch = controller.universalSearch(KAUVERY_SITE_ID, "GROK_XRAY_AI", false, null);
            assertThat(radDestAeSearch.hits()).isEmpty();

            var radMetrics = controller.getVendorMetrics(KAUVERY_SITE_ID);
            assertThat(radMetrics).allMatch(m -> "RESTRICTED".equals(m.destinationAeTitle()) && "RESTRICTED".equals(m.destinationHost()));

            // 4b. Restricted Administrator (canView/Create/Edit = true, canDelete = false) can create & edit a DICOM source,
            //     but is blocked from deleting it!
            controller.setTestActingUser(restrictedAdmin);
            var newSrc = new com.kauvery.dicomhub.domain.entity.DicomSource();
            newSrc.setSourceName("X-Ray Console 2 Test");
            newSrc.setSourceType("MODALITY");
            newSrc.setCallingAeTitle("KAUVERY_DX02");
            newSrc.setCalledAeTitle("DICOMHUB_AE");
            newSrc.setHostAddress("10.10.20.19");
            newSrc.setDicomPort(104);
            newSrc.setQuietPeriodSeconds(90);
            newSrc.setActive(true);
            var createdSrc = controller.createSource(KAUVERY_SITE_ID, newSrc);
            assertThat(createdSrc.getId()).isNotNull();

            createdSrc.setDicomPort(11112);
            var updatedSrc = controller.updateSource(createdSrc.getId(), KAUVERY_SITE_ID, createdSrc);
            assertThat(updatedSrc.getDicomPort()).isEqualTo(11112);

            assertThatThrownBy(() -> controller.deleteSource(createdSrc.getId(), KAUVERY_SITE_ID))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("not authorised to DELETE DICOM configuration");

            // 4c. Super User can delete the DICOM source, update Local AE settings, and manage users
            controller.setTestActingUser(superUser);
            Map<String, Object> delSrcRes = controller.deleteSource(createdSrc.getId(), KAUVERY_SITE_ID);
            assertThat(delSrcRes.get("deleted")).isEqualTo(true);

            localSettings.setDefaultQuietPeriodSeconds(90);
            var updatedSettings = controller.updateDicomNodeSettings(KAUVERY_SITE_ID, localSettings);
            assertThat(updatedSettings.getDefaultQuietPeriodSeconds()).isEqualTo(90);

            // Create, update permissions, and delete a user account
            var tempUser = new com.kauvery.dicomhub.domain.entity.HubUser();
            tempUser.setUsername("jr.admin.test");
            tempUser.setFullName("Junior Admin Test");
            tempUser.setEmail("jr.admin@kauveryhospital.com");
            tempUser.setDepartment("PACS IT");
            tempUser.setUserRole("ADMINISTRATOR");
            tempUser.setCanViewDicomConfig(true);
            tempUser.setCanCreateDicomConfig(false);
            tempUser.setCanEditDicomConfig(true);
            tempUser.setCanDeleteDicomConfig(false);
            tempUser.setCanManageUsers(false);
            var savedUser = controller.createUser(KAUVERY_SITE_ID, tempUser);
            assertThat(savedUser.getId()).isNotNull();

            savedUser.setCanDeleteDicomConfig(true);
            var promotedUser = controller.updateUser(savedUser.getId(), KAUVERY_SITE_ID, savedUser);
            assertThat(promotedUser.isCanDeleteDicomConfig()).isTrue();

            Map<String, Object> delUserRes = controller.deleteUser(savedUser.getId(), KAUVERY_SITE_ID);
            assertThat(delUserRes.get("deleted")).isEqualTo(true);

            // Verify configuration changes & blocked attempts were recorded in the audit log with changedSetting & user
            var recentAudits = controller.listAuditEvents(KAUVERY_SITE_ID);
            assertThat(recentAudits).anyMatch(a ->
                    "DICOM_CONFIG_ACCESS_DENIED".equals(a.getAction()) && "BLOCKED".equals(a.getOutcome()));
            assertThat(recentAudits).anyMatch(a ->
                    "LOCAL_AE_CONNECTIVITY_SETTINGS_UPDATED".equals(a.getAction())
                            && "super.admin".equals(a.getActorUsername())
                            && a.getChangedSetting() != null);

            // 5. Verify Multi-Tenant site_id isolation on User Management & last SUPER_USER protection
            assertThatThrownBy(() -> controller.updateUser(radiologist.getId(), UNREGISTERED_OTHER_SITE_ID, radiologist))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User not found for site");
            assertThatThrownBy(() -> controller.deleteUser(radiologist.getId(), UNREGISTERED_OTHER_SITE_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User not found for site");

            assertThatThrownBy(() -> controller.deleteUser(superUser.getId(), KAUVERY_SITE_ID))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("Cannot delete the last active SUPER_USER");
            assertThatThrownBy(() -> controller.toggleUserActive(superUser.getId(), KAUVERY_SITE_ID))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("Cannot disable the last active SUPER_USER");

            // 6. Verify Dynamic Local SCP Port & AE Title Rebinding without JVM restart
            int dynamicScpPort;
            try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
                dynamicScpPort = ss.getLocalPort();
            }
            localSettings.setLocalAeTitle("KAUVERY_HUB_SCP");
            localSettings.setLocalPort(dynamicScpPort);
            controller.updateDicomNodeSettings(KAUVERY_SITE_ID, localSettings);

            var reboundEcho = controller.testLocalScpCEcho(KAUVERY_SITE_ID);
            assertThat(reboundEcho.success()).isTrue();
            assertThat(reboundEcho.mode()).isEqualTo("LIVE_DICOM_CECHO");
            assertThat(reboundEcho.negotiatedCalledAeTitle()).isEqualTo("KAUVERY_HUB_SCP");
            assertThat(reboundEcho.port()).isEqualTo(dynamicScpPort);

            // Restore default Local SCP port 11112 and AE Title DICOMHUB_AE
            localSettings.setLocalAeTitle("DICOMHUB_AE");
            localSettings.setLocalPort(11112);
            controller.updateDicomNodeSettings(KAUVERY_SITE_ID, localSettings);
            var restoredEcho = controller.testLocalScpCEcho(KAUVERY_SITE_ID);
            assertThat(restoredEcho.success()).isTrue();
            assertThat(restoredEcho.mode()).isEqualTo("LIVE_DICOM_CECHO");
            assertThat(restoredEcho.port()).isEqualTo(11112);

            // 7. Verify C-ECHO accurately rejects closed loopback ports and non-DICOM TCP ports
            int closedLoopbackPort;
            try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
                closedLoopbackPort = ss.getLocalPort();
            }
            var closedPortEcho = controller.runAdHocCEcho(
                    KAUVERY_SITE_ID,
                    new CommandCentreController.AdHocCEchoRequestDto(
                            "DICOMHUB_AE", "DICOMHUB_AE", "127.0.0.1", closedLoopbackPort, "ClosedLocalPort"));
            assertThat(closedPortEcho.success()).isFalse();
            assertThat(closedPortEcho.mode()).isEqualTo("TCP_CONNECTION_REFUSED");

            try (java.net.ServerSocket fakeHttpServer = new java.net.ServerSocket(0)) {
                int fakeHttpPort = fakeHttpServer.getLocalPort();
                Thread httpResponder = new Thread(() -> {
                    while (!fakeHttpServer.isClosed()) {
                        try (java.net.Socket client = fakeHttpServer.accept()) {
                            client.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
                            client.getOutputStream().flush();
                        } catch (Exception ignored) {
                            break;
                        }
                    }
                });
                httpResponder.setDaemon(true);
                httpResponder.start();

                var nonDicomEcho = controller.runAdHocCEcho(
                        KAUVERY_SITE_ID,
                        new CommandCentreController.AdHocCEchoRequestDto(
                                "DICOMHUB_AE", "DICOMHUB_AE", "127.0.0.1", fakeHttpPort, "FakeHttpPort"));
                assertThat(nonDicomEcho.success()).isFalse();
                assertThat(nonDicomEcho.mode()).isEqualTo("DICOM_ASSOCIATION_FAILED");
            }

            // 8. Verify Concurrent C-STORE Ingestion & Duplicate Retransmission Thread-Safety (zero race exceptions or lost counts)
            String concurrentStudyUid = UIDUtils.createUID();
            String concurrentSeriesUid = UIDUtils.createUID();
            List<String> distinctSops = List.of(
                    UIDUtils.createUID(), UIDUtils.createUID(), UIDUtils.createUID(), UIDUtils.createUID());
            int totalThreads = 8; // 4 distinct SOPs + 4 concurrent duplicates of distinctSops.get(0)
            java.util.concurrent.CountDownLatch startGate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch doneGate = new java.util.concurrent.CountDownLatch(totalThreads);
            List<Throwable> threadErrors = new java.util.concurrent.CopyOnWriteArrayList<>();

            for (int t = 0; t < totalThreads; t++) {
                final String sopToPush = (t < 4) ? distinctSops.get(t) : distinctSops.getFirst();
                Thread worker = new Thread(() -> {
                    try {
                        Attributes ds = new Attributes();
                        ds.setString(Tag.PatientName, VR.PN, "CONCURRENT^PACS^PATIENT");
                        ds.setString(Tag.PatientID, VR.LO, "MR-CONC-777");
                        ds.setString(Tag.AccessionNumber, VR.SH, "ACC-CONC-777");
                        ds.setString(Tag.Modality, VR.CS, "DX");
                        ds.setString(Tag.StudyDescription, VR.LO, "CHEST X-RAY PA");
                        ds.setString(Tag.BodyPartExamined, VR.CS, "CHEST");
                        ds.setDouble(Tag.SliceThickness, VR.DS, 1.5);
                        ds.setString(Tag.StudyInstanceUID, VR.UI, concurrentStudyUid);
                        ds.setString(Tag.SeriesInstanceUID, VR.UI, concurrentSeriesUid);
                        ds.setString(Tag.SOPInstanceUID, VR.UI, sopToPush);
                        ds.setString(Tag.SOPClassUID, VR.UI, UID.DigitalXRayImageStorageForPresentation);
                        Attributes fmi = ds.createFileMetaInformation(UID.ExplicitVRLittleEndian);
                        startGate.await();
                        orchestratorService.ingestDicomInstance(
                                KAUVERY_SITE_ID, "TEAMPACS", "DICOMHUB_AE", fmi, ds);
                    } catch (Throwable ex) {
                        threadErrors.add(ex);
                    } finally {
                        doneGate.countDown();
                    }
                });
                worker.start();
            }
            startGate.countDown();
            doneGate.await();

            assertThat(threadErrors).isEmpty();
            StudyRecord concStudy = studyRecordRepository
                    .findBySiteIdAndStudyInstanceUid(KAUVERY_SITE_ID, concurrentStudyUid)
                    .orElseThrow();
            assertThat(concStudy.getInstanceCount()).isEqualTo(4);
            assertThat(concStudy.getReceivedTransferEvents()).isEqualTo(8);
            assertThat(concStudy.getDuplicateInstancesCount()).isEqualTo(4);
        } finally {
            controller.clearTestActingUser();
        }
    }

    @Test
    void shouldPutUnmatchedStudyOnHoldAllowManualAgentSelectionSupportAgentStudyDescriptionsAndMinimalisticDicomRouter() throws Exception {
        UUID siteId = KAUVERY_SITE_ID;

        // 1. Ingest a study that does NOT match any active routing rule (MG / Mammogram) -> must become ON_HOLD
        String unmatchedUid = UIDUtils.createUID();
        Attributes mgDs = new Attributes();
        mgDs.setString(Tag.PatientName, VR.PN, "VANAJA^MAMMO^HOLD");
        mgDs.setString(Tag.PatientID, VR.LO, "MR331995");
        mgDs.setString(Tag.PatientAge, VR.AS, "054Y");
        mgDs.setString(Tag.PatientSex, VR.CS, "F");
        mgDs.setString(Tag.Modality, VR.CS, "MG");
        mgDs.setString(Tag.StudyDescription, VR.LO, "BILATERAL MAMMOGRAM SCREENING");
        mgDs.setString(Tag.BodyPartExamined, VR.CS, "BREAST");
        mgDs.setDouble(Tag.SliceThickness, VR.DS, 1.0);
        mgDs.setString(Tag.StudyInstanceUID, VR.UI, unmatchedUid);
        mgDs.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
        mgDs.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
        mgDs.setString(Tag.SOPClassUID, VR.UI, UID.DigitalMammographyXRayImageStorageForPresentation);

        InstanceRecord mgInst = orchestratorService.ingestDicomInstance(
                siteId, "TEAMPACS", "DICOMHUB_AE",
                mgDs.createFileMetaInformation(UID.ExplicitVRLittleEndian), mgDs);

        List<VendorDeliveryJob> initialJobs = orchestratorService.completeValidateAndRouteStudy(
                siteId, mgInst.getStudyId(), false);
        assertThat(initialJobs).isEmpty();

        StudyRecord heldStudy = studyRecordRepository.findById(mgInst.getStudyId()).orElseThrow();
        assertThat(heldStudy.getLifecycleState()).isEqualTo("UNMATCHED_NO_RULE");
        assertThat(heldStudy.getReceivingStatus()).isEqualTo("ON_HOLD");

        DashboardOverviewDto overviewWithHold = controller.getOverview(siteId);
        assertThat(overviewWithHold.studiesOnHoldReceiving()).isGreaterThanOrEqualTo(1);

        // 2. Previewing PS3.15 anonymization on an ON_HOLD study must NOT persist a fake VendorDeliveryJob
        var preview = controller.getStudyAnonymizationPreview(mgInst.getStudyId(), siteId, false);
        assertThat(preview.allTenRulesCompliant()).isTrue();
        assertThat(jobRepository.findBySiteIdAndStudyId(siteId, mgInst.getStudyId())).isEmpty();

        // 3. Manually send the ON_HOLD study to a selected AI Agent from the dropdown
        List<VendorDeliveryJob> manualJobs = controller.routeStudyOnDemand(
                mgInst.getStudyId(), siteId, true, GROK_XRAY_VENDOR_ID);
        assertThat(manualJobs).hasSize(1);
        assertThat(manualJobs.getFirst().getVendorId()).isEqualTo(GROK_XRAY_VENDOR_ID);
        assertThat(manualJobs.getFirst().getStatus()).isIn("VENDOR_ACCEPTED", "COMPLETED");

        StudyRecord routedFromHold = studyRecordRepository.findById(mgInst.getStudyId()).orElseThrow();
        assertThat(routedFromHold.getLifecycleState()).isEqualTo("ROUTED");
        assertThat(routedFromHold.getReceivingStatus()).isEqualTo("COMPLETED");

        // 4. Onboard a minimalistic On-Premise DICOM Router with studyDescriptions (using * and specific descriptions)
        //    even if vendorCode was left blank or set to VENDOR-GROK-XRAY, it must NOT overwrite VENDOR-GROK-XRAY!
        VendorConfig routerReq = new VendorConfig();
        routerReq.setVendorCode("VENDOR-GROK-XRAY"); // simulate legacy prefill collision
        routerReq.setVendorName("Mayam On-Premise DICOM Router Test");
        routerReq.setDestinationType("DICOM_ROUTER");
        routerReq.setDestinationAeTitle("MAYAM_TEST_AE");
        routerReq.setDestinationHost("127.0.0.1");
        routerReq.setDestinationPort(11112);
        routerReq.setModality("CT,MR");
        routerReq.setStudyDescriptions("Brain_CT, HEAD Protocol, Vascular^BRAIN_ANGIO, *");

        UUID ctStudyId = null;
        UUID kubStudyId = null;
        VendorConfig savedRouter = controller.createOrOnboardVendor(siteId, routerReq);
        VendorConfig kubRouter = null;
        try {
            assertThat(savedRouter.getId()).isNotEqualTo(GROK_XRAY_VENDOR_ID);
            assertThat(savedRouter.getDestinationType()).isEqualTo("DICOM_ROUTER");
            assertThat(savedRouter.getConnectorType()).isEqualTo("DICOM_CSTORE");
            assertThat(savedRouter.getOutboundImageFormat()).isEqualTo("DICOM_NATIVE");
            assertThat(savedRouter.getModality()).isEqualTo("CT,MR");
            assertThat(savedRouter.getStudyDescriptions()).contains("Brain_CT", "*");

            // Verify Grok AI vendor was NOT overwritten
            VendorConfig grokVendor = controller.listVendors(siteId).stream()
                    .filter(v -> GROK_XRAY_VENDOR_ID.equals(v.getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(grokVendor.getDestinationType()).isEqualTo("AI_REST_API");
            assertThat(grokVendor.getConnectorType()).isEqualTo("GROK_AI_VISION");

            // 5. Verify a CT Angio study with ImageType DERIVED\SECONDARY\AXIAL and StudyDescription Vascular^BRAIN_ANGIO
            //    passes BurnedInTextInspector and routes to savedRouter (DICOM_ROUTER), NOT to VENDOR-GROK-XRAY,
            //    and processOutboundJob does NOT self-loop a PSEUDO-PAT study back into the Hub!
            long studyCountBeforeOut = studyRecordRepository.findBySiteIdOrderByCreatedAtDesc(siteId).size();
            String ctUid = UIDUtils.createUID();
            Attributes ctDs = new Attributes();
            ctDs.setString(Tag.PatientName, VR.PN, "DLAMINI^PRINCE^MAJAHA");
            ctDs.setString(Tag.PatientID, VR.LO, "9111296100023");
            ctDs.setString(Tag.PatientAge, VR.AS, "062Y");
            ctDs.setString(Tag.PatientSex, VR.CS, "M");
            ctDs.setString(Tag.Modality, VR.CS, "CT");
            ctDs.setString(Tag.StudyDescription, VR.LO, "Vascular^BRAIN_ANGIO");
            ctDs.setString(Tag.BodyPartExamined, VR.CS, "SKULL");
            ctDs.setString(Tag.ImageType, VR.CS, "DERIVED", "SECONDARY", "AXIAL", "CT_SOM5 MIP");
            ctDs.setDouble(Tag.SliceThickness, VR.DS, 6.0);
            ctDs.setString(Tag.StudyInstanceUID, VR.UI, ctUid);
            ctDs.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
            ctDs.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
            ctDs.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);

            InstanceRecord ctInst = orchestratorService.ingestDicomInstance(
                    siteId, "HOROS", "DICOMHUB_AE",
                    ctDs.createFileMetaInformation(UID.ExplicitVRLittleEndian), ctDs);
            ctStudyId = ctInst.getStudyId();

            List<VendorDeliveryJob> ctJobs = orchestratorService.completeValidateAndRouteStudy(
                    siteId, ctStudyId, false);
            assertThat(ctJobs).hasSize(1);
            assertThat(ctJobs.getFirst().getVendorId()).isEqualTo(savedRouter.getId());
            assertThat(ctJobs.getFirst().getStatus()).isEqualTo("QUEUED");

            VendorDeliveryJob deliveredCtJob = orchestratorService.processOutboundJob(
                    siteId, ctJobs.getFirst().getId(), false);
            assertThat(deliveredCtJob.getStatus()).isEqualTo("VENDOR_ACCEPTED");
            // Verify zero self-looped PSEUDO-PAT studies were ingested
            assertThat(studyRecordRepository.findBySiteIdOrderByCreatedAtDesc(siteId))
                    .noneMatch(s -> s.getPatientId() != null && s.getPatientId().startsWith("PSEUDO-PAT-"));
            assertThat(studyRecordRepository.findBySiteIdOrderByCreatedAtDesc(siteId).size())
                    .isEqualTo(studyCountBeforeOut + 1);

            // 6. Verify specific study description rule (CT KUB) takes precedence over savedRouter's wildcard '*'
            VendorConfig kubRouterReq = new VendorConfig();
            kubRouterReq.setVendorName("KUB Dedicated DICOM Router");
            kubRouterReq.setDestinationType("DICOM_ROUTER");
            kubRouterReq.setDestinationAeTitle("KUB_ROUTER_AE");
            kubRouterReq.setDestinationHost("10.10.30.88");
            kubRouterReq.setDestinationPort(11112);
            kubRouterReq.setModality("CT");
            kubRouterReq.setStudyDescriptions("CT KUB, RENAL CALCULUS");
            kubRouter = controller.createOrOnboardVendor(siteId, kubRouterReq);

            Attributes kubDs = new Attributes(ctDs);
            kubDs.setString(Tag.StudyInstanceUID, VR.UI, UIDUtils.createUID());
            kubDs.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
            kubDs.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
            kubDs.setString(Tag.StudyDescription, VR.LO, "CT KUB NON CONTRAST");
            kubDs.setString(Tag.BodyPartExamined, VR.CS, "ABDOMEN");
            InstanceRecord kubInst = orchestratorService.ingestDicomInstance(
                    siteId, "HOROS", "DICOMHUB_AE",
                    kubDs.createFileMetaInformation(UID.ExplicitVRLittleEndian), kubDs);
            kubStudyId = kubInst.getStudyId();

            List<VendorDeliveryJob> kubJobs = orchestratorService.completeValidateAndRouteStudy(
                    siteId, kubStudyId, false);
            assertThat(kubJobs).hasSize(1);
            assertThat(kubJobs.getFirst().getVendorId()).isEqualTo(kubRouter.getId());
        } finally {
            if (kubStudyId != null) {
                controller.deleteSingleStudyAndLogs(kubStudyId, siteId);
            }
            if (ctStudyId != null) {
                controller.deleteSingleStudyAndLogs(ctStudyId, siteId);
            }
            controller.deleteSingleStudyAndLogs(mgInst.getStudyId(), siteId);
            if (kubRouter != null) {
                final UUID kubRouterId = kubRouter.getId();
                controller.listRoutingRules(siteId).stream()
                        .filter(r -> kubRouterId.equals(r.getVendorId()))
                        .forEach(r -> controller.deleteRoutingRule(r.getId(), siteId));
                controller.listRegionMappings(siteId).stream()
                        .filter(m -> kubRouterId.equals(m.getTargetVendorId()))
                        .forEach(m -> controller.deleteRegionMapping(m.getId(), siteId));
                controller.deleteVendor(kubRouterId, siteId);
            }
            // Clean up rules and mappings created for savedRouter
            controller.listRoutingRules(siteId).stream()
                    .filter(r -> savedRouter.getId().equals(r.getVendorId()))
                    .forEach(r -> controller.deleteRoutingRule(r.getId(), siteId));
            controller.listRegionMappings(siteId).stream()
                    .filter(m -> savedRouter.getId().equals(m.getTargetVendorId()))
                    .forEach(m -> controller.deleteRegionMapping(m.getId(), siteId));
            controller.deleteVendor(savedRouter.getId(), siteId);
        }
    }
}
