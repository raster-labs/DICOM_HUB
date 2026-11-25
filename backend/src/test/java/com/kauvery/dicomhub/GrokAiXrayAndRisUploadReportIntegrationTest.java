package com.kauvery.dicomhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kauvery.dicomhub.api.CommandCentreController;
import com.kauvery.dicomhub.api.CommandCentreController.OperationalAlertDto;
import com.kauvery.dicomhub.api.CommandCentreController.StudyDetailTimelineDto;
import com.kauvery.dicomhub.api.CommandCentreController.VendorDestinationUpdateDto;
import com.kauvery.dicomhub.config.HubProperties;
import com.kauvery.dicomhub.dicom.DicomWorkflowOrchestratorService;
import com.kauvery.dicomhub.domain.entity.AiResultRecord;
import com.kauvery.dicomhub.domain.entity.InstanceRecord;
import com.kauvery.dicomhub.domain.entity.RecordRetentionSettings;
import com.kauvery.dicomhub.domain.entity.RisReportEndpoint;
import com.kauvery.dicomhub.domain.entity.RisUploadedReport;
import com.kauvery.dicomhub.domain.entity.RoutingRule;
import com.kauvery.dicomhub.domain.entity.StudyRegionMapping;
import com.kauvery.dicomhub.domain.entity.VendorConfig;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.domain.repository.AiResultRecordRepository;
import com.kauvery.dicomhub.domain.repository.RisUploadedReportRepository;
import com.kauvery.dicomhub.domain.repository.VendorConfigRepository;
import com.kauvery.dicomhub.domain.repository.VendorDeliveryJobRepository;
import com.kauvery.dicomhub.report.RisTeleradUploadReportService;
import com.kauvery.dicomhub.report.RisTeleradUploadReportService.UploadReportServletResult;
import com.kauvery.dicomhub.worker.DurableQueueWorkerService;
import com.kauvery.dicomhub.worker.RetentionCleanupWorkerService.RecordPurgeResultDto;
import com.kauvery.dicomhub.worker.RetentionCleanupWorkerService.RetentionDashboardSummaryDto;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.util.UIDUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class GrokAiXrayAndRisUploadReportIntegrationTest {

    @Autowired
    private HubProperties hubProperties;

    @Autowired
    private CommandCentreController controller;

    @Autowired
    private DicomWorkflowOrchestratorService orchestratorService;

    @Autowired
    private VendorConfigRepository vendorConfigRepository;

    @Autowired
    private VendorDeliveryJobRepository jobRepository;

    @Autowired
    private AiResultRecordRepository aiResultRecordRepository;

    @Autowired
    private RisUploadedReportRepository risUploadedReportRepository;

    @Autowired
    private RisTeleradUploadReportService risUploadService;

    @Autowired
    private DurableQueueWorkerService queueWorkerService;

    private HttpServer embeddedRisHttpServer;
    private int embeddedRisPort;
    private final AtomicInteger simulatedRisHttpStatus = new AtomicInteger(200);
    private final AtomicReference<String> simulatedRisResponseJson = new AtomicReference<>(
            "{\n  \"status\": \"success\",\n  \"message\": \"Upload reports successfully\"\n}");
    private final AtomicReference<String> lastReceivedMultipartBody = new AtomicReference<>("");
    private final AtomicReference<String> lastReceivedUsernameHeader = new AtomicReference<>("");

    @BeforeEach
    void startEmbeddedLiveRisHttpServer() throws Exception {
        simulatedRisHttpStatus.set(200);
        simulatedRisResponseJson.set("{\n  \"status\": \"success\",\n  \"message\": \"Upload reports successfully\"\n}");
        lastReceivedMultipartBody.set("");
        lastReceivedUsernameHeader.set("");

        embeddedRisHttpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        embeddedRisPort = embeddedRisHttpServer.getAddress().getPort();
        embeddedRisHttpServer.createContext("/telerad/UploadReportServlet", exchange -> {
            byte[] reqBytes = exchange.getRequestBody().readAllBytes();
            lastReceivedMultipartBody.set(new String(reqBytes, StandardCharsets.ISO_8859_1));
            String userHdr = exchange.getRequestHeaders().getFirst("username");
            lastReceivedUsernameHeader.set(userHdr != null ? userHdr : "");

            int status = simulatedRisHttpStatus.get();
            byte[] respBytes = simulatedRisResponseJson.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.getResponseHeaders().set("Server", "Apache-Coyote/1.1");
            exchange.getResponseHeaders().set("X-Powered-By", "Servlet 2.4; JBoss-4.2.3.GA");
            exchange.sendResponseHeaders(status, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });
        embeddedRisHttpServer.start();

        UUID siteId = hubProperties.defaultSiteId();
        RisReportEndpoint ep = controller.getRisReportEndpoint(siteId);
        ep.setHostAddress("127.0.0.1");
        ep.setPort(embeddedRisPort);
        ep.setContextPath("/telerad/UploadReportServlet");
        ep.setTargetUrl("http://127.0.0.1:" + embeddedRisPort + "/telerad/UploadReportServlet");
        ep.setUseBuiltinMock(false);
        controller.updateRisReportEndpoint(siteId, ep);
    }

    @AfterEach
    void stopEmbeddedLiveRisHttpServerAndRestoreDefaultIp() {
        if (embeddedRisHttpServer != null) {
            embeddedRisHttpServer.stop(0);
        }
        UUID siteId = hubProperties.defaultSiteId();
        RisReportEndpoint ep = controller.getRisReportEndpoint(siteId);
        ep.setHostAddress("172.17.1.200");
        ep.setPort(8080);
        ep.setContextPath("/telerad/UploadReportServlet");
        ep.setTargetUrl("http://172.17.1.200:8080/telerad/UploadReportServlet");
        ep.setUseBuiltinMock(false);
        controller.updateRisReportEndpoint(siteId, ep);
    }

    @Test
    void shouldRouteXrayModalitiesCrDxRfThroughGrokVisionGenerateReidentifiedPdfAndUploadToRis() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();

        // 1. Run end-to-end Grok X-Ray simulations for DX, CR, and RF modalities
        for (String scenario : List.of("grok-xray", "grok-xray-cr", "grok-xray-rf")) {
            Map<String, Object> simRes = controller.runSimulatorScenario(siteId, scenario);
            UUID studyId = (UUID) simRes.get("studyId");
            String studyInstanceUid = (String) simRes.get("studyInstanceUid");
            assertThat(studyId).isNotNull();
            assertThat((Integer) simRes.get("jobsCreated")).isGreaterThanOrEqualTo(1);

            List<VendorDeliveryJob> jobs = jobRepository.findBySiteIdAndStudyId(siteId, studyId);
            assertThat(jobs).isNotEmpty();
            VendorDeliveryJob grokJob = jobs.stream()
                    .filter(j -> {
                        VendorConfig vc = vendorConfigRepository.findById(j.getVendorId()).orElseThrow();
                        return "GROK_AI_VISION".equalsIgnoreCase(vc.getConnectorType());
                    })
                    .findFirst()
                    .orElseThrow();

            assertThat(grokJob.getStatus()).isEqualTo("COMPLETED");
            assertThat(grokJob.getVendorJobId()).startsWith("GROK-XRAY-");

            // Verify converted PNG preview & findings JSON exist in the isolated working copy directory
            Path workingCopyDir = Path.of(grokJob.getWorkingCopyDir());
            assertThat(Files.exists(workingCopyDir.resolve("deidentified-xray-preview.png"))).isTrue();
            assertThat(Files.exists(workingCopyDir.resolve("grok-vision-findings.json"))).isTrue();

            // Verify AiResultRecord has generated valid %PDF-1.4 file and uploaded to RIS UploadReportServlet
            List<AiResultRecord> results = aiResultRecordRepository.findBySiteIdAndStudyIdOrderByReceivedAtDesc(siteId, studyId);
            assertThat(results).isNotEmpty();
            AiResultRecord result = results.getFirst();
            assertThat(result.getResultType()).isEqualTo("ENCAPSULATED_PDF");
            assertThat(result.getSeriesDescriptionLabel()).contains("AI draft (LLM, grok-2-vision-1212");
            assertThat(result.getRisUploadStatus()).isEqualTo("UPLOADED_HTTP_200");
            assertThat(result.getPdfStoragePath()).isNotBlank();

            byte[] pdfBytes = Files.readAllBytes(Path.of(result.getPdfStoragePath()));
            String pdfAscii = new String(pdfBytes, StandardCharsets.US_ASCII);
            assertThat(pdfAscii).startsWith("%PDF-1.4");
            assertThat(pdfAscii).contains(studyInstanceUid);
            assertThat(pdfAscii).contains("AI-generated - not verified");

            // Verify PDF download endpoint streams application/pdf bytes
            ResponseEntity<byte[]> downloadedResultPdf = controller.downloadAiResultPdf(result.getId(), siteId);
            assertThat(downloadedResultPdf.getStatusCode().value()).isEqualTo(200);
            assertThat(downloadedResultPdf.getBody()).isNotEmpty();

            // Verify RisUploadedReport was recorded for this studyIuid with hardcoded reportType = "Preliminary Report"
            List<RisUploadedReport> uploadedForStudy = risUploadedReportRepository
                    .findBySiteIdAndStudyIuidOrderByUploadedAtDesc(siteId, studyInstanceUid);
            assertThat(uploadedForStudy).hasSize(1);
            RisUploadedReport risReport = uploadedForStudy.getFirst();
            assertThat(risReport.getReportType()).isEqualTo("Preliminary Report");
            assertThat(risReport.getReportData()).contains("\"reportType\": \"Preliminary Report\"");
            assertThat(risReport.getHttpStatus()).isEqualTo(200);
            assertThat(risReport.getResponseJson()).contains("\"status\": \"success\"");
            assertThat(risReport.getResponseJson()).contains("Upload reports successfully");

            ResponseEntity<byte[]> downloadedRisPdf = controller.downloadRisUploadedReportPdf(risReport.getId(), siteId);
            assertThat(downloadedRisPdf.getStatusCode().value()).isEqualTo(200);
            assertThat(downloadedRisPdf.getBody()).hasSize((int) risReport.getPdfFileSizeBytes());
        }
    }

    @Test
    void shouldEnforceAllUploadReportServletValidationRulesAndDuplicateReplaceOrAddendumSemantics() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();
        RisReportEndpoint ep = controller.getRisReportEndpoint(siteId);
        String validUser = ep.getAuthUsername();
        String validPass = ep.getAuthPassword();

        // Create a real X-Ray study so studyIuid exists in PACS/Hub
        Map<String, Object> simRes = controller.runSimulatorScenario(siteId, "grok-xray");
        String existingStudyIuid = (String) simRes.get("studyInstanceUid");
        byte[] validPdfBytes = "%PDF-1.4\n1 0 obj <<>> endobj\ntrailer <<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

        // 1. 401 Authorization error on missing or invalid username/password
        String validJson = RisTeleradUploadReportService.buildUploadReportJsonPayload(
                "Preliminary Report", existingStudyIuid, "2026-10-30 14:00:00", validUser, "2026-10-30 14:00:00", validUser);
        UploadReportServletResult unauthRes = risUploadService.handleMockUploadReportServlet(
                siteId, null, null, "wrong_user", "wrong_pass", validJson, "report.pdf", null, validPdfBytes, false);
        assertThat(unauthRes.httpStatus()).isEqualTo(401);
        assertThat(unauthRes.status()).isEqualTo("failure");
        assertThat(unauthRes.reason()).isEqualTo("Missing or Invalid username/password");

        // 2. 400 Validation error on invalid reportType
        String badTypeJson = RisTeleradUploadReportService.buildUploadReportJsonPayload(
                "Invalid Category", existingStudyIuid, "2026-10-30 14:00:00", validUser, "2026-10-30 14:00:00", validUser);
        UploadReportServletResult badTypeRes = risUploadService.handleMockUploadReportServlet(
                siteId, null, null, validUser, validPass, badTypeJson, "report.pdf", null, validPdfBytes, false);
        assertThat(badTypeRes.httpStatus()).isEqualTo(400);
        assertThat(badTypeRes.reason()).isEqualTo("Invalid reportType");

        // 3. 400 Validation error on invalid date-time format (ISO UTC instead of yyyy-MM-dd HH:mm:ss local)
        String badDateJson = RisTeleradUploadReportService.buildUploadReportJsonPayload(
                "Study Report", existingStudyIuid, "2026-10-30T14:00:00Z", validUser, "2026-10-30T14:00:00Z", validUser);
        UploadReportServletResult badDateRes = risUploadService.handleMockUploadReportServlet(
                siteId, null, null, validUser, validPass, badDateJson, "report.pdf", null, validPdfBytes, false);
        assertThat(badDateRes.httpStatus()).isEqualTo(400);
        assertThat(badDateRes.reason()).isEqualTo("Invalid date-time format");

        // 4. 400 Validation error when reviewedTime is earlier than draftedTime
        String earlierReviewJson = RisTeleradUploadReportService.buildUploadReportJsonPayload(
                "Study Report", existingStudyIuid, "2026-10-30 15:00:00", validUser, "2026-10-30 14:00:00", validUser);
        UploadReportServletResult earlierReviewRes = risUploadService.handleMockUploadReportServlet(
                siteId, null, null, validUser, validPass, earlierReviewJson, "report.pdf", null, validPdfBytes, false);
        assertThat(earlierReviewRes.httpStatus()).isEqualTo(400);
        assertThat(earlierReviewRes.reason()).isEqualTo("ReviewedTime earlier than draftedTime");

        // 5. 400 Validation error on empty or non-PDF file
        UploadReportServletResult nonPdfRes = risUploadService.handleMockUploadReportServlet(
                siteId, null, null, validUser, validPass, validJson, "not-a-pdf.txt", null, "HELLO".getBytes(StandardCharsets.UTF_8), false);
        assertThat(nonPdfRes.httpStatus()).isEqualTo(400);
        assertThat(nonPdfRes.reason()).isEqualTo("Empty or non-PDF file");

        // 6. 404 Study not found when studyIuid does not exist in PACS/Hub
        String unknownStudyJson = RisTeleradUploadReportService.buildUploadReportJsonPayload(
                "Study Report", "1.2.840.999999.99999.0000", "2026-10-30 14:00:00", validUser, "2026-10-30 14:00:00", validUser);
        UploadReportServletResult notFoundRes = risUploadService.handleMockUploadReportServlet(
                siteId, null, null, validUser, validPass, unknownStudyJson, "report.pdf", null, validPdfBytes, false);
        assertThat(notFoundRes.httpStatus()).isEqualTo(404);
        assertThat(notFoundRes.reason()).isEqualTo("No study found for the given studyIuid");

        // 7. Section 7 Duplicate/Re-upload handling:
        // Re-uploading "Study Report" for existingStudyIuid replaces the existing Study Report (count stays 1)
        UploadReportServletResult replaceStudyReportRes = risUploadService.handleMockUploadReportServlet(
                siteId, null, null, validUser, validPass, validJson, "replaced-report.pdf", null, validPdfBytes, false);
        assertThat(replaceStudyReportRes.httpStatus()).isEqualTo(200);
        assertThat(replaceStudyReportRes.replacedPrevious()).isTrue();
        assertThat(risUploadedReportRepository.findBySiteIdAndStudyIuidOrderByUploadedAtDesc(siteId, existingStudyIuid))
                .hasSize(1);

        // Uploading "Addendum Report" for existingStudyIuid adds a second report row alongside the Study Report
        String addendumJson = RisTeleradUploadReportService.buildUploadReportJsonPayload(
                "Addendum Report", existingStudyIuid, "2026-10-30 14:05:00", validUser, "2026-10-30 14:10:00", validUser);
        UploadReportServletResult addendumRes = risUploadService.handleMockUploadReportServlet(
                siteId, null, null, validUser, validPass, addendumJson, "addendum-report.pdf", null, validPdfBytes, false);
        assertThat(addendumRes.httpStatus()).isEqualTo(200);
        assertThat(addendumRes.replacedPrevious()).isFalse();
        assertThat(risUploadedReportRepository.findBySiteIdAndStudyIuidOrderByUploadedAtDesc(siteId, existingStudyIuid))
                .hasSize(2);
    }

    @Test
    void shouldAllowUpdatingGrokAiVendorApiDetailsRisEndpointUrlAndEditableModalitiesWithoutHardcoding() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();

        // 1. Update Grok AI Vendor API details (URL, API Key, Model Name, Prompt, Execution Mode)
        VendorConfig grokVendor = controller.listVendors(siteId).stream()
                .filter(v -> "VENDOR-GROK-XRAY".equalsIgnoreCase(v.getVendorCode()))
                .findFirst()
                .orElseThrow();

        String originalModel = grokVendor.getAiModelName();
        String originalPrompt = grokVendor.getSystemPromptTemplate();
        try {
            VendorDestinationUpdateDto updateReq = new VendorDestinationUpdateDto(
                    "Grok AI Vision X-Ray Enterprise",
                    "X-Ray (CR, DX, RF) Automated AI Findings & RIS PDF Delivery",
                    "AI_REST_API",
                    "GROK_XRAY_AI",
                    "api.x.ai",
                    443,
                    "GROK_AI_VISION",
                    "https://api.x.ai/v1/chat/completions",
                    "BEARER_API_KEY",
                    "xai-mock-custom-key-999",
                    "grok-2-vision-latest",
                    "MOCK_OR_LIVE_FALLBACK",
                    "Custom zero-PHI X-Ray prompt for modality={{modality}} bodyPart={{bodyPart}}",
                    "ENCAPSULATED_PDF");

            VendorConfig updatedVendor = controller.updateVendorDestination(grokVendor.getId(), siteId, updateReq);
            assertThat(updatedVendor.getApiBaseUrl()).isEqualTo("https://api.x.ai/v1/chat/completions");
            assertThat(updatedVendor.getAiModelName()).isEqualTo("grok-2-vision-latest");
            assertThat(updatedVendor.getApiKeySecret()).isEqualTo("xai-mock-custom-key-999");
            assertThat(updatedVendor.getSystemPromptTemplate()).contains("Custom zero-PHI X-Ray prompt");

            Map<String, Object> connTest = controller.testVendorConnection(updatedVendor.getId(), siteId);
            assertThat(connTest.get("connected")).isEqualTo(true);

            // 2. Update RIS / Telerad UploadReportServlet IP, Port, Path, and Common Username
            RisReportEndpoint ep = controller.getRisReportEndpoint(siteId);
            String origHost = ep.getHostAddress();
            int origPort = ep.getPort();
            try {
                ep.setHostAddress("172.17.1.250");
                ep.setPort(9090);
                ep.setContextPath("/telerad/UploadReportServlet");
                ep.setUseBuiltinMock(true);
                RisReportEndpoint savedEp = controller.updateRisReportEndpoint(siteId, ep);
                assertThat(savedEp.getTargetUrl()).isEqualTo("http://172.17.1.250:9090/telerad/UploadReportServlet");

                Map<String, Object> testUpload = controller.testRisReportUpload(siteId, "Study Report", null);
                assertThat(testUpload.get("httpStatus")).isEqualTo(200);
                assertThat(testUpload.get("status")).isEqualTo("success");
            } finally {
                ep.setHostAddress(origHost);
                ep.setPort(origPort);
                controller.updateRisReportEndpoint(siteId, ep);
            }

            // 3. Verify X-Ray Region Mapping & Routing Rule modalities and descriptions are editable in config
            StudyRegionMapping xrayMapping = controller.listRegionMappings(siteId).stream()
                    .filter(m -> "X-Ray Study".equalsIgnoreCase(m.getCanonicalRegion()))
                    .findFirst()
                    .orElseThrow();
            String origModality = xrayMapping.getModality();
            String origDescs = xrayMapping.getStudyDescriptions();
            try {
                xrayMapping.setModality("CR,DX,RF,MG");
                xrayMapping.setStudyDescriptions("CHEST PA, KUB ERECT, LUMBAR SPINE, FLUORO BARIUM, MAMMOGRAM, *");
                StudyRegionMapping updatedMapping = controller.updateRegionMapping(xrayMapping.getId(), siteId, xrayMapping);
                assertThat(updatedMapping.getModality()).isEqualTo("CR,DX,RF,MG");
                assertThat(updatedMapping.getStudyDescriptions()).contains("FLUORO BARIUM");
            } finally {
                xrayMapping.setModality(origModality);
                xrayMapping.setStudyDescriptions(origDescs);
                controller.updateRegionMapping(xrayMapping.getId(), siteId, xrayMapping);
            }
        } finally {
            VendorDestinationUpdateDto restoreReq = new VendorDestinationUpdateDto(
                    grokVendor.getVendorName(),
                    grokVendor.getUseCaseName(),
                    grokVendor.getDestinationType(),
                    grokVendor.getDestinationAeTitle(),
                    grokVendor.getDestinationHost(),
                    grokVendor.getDestinationPort(),
                    grokVendor.getConnectorType(),
                    grokVendor.getApiBaseUrl(),
                    grokVendor.getAuthType(),
                    grokVendor.getApiKeySecret(),
                    originalModel,
                    grokVendor.getExecutionMode(),
                    originalPrompt,
                    grokVendor.getOutputReportFormat(),
                    "BASE64_PNG_LOSSLESS",
                    "PDF_VIA_RIS_API");
            controller.updateVendorDestination(grokVendor.getId(), siteId, restoreReq);
        }
    }

    @Test
    void shouldEnforceSingleKauveryHospitalSiteGrokOnlyDataLosslessFormatsAndDualDestinationReturn() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();

        // 1. Verify single Kauvery Hospital site deployment only
        var sites = controller.listSites();
        assertThat(sites).hasSize(1);
        assertThat(sites.getFirst().getId()).isEqualTo(siteId);
        assertThat(sites.getFirst().getSiteName()).isEqualTo("Kauvery Hospital");

        // 2. Verify Base64 PNG Return -> Converted to Re-identified DICOM & Sent via PACS Destination AE Title, IP & Port
        Map<String, Object> pngAeSim = controller.runSimulatorScenario(siteId, "grok-xray-base64-png-ae");
        UUID pngStudyId = (UUID) pngAeSim.get("studyId");
        String pngStudyUid = (String) pngAeSim.get("studyInstanceUid");
        List<AiResultRecord> pngResults = aiResultRecordRepository.findBySiteIdAndStudyIdOrderByReceivedAtDesc(siteId, pngStudyId);
        assertThat(pngResults).isNotEmpty();
        AiResultRecord pngRes = pngResults.getFirst();
        assertThat(pngRes.getResultType()).isEqualTo("BASE64_PNG");
        assertThat(pngRes.getReportPrefillStrategy()).isEqualTo("DICOM_CSTORE_TO_PACS_AE");
        assertThat(pngRes.getDicomAeReturnStatus()).startsWith("SENT_TO_AE_RASTERIPACS@172.17.1.200:11112");
        assertThat(pngRes.getImageStoragePath()).isNotBlank();
        assertThat(Files.exists(Path.of(pngRes.getImageStoragePath()))).isTrue();
        assertThat(Files.exists(Path.of(pngRes.getStoragePath()))).isTrue();

        // Inspect re-identified DICOM Secondary Capture (.dcm) prepared for PACS AE return
        try (org.dcm4che3.io.DicomInputStream dis = new org.dcm4che3.io.DicomInputStream(Path.of(pngRes.getStoragePath()).toFile())) {
            org.dcm4che3.data.Attributes returnedDcm = dis.readDataset();
            assertThat(returnedDcm.getString(org.dcm4che3.data.Tag.StudyInstanceUID)).isEqualTo(pngStudyUid);
            assertThat(returnedDcm.getString(org.dcm4che3.data.Tag.SOPClassUID))
                    .isEqualTo(org.dcm4che3.data.UID.SecondaryCaptureImageStorage);
            assertThat(returnedDcm.getString(org.dcm4che3.data.Tag.SeriesDescription))
                    .contains("AI draft (LLM, grok-2-vision-1212");
            assertThat(returnedDcm.getBytes(org.dcm4che3.data.Tag.PixelData)).isNotEmpty();
        }

        ResponseEntity<byte[]> imageResp = controller.downloadAiResultImage(pngRes.getId(), siteId);
        assertThat(imageResp.getStatusCode().value()).isEqualTo(200);
        assertThat(imageResp.getBody()).isNotEmpty();

        // 3. Verify Base64 JPEG (Lossless Quality 1.0) Outbound & Return via PACS AE Title, IP & Port
        Map<String, Object> jpegAeSim = controller.runSimulatorScenario(siteId, "grok-xray-base64-jpeg-ae");
        UUID jpegStudyId = (UUID) jpegAeSim.get("studyId");
        List<AiResultRecord> jpegResults = aiResultRecordRepository.findBySiteIdAndStudyIdOrderByReceivedAtDesc(siteId, jpegStudyId);
        assertThat(jpegResults).isNotEmpty();
        AiResultRecord jpegRes = jpegResults.getFirst();
        assertThat(jpegRes.getResultType()).isEqualTo("BASE64_JPEG");
        assertThat(jpegRes.getDicomAeReturnStatus()).startsWith("SENT_TO_AE_RASTERIPACS@172.17.1.200:11112");
        assertThat(jpegRes.getImageStoragePath()).endsWith(".jpg");

        // 4. Verify Purge Non-Grok Demo Data endpoint keeps only Grok AI vendor, X-Ray rule, and Grok X-Ray studies
        Map<String, Object> purgeRes = controller.purgeDemoDataKeepGrokOnly(siteId);
        assertThat(purgeRes.get("purged")).isEqualTo(true);
        assertThat(controller.listVendors(siteId))
                .extracting(VendorConfig::getVendorCode)
                .containsExactly("VENDOR-GROK-XRAY");
        assertThat(controller.listStudies(siteId, null, null, null)).isNotEmpty();

        // Restore default PDF_VIA_RIS_API mode after test
        controller.runSimulatorScenario(siteId, "grok-xray");
    }

    @Test
    void shouldPropagateConfiguredAiAgentNameIntoGeneratedByAndReportDataAndProvidePdfAndStudyProcessingLogs() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();

        // 1. Update AI Agent Name (agentName) in the Agent Configuration screen
        VendorConfig grokVendor = controller.listVendors(siteId).stream()
                .filter(v -> "VENDOR-GROK-XRAY".equalsIgnoreCase(v.getVendorCode()))
                .findFirst()
                .orElseThrow();
        String originalAgentName = grokVendor.getAgentName();

        try {
            String customAgentName = "Kauvery Grok X-Ray AI Agent v2";
            VendorDestinationUpdateDto agentUpdate = new VendorDestinationUpdateDto(
                    grokVendor.getVendorName(),
                    grokVendor.getUseCaseName(),
                    grokVendor.getDestinationType(),
                    grokVendor.getDestinationAeTitle(),
                    grokVendor.getDestinationHost(),
                    grokVendor.getDestinationPort(),
                    grokVendor.getConnectorType(),
                    grokVendor.getApiBaseUrl(),
                    grokVendor.getAuthType(),
                    grokVendor.getApiKeySecret(),
                    grokVendor.getAiModelName(),
                    grokVendor.getExecutionMode(),
                    grokVendor.getSystemPromptTemplate(),
                    grokVendor.getOutputReportFormat(),
                    "BASE64_PNG_LOSSLESS",
                    "PDF_VIA_RIS_API",
                    customAgentName);
            VendorConfig updatedVendor = controller.updateVendorDestination(grokVendor.getId(), siteId, agentUpdate);
            assertThat(updatedVendor.getAgentName()).isEqualTo(customAgentName);
            assertThat(updatedVendor.resolveEffectiveAgentName()).isEqualTo(customAgentName);

            // 2. Process an X-Ray study through the AI Agent and verify reportData and generatedBy sent to RIS
            Map<String, Object> simRes = controller.runSimulatorScenario(siteId, "grok-xray");
            UUID studyId = (UUID) simRes.get("studyId");
            String studyInstanceUid = (String) simRes.get("studyInstanceUid");

            List<RisUploadedReport> risReports = risUploadedReportRepository
                    .findBySiteIdAndStudyIuidOrderByUploadedAtDesc(siteId, studyInstanceUid);
            assertThat(risReports).hasSize(1);
            RisUploadedReport risReport = risReports.getFirst();
            assertThat(risReport.getGeneratedBy()).isEqualTo(customAgentName);
            assertThat(risReport.getReportData()).isNotBlank();
            assertThat(risReport.getReportData()).contains("\"generatedBy\": \"" + customAgentName + "\"");
            assertThat(risReport.getReportData()).contains("\"studyIuid\": \"" + studyInstanceUid + "\"");
            assertThat(risReport.getProcessingLog()).contains("generatedBy = \"" + customAgentName + "\"");

            List<AiResultRecord> aiResults = aiResultRecordRepository.findBySiteIdAndStudyIdOrderByReceivedAtDesc(siteId, studyId);
            assertThat(aiResults).isNotEmpty();
            assertThat(aiResults.getFirst().getGeneratedBy()).isEqualTo(customAgentName);
            assertThat(aiResults.getFirst().getReportData()).contains("\"generatedBy\": \"" + customAgentName + "\"");

            // 3. Verify generated PDF file is viewable via /studies/{studyId}/pdf and contains the AI Agent generatedBy name
            ResponseEntity<byte[]> studyPdfResp = controller.downloadStudyPdf(studyId, siteId);
            assertThat(studyPdfResp.getStatusCode().value()).isEqualTo(200);
            assertThat(studyPdfResp.getBody()).isNotEmpty();
            String pdfContent = new String(studyPdfResp.getBody(), StandardCharsets.US_ASCII);
            assertThat(pdfContent).startsWith("%PDF-1.4");
            assertThat(pdfContent).contains(customAgentName);

            // 4. Verify per-study processing logs endpoint (/studies/{studyId}/logs) returns all 7 lifecycle steps
            CommandCentreController.StudyDetailTimelineDto studyLogs = controller.getStudyProcessingLogs(studyId, siteId);
            assertThat(studyLogs.latestGeneratedBy()).isEqualTo(customAgentName);
            assertThat(studyLogs.latestReportDataJson())
                    .contains("\"reportType\": \"Preliminary Report\"")
                    .contains("\"generatedBy\": \"" + customAgentName + "\"");
            assertThat(studyLogs.latestPdfUrl()).isNotBlank();
            assertThat(studyLogs.pdfTextPreview())
                    .contains("Generated By (AI)  : " + customAgentName)
                    .contains("Report Type        : Preliminary Report");
            assertThat(studyLogs.risUploadedReports()).hasSize(1);
            assertThat(studyLogs.processingLogs()).hasSizeGreaterThanOrEqualTo(7);
            assertThat(studyLogs.processingLogs())
                    .extracting(CommandCentreController.StudyProcessingLogEntryDto::stage)
                    .anyMatch(s -> s.contains("DICOM RECEPTION"))
                    .anyMatch(s -> s.contains("VALIDATION"))
                    .anyMatch(s -> s.contains("ROUTING ENGINE"))
                    .anyMatch(s -> s.contains("PS3.15 DE-IDENTIFICATION"))
                    .anyMatch(s -> s.contains("AI AGENT IMAGE TRANSFER"))
                    .anyMatch(s -> s.contains("CLINICAL PDF GENERATION"))
                    .anyMatch(s -> s.contains("RIS / TELERAD UploadReportServlet"));

            // 5. Verify changing defaultReportType in RIS configuration (e.g. to 'Study Report') propagates to RIS API calls
            RisReportEndpoint risEp = controller.getRisReportEndpoint(siteId);
            String origReportType = risEp.getDefaultReportType();
            try {
                risEp.setDefaultReportType("Study Report");
                controller.updateRisReportEndpoint(siteId, risEp);

                CommandCentreController.StudyDetailTimelineDto studyReportTimeline =
                        controller.sendStudyPdfToRisOnDemand(studyId, siteId, null);
                assertThat(studyReportTimeline.risUploadedReports().getFirst().getReportType()).isEqualTo("Study Report");
                assertThat(studyReportTimeline.risUploadedReports().getFirst().getReportData())
                        .contains("\"reportType\": \"Study Report\"")
                        .contains("\"generatedBy\": \"" + customAgentName + "\"");
            } finally {
                risEp.setDefaultReportType(origReportType != null ? origReportType : "Preliminary Report");
                controller.updateRisReportEndpoint(siteId, risEp);
            }

            // Also verify explicit Addendum Report override on re-send
            CommandCentreController.StudyDetailTimelineDto resentTimeline =
                    controller.sendStudyPdfToRisOnDemand(studyId, siteId, "Addendum Report");
            assertThat(resentTimeline.risUploadedReports().getFirst().getReportType()).isEqualTo("Addendum Report");
            assertThat(resentTimeline.risUploadedReports().getFirst().getGeneratedBy()).isEqualTo(customAgentName);
            assertThat(resentTimeline.risUploadedReports().getFirst().getReportData())
                    .contains("\"reportType\": \"Addendum Report\"")
                    .contains("\"generatedBy\": \"" + customAgentName + "\"");

            // 6. Edge Case: AI Agent name containing quotes, embedded backslashes, and trailing backslash
            String specialCharAgentName = "Kauvery AI \"Vision\" \\ Agent\\";
            VendorDestinationUpdateDto specialNameUpdate = new VendorDestinationUpdateDto(
                    grokVendor.getVendorName(),
                    grokVendor.getUseCaseName(),
                    grokVendor.getDestinationType(),
                    grokVendor.getDestinationAeTitle(),
                    grokVendor.getDestinationHost(),
                    grokVendor.getDestinationPort(),
                    grokVendor.getConnectorType(),
                    grokVendor.getApiBaseUrl(),
                    grokVendor.getAuthType(),
                    grokVendor.getApiKeySecret(),
                    grokVendor.getAiModelName(),
                    grokVendor.getExecutionMode(),
                    grokVendor.getSystemPromptTemplate(),
                    grokVendor.getOutputReportFormat(),
                    "BASE64_PNG_LOSSLESS",
                    "PDF_VIA_RIS_API",
                    null,
                    specialCharAgentName);
            VendorConfig specialVendor = controller.updateVendorDestination(grokVendor.getId(), siteId, specialNameUpdate);
            assertThat(specialVendor.getAgentName()).isEqualTo(specialCharAgentName);
            assertThat(specialVendor.getName()).isEqualTo(specialCharAgentName);

            Map<String, Object> specialSim = controller.runSimulatorScenario(siteId, "grok-xray");
            UUID specialStudyId = (UUID) specialSim.get("studyId");
            String specialStudyUid = (String) specialSim.get("studyInstanceUid");

            List<RisUploadedReport> specialRisReports = risUploadedReportRepository
                    .findBySiteIdAndStudyIuidOrderByUploadedAtDesc(siteId, specialStudyUid);
            assertThat(specialRisReports).hasSize(1);
            RisUploadedReport specialRis = specialRisReports.getFirst();
            // Verify round-trip extraction did not drop trailing backslash or double-escape backslashes
            assertThat(specialRis.getGeneratedBy()).isEqualTo(specialCharAgentName);
            assertThat(RisTeleradUploadReportService.extractJsonField(specialRis.getReportData(), "generatedBy"))
                    .isEqualTo(specialCharAgentName);

            // Verify fallback getReportData() on RisUploadedReport and AiResultRecord also escapes special JSON chars
            RisUploadedReport fallbackRis = new RisUploadedReport();
            fallbackRis.setReportType("Study Report");
            fallbackRis.setStudyIuid(specialStudyUid);
            fallbackRis.setGeneratedBy(specialCharAgentName);
            assertThat(RisTeleradUploadReportService.extractJsonField(fallbackRis.getReportData(), "generatedBy"))
                    .isEqualTo(specialCharAgentName);

            AiResultRecord fallbackAiRes = new AiResultRecord();
            fallbackAiRes.setStudyId(specialStudyId);
            fallbackAiRes.setGeneratedBy(specialCharAgentName);
            assertThat(RisTeleradUploadReportService.extractJsonField(fallbackAiRes.getReportData(), "generatedBy"))
                    .isEqualTo(specialCharAgentName);

            // 7. Verify Base64 PNG scenario also creates RisUploadedReport and all 7 study processing log steps,
            //    and purgeDemoDataKeepGrokOnly retains transfer attempts so Step 5 is preserved
            Map<String, Object> base64Sim = controller.runSimulatorScenario(siteId, "grok-xray-base64-png-ae");
            UUID base64StudyId = (UUID) base64Sim.get("studyId");
            controller.purgeDemoDataKeepGrokOnly(siteId);
            CommandCentreController.StudyDetailTimelineDto base64Logs = controller.getStudyProcessingLogs(base64StudyId, siteId);
            assertThat(base64Logs.risUploadedReports()).isNotEmpty();
            assertThat(base64Logs.processingLogs()).hasSizeGreaterThanOrEqualTo(7);
            assertThat(base64Logs.processingLogs())
                    .extracting(CommandCentreController.StudyProcessingLogEntryDto::stage)
                    .anyMatch(s -> s.contains("5. AI AGENT IMAGE TRANSFER"))
                    .anyMatch(s -> s.contains("7. RIS / TELERAD UploadReportServlet"));
        } finally {
            VendorDestinationUpdateDto restore = new VendorDestinationUpdateDto(
                    grokVendor.getVendorName(),
                    grokVendor.getUseCaseName(),
                    grokVendor.getDestinationType(),
                    grokVendor.getDestinationAeTitle(),
                    grokVendor.getDestinationHost(),
                    grokVendor.getDestinationPort(),
                    grokVendor.getConnectorType(),
                    grokVendor.getApiBaseUrl(),
                    grokVendor.getAuthType(),
                    grokVendor.getApiKeySecret(),
                    grokVendor.getAiModelName(),
                    grokVendor.getExecutionMode(),
                    grokVendor.getSystemPromptTemplate(),
                    grokVendor.getOutputReportFormat(),
                    "BASE64_PNG_LOSSLESS",
                    "PDF_VIA_RIS_API",
                    originalAgentName != null ? originalAgentName : "Grok AI Vision Agent");
            controller.updateVendorDestination(grokVendor.getId(), siteId, restore);
        }
    }

    @Test
    void shouldSurfaceLiveRisServerHttpErrorsInHubLogsHidePdfBeforeAiReportAndSupportOldRecordAndLogAutoPurge() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();

        // 1. Requirement 3: While receiving images from RIS/PACS (before AI report arrives),
        //    latestPdfUrl & latestReportDataJson MUST be null and /studies/{id}/pdf MUST return 404
        String receivingStudyUid = UIDUtils.createUID();
        Attributes receivingDs = new Attributes();
        receivingDs.setString(Tag.PatientName, VR.PN, "RECEIVING^PACS^PATIENT");
        receivingDs.setString(Tag.PatientID, VR.LO, "KH-REC-001");
        receivingDs.setString(Tag.AccessionNumber, VR.SH, "ACC-REC-001");
        receivingDs.setString(Tag.Modality, VR.CS, "DX");
        receivingDs.setString(Tag.StudyDescription, VR.LO, "X-RAY CHEST PA");
        receivingDs.setString(Tag.BodyPartExamined, VR.CS, "CHEST");
        receivingDs.setString(Tag.StudyInstanceUID, VR.UI, receivingStudyUid);
        receivingDs.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
        receivingDs.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
        receivingDs.setString(Tag.SOPClassUID, VR.UI, UID.DigitalXRayImageStorageForPresentation);
        InstanceRecord recInst = orchestratorService.ingestDicomInstance(
                siteId,
                "RASTERIPACS",
                "DICOMHUB_AE",
                receivingDs.createFileMetaInformation(UID.ExplicitVRLittleEndian),
                receivingDs);

        StudyDetailTimelineDto preAiTimeline = controller.getStudyDetails(recInst.getStudyId(), siteId);
        assertThat(preAiTimeline.results()).isEmpty();
        assertThat(preAiTimeline.risUploadedReports()).isEmpty();
        assertThat(preAiTimeline.latestPdfUrl()).isNull();
        assertThat(preAiTimeline.latestReportDataJson()).isNull();
        assertThat(preAiTimeline.pdfTextPreview()).isNull();

        assertThatThrownBy(() -> controller.downloadStudyPdf(recInst.getStudyId(), siteId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("AI report not yet received");

        // Even if the user previews PS3.15 anonymization while the study is still RECEIVING,
        // it must NOT queue the job prematurely or generate a PDF before study reception completes!
        controller.getStudyAnonymizationPreview(recInst.getStudyId(), siteId, false);
        queueWorkerService.drainDueOutboundJobs(OffsetDateTime.now().plusSeconds(10));
        assertThat(aiResultRecordRepository.findBySiteIdAndStudyIdOrderByReceivedAtDesc(siteId, recInst.getStudyId()))
                .isEmpty();
        StudyDetailTimelineDto stillReceivingTimeline = controller.getStudyDetails(recInst.getStudyId(), siteId);
        assertThat(stillReceivingTimeline.results()).isEmpty();
        assertThat(stillReceivingTimeline.latestPdfUrl()).isNull();
        assertThatThrownBy(() -> controller.downloadStudyPdf(recInst.getStudyId(), siteId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("AI report not yet received");

        // 2. Requirement 1: Simulate RIS server (JBoss / UploadReportServlet) throwing HTTP 401 error
        //    {"status":"failure","message":"Failed to upload the reports","reason":"Invalid username or password"}
        simulatedRisHttpStatus.set(401);
        simulatedRisResponseJson.set(
                "{\"status\":\"failure\",\"message\":\"Failed to upload the reports\",\"reason\":\"Invalid username or password\"}");

        Map<String, Object> errSim = controller.runSimulatorScenario(siteId, "grok-xray");
        UUID errStudyId = (UUID) errSim.get("studyId");
        String errStudyUid = (String) errSim.get("studyInstanceUid");

        // Verify live HTTP multipart/form-data POST was actually sent to the RIS server
        assertThat(lastReceivedUsernameHeader.get()).isNotBlank();
        assertThat(lastReceivedMultipartBody.get())
                .contains("name=\"reportData\"")
                .contains("\"reportType\": \"Preliminary Report\"")
                .contains("\"studyIuid\": \"" + errStudyUid + "\"")
                .contains("name=\"pdfFile\"")
                .contains("%PDF-1.4");

        // Verify RIS server error and exact HTTP request/response logs are recorded in RisUploadedReport
        List<RisUploadedReport> errReports = risUploadedReportRepository
                .findBySiteIdAndStudyIuidOrderByUploadedAtDesc(siteId, errStudyUid);
        assertThat(errReports).hasSize(1);
        RisUploadedReport errRep = errReports.getFirst();
        assertThat(errRep.getHttpStatus()).isEqualTo(401);
        assertThat(errRep.getResponseMessage()).contains("Failed to upload the reports").contains("Invalid username or password");
        assertThat(errRep.getResponseJson()).contains("\"status\":\"failure\"").contains("Invalid username or password");
        assertThat(errRep.getProcessingLog())
                .contains("[LIVE_HTTP_MULTIPART_POST]")
                .contains("OUTBOUND HTTP REQUEST HEADERS")
                .contains("INBOUND HTTP RESPONSE FROM RIS SERVER")
                .contains("HTTP 401")
                .contains("Invalid username or password");

        // Verify AiResultRecord and VendorDeliveryJob record the RIS error
        List<AiResultRecord> errAiResults = aiResultRecordRepository
                .findBySiteIdAndStudyIdOrderByReceivedAtDesc(siteId, errStudyId);
        assertThat(errAiResults).isNotEmpty();
        assertThat(errAiResults.getFirst().getRisUploadStatus()).isEqualTo("FAILED_HTTP_401");
        assertThat(errAiResults.getFirst().getReportPrefillStatus()).isEqualTo("RIS_TELERAD_UPLOAD_FAILED");

        List<VendorDeliveryJob> errJobs = jobRepository.findBySiteIdAndStudyId(siteId, errStudyId);
        assertThat(errJobs).isNotEmpty();
        assertThat(errJobs.getFirst().getLastError())
                .contains("HTTP 401")
                .contains("Invalid username or password");

        // Verify Active Alerts surface the RIS_SERVER_ERROR
        List<OperationalAlertDto> activeAlerts = controller.listActiveAlerts(siteId);
        assertThat(activeAlerts)
                .anyMatch(a -> "RIS_SERVER_ERROR".equals(a.category())
                        && "CRITICAL".equals(a.severity())
                        && a.description().contains("Invalid username or password"));

        // Verify Study Processing Logs Step 7 marks FAILURE and contains exact HTTP request/response log
        StudyDetailTimelineDto errTimeline = controller.getStudyProcessingLogs(errStudyId, siteId);
        assertThat(errTimeline.latestPdfUrl()).isNotBlank();
        assertThat(errTimeline.processingLogs())
                .anyMatch(l -> l.stage().contains("7. RIS / TELERAD UploadReportServlet")
                        && "FAILURE".equals(l.status())
                        && l.message().contains("HTTP 401")
                        && l.detailsJson().contains("EXACT HTTP REQUEST/RESPONSE TRANSACTION LOG"));

        // Restore HTTP 200 on embedded RIS server
        simulatedRisHttpStatus.set(200);
        simulatedRisResponseJson.set("{\n  \"status\": \"success\",\n  \"message\": \"Upload reports successfully\"\n}");

        // 3. Requirement 2 & 5: Configure Frequent Auto-Removal and Delete Single / Purge All Old Study Records & Logs
        //    while preserving 100% of configurations
        RetentionDashboardSummaryDto initialSummary = controller.getRetentionDashboardSummary(siteId);
        assertThat(initialSummary.activeStudiesCount()).isGreaterThanOrEqualTo(2);

        RecordRetentionSettings updateSettings = initialSummary.settings();
        updateSettings.setAutoRemoveEnabled(true);
        updateSettings.setRetentionMaxAgeMinutes(120);
        updateSettings.setCheckIntervalMinutes(5);
        updateSettings.setRemoveStudyLogsAndAudits(true);
        updateSettings.setRemoveDiskFiles(true);
        updateSettings.setProtectActiveReceivingStudies(true);

        RetentionDashboardSummaryDto savedSummary = controller.updateRetentionSettings(siteId, updateSettings);
        assertThat(savedSummary.settings().isAutoRemoveEnabled()).isTrue();
        assertThat(savedSummary.settings().getRetentionMaxAgeMinutes()).isEqualTo(120);
        assertThat(savedSummary.settings().getCheckIntervalMinutes()).isEqualTo(5);

        // Delete a single study record and its logs
        RecordPurgeResultDto singleDel = controller.deleteSingleStudyAndLogs(recInst.getStudyId(), siteId);
        assertThat(singleDel.removedStudiesCount()).isEqualTo(1);

        // Purge all study records and logs, then re-seed 1 Grok study for subsequent tests
        int vendorCountBefore = controller.listVendors(siteId).size();
        int ruleCountBefore = controller.listRoutingRules(siteId).size();
        int sourceCountBefore = controller.listSources(siteId).size();

        RecordPurgeResultDto fullPurge = controller.purgeOldRecordsAndLogsOnDemand(siteId, null, true, true, true);
        assertThat(fullPurge.removedStudiesCount()).isGreaterThanOrEqualTo(1);
        assertThat(controller.listStudies(siteId, null, null, null)).isEmpty();
        assertThat(controller.listRisUploadedReports(siteId)).isEmpty();

        // Configurations MUST remain 100% intact!
        assertThat(controller.listVendors(siteId)).hasSize(vendorCountBefore);
        assertThat(controller.listRoutingRules(siteId)).hasSize(ruleCountBefore);
        assertThat(controller.listSources(siteId)).hasSize(sourceCountBefore);

        // 4. Requirement 5: Verify bidirectional RIS endpoint URL persistence when edited via RIS Endpoint or PACS Destination
        RisReportEndpoint customEpReq = controller.getRisReportEndpoint(siteId);
        customEpReq.setTargetUrl("http://172.17.1.205:9090/custom/UploadReportServlet");
        customEpReq.setHostAddress("172.17.1.205");
        RisReportEndpoint savedCustomEp = controller.updateRisReportEndpoint(siteId, customEpReq);
        assertThat(savedCustomEp.getTargetUrl()).isEqualTo("http://172.17.1.205:9090/custom/UploadReportServlet");
        assertThat(savedCustomEp.getHostAddress()).isEqualTo("172.17.1.205");
        assertThat(savedCustomEp.getPort()).isEqualTo(9090);
        assertThat(savedCustomEp.getContextPath()).isEqualTo("/custom/UploadReportServlet");

        var pacsList = controller.listPacsDestinations(siteId);
        assertThat(pacsList).isNotEmpty();
        var pacs = pacsList.getFirst();
        pacs.setHostAddress("172.17.1.210");
        pacs.setReportEndpointUrl("http://172.17.1.210:8080/telerad/UploadReportServlet");
        controller.updatePacsDestination(pacs.getId(), siteId, pacs);
        RisReportEndpoint syncedFromPacs = controller.getRisReportEndpoint(siteId);
        assertThat(syncedFromPacs.getTargetUrl()).isEqualTo("http://172.17.1.210:8080/telerad/UploadReportServlet");
        assertThat(syncedFromPacs.getHostAddress()).isEqualTo("172.17.1.210");

        // Restore PACS destination default host 172.17.1.200 and point RIS endpoint to embeddedRisPort for final test step
        pacs.setHostAddress("172.17.1.200");
        pacs.setReportEndpointUrl("http://172.17.1.200:8080/telerad/UploadReportServlet");
        controller.updatePacsDestination(pacs.getId(), siteId, pacs);

        syncedFromPacs.setHostAddress("127.0.0.1");
        syncedFromPacs.setPort(embeddedRisPort);
        syncedFromPacs.setContextPath("/telerad/UploadReportServlet");
        syncedFromPacs.setTargetUrl("http://127.0.0.1:" + embeddedRisPort + "/telerad/UploadReportServlet");
        controller.updateRisReportEndpoint(siteId, syncedFromPacs);

        // Create one clean study so any test checking non-empty overview after this test also succeeds
        controller.runSimulatorScenario(siteId, "grok-xray");
    }

    @Test
    void testMandatoryAdminLoginAndAuthenticationEnforcement() {
        UUID siteId = hubProperties.defaultSiteId();

        // 1. Verify default admin/admin user exists in user list with SUPER_USER privileges
        var users = controller.listUsers(siteId);
        assertThat(users)
                .anyMatch(u -> "admin".equalsIgnoreCase(u.getUsername())
                        && "SUPER_USER".equalsIgnoreCase(u.getUserRole())
                        && u.isActive()
                        && u.isCanViewDicomConfig()
                        && u.isCanCreateDicomConfig()
                        && u.isCanEditDicomConfig()
                        && u.isCanDeleteDicomConfig()
                        && u.isCanManageUsers());

        // 2. Verify valid login with admin / admin succeeds
        var loginRes = controller.login(
                siteId,
                new CommandCentreController.AuthLoginRequestDto("admin", "admin", siteId));
        assertThat(loginRes.authenticated()).isTrue();
        assertThat(loginRes.token()).startsWith("DHUB-AUTH-");
        assertThat(loginRes.user()).isNotNull();
        assertThat(loginRes.user().getUsername()).isEqualToIgnoringCase("admin");
        assertThat(loginRes.user().getUserRole()).isEqualTo("SUPER_USER");

        // 3. Verify invalid password fails with 401 UNAUTHORIZED
        assertThatThrownBy(() -> controller.login(
                siteId,
                new CommandCentreController.AuthLoginRequestDto("admin", "wrong-password", siteId)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(401));

        // 4. Verify unknown username fails with 401 UNAUTHORIZED
        assertThatThrownBy(() -> controller.login(
                siteId,
                new CommandCentreController.AuthLoginRequestDto("nonexistent.user", "admin", siteId)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(401));

        // 5. Verify blank credentials fail with 401 UNAUTHORIZED
        assertThatThrownBy(() -> controller.login(
                siteId,
                new CommandCentreController.AuthLoginRequestDto("", "", siteId)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(401));

        // 6. Verify logout succeeds
        Map<String, Object> logoutRes = controller.logout(siteId);
        assertThat(logoutRes.get("authenticated")).isEqualTo(false);
    }

    @Test
    void testReceiveAiVendorDicomFileReidentifyAndDeliverToRisAndPacsAe() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();

        // 1. Run end-to-end scenario where AI Vendor returns a DICOM (.dcm) file -> Re-identified & delivered to RIS & PACS AE
        Map<String, Object> simRes = controller.runSimulatorScenario(siteId, "grok-xray-dicom-ris");
        UUID studyId = (UUID) simRes.get("studyId");
        String studyInstanceUid = (String) simRes.get("studyInstanceUid");
        assertThat(studyId).isNotNull();
        assertThat(studyInstanceUid).isNotBlank();

        // 2. Verify live HTTP multipart/form-data POST to RIS UploadReportServlet included reportData + dicomFile (.dcm)
        assertThat(lastReceivedMultipartBody.get())
                .contains("name=\"reportData\"")
                .contains("\"studyIuid\": \"" + studyInstanceUid + "\"")
                .contains("\"fileFormat\": \"DICOM\"")
                .contains("name=\"dicomFile\"")
                .contains("Content-Type: application/dicom")
                .contains("DICM");

        // 3. Verify AiResultRecord has re-identified DICOM file metadata and RIS + AE statuses
        List<AiResultRecord> aiResults = aiResultRecordRepository
                .findBySiteIdAndStudyIdOrderByReceivedAtDesc(siteId, studyId);
        assertThat(aiResults).isNotEmpty();
        AiResultRecord dicomResult = aiResults.getFirst();
        assertThat(dicomResult.isReidentified()).isTrue();
        assertThat(dicomResult.isPacsForwarded()).isTrue();
        assertThat(dicomResult.getDicomFileName()).endsWith("-AI-VENDOR-DICOM.dcm");
        assertThat(dicomResult.getDicomFileSizeBytes()).isGreaterThan(512L);
        assertThat(dicomResult.getDicomStoragePath()).isNotBlank();
        assertThat(Files.exists(Path.of(dicomResult.getDicomStoragePath()))).isTrue();
        assertThat(dicomResult.getRisDicomUploadStatus()).isEqualTo("DICOM_UPLOADED_HTTP_200");
        assertThat(dicomResult.getDicomAeReturnStatus()).startsWith("SENT_DICOM_TO_AE_");
        assertThat(dicomResult.getReportPrefillStatus()).isEqualTo("RIS_DICOM_UPLOADED_AND_AE_SENT");

        // 4. Parse the re-identified .dcm file using dcm4che and verify PatientID, PatientName, StudyInstanceUID & Draft Label
        ResponseEntity<byte[]> downloadedDcmResp = controller.downloadAiResultDicom(dicomResult.getId(), siteId);
        assertThat(downloadedDcmResp.getStatusCode().value()).isEqualTo(200);
        assertThat(downloadedDcmResp.getBody()).isNotNull();
        assertThat(downloadedDcmResp.getBody().length).isEqualTo((int) dicomResult.getDicomFileSizeBytes());

        try (org.dcm4che3.io.DicomInputStream dis = new org.dcm4che3.io.DicomInputStream(
                new java.io.ByteArrayInputStream(downloadedDcmResp.getBody()))) {
            Attributes reidDs = dis.readDataset();
            assertThat(reidDs.getString(Tag.StudyInstanceUID)).isEqualTo(studyInstanceUid);
            assertThat(reidDs.getString(Tag.PatientName)).isEqualTo("SIVAKUMAR^V");
            assertThat(reidDs.getString(Tag.PatientID)).startsWith("KH-PAT-");
            assertThat(reidDs.getString(Tag.PatientIdentityRemoved)).isEqualTo("NO");
            assertThat(reidDs.getString(Tag.SeriesDescription))
                    .contains("AI draft (LLM, grok-2-vision-1212")
                    .contains("not verified");
        }

        // 5. Verify RisUploadedReport recorded the DICOM upload transaction and supports .dcm download
        List<RisUploadedReport> risReports = risUploadedReportRepository
                .findBySiteIdAndStudyIuidOrderByUploadedAtDesc(siteId, studyInstanceUid);
        assertThat(risReports).isNotEmpty();
        RisUploadedReport risDcmRep = risReports.getFirst();
        assertThat(risDcmRep.getFileFormat()).isEqualTo("DICOM");
        assertThat(risDcmRep.getDicomFileName()).isEqualTo(dicomResult.getDicomFileName());
        assertThat(risDcmRep.getDicomFileSizeBytes()).isEqualTo(dicomResult.getDicomFileSizeBytes());
        assertThat(risDcmRep.getHttpStatus()).isEqualTo(200);

        ResponseEntity<byte[]> risDcmDownload = controller.downloadRisUploadedReportDicom(risDcmRep.getId(), siteId);
        assertThat(risDcmDownload.getStatusCode().value()).isEqualTo(200);
        assertThat(risDcmDownload.getBody()).hasSize((int) dicomResult.getDicomFileSizeBytes());

        // 6. Test Dual PDF + DICOM (.dcm) Return Mode (BOTH_PDF_API_AND_DICOM_AE)
        Map<String, Object> dualSimRes = controller.runSimulatorScenario(siteId, "grok-xray-both-pdf-and-dicom-ris");
        UUID dualStudyId = (UUID) dualSimRes.get("studyId");
        String dualStudyUid = (String) dualSimRes.get("studyInstanceUid");

        assertThat(lastReceivedMultipartBody.get())
                .contains("name=\"reportData\"")
                .contains("\"fileFormat\": \"PDF_AND_DICOM\"")
                .contains("name=\"dicomFile\"")
                .contains("name=\"pdfFile\"");

        List<AiResultRecord> dualResults = aiResultRecordRepository
                .findBySiteIdAndStudyIdOrderByReceivedAtDesc(siteId, dualStudyId);
        assertThat(dualResults).isNotEmpty();
        assertThat(dualResults.getFirst().getRisUploadStatus()).isEqualTo("PDF_AND_DICOM_UPLOADED_HTTP_200");
        assertThat(dualResults.getFirst().getReportPrefillStatus()).isEqualTo("RIS_PDF_AND_DICOM_UPLOADED");

        List<RisUploadedReport> dualRisReports = risUploadedReportRepository
                .findBySiteIdAndStudyIuidOrderByUploadedAtDesc(siteId, dualStudyUid);
        assertThat(dualRisReports).isNotEmpty();
        assertThat(dualRisReports.getFirst().getFileFormat()).isEqualTo("PDF_AND_DICOM");

        // 7. Test on-demand RIS DICOM test-upload endpoint
        Map<String, Object> testDcmUpload = controller.testRisDicomUpload(siteId, "Preliminary Report", null, false);
        assertThat(testDcmUpload.get("httpStatus")).isEqualTo(200);
        assertThat(testDcmUpload.get("fileFormat")).isEqualTo("DICOM");
        assertThat((String) testDcmUpload.get("dicomFileName")).endsWith("-AI-VENDOR-DICOM.dcm");
    }
}
