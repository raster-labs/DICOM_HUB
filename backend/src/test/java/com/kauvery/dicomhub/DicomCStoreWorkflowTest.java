package com.kauvery.dicomhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kauvery.dicomhub.api.CommandCentreController;
import com.kauvery.dicomhub.api.CommandCentreController.ForwardedPatientTransferDto;
import com.kauvery.dicomhub.api.CommandCentreController.VendorDestinationUpdateDto;
import com.kauvery.dicomhub.config.HubProperties;
import com.kauvery.dicomhub.dicom.DicomWorkflowOrchestratorService;
import com.kauvery.dicomhub.domain.entity.AiResultRecord;
import com.kauvery.dicomhub.domain.entity.InstanceRecord;
import com.kauvery.dicomhub.domain.entity.PseudonymMapping;
import com.kauvery.dicomhub.domain.entity.RoutingRule;
import com.kauvery.dicomhub.domain.entity.StudyRecord;
import com.kauvery.dicomhub.domain.entity.StudyRegionMapping;
import com.kauvery.dicomhub.domain.entity.VendorConfig;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.domain.entity.VendorTransferAttempt;
import com.kauvery.dicomhub.domain.repository.PseudonymMappingRepository;
import com.kauvery.dicomhub.domain.repository.StudyRecordRepository;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.dcm4che3.util.UIDUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DicomCStoreWorkflowTest {

    @Autowired
    private HubProperties hubProperties;

    @Autowired
    private DicomWorkflowOrchestratorService orchestratorService;

    @Autowired
    private PseudonymMappingRepository pseudonymMappingRepository;

    @Autowired
    private StudyRecordRepository studyRecordRepository;

    @Autowired
    private CommandCentreController controller;

    @Test
    void shouldExecuteEndToEndLifecycleAndProtectOriginalRepositoryDuringVendorCleanup() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();

        // Dynamically onboard a temporary vendor & routing rule for this test and clean up in finally
        VendorConfig tempVendor = new VendorConfig();
        tempVendor.setVendorCode("TEMP-SR-VENDOR");
        tempVendor.setVendorName("Temp SR Verification Vendor");
        tempVendor.setUseCaseName("Lifecycle & Working Copy Cleanup Test");
        tempVendor.setConnectorType("REST_MULTIPART");
        tempVendor.setDestinationType("DICOM_UPLOADER");
        tempVendor.setDestinationAeTitle("TEMP_UPL");
        tempVendor.setDestinationHost("127.0.0.1");
        tempVendor.setDestinationPort(11115);
        tempVendor.setApiBaseUrl("https://api.temp-sr.example.com/v1/studies");
        tempVendor.setAuthType("MTLS");
        tempVendor.setSlaTurnaroundMinutes(10);
        VendorConfig savedVendor = controller.createOrOnboardVendor(siteId, tempVendor);

        RoutingRule tempRule = new RoutingRule();
        tempRule.setRuleName("Temp Lifecycle Rule");
        tempRule.setPriorityOrder(99);
        tempRule.setModality("CT");
        tempRule.setCanonicalRegion("CT KUB");
        tempRule.setStudyDescriptionPattern("CT KUB CALCULUS PROTOCOL");
        tempRule.setBodyPartPattern("ABDOMEN");
        tempRule.setMatchOperator("CONTAINS");
        tempRule.setVendorId(savedVendor.getId());
        tempRule.setMaxSliceThicknessMm(5.0);
        RoutingRule savedRule = controller.createRoutingRule(siteId, tempRule);

        try {
            String studyUid = UIDUtils.createUID();
            String seriesUid = UIDUtils.createUID();
            String sopUid = UIDUtils.createUID();
            String realPatientId = "KH-PAT-20261123";

            Attributes dataset = new Attributes();
            dataset.setString(Tag.PatientName, VR.PN, "SUBRAMANIAN^V");
            dataset.setString(Tag.PatientID, VR.LO, realPatientId);
            dataset.setString(Tag.AccessionNumber, VR.SH, "ACC-KUB-7788");
            dataset.setString(Tag.Modality, VR.CS, "CT");
            dataset.setString(Tag.StudyDescription, VR.LO, "CT KUB CALCULUS PROTOCOL");
            dataset.setString(Tag.BodyPartExamined, VR.CS, "ABDOMEN");
            dataset.setDouble(Tag.SliceThickness, VR.DS, 1.5);
            dataset.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
            dataset.setString(Tag.SeriesInstanceUID, VR.UI, seriesUid);
            dataset.setString(Tag.SOPInstanceUID, VR.UI, sopUid);
            dataset.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);

            Attributes fmi = dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian);

            // 1. Ingest and verify duplicate SOPInstanceUID idempotency
            InstanceRecord instance = orchestratorService.ingestDicomInstance(
                    siteId, "KAUVERY_DX01", "DICOMHUB_AE", fmi, dataset);
            InstanceRecord duplicateRetry = orchestratorService.ingestDicomInstance(
                    siteId, "KAUVERY_DX01", "DICOMHUB_AE", fmi, dataset);
            assertThat(duplicateRetry.getId()).isEqualTo(instance.getId());
            assertThat(duplicateRetry.getDuplicateCount()).isEqualTo(1);

            // 2. Complete & route study
            List<VendorDeliveryJob> jobs = orchestratorService.completeValidateAndRouteStudy(
                    siteId, instance.getStudyId(), false);
            assertThat(jobs).hasSize(1);
            VendorDeliveryJob job = jobs.getFirst();

            // FR-06: Second routing call without deliberate resend must not create duplicate jobs
            assertThat(orchestratorService.completeValidateAndRouteStudy(siteId, instance.getStudyId(), false))
                    .isEmpty();

            // 3. AC-8: Simulate transient vendor outage -> job enters RETRY_WAIT with exponential backoff
            job = orchestratorService.processOutboundJob(siteId, job.getId(), true);
            assertThat(job.getStatus()).isEqualTo("RETRY_WAIT");
            assertThat(job.getAttemptCount()).isEqualTo(1);
            assertThat(job.getNextRetryAt()).isNotNull();

            // 4. Retry succeeds -> VENDOR_ACCEPTED with vendorJobId
            job = orchestratorService.processOutboundJob(siteId, job.getId(), false);
            assertThat(job.getStatus()).isEqualTo("VENDOR_ACCEPTED");
            assertThat(job.getVendorJobId()).isNotBlank();

            PseudonymMapping mapping = pseudonymMappingRepository
                    .findBySiteIdAndStudyIdAndVendorId(siteId, instance.getStudyId(), job.getVendorId())
                    .orElseThrow();

            // 5. FR-19: Standalone PDF result must be rejected
            UUID jobId = job.getId();
            assertThatThrownBy(() -> orchestratorService.receiveAndReturnAiResult(
                    siteId, jobId, mapping.getPseudoStudyUid(), "PDF_ONLY", "UroStone", "v1.0", "{}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("FR-19 Violation");

            // 6. Valid DICOM SR result is re-identified, labeled "AI-generated – not verified" (FR-21), and forwarded to PACS
            AiResultRecord aiResult = orchestratorService.receiveAndReturnAiResult(
                    siteId,
                    jobId,
                    mapping.getPseudoStudyUid(),
                    "DICOM_SR_TID1500",
                    "UroStone-Net",
                    "v3.2.1",
                    "{\"findings\":[{\"type\":\"ureteric_calculus\",\"size_mm\":5.2,\"hu\":910}]}");

            assertThat(aiResult.isReidentified()).isTrue();
            assertThat(aiResult.isPacsForwarded()).isTrue();
            assertThat(aiResult.getSeriesDescriptionLabel()).isEqualTo("AI-generated – not verified");

            Attributes returnedSr;
            try (DicomInputStream dis = new DicomInputStream(new File(aiResult.getStoragePath()))) {
                returnedSr = dis.readDataset();
            }
            assertThat(returnedSr.getString(Tag.StudyInstanceUID)).isEqualTo(studyUid);
            assertThat(returnedSr.getString(Tag.PatientID)).isEqualTo(realPatientId);
            assertThat(returnedSr.getString(Tag.SeriesDescription)).isEqualTo("AI-generated – not verified");

            // 7. Execute independent per-vendor working-copy cleanup (Spec Section 5.1 & 5.2)
            Path workingDir = Path.of(job.getWorkingCopyDir());
            Path originalFile = Path.of(instance.getOriginalRepoPath());
            assertThat(Files.exists(workingDir)).isTrue();
            assertThat(Files.exists(originalFile)).isTrue();

            int cleaned = orchestratorService.cleanupEligibleVendorWorkingCopies(siteId, job.getVendorId(), false);
            assertThat(cleaned).isEqualTo(1);
            assertThat(Files.exists(workingDir)).isFalse();
            // Original DICOM Repository object MUST remain intact!
            assertThat(Files.exists(originalFile)).isTrue();
        } finally {
            controller.deleteRoutingRule(savedRule.getId(), siteId);
            controller.deleteVendor(savedVendor.getId(), siteId);
        }
    }

    @Test
    void shouldFallbackBlankStudyDescriptionAcceptNumericSeriesDescriptionsAndExecuteRealDcm4cheCStoreTransfer() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();

        // Create temporary C-STORE vendor, region mapping, and routing rule for live TCP C-STORE test
        VendorConfig tempCStoreVendor = new VendorConfig();
        tempCStoreVendor.setVendorCode("TEMP-CSTORE-ROUTER");
        tempCStoreVendor.setVendorName("Temp On-Prem C-STORE Router");
        tempCStoreVendor.setUseCaseName("Live dcm4che C-STORE Verification");
        tempCStoreVendor.setConnectorType("DICOM_CSTORE");
        tempCStoreVendor.setDestinationType("DICOM_ROUTER");
        tempCStoreVendor.setDestinationAeTitle("RASTERIPACS");
        tempCStoreVendor.setDestinationHost("127.0.0.1");
        tempCStoreVendor.setDestinationPort(11112);
        tempCStoreVendor.setApiBaseUrl("dicom://RASTERIPACS@127.0.0.1:11112");
        tempCStoreVendor.setAuthType("MTLS");
        tempCStoreVendor.setOutboundImageFormat("DICOM_NATIVE");
        VendorConfig savedCStoreVendor = controller.createOrOnboardVendor(siteId, tempCStoreVendor);
        UUID neuroCtVendorId = savedCStoreVendor.getId();

        RoutingRule tempRule = new RoutingRule();
        tempRule.setRuleName("Temp CT Brain C-STORE Rule");
        tempRule.setPriorityOrder(98);
        tempRule.setModality("CT");
        tempRule.setCanonicalRegion("CT Brain");
        tempRule.setStudyDescriptionPattern("CT BRAIN, HEAD PROTOCOL, BRAIN");
        tempRule.setBodyPartPattern("HEAD, BRAIN, CT BRAIN");
        tempRule.setMatchOperator("CONTAINS");
        tempRule.setVendorId(neuroCtVendorId);
        tempRule.setMaxSliceThicknessMm(5.0);
        RoutingRule savedRule = controller.createRoutingRule(siteId, tempRule);

        StudyRegionMapping tempMapping = new StudyRegionMapping();
        tempMapping.setCanonicalRegion("CT Brain");
        tempMapping.setModality("CT");
        tempMapping.setBodyPart("HEAD");
        tempMapping.setStudyDescriptions("CT BRAIN, HEAD PROTOCOL, BRAIN");
        tempMapping.setTargetVendorId(neuroCtVendorId);
        StudyRegionMapping savedMapping = controller.createOrUpdateRegionMapping(siteId, tempMapping);

        // 1. Start a real embedded dcm4che Storage SCP (C-ECHO + C-STORE) on 127.0.0.1 ephemeral port
        int freePort;
        try (ServerSocket ss = new ServerSocket(0)) {
            freePort = ss.getLocalPort();
        }
        List<Attributes> receivedByOnPremRouterScp = new CopyOnWriteArrayList<>();
        AtomicBoolean requireNonEmptyPatientName = new AtomicBoolean(false);
        Device routerScpDevice = new Device("test-onprem-router-scp");
        ApplicationEntity routerAe = new ApplicationEntity("TEAMPACS");
        Connection routerConn = new Connection(null, "127.0.0.1", freePort);
        routerAe.setAssociationAcceptor(true);
        routerAe.addConnection(routerConn);
        routerAe.addTransferCapability(new TransferCapability(null, "*", TransferCapability.Role.SCP, "*"));

        DicomServiceRegistry registry = new DicomServiceRegistry();
        registry.addDicomService(new BasicCEchoSCP());
        registry.addDicomService(new BasicCStoreSCP("*") {
            @Override
            protected void store(
                    Association as,
                    PresentationContext pc,
                    Attributes rq,
                    PDVInputStream data,
                    Attributes rsp) throws IOException {
                try (DicomInputStream dis = new DicomInputStream(data, pc.getTransferSyntax())) {
                    Attributes ds = dis.readDataset();
                    if (requireNonEmptyPatientName.get()) {
                        String pn = ds.getString(Tag.PatientName);
                        if (pn == null || pn.isBlank()) {
                            throw new DicomServiceException(
                                    Status.DataSetDoesNotMatchSOPClassError,
                                    "Acceptance of objects without Patient Name is disabled");
                        }
                    }
                    receivedByOnPremRouterScp.add(ds);
                }
            }
        });
        routerScpDevice.setDimseRQHandler(registry);
        routerScpDevice.addConnection(routerConn);
        routerScpDevice.addApplicationEntity(routerAe);
        ExecutorService exec = Executors.newCachedThreadPool();
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        routerScpDevice.setExecutor(exec);
        routerScpDevice.setScheduledExecutor(sched);
        routerScpDevice.bindConnections();

        try {
            // 2. Point VENDOR-NEURO-CT to the live embedded DICOM Router SCP on 127.0.0.1:freePort
            //    using Called AET "RASTERIPACS" while the SCP's actual AE Title is "TEAMPACS"
            //    to verify automatic Called-AET fallback negotiation on A-ASSOCIATE-RJ reason 7.
            controller.updateVendorDestination(
                    neuroCtVendorId,
                    siteId,
                    new VendorDestinationUpdateDto(
                            "NeuroScan Stroke AI",
                            "CT Brain ICH & Midline Shift Triage",
                            "DICOM_ROUTER",
                            "RASTERIPACS",
                            "127.0.0.1",
                            freePort,
                            "DICOM_CSTORE",
                            null));

            // 3. Test real dcm4che C-ECHO against the live SCP (must fallback RASTERIPACS -> TEAMPACS)
            Map<String, Object> echoTest = controller.testVendorConnection(neuroCtVendorId, siteId);
            assertThat(echoTest.get("connected")).isEqualTo(true);

            // 4. Ingest a real-scanner-style CT Brain study with BLANK StudyDescription (""),
            //    BodyPartExamined = "CT BRAIN", ProtocolName = "Brain",
            //    Series 1: SeriesDescription = "2.0" (sliceThickness = 2.0)
            //    Series 2: SeriesDescription = "Head 1.0" (sliceThickness = 1.0, 2 slices)
            String studyUid = UIDUtils.createUID();
            String series1Uid = UIDUtils.createUID();
            String series2Uid = UIDUtils.createUID();

            Attributes s1 = new Attributes();
            s1.setString(Tag.PatientName, VR.PN, "AMUTHA 58Y/F");
            s1.setString(Tag.PatientID, VR.LO, "MR138970");
            s1.setString(Tag.PatientAge, VR.AS, "058Y");
            s1.setString(Tag.PatientSex, VR.CS, "F");
            s1.setString(Tag.AccessionNumber, VR.SH, "25252");
            s1.setString(Tag.Modality, VR.CS, "CT");
            s1.setString(Tag.StudyDescription, VR.LO, "");
            s1.setString(Tag.BodyPartExamined, VR.CS, "CT BRAIN");
            s1.setString(Tag.ProtocolName, VR.LO, "Brain");
            s1.setString(Tag.SeriesDescription, VR.LO, "2.0");
            s1.setDouble(Tag.SliceThickness, VR.DS, 2.0);
            s1.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
            s1.setString(Tag.SeriesInstanceUID, VR.UI, series1Uid);
            s1.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
            s1.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
            orchestratorService.ingestDicomInstance(
                    siteId, "TEAMPACS", "DICOMHUB_AE",
                    s1.createFileMetaInformation(UID.ImplicitVRLittleEndian), s1);

            for (int i = 0; i < 2; i++) {
                Attributes s2 = new Attributes(s1);
                s2.setString(Tag.SeriesInstanceUID, VR.UI, series2Uid);
                s2.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
                s2.setString(Tag.SeriesDescription, VR.LO, "Head 1.0");
                s2.setDouble(Tag.SliceThickness, VR.DS, 1.0);
                orchestratorService.ingestDicomInstance(
                        siteId, "TEAMPACS", "DICOMHUB_AE",
                        s2.createFileMetaInformation(UID.ImplicitVRLittleEndian), s2);
            }

            StudyRecord ingestedStudy = studyRecordRepository
                    .findBySiteIdAndStudyInstanceUid(siteId, studyUid)
                    .orElseThrow();
            assertThat(ingestedStudy.getStudyDescription()).isEqualTo("CT BRAIN");
            assertThat(ingestedStudy.getCanonicalRegion()).isEqualTo("CT Brain");
            assertThat(ingestedStudy.getInstanceCount()).isEqualTo(3);

            // 5. Route on-demand -> must select all 3 instances ("2.0" + 2x "Head 1.0") and transfer over real C-STORE
            List<VendorDeliveryJob> routedJobs = controller.routeStudyOnDemand(ingestedStudy.getId(), siteId, true);
            assertThat(routedJobs).hasSize(1);
            VendorDeliveryJob job = routedJobs.getFirst();
            assertThat(job.getStatus()).isEqualTo("VENDOR_ACCEPTED");
            assertThat(job.getSelectedInstances()).isEqualTo(3);
            assertThat(job.getTransferredInstances()).isEqualTo(3);

            // 6. Verify the embedded DICOM Router SCP actually received all 3 de-identified DICOM instances over TCP
            assertThat(receivedByOnPremRouterScp).hasSize(3);
            for (Attributes receivedDs : receivedByOnPremRouterScp) {
                assertThat(receivedDs.getString(Tag.PatientIdentityRemoved)).isEqualTo("YES");
                assertThat(receivedDs.getString(Tag.PatientName)).isNull();
                assertThat(receivedDs.getString(Tag.PatientID)).startsWith("PSEUDO-PAT-");
            }

            // 7. Re-route (forceResend=true) with strict 0xA900 PatientName check enabled on the remote SCP:
            //    Must purge previous working copy files (keeping transferredInstances at 3, NEVER doubling to 6!),
            //    auto-retry 0xA900 by populating PatientName with the pseudonym ID (never real PHI!),
            //    and increment attemptNumber monotonically in vendor_transfer_attempts.
            receivedByOnPremRouterScp.clear();
            requireNonEmptyPatientName.set(true);
            List<VendorDeliveryJob> reroutedJobs = controller.routeStudyOnDemand(ingestedStudy.getId(), siteId, true);
            assertThat(reroutedJobs).hasSize(1);
            VendorDeliveryJob reroutedJob = reroutedJobs.getFirst();
            assertThat(reroutedJob.getStatus()).isEqualTo("VENDOR_ACCEPTED");
            assertThat(reroutedJob.getSelectedInstances()).isEqualTo(3);
            assertThat(reroutedJob.getTransferredInstances()).isEqualTo(3);
            assertThat(receivedByOnPremRouterScp).hasSize(3);
            for (Attributes receivedDs : receivedByOnPremRouterScp) {
                assertThat(receivedDs.getString(Tag.PatientName))
                        .startsWith("PSEUDO-PAT-")
                        .doesNotContain("AMUTHA");
            }
            List<VendorTransferAttempt> attempts = controller.listJobAttempts(reroutedJob.getId(), siteId);
            assertThat(attempts).hasSize(2);
            assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
            assertThat(attempts.get(1).getAttemptNumber()).isEqualTo(2);
            assertThat(attempts.get(0).getIdempotencyKey()).isNotEqualTo(attempts.get(1).getIdempotencyKey());

            // 8. Verify Forwarded Patients table reports 3 / 3 transferred instances with "CT BRAIN" description
            ForwardedPatientTransferDto row = controller.listForwardedPatients(siteId, null, "MR138970").stream()
                    .filter(r -> ingestedStudy.getId().equals(r.studyId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(row.patientName()).isEqualTo("AMUTHA 58Y/F");
            assertThat(row.studyDescription()).isEqualTo("CT BRAIN");
            assertThat(row.canonicalRegion()).isEqualTo("CT Brain");
            assertThat(row.totalStudyInstances()).isEqualTo(3);
            assertThat(row.selectedInstances()).isEqualTo(3);
            assertThat(row.transferredInstances()).isEqualTo(3);
            assertThat(row.destinationAeTitle()).isEqualTo("RASTERIPACS");
        } finally {
            routerScpDevice.unbindConnections();
            exec.shutdownNow();
            sched.shutdownNow();
            controller.deleteRoutingRule(savedRule.getId(), siteId);
            controller.deleteRegionMapping(savedMapping.getId(), siteId);
            controller.deleteVendor(neuroCtVendorId, siteId);
        }
    }
}
