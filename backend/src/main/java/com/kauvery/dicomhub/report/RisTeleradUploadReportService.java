package com.kauvery.dicomhub.report;

import com.kauvery.dicomhub.audit.AuditService;
import com.kauvery.dicomhub.config.HubProperties;
import com.kauvery.dicomhub.domain.entity.RisReportEndpoint;
import com.kauvery.dicomhub.domain.entity.RisUploadedReport;
import com.kauvery.dicomhub.domain.entity.Site;
import com.kauvery.dicomhub.domain.entity.StudyRecord;
import com.kauvery.dicomhub.domain.entity.VendorConfig;
import com.kauvery.dicomhub.domain.repository.RisReportEndpointRepository;
import com.kauvery.dicomhub.domain.repository.RisUploadedReportRepository;
import com.kauvery.dicomhub.domain.repository.SiteRepository;
import com.kauvery.dicomhub.domain.repository.StudyRecordRepository;
import com.kauvery.dicomhub.domain.repository.VendorConfigRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the RIS / PACS Telerad UploadReportServlet integration contract
 * specified in Upload_Report_API_PACS_Integration_Updated.pdf:
 *
 * - Endpoint: POST http://<PACS_IP>:PORT/telerad/UploadReportServlet
 * - Content-Type: multipart/form-data
 * - Headers: username, password (plus Basic Auth header)
 * - Part 1 (application/json):
 *   {
 *     "reportType": "Study Report" | "Addendum Report",
 *     "studyIuid": "<original StudyInstanceUID>",
 *     "draftedTime": "yyyy-MM-dd HH:mm:ss",
 *     "draftedBy": "<commonUsername>",
 *     "reviewedTime": "yyyy-MM-dd HH:mm:ss",
 *     "reviewedBy": "<commonUsername>"
 *   }
 * - Part 2 (application/pdf): file part named "pdfFile" (<= 15 MB)
 * - Duplicate handling (Section 7): "Study Report" replaces existing report for studyIuid;
 *   "Addendum Report" adds as a new report.
 */
@Service
public class RisTeleradUploadReportService {

    public static final long MAX_PDF_SIZE_BYTES = 15L * 1024L * 1024L; // 15 MB per Section 3.2 & 5
    public static final DateTimeFormatter TELERAD_LOCAL_DATETIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final String HARDCODED_RIS_REPORT_TYPE = "Preliminary Report";
    public static final Set<String> ALLOWED_REPORT_TYPES =
            Set.of("Preliminary Report", "Study Report", "Addendum Report");

    private final HubProperties hubProperties;
    private final SiteRepository siteRepository;
    private final StudyRecordRepository studyRecordRepository;
    private final VendorConfigRepository vendorConfigRepository;
    private final RisReportEndpointRepository endpointRepository;
    private final RisUploadedReportRepository uploadedReportRepository;
    private final AuditService auditService;
    private final HttpClient httpClient;

    public RisTeleradUploadReportService(
            HubProperties hubProperties,
            SiteRepository siteRepository,
            StudyRecordRepository studyRecordRepository,
            VendorConfigRepository vendorConfigRepository,
            RisReportEndpointRepository endpointRepository,
            RisUploadedReportRepository uploadedReportRepository,
            AuditService auditService) {
        this.hubProperties = hubProperties;
        this.siteRepository = siteRepository;
        this.studyRecordRepository = studyRecordRepository;
        this.vendorConfigRepository = vendorConfigRepository;
        this.endpointRepository = endpointRepository;
        this.uploadedReportRepository = uploadedReportRepository;
        this.auditService = auditService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public record UploadReportServletResult(
            int httpStatus,
            String status,
            String message,
            String reason,
            String rawResponseJson,
            UUID uploadedReportId,
            boolean replacedPrevious,
            String reportData,
            String generatedBy,
            String processingLog
    ) {
        public UploadReportServletResult(
                int httpStatus,
                String status,
                String message,
                String reason,
                String rawResponseJson,
                UUID uploadedReportId,
                boolean replacedPrevious) {
            this(httpStatus, status, message, reason, rawResponseJson, uploadedReportId, replacedPrevious, null, null, null);
        }

        public UploadReportServletResult(
                int httpStatus,
                String status,
                String message,
                String reason,
                String rawResponseJson,
                UUID uploadedReportId,
                boolean replacedPrevious,
                String reportData,
                String generatedBy) {
            this(httpStatus, status, message, reason, rawResponseJson, uploadedReportId, replacedPrevious, reportData, generatedBy, null);
        }
    }

    public String resolveSiteConfiguredAgentName(UUID siteId) {
        return vendorConfigRepository.findBySiteIdOrderByVendorCodeAsc(siteId).stream()
                .filter(VendorConfig::isActive)
                .filter(v -> "GROK_AI_VISION".equalsIgnoreCase(v.getConnectorType())
                        || (v.getVendorCode() != null && v.getVendorCode().toUpperCase().contains("GROK")))
                .findFirst()
                .or(() -> vendorConfigRepository.findBySiteIdOrderByVendorCodeAsc(siteId).stream().findFirst())
                .map(VendorConfig::resolveEffectiveAgentName)
                .orElse("Grok AI Vision Agent");
    }

    public RisReportEndpoint getOrCreateSiteEndpoint(UUID siteId) {
        return endpointRepository.findFirstBySiteIdAndActiveTrueOrderByCreatedAtAsc(siteId)
                .or(() -> endpointRepository.findBySiteIdOrderByCreatedAtAsc(siteId).stream().findFirst())
                .orElseGet(() -> {
                    RisReportEndpoint ep = new RisReportEndpoint();
                    ep.setId(UUID.randomUUID());
                    ep.setSiteId(siteId);
                    ep.setEndpointName("Kauvery Telerad / RIS UploadReportServlet");
                    ep.setHostAddress("172.17.1.200");
                    ep.setPort(8080);
                    ep.setContextPath("/telerad/UploadReportServlet");
                    ep.setTargetUrl("http://172.17.1.200:8080/telerad/UploadReportServlet");
                    ep.setAuthUsername("sonocare");
                    ep.setAuthPassword("sonocare123");
                    ep.setJsonPartName("reportData");
                    ep.setFilePartName("pdfFile");
                    ep.setDicomPartName("dicomFile");
                    ep.setSupportDicomUpload(true);
                    ep.setForwardDicomToPacsAe(true);
                    ep.setDefaultReportType(HARDCODED_RIS_REPORT_TYPE);
                    ep.setUseBuiltinMock(false);
                    ep.setActive(true);
                    ep.setCreatedAt(OffsetDateTime.now());
                    ep.setUpdatedAt(OffsetDateTime.now());
                    return endpointRepository.save(ep);
                });
    }

    public static String formatCurrentLocalTeleradTime() {
        return LocalDateTime.now().format(TELERAD_LOCAL_DATETIME_FMT);
    }

    public static String buildUploadReportJsonPayload(
            String reportType,
            String studyIuid,
            String draftedTime,
            String draftedBy,
            String reviewedTime,
            String reviewedBy) {
        return buildUploadReportJsonPayload(
                reportType, studyIuid, draftedTime, draftedBy, reviewedTime, reviewedBy, "Grok AI Vision Agent");
    }

    public static String buildUploadReportJsonPayload(
            String reportType,
            String studyIuid,
            String draftedTime,
            String draftedBy,
            String reviewedTime,
            String reviewedBy,
            String generatedBy) {
        String effectiveGeneratedBy = (generatedBy != null && !generatedBy.isBlank())
                ? generatedBy.trim()
                : (reviewedBy != null && !reviewedBy.isBlank() ? reviewedBy.trim() : "Grok AI Vision Agent");
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"reportType\": \"").append(escapeJson(reportType)).append("\",\n");
        sb.append("  \"studyIuid\": \"").append(escapeJson(studyIuid)).append("\",\n");
        if (draftedTime != null && !draftedTime.isBlank()) {
            sb.append("  \"draftedTime\": \"").append(escapeJson(draftedTime)).append("\",\n");
        }
        if (draftedBy != null && !draftedBy.isBlank()) {
            sb.append("  \"draftedBy\": \"").append(escapeJson(draftedBy)).append("\",\n");
        }
        sb.append("  \"reviewedTime\": \"").append(escapeJson(reviewedTime)).append("\",\n");
        sb.append("  \"reviewedBy\": \"").append(escapeJson(reviewedBy)).append("\",\n");
        sb.append("  \"generatedBy\": \"").append(escapeJson(effectiveGeneratedBy)).append("\"\n");
        sb.append("}");
        return sb.toString();
    }

    public static String buildUploadReportWithDicomJsonPayload(
            String reportType,
            String studyIuid,
            String draftedTime,
            String draftedBy,
            String reviewedTime,
            String reviewedBy,
            String generatedBy,
            String fileFormat,
            String dicomFileName) {
        String effectiveGeneratedBy = (generatedBy != null && !generatedBy.isBlank())
                ? generatedBy.trim()
                : (reviewedBy != null && !reviewedBy.isBlank() ? reviewedBy.trim() : "Grok AI Vision Agent");
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"reportType\": \"").append(escapeJson(reportType)).append("\",\n");
        sb.append("  \"studyIuid\": \"").append(escapeJson(studyIuid)).append("\",\n");
        if (draftedTime != null && !draftedTime.isBlank()) {
            sb.append("  \"draftedTime\": \"").append(escapeJson(draftedTime)).append("\",\n");
        }
        if (draftedBy != null && !draftedBy.isBlank()) {
            sb.append("  \"draftedBy\": \"").append(escapeJson(draftedBy)).append("\",\n");
        }
        sb.append("  \"reviewedTime\": \"").append(escapeJson(reviewedTime)).append("\",\n");
        sb.append("  \"reviewedBy\": \"").append(escapeJson(reviewedBy)).append("\",\n");
        if (fileFormat != null && !fileFormat.isBlank()) {
            sb.append("  \"fileFormat\": \"").append(escapeJson(fileFormat)).append("\",\n");
        }
        if (dicomFileName != null && !dicomFileName.isBlank()) {
            sb.append("  \"dicomFileName\": \"").append(escapeJson(dicomFileName)).append("\",\n");
        }
        sb.append("  \"generatedBy\": \"").append(escapeJson(effectiveGeneratedBy)).append("\"\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Dispatches a generated PDF report to the site's configured RIS / Telerad UploadReportServlet endpoint.
     * Supports both the built-in Mock UploadReportServlet and live HTTP multipart/form-data POST.
     */
    @Transactional
    public UploadReportServletResult uploadPdfReportToRis(
            UUID siteId,
            UUID jobId,
            UUID studyId,
            String studyIuid,
            String reportTypeOverride,
            String draftedTime,
            String reviewedTime,
            String pdfFileName,
            Path pdfFilePath,
            byte[] pdfBytes,
            boolean allowSyntheticStudyForTest) throws IOException {
        return uploadPdfReportToRis(
                siteId,
                jobId,
                studyId,
                studyIuid,
                reportTypeOverride,
                draftedTime,
                reviewedTime,
                resolveSiteConfiguredAgentName(siteId),
                pdfFileName,
                pdfFilePath,
                pdfBytes,
                allowSyntheticStudyForTest);
    }

    @Transactional
    public UploadReportServletResult uploadPdfReportToRis(
            UUID siteId,
            UUID jobId,
            UUID studyId,
            String studyIuid,
            String reportTypeOverride,
            String draftedTime,
            String reviewedTime,
            String generatedByOverride,
            String pdfFileName,
            Path pdfFilePath,
            byte[] pdfBytes,
            boolean allowSyntheticStudyForTest) throws IOException {

        RisReportEndpoint endpoint = getOrCreateSiteEndpoint(siteId);
        String configuredDefaultType = (endpoint.getDefaultReportType() != null && !endpoint.getDefaultReportType().isBlank())
                ? endpoint.getDefaultReportType().trim()
                : HARDCODED_RIS_REPORT_TYPE;
        String effectiveReportType = (reportTypeOverride != null && !reportTypeOverride.isBlank())
                ? reportTypeOverride.trim()
                : configuredDefaultType;
        String commonUser = endpoint.getAuthUsername();
        String effectiveGeneratedBy = (generatedByOverride != null && !generatedByOverride.isBlank())
                ? generatedByOverride.trim()
                : resolveSiteConfiguredAgentName(siteId);
        String effectiveDraftedTime = (draftedTime != null && !draftedTime.isBlank())
                ? draftedTime.trim()
                : formatCurrentLocalTeleradTime();
        String effectiveReviewedTime = (reviewedTime != null && !reviewedTime.isBlank())
                ? reviewedTime.trim()
                : effectiveDraftedTime;

        String reportDataJsonPayload = buildUploadReportJsonPayload(
                effectiveReportType,
                studyIuid,
                effectiveDraftedTime,
                commonUser,
                effectiveReviewedTime,
                commonUser,
                effectiveGeneratedBy);

        if (endpoint.isUseBuiltinMock()) {
            return handleMockUploadReportServlet(
                    siteId,
                    jobId,
                    studyId,
                    endpoint.getAuthUsername(),
                    endpoint.getAuthPassword(),
                    reportDataJsonPayload,
                    effectiveGeneratedBy,
                    pdfFileName,
                    pdfFilePath,
                    pdfBytes,
                    allowSyntheticStudyForTest);
        }

        // Ensure PDF is persisted on disk for live external RIS dispatch too
        Path storedPdfPath = pdfFilePath;
        String safeFileName = (pdfFileName != null && !pdfFileName.isBlank())
                ? pdfFileName
                : ("report-" + studyIuid.trim().replaceAll("[^A-Za-z0-9.]", "_") + ".pdf");
        if ((storedPdfPath == null || !Files.exists(storedPdfPath)) && pdfBytes != null && pdfBytes.length > 0) {
            Site site = siteRepository.findById(siteId).orElseThrow();
            Path risDir = Path.of(
                    hubProperties.storage().rootDir(),
                    "ris-telerad-reports",
                    site.getSiteCode(),
                    studyIuid.trim().replaceAll("[^A-Za-z0-9.]", "_"));
            Files.createDirectories(risDir);
            storedPdfPath = risDir.resolve(safeFileName);
            Files.write(storedPdfPath, pdfBytes);
        }

        // Live external RIS HTTP POST with multipart/form-data (sending reportData JSON and pdfFile)
        String jsonPartName = endpoint.getJsonPartName() != null && !endpoint.getJsonPartName().isBlank()
                ? endpoint.getJsonPartName()
                : "reportData";
        String filePartName = endpoint.getFilePartName() != null && !endpoint.getFilePartName().isBlank()
                ? endpoint.getFilePartName()
                : "pdfFile";
        String boundary = "----KauveryDicomHubRisBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] multipartBody = buildMultipartFormDataBody(
                boundary,
                jsonPartName,
                reportDataJsonPayload,
                filePartName,
                safeFileName,
                pdfBytes);

        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                (endpoint.getAuthUsername() + ":" + endpoint.getAuthPassword()).getBytes(StandardCharsets.UTF_8));
        String maskedPass = endpoint.getAuthPassword() != null
                ? ("*".repeat(Math.min(8, endpoint.getAuthPassword().length()))
                        + " (len=" + endpoint.getAuthPassword().length() + ")")
                : "<empty>";
        long startNanos = System.nanoTime();
        OffsetDateTime requestTimestamp = OffsetDateTime.now();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.getTargetUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("username", endpoint.getAuthUsername())
                    .header("password", endpoint.getAuthPassword())
                    .header("generatedBy", effectiveGeneratedBy)
                    .header("X-Generated-By", effectiveGeneratedBy)
                    .header("Authorization", basicAuth)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
            int statusCode = response.statusCode();
            String respBody = response.body() != null && !response.body().isBlank()
                    ? response.body().trim()
                    : (statusCode == 200
                            ? "{\"status\":\"success\",\"message\":\"Upload reports successfully\"}"
                            : "{\"status\":\"failure\",\"message\":\"Failed to upload the reports\",\"reason\":\"HTTP " + statusCode + " from RIS server\"}");

            String serverHeader = response.headers().firstValue("Server").orElse("N/A");
            String poweredByHeader = response.headers().firstValue("X-Powered-By").orElse("N/A");
            String contentTypeHeader = response.headers().firstValue("Content-Type").orElse("N/A");
            String dateHeader = response.headers().firstValue("Date").orElse("N/A");
            String extractedStatus = extractJsonField(respBody, "status");
            String extractedMessage = extractJsonField(respBody, "message");
            String extractedReason = extractJsonField(respBody, "reason");
            boolean isSuccess = (statusCode >= 200 && statusCode < 300)
                    && !"failure".equalsIgnoreCase(extractedStatus)
                    && !"error".equalsIgnoreCase(extractedStatus);

            String liveHttpTransactionLog = String.format(
                    "[%s] LIVE RIS UploadReportServlet HTTP Transaction [LIVE_HTTP_MULTIPART_POST]:\n"
                            + "  >>> OUTBOUND HTTP REQUEST HEADERS TO RIS SERVER:\n"
                            + "      POST %s\n"
                            + "      Headers:\n"
                            + "        Content-Type : multipart/form-data; boundary=%s\n"
                            + "        username     : %s\n"
                            + "        password     : %s\n"
                            + "        generatedBy = \"%s\"\n"
                            + "        Authorization: Basic <encoded>\n"
                            + "      Multipart Part 1 (name=\"%s\", Content-Type: application/json; charset=UTF-8):\n"
                            + "        %s\n"
                            + "      Multipart Part 2 (name=\"%s\", filename=\"%s\", Content-Type: application/pdf):\n"
                            + "        <%d bytes PDF-1.4 payload, path=%s>\n"
                            + "  <<< INBOUND HTTP RESPONSE FROM RIS SERVER (%d ms):\n"
                            + "      HTTP Status  : HTTP %d (%s)\n"
                            + "      Server       : %s (X-Powered-By: %s)\n"
                            + "      Content-Type : %s | Date: %s\n"
                            + "      Response Body: %s%s",
                    requestTimestamp,
                    endpoint.getTargetUrl(),
                    boundary,
                    endpoint.getAuthUsername(),
                    maskedPass,
                    effectiveGeneratedBy,
                    jsonPartName,
                    reportDataJsonPayload.replace("\n", " "),
                    filePartName,
                    safeFileName,
                    pdfBytes != null ? pdfBytes.length : 0,
                    storedPdfPath != null ? storedPdfPath.toAbsolutePath() : "N/A",
                    latencyMs,
                    statusCode,
                    isSuccess ? "SUCCESS" : "RIS_SERVER_ERROR",
                    serverHeader,
                    poweredByHeader,
                    contentTypeHeader,
                    dateHeader,
                    respBody.replace("\n", " "),
                    !isSuccess
                            ? ("\n      ERROR REASON : "
                                    + (extractedReason != null && !extractedReason.isBlank()
                                            ? extractedReason
                                            : (extractedMessage != null ? extractedMessage : "HTTP " + statusCode)))
                            : "");

            if (!isSuccess) {
                auditService.record(
                        siteId, commonUser, "RIS_UPLOAD_CLIENT", "RESULT_RETURN",
                        "RIS_REPORT_UPLOAD_SERVER_ERROR", "StudyRecord", studyIuid,
                        "FAILURE", respBody);
            }

            return recordUploadedReportTransaction(
                    siteId,
                    jobId,
                    studyId,
                    studyIuid,
                    effectiveReportType,
                    effectiveDraftedTime,
                    commonUser,
                    effectiveReviewedTime,
                    commonUser,
                    effectiveGeneratedBy,
                    reportDataJsonPayload,
                    safeFileName,
                    storedPdfPath,
                    pdfBytes,
                    endpoint.getTargetUrl(),
                    statusCode,
                    respBody,
                    liveHttpTransactionLog);
        } catch (Exception ex) {
            long latencyMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
            String errReason = "Live RIS connection error (" + endpoint.getTargetUrl() + "): "
                    + ex.getClass().getSimpleName() + (ex.getMessage() != null ? " - " + ex.getMessage() : "");
            String errJson = "{\"status\":\"failure\",\"message\":\"Failed to upload the reports\",\"reason\":\""
                    + escapeJson(errReason) + "\"}";
            String liveErrorLog = String.format(
                    "[%s] LIVE RIS UploadReportServlet HTTP Connection Failure (%d ms):\n"
                            + "  >>> OUTBOUND HTTP REQUEST TO RIS SERVER:\n"
                            + "      POST %s\n"
                            + "      Headers: username=%s | password=%s | generatedBy=%s\n"
                            + "      Multipart Part 1 (name=\"%s\"): %s\n"
                            + "      Multipart Part 2 (name=\"%s\", filename=\"%s\"): %d bytes\n"
                            + "  <<< CONNECTION / TRANSPORT EXCEPTION:\n"
                            + "      HTTP Status  : HTTP 502 (BAD_GATEWAY / CONNECTION_ERROR)\n"
                            + "      Exception    : %s\n"
                            + "      Error JSON   : %s",
                    requestTimestamp,
                    latencyMs,
                    endpoint.getTargetUrl(),
                    endpoint.getAuthUsername(),
                    maskedPass,
                    effectiveGeneratedBy,
                    jsonPartName,
                    reportDataJsonPayload.replace("\n", " "),
                    filePartName,
                    safeFileName,
                    pdfBytes != null ? pdfBytes.length : 0,
                    errReason,
                    errJson);
            auditService.record(
                    siteId, commonUser, "RIS_UPLOAD_CLIENT", "RESULT_RETURN",
                    "RIS_REPORT_UPLOAD_LIVE_ERROR", "StudyRecord", studyIuid,
                    "FAILURE", errJson);
            return recordUploadedReportTransaction(
                    siteId,
                    jobId,
                    studyId,
                    studyIuid,
                    effectiveReportType,
                    effectiveDraftedTime,
                    commonUser,
                    effectiveReviewedTime,
                    commonUser,
                    effectiveGeneratedBy,
                    reportDataJsonPayload,
                    safeFileName,
                    storedPdfPath,
                    pdfBytes,
                    endpoint.getTargetUrl(),
                    502,
                    errJson,
                    liveErrorLog);
        }
    }

    /**
     * Dispatches a re-identified DICOM (.dcm) file (alone or alongside a generated PDF report) to the site's
     * configured RIS / Telerad UploadReportServlet endpoint, supporting AI agents that return DICOM files.
     */
    @Transactional
    public UploadReportServletResult uploadDicomFileToRis(
            UUID siteId,
            UUID jobId,
            UUID studyId,
            String studyIuid,
            String reportTypeOverride,
            String draftedTime,
            String reviewedTime,
            String generatedByOverride,
            String dicomFileName,
            Path dicomFilePath,
            byte[] dicomBytes,
            String pdfFileName,
            Path pdfFilePath,
            byte[] pdfBytes,
            String dicomAeStatus,
            boolean allowSyntheticStudyForTest) throws IOException {

        RisReportEndpoint endpoint = getOrCreateSiteEndpoint(siteId);
        String configuredDefaultType = (endpoint.getDefaultReportType() != null && !endpoint.getDefaultReportType().isBlank())
                ? endpoint.getDefaultReportType().trim()
                : HARDCODED_RIS_REPORT_TYPE;
        String effectiveReportType = (reportTypeOverride != null && !reportTypeOverride.isBlank())
                ? reportTypeOverride.trim()
                : configuredDefaultType;
        String commonUser = endpoint.getAuthUsername();
        String effectiveGeneratedBy = (generatedByOverride != null && !generatedByOverride.isBlank())
                ? generatedByOverride.trim()
                : resolveSiteConfiguredAgentName(siteId);
        String effectiveDraftedTime = (draftedTime != null && !draftedTime.isBlank())
                ? draftedTime.trim()
                : formatCurrentLocalTeleradTime();
        String effectiveReviewedTime = (reviewedTime != null && !reviewedTime.isBlank())
                ? reviewedTime.trim()
                : effectiveDraftedTime;

        boolean hasPdf = pdfBytes != null && pdfBytes.length > 0;
        boolean hasDicom = dicomBytes != null && dicomBytes.length > 0;
        String fileFormat = (hasPdf && hasDicom) ? "PDF_AND_DICOM" : (hasDicom ? "DICOM" : "PDF");
        String safeDicomName = (dicomFileName != null && !dicomFileName.isBlank())
                ? dicomFileName
                : ("ai-result-" + studyIuid.trim().replaceAll("[^A-Za-z0-9.]", "_") + ".dcm");

        String reportDataJsonPayload = buildUploadReportWithDicomJsonPayload(
                effectiveReportType,
                studyIuid,
                effectiveDraftedTime,
                commonUser,
                effectiveReviewedTime,
                commonUser,
                effectiveGeneratedBy,
                fileFormat,
                safeDicomName);

        if (endpoint.isUseBuiltinMock()) {
            return handleMockUploadReportServletWithDicom(
                    siteId,
                    jobId,
                    studyId,
                    endpoint.getAuthUsername(),
                    endpoint.getAuthPassword(),
                    reportDataJsonPayload,
                    effectiveGeneratedBy,
                    pdfFileName,
                    pdfFilePath,
                    pdfBytes,
                    safeDicomName,
                    dicomFilePath,
                    dicomBytes,
                    dicomAeStatus,
                    allowSyntheticStudyForTest);
        }

        Site site = siteRepository.findById(siteId).orElseThrow();
        Path risDir = Path.of(
                hubProperties.storage().rootDir(),
                "ris-telerad-reports",
                site.getSiteCode(),
                studyIuid.trim().replaceAll("[^A-Za-z0-9.]", "_"));
        Files.createDirectories(risDir);

        Path storedDicomPath = dicomFilePath;
        if ((storedDicomPath == null || !Files.exists(storedDicomPath)) && hasDicom) {
            storedDicomPath = risDir.resolve(safeDicomName);
            Files.write(storedDicomPath, dicomBytes);
        }

        Path storedPdfPath = pdfFilePath;
        String safePdfName = (pdfFileName != null && !pdfFileName.isBlank())
                ? pdfFileName
                : (hasPdf ? ("report-" + studyIuid.trim().replaceAll("[^A-Za-z0-9.]", "_") + ".pdf") : safeDicomName);
        if ((storedPdfPath == null || !Files.exists(storedPdfPath)) && hasPdf) {
            storedPdfPath = risDir.resolve(safePdfName);
            Files.write(storedPdfPath, pdfBytes);
        }

        String jsonPartName = endpoint.getJsonPartName() != null && !endpoint.getJsonPartName().isBlank()
                ? endpoint.getJsonPartName()
                : "reportData";
        String pdfPartName = endpoint.getFilePartName() != null && !endpoint.getFilePartName().isBlank()
                ? endpoint.getFilePartName()
                : "pdfFile";
        String dicomPartName = endpoint.getDicomPartName() != null && !endpoint.getDicomPartName().isBlank()
                ? endpoint.getDicomPartName()
                : "dicomFile";
        String boundary = "----KauveryDicomHubRisBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] multipartBody = buildMultipartFormDataBodyWithDicom(
                boundary,
                jsonPartName,
                reportDataJsonPayload,
                pdfPartName,
                hasPdf ? safePdfName : null,
                pdfBytes,
                dicomPartName,
                hasDicom ? safeDicomName : null,
                dicomBytes);

        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                (endpoint.getAuthUsername() + ":" + endpoint.getAuthPassword()).getBytes(StandardCharsets.UTF_8));
        String maskedPass = endpoint.getAuthPassword() != null
                ? ("*".repeat(Math.min(8, endpoint.getAuthPassword().length()))
                        + " (len=" + endpoint.getAuthPassword().length() + ")")
                : "<empty>";
        long startNanos = System.nanoTime();
        OffsetDateTime requestTimestamp = OffsetDateTime.now();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.getTargetUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("username", endpoint.getAuthUsername())
                    .header("password", endpoint.getAuthPassword())
                    .header("generatedBy", effectiveGeneratedBy)
                    .header("X-Generated-By", effectiveGeneratedBy)
                    .header("X-RIS-File-Format", fileFormat)
                    .header("Authorization", basicAuth)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
            int statusCode = response.statusCode();
            String respBody = response.body() != null && !response.body().isBlank()
                    ? response.body().trim()
                    : (statusCode == 200
                            ? "{\"status\":\"success\",\"message\":\"Upload reports and DICOM file successfully\"}"
                            : "{\"status\":\"failure\",\"message\":\"Failed to upload the reports\",\"reason\":\"HTTP " + statusCode + " from RIS server\"}");

            String extractedStatus = extractJsonField(respBody, "status");
            String extractedMessage = extractJsonField(respBody, "message");
            String extractedReason = extractJsonField(respBody, "reason");
            boolean isSuccess = (statusCode >= 200 && statusCode < 300)
                    && !"failure".equalsIgnoreCase(extractedStatus)
                    && !"error".equalsIgnoreCase(extractedStatus);

            String liveHttpTransactionLog = String.format(
                    "[%s] LIVE RIS UploadReportServlet DICOM + Report HTTP Transaction [%s]:\n"
                            + "  >>> OUTBOUND HTTP REQUEST TO RIS SERVER:\n"
                            + "      POST %s\n"
                            + "      Headers:\n"
                            + "        Content-Type : multipart/form-data; boundary=%s\n"
                            + "        username     : %s\n"
                            + "        password     : %s\n"
                            + "        generatedBy = \"%s\" | fileFormat = \"%s\"\n"
                            + "      Multipart Part 1 (name=\"%s\", Content-Type: application/json; charset=UTF-8):\n"
                            + "        %s\n"
                            + "      Multipart DICOM Part (name=\"%s\", filename=\"%s\", Content-Type: application/dicom):\n"
                            + "        <%d bytes DICOM .dcm payload, path=%s>\n"
                            + "      %s"
                            + "  <<< INBOUND HTTP RESPONSE FROM RIS SERVER (%d ms):\n"
                            + "      HTTP Status  : HTTP %d (%s)\n"
                            + "      Response Body: %s%s",
                    requestTimestamp,
                    fileFormat,
                    endpoint.getTargetUrl(),
                    boundary,
                    endpoint.getAuthUsername(),
                    maskedPass,
                    effectiveGeneratedBy,
                    fileFormat,
                    jsonPartName,
                    reportDataJsonPayload.replace("\n", " "),
                    dicomPartName,
                    safeDicomName,
                    hasDicom ? dicomBytes.length : 0,
                    storedDicomPath != null ? storedDicomPath.toAbsolutePath() : "N/A",
                    hasPdf
                            ? String.format("Multipart PDF Part (name=\"%s\", filename=\"%s\", Content-Type: application/pdf): <%d bytes>\n      ",
                                    pdfPartName, safePdfName, pdfBytes.length)
                            : "",
                    latencyMs,
                    statusCode,
                    isSuccess ? "SUCCESS" : "RIS_SERVER_ERROR",
                    respBody.replace("\n", " "),
                    dicomAeStatus != null && !dicomAeStatus.isBlank() ? "\n      DICOM AE C-STORE: " + dicomAeStatus : "");

            return recordUploadedReportWithDicomTransaction(
                    siteId,
                    jobId,
                    studyId,
                    studyIuid,
                    effectiveReportType,
                    effectiveDraftedTime,
                    commonUser,
                    effectiveReviewedTime,
                    commonUser,
                    effectiveGeneratedBy,
                    reportDataJsonPayload,
                    safePdfName,
                    storedPdfPath != null ? storedPdfPath : storedDicomPath,
                    pdfBytes,
                    fileFormat,
                    safeDicomName,
                    storedDicomPath,
                    dicomBytes,
                    dicomAeStatus,
                    endpoint.getTargetUrl(),
                    statusCode,
                    respBody,
                    liveHttpTransactionLog);
        } catch (Exception ex) {
            long latencyMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
            String errReason = "Live RIS connection error (" + endpoint.getTargetUrl() + "): "
                    + ex.getClass().getSimpleName() + (ex.getMessage() != null ? " - " + ex.getMessage() : "");
            String errJson = "{\"status\":\"failure\",\"message\":\"Failed to upload the reports\",\"reason\":\""
                    + escapeJson(errReason) + "\"}";
            String liveErrorLog = String.format(
                    "[%s] LIVE RIS UploadReportServlet DICOM HTTP Connection Failure (%d ms): POST %s | format=%s | dicom=%s (%d bytes) | error=%s",
                    requestTimestamp, latencyMs, endpoint.getTargetUrl(), fileFormat, safeDicomName,
                    hasDicom ? dicomBytes.length : 0, errReason);
            return recordUploadedReportWithDicomTransaction(
                    siteId,
                    jobId,
                    studyId,
                    studyIuid,
                    effectiveReportType,
                    effectiveDraftedTime,
                    commonUser,
                    effectiveReviewedTime,
                    commonUser,
                    effectiveGeneratedBy,
                    reportDataJsonPayload,
                    safePdfName,
                    storedPdfPath != null ? storedPdfPath : storedDicomPath,
                    pdfBytes,
                    fileFormat,
                    safeDicomName,
                    storedDicomPath,
                    dicomBytes,
                    dicomAeStatus,
                    endpoint.getTargetUrl(),
                    502,
                    errJson,
                    liveErrorLog);
        }
    }

    /**
     * Strict implementation of the UploadReportServlet validation & storage contract
     * from Upload_Report_API_PACS_Integration_Updated.pdf (Sections 3, 4, 5, 6, 7).
     */
    @Transactional
    public UploadReportServletResult handleMockUploadReportServlet(
            UUID siteId,
            UUID jobId,
            UUID studyId,
            String headerUsername,
            String headerPassword,
            String jsonPartContent,
            String pdfFileName,
            Path existingPdfPath,
            byte[] pdfBytes,
            boolean allowSyntheticStudyForTest) throws IOException {
        return handleMockUploadReportServlet(
                siteId,
                jobId,
                studyId,
                headerUsername,
                headerPassword,
                jsonPartContent,
                null,
                pdfFileName,
                existingPdfPath,
                pdfBytes,
                allowSyntheticStudyForTest);
    }

    @Transactional
    public UploadReportServletResult handleMockUploadReportServlet(
            UUID siteId,
            UUID jobId,
            UUID studyId,
            String headerUsername,
            String headerPassword,
            String jsonPartContent,
            String generatedByParam,
            String pdfFileName,
            Path existingPdfPath,
            byte[] pdfBytes,
            boolean allowSyntheticStudyForTest) throws IOException {

        RisReportEndpoint endpoint = getOrCreateSiteEndpoint(siteId);

        // 1. Section 3.1 & 5: Authentication check
        if (headerUsername == null || headerUsername.isBlank()
                || headerPassword == null || headerPassword.isBlank()) {
            return failureResponse(401, "Missing or Invalid username/password");
        }
        if (!endpoint.getAuthUsername().equals(headerUsername.trim())
                || !endpoint.getAuthPassword().equals(headerPassword)) {
            return failureResponse(401, "Missing or Invalid username/password");
        }

        // 2. Section 3.2 & 4: Parse JSON part fields (reportData)
        if (jsonPartContent == null || jsonPartContent.isBlank()) {
            return failureResponse(400, "Missing mandatory field");
        }
        String reportType = extractJsonField(jsonPartContent, "reportType");
        String studyIuid = extractJsonField(jsonPartContent, "studyIuid");
        String draftedTime = extractJsonField(jsonPartContent, "draftedTime");
        String draftedBy = extractJsonField(jsonPartContent, "draftedBy");
        String reviewedTime = extractJsonField(jsonPartContent, "reviewedTime");
        String reviewedBy = extractJsonField(jsonPartContent, "reviewedBy");
        String generatedByFromJson = extractJsonField(jsonPartContent, "generatedBy");
        String effectiveGeneratedBy = !isBlank(generatedByFromJson)
                ? generatedByFromJson.trim()
                : (!isBlank(generatedByParam)
                        ? generatedByParam.trim()
                        : resolveSiteConfiguredAgentName(siteId));

        // 3. Section 4 & 5: Mandatory fields check (reportType, studyIuid, reviewedTime, reviewedBy, pdfFile)
        if (isBlank(reportType) || isBlank(studyIuid) || isBlank(reviewedTime) || isBlank(reviewedBy)
                || pdfBytes == null) {
            return failureResponse(400, "Missing mandatory field");
        }

        // 4. Section 5: Validate reportType ("Study Report" or "Addendum Report")
        if (!ALLOWED_REPORT_TYPES.contains(reportType.trim())) {
            return failureResponse(400, "Invalid reportType");
        }

        // 5. Section 4.2 & 5: Validate DateTime format (yyyy-MM-dd HH:mm:ss local time) & reviewedTime >= draftedTime
        LocalDateTime parsedReviewed;
        try {
            parsedReviewed = LocalDateTime.parse(reviewedTime.trim(), TELERAD_LOCAL_DATETIME_FMT);
        } catch (DateTimeParseException e) {
            return failureResponse(400, "Invalid date-time format");
        }
        if (!isBlank(draftedTime)) {
            LocalDateTime parsedDrafted;
            try {
                parsedDrafted = LocalDateTime.parse(draftedTime.trim(), TELERAD_LOCAL_DATETIME_FMT);
            } catch (DateTimeParseException e) {
                return failureResponse(400, "Invalid date-time format");
            }
            if (parsedReviewed.isBefore(parsedDrafted)) {
                return failureResponse(400, "ReviewedTime earlier than draftedTime");
            }
        } else {
            draftedTime = reviewedTime.trim();
        }
        if (isBlank(draftedBy)) {
            draftedBy = reviewedBy.trim();
        }

        // 6. Section 3.2 & 5: Validate PDF or DICOM file (non-empty, valid %PDF- header or DICOM .dcm dataset, <= 15 MB)
        if (pdfBytes.length == 0) {
            return failureResponse(400, "Empty or non-PDF file");
        }
        if (!startsWithPdfHeader(pdfBytes)) {
            if (isValidDicomBytes(pdfBytes)) {
                return handleMockUploadReportServletWithDicom(
                        siteId,
                        jobId,
                        studyId,
                        headerUsername,
                        headerPassword,
                        jsonPartContent,
                        generatedByParam,
                        null,
                        null,
                        null,
                        pdfFileName != null && !pdfFileName.isBlank() ? pdfFileName : "ai-result.dcm",
                        existingPdfPath,
                        pdfBytes,
                        null,
                        allowSyntheticStudyForTest);
            }
            return failureResponse(400, "Empty or non-PDF file");
        }
        if (pdfBytes.length > MAX_PDF_SIZE_BYTES) {
            return failureResponse(400, "File size exceeds 15 MB");
        }

        // 7. Section 5 & 6: Validate studyIuid matches an existing study in PACS / DICOM Hub
        Optional<StudyRecord> studyOpt = studyRecordRepository
                .findBySiteIdAndStudyInstanceUid(siteId, studyIuid.trim());
        if (studyOpt.isEmpty() && !allowSyntheticStudyForTest) {
            return failureResponse(404, "No study found for the given studyIuid");
        }
        UUID resolvedStudyId = studyId != null
                ? studyId
                : studyOpt.map(StudyRecord::getId).orElse(null);

        // Ensure PDF is persisted on disk under storage/ris-telerad-reports/{siteCode}/ if not already saved
        Path storedPdfPath = existingPdfPath;
        String safeFileName = (pdfFileName != null && !pdfFileName.isBlank())
                ? pdfFileName
                : ("report-" + studyIuid.trim().replaceAll("[^A-Za-z0-9.]", "_") + ".pdf");
        if (storedPdfPath == null || !Files.exists(storedPdfPath)) {
            Site site = siteRepository.findById(siteId).orElseThrow();
            Path risDir = Path.of(
                    hubProperties.storage().rootDir(),
                    "ris-telerad-reports",
                    site.getSiteCode(),
                    studyIuid.trim().replaceAll("[^A-Za-z0-9.]", "_"));
            Files.createDirectories(risDir);
            storedPdfPath = risDir.resolve(safeFileName);
            Files.write(storedPdfPath, pdfBytes);
        }

        String normalizedReportDataJson = buildUploadReportJsonPayload(
                reportType.trim(),
                studyIuid.trim(),
                draftedTime.trim(),
                draftedBy.trim(),
                reviewedTime.trim(),
                reviewedBy.trim(),
                effectiveGeneratedBy);

        String successBody = "{\n  \"status\": \"success\",\n  \"message\": \"Upload reports successfully\"\n}";
        return recordUploadedReportTransaction(
                siteId,
                jobId,
                resolvedStudyId,
                studyIuid.trim(),
                reportType.trim(),
                draftedTime.trim(),
                draftedBy.trim(),
                reviewedTime.trim(),
                reviewedBy.trim(),
                effectiveGeneratedBy,
                normalizedReportDataJson,
                safeFileName,
                storedPdfPath,
                pdfBytes,
                endpoint.getTargetUrl() + " [MOCK_SERVLET]",
                200,
                successBody);
    }

    private UploadReportServletResult recordUploadedReportTransaction(
            UUID siteId,
            UUID jobId,
            UUID studyId,
            String studyIuid,
            String reportType,
            String draftedTime,
            String draftedBy,
            String reviewedTime,
            String reviewedBy,
            String generatedBy,
            String reportDataJson,
            String pdfFileName,
            Path pdfFilePath,
            byte[] pdfBytes,
            String targetEndpointUrl,
            int httpStatus,
            String responseJson) {
        return recordUploadedReportTransaction(
                siteId,
                jobId,
                studyId,
                studyIuid,
                reportType,
                draftedTime,
                draftedBy,
                reviewedTime,
                reviewedBy,
                generatedBy,
                reportDataJson,
                pdfFileName,
                pdfFilePath,
                pdfBytes,
                targetEndpointUrl,
                httpStatus,
                responseJson,
                null);
    }

    private UploadReportServletResult recordUploadedReportTransaction(
            UUID siteId,
            UUID jobId,
            UUID studyId,
            String studyIuid,
            String reportType,
            String draftedTime,
            String draftedBy,
            String reviewedTime,
            String reviewedBy,
            String generatedBy,
            String reportDataJson,
            String pdfFileName,
            Path pdfFilePath,
            byte[] pdfBytes,
            String targetEndpointUrl,
            int httpStatus,
            String responseJson,
            String customProcessingLog) {

        boolean replacedPrevious = false;
        RisUploadedReport record;

        // Section 7: If reportType is "Preliminary Report" or "Study Report", replace existing primary report for studyIuid.
        // If reportType is "Addendum Report", add it as a new report.
        if (HARDCODED_RIS_REPORT_TYPE.equalsIgnoreCase(reportType) || "Study Report".equalsIgnoreCase(reportType)) {
            Optional<RisUploadedReport> existingStudyReport = uploadedReportRepository
                    .findBySiteIdAndStudyIuidOrderByUploadedAtDesc(siteId, studyIuid)
                    .stream()
                    .filter(r -> !"Addendum Report".equalsIgnoreCase(r.getReportType()))
                    .findFirst();
            if (existingStudyReport.isPresent()) {
                record = existingStudyReport.get();
                replacedPrevious = true;
            } else {
                record = new RisUploadedReport();
                record.setId(UUID.randomUUID());
            }
        } else {
            record = new RisUploadedReport();
            record.setId(UUID.randomUUID());
        }

        String effectiveGeneratedBy = (generatedBy != null && !generatedBy.isBlank())
                ? generatedBy.trim()
                : resolveSiteConfiguredAgentName(siteId);
        String effectiveReportData = (reportDataJson != null && !reportDataJson.isBlank())
                ? reportDataJson
                : buildUploadReportJsonPayload(
                        reportType, studyIuid, draftedTime, draftedBy, reviewedTime, reviewedBy, effectiveGeneratedBy);

        String extractedStatus = extractJsonField(responseJson, "status");
        String extractedMessage = extractJsonField(responseJson, "message");
        String extractedReason = extractJsonField(responseJson, "reason");
        boolean isSuccess = (httpStatus >= 200 && httpStatus < 300)
                && !"failure".equalsIgnoreCase(extractedStatus)
                && !"error".equalsIgnoreCase(extractedStatus);
        String effectiveStatus = isSuccess ? "success" : "failure";
        String effectiveMessage = (extractedMessage != null && !extractedMessage.isBlank())
                ? extractedMessage
                : (isSuccess ? "Upload reports successfully" : "Failed to upload the reports");
        String effectiveReason = (extractedReason != null && !extractedReason.isBlank())
                ? extractedReason
                : (!isSuccess ? "HTTP " + httpStatus + " error from RIS server" : null);

        OffsetDateTime now = OffsetDateTime.now();
        String processingLogText = (customProcessingLog != null && !customProcessingLog.isBlank())
                ? customProcessingLog
                : String.format(
                        "[%s] RIS UploadReportServlet Dispatch:\n"
                                + "  • Target Endpoint : %s\n"
                                + "  • StudyInstanceUID: %s (studyId=%s, jobId=%s)\n"
                                + "  • AI Agent Name   : generatedBy = \"%s\"\n"
                                + "  • Report Type     : %s (%s)\n"
                                + "  • Sign-Off        : draftedBy=%s @ %s | reviewedBy=%s @ %s\n"
                                + "  • PDF Attachment  : %s (%d bytes, path=%s)\n"
                                + "  • reportData JSON : %s\n"
                                + "  • RIS HTTP Status : HTTP %d -> %s%s",
                        now,
                        targetEndpointUrl,
                        studyIuid,
                        studyId != null ? studyId : "N/A",
                        jobId != null ? jobId : "N/A",
                        effectiveGeneratedBy,
                        reportType,
                        replacedPrevious ? "Replaced Existing Study Report" : "Created New Report Entry",
                        draftedBy,
                        draftedTime,
                        reviewedBy,
                        reviewedTime,
                        pdfFileName,
                        pdfBytes != null ? pdfBytes.length : 0L,
                        pdfFilePath != null ? pdfFilePath.toAbsolutePath() : "N/A",
                        effectiveReportData.replace("\n", " "),
                        httpStatus,
                        responseJson.replace("\n", " "),
                        effectiveReason != null ? "\n  • RIS Error Reason: " + effectiveReason : "");

        record.setSiteId(siteId);
        record.setJobId(jobId);
        record.setStudyId(studyId);
        record.setStudyIuid(studyIuid);
        record.setReportType(reportType);
        record.setDraftedTime(draftedTime);
        record.setDraftedBy(draftedBy);
        record.setReviewedTime(reviewedTime);
        record.setReviewedBy(reviewedBy);
        record.setGeneratedBy(effectiveGeneratedBy);
        record.setReportData(effectiveReportData);
        record.setProcessingLog(processingLogText);
        record.setPdfFileName(pdfFileName);
        record.setPdfFilePath(pdfFilePath != null ? pdfFilePath.toAbsolutePath().toString() : "");
        record.setPdfFileSizeBytes(pdfBytes != null ? pdfBytes.length : 0L);
        record.setTargetEndpointUrl(targetEndpointUrl);
        record.setHttpStatus(httpStatus);
        record.setResponseJson(responseJson);
        record.setReplacedPrevious(replacedPrevious);
        record.setUploadedAt(now);
        record = uploadedReportRepository.save(record);

        auditService.record(
                siteId,
                effectiveGeneratedBy,
                "TELERAD_UPLOAD_SERVLET",
                "RESULT_RETURN",
                replacedPrevious ? "RIS_PDF_REPORT_REPLACED" : "RIS_PDF_REPORT_UPLOADED",
                "RisUploadedReport",
                record.getId().toString(),
                isSuccess ? "SUCCESS" : "FAILURE",
                "{\"studyIuid\":\"" + escapeJson(studyIuid)
                        + "\",\"studyId\":\"" + (studyId != null ? studyId : "")
                        + "\",\"jobId\":\"" + (jobId != null ? jobId : "")
                        + "\",\"reportType\":\"" + escapeJson(reportType)
                        + "\",\"generatedBy\":\"" + escapeJson(effectiveGeneratedBy)
                        + "\",\"pdfFileName\":\"" + escapeJson(pdfFileName)
                        + "\",\"replacedPrevious\":" + replacedPrevious
                        + ",\"pdfBytes\":" + record.getPdfFileSizeBytes()
                        + ",\"httpStatus\":" + httpStatus
                        + (effectiveReason != null ? ",\"reason\":\"" + escapeJson(effectiveReason) + "\"" : "")
                        + "}");

        return new UploadReportServletResult(
                httpStatus,
                effectiveStatus,
                effectiveMessage,
                effectiveReason,
                responseJson,
                record.getId(),
                replacedPrevious,
                effectiveReportData,
                effectiveGeneratedBy,
                processingLogText);
    }

    @Transactional
    public UploadReportServletResult handleMockUploadReportServletWithDicom(
            UUID siteId,
            UUID jobId,
            UUID studyId,
            String headerUsername,
            String headerPassword,
            String jsonPartContent,
            String generatedByParam,
            String pdfFileName,
            Path existingPdfPath,
            byte[] pdfBytes,
            String dicomFileName,
            Path existingDicomPath,
            byte[] dicomBytes,
            String dicomAeStatus,
            boolean allowSyntheticStudyForTest) throws IOException {

        RisReportEndpoint endpoint = getOrCreateSiteEndpoint(siteId);

        if (headerUsername == null || headerUsername.isBlank()
                || headerPassword == null || headerPassword.isBlank()) {
            return failureResponse(401, "Missing or Invalid username/password");
        }
        if (!endpoint.getAuthUsername().equals(headerUsername.trim())
                || !endpoint.getAuthPassword().equals(headerPassword)) {
            return failureResponse(401, "Missing or Invalid username/password");
        }

        if (jsonPartContent == null || jsonPartContent.isBlank()) {
            return failureResponse(400, "Missing mandatory field");
        }
        String reportType = extractJsonField(jsonPartContent, "reportType");
        String studyIuid = extractJsonField(jsonPartContent, "studyIuid");
        String draftedTime = extractJsonField(jsonPartContent, "draftedTime");
        String draftedBy = extractJsonField(jsonPartContent, "draftedBy");
        String reviewedTime = extractJsonField(jsonPartContent, "reviewedTime");
        String reviewedBy = extractJsonField(jsonPartContent, "reviewedBy");
        String generatedByFromJson = extractJsonField(jsonPartContent, "generatedBy");
        String effectiveGeneratedBy = !isBlank(generatedByFromJson)
                ? generatedByFromJson.trim()
                : (!isBlank(generatedByParam)
                        ? generatedByParam.trim()
                        : resolveSiteConfiguredAgentName(siteId));

        boolean hasPdf = pdfBytes != null && pdfBytes.length > 0;
        boolean hasDicom = dicomBytes != null && dicomBytes.length > 0;
        if (isBlank(reportType) || isBlank(studyIuid) || isBlank(reviewedTime) || isBlank(reviewedBy)
                || (!hasPdf && !hasDicom)) {
            return failureResponse(400, "Missing mandatory field");
        }
        if (!ALLOWED_REPORT_TYPES.contains(reportType.trim())) {
            return failureResponse(400, "Invalid reportType");
        }

        LocalDateTime parsedReviewed;
        try {
            parsedReviewed = LocalDateTime.parse(reviewedTime.trim(), TELERAD_LOCAL_DATETIME_FMT);
        } catch (DateTimeParseException e) {
            return failureResponse(400, "Invalid date-time format");
        }
        if (!isBlank(draftedTime)) {
            LocalDateTime parsedDrafted;
            try {
                parsedDrafted = LocalDateTime.parse(draftedTime.trim(), TELERAD_LOCAL_DATETIME_FMT);
            } catch (DateTimeParseException e) {
                return failureResponse(400, "Invalid date-time format");
            }
            if (parsedReviewed.isBefore(parsedDrafted)) {
                return failureResponse(400, "ReviewedTime earlier than draftedTime");
            }
        } else {
            draftedTime = reviewedTime.trim();
        }
        if (isBlank(draftedBy)) {
            draftedBy = reviewedBy.trim();
        }

        if (hasPdf) {
            if (!startsWithPdfHeader(pdfBytes)) {
                return failureResponse(400, "Empty or non-PDF file");
            }
            if (pdfBytes.length > MAX_PDF_SIZE_BYTES) {
                return failureResponse(400, "File size exceeds 15 MB");
            }
        }
        if (hasDicom) {
            if (!isValidDicomBytes(dicomBytes)) {
                return failureResponse(400, "Empty or invalid DICOM (.dcm) file");
            }
            if (dicomBytes.length > MAX_PDF_SIZE_BYTES) {
                return failureResponse(400, "File size exceeds 15 MB");
            }
        }

        Optional<StudyRecord> studyOpt = studyRecordRepository
                .findBySiteIdAndStudyInstanceUid(siteId, studyIuid.trim());
        if (studyOpt.isEmpty() && !allowSyntheticStudyForTest) {
            return failureResponse(404, "No study found for the given studyIuid");
        }
        UUID resolvedStudyId = studyId != null
                ? studyId
                : studyOpt.map(StudyRecord::getId).orElse(null);

        Site site = siteRepository.findById(siteId).orElseThrow();
        Path risDir = Path.of(
                hubProperties.storage().rootDir(),
                "ris-telerad-reports",
                site.getSiteCode(),
                studyIuid.trim().replaceAll("[^A-Za-z0-9.]", "_"));
        Files.createDirectories(risDir);

        String safeDicomName = (dicomFileName != null && !dicomFileName.isBlank())
                ? dicomFileName
                : ("ai-result-" + studyIuid.trim().replaceAll("[^A-Za-z0-9.]", "_") + ".dcm");
        Path storedDicomPath = existingDicomPath;
        if (hasDicom && (storedDicomPath == null || !Files.exists(storedDicomPath))) {
            storedDicomPath = risDir.resolve(safeDicomName);
            Files.write(storedDicomPath, dicomBytes);
        }

        String safePdfName = (pdfFileName != null && !pdfFileName.isBlank())
                ? pdfFileName
                : (hasPdf ? ("report-" + studyIuid.trim().replaceAll("[^A-Za-z0-9.]", "_") + ".pdf") : safeDicomName);
        Path storedPdfPath = existingPdfPath;
        if (hasPdf && (storedPdfPath == null || !Files.exists(storedPdfPath))) {
            storedPdfPath = risDir.resolve(safePdfName);
            Files.write(storedPdfPath, pdfBytes);
        }

        String fileFormat = (hasPdf && hasDicom) ? "PDF_AND_DICOM" : (hasDicom ? "DICOM" : "PDF");
        String normalizedReportDataJson = buildUploadReportWithDicomJsonPayload(
                reportType.trim(),
                studyIuid.trim(),
                draftedTime.trim(),
                draftedBy.trim(),
                reviewedTime.trim(),
                reviewedBy.trim(),
                effectiveGeneratedBy,
                fileFormat,
                hasDicom ? safeDicomName : null);

        String successBody = "{\n  \"status\": \"success\",\n  \"message\": \"Upload reports and DICOM file successfully\"\n}";
        return recordUploadedReportWithDicomTransaction(
                siteId,
                jobId,
                resolvedStudyId,
                studyIuid.trim(),
                reportType.trim(),
                draftedTime.trim(),
                draftedBy.trim(),
                reviewedTime.trim(),
                reviewedBy.trim(),
                effectiveGeneratedBy,
                normalizedReportDataJson,
                safePdfName,
                storedPdfPath != null ? storedPdfPath : storedDicomPath,
                pdfBytes,
                fileFormat,
                hasDicom ? safeDicomName : null,
                storedDicomPath,
                dicomBytes,
                dicomAeStatus,
                endpoint.getTargetUrl() + " [MOCK_SERVLET]",
                200,
                successBody,
                null);
    }

    private UploadReportServletResult recordUploadedReportWithDicomTransaction(
            UUID siteId,
            UUID jobId,
            UUID studyId,
            String studyIuid,
            String reportType,
            String draftedTime,
            String draftedBy,
            String reviewedTime,
            String reviewedBy,
            String generatedBy,
            String reportDataJson,
            String pdfFileName,
            Path pdfFilePath,
            byte[] pdfBytes,
            String fileFormat,
            String dicomFileName,
            Path dicomFilePath,
            byte[] dicomBytes,
            String dicomAeStatus,
            String targetEndpointUrl,
            int httpStatus,
            String responseJson,
            String customProcessingLog) {

        boolean replacedPrevious = false;
        RisUploadedReport record;
        if (HARDCODED_RIS_REPORT_TYPE.equalsIgnoreCase(reportType) || "Study Report".equalsIgnoreCase(reportType)) {
            Optional<RisUploadedReport> existingStudyReport = uploadedReportRepository
                    .findBySiteIdAndStudyIuidOrderByUploadedAtDesc(siteId, studyIuid)
                    .stream()
                    .filter(r -> !"Addendum Report".equalsIgnoreCase(r.getReportType()))
                    .findFirst();
            if (existingStudyReport.isPresent()) {
                record = existingStudyReport.get();
                replacedPrevious = true;
            } else {
                record = new RisUploadedReport();
                record.setId(UUID.randomUUID());
            }
        } else {
            record = new RisUploadedReport();
            record.setId(UUID.randomUUID());
        }

        String effectiveGeneratedBy = (generatedBy != null && !generatedBy.isBlank())
                ? generatedBy.trim()
                : resolveSiteConfiguredAgentName(siteId);
        String effectiveFileFormat = (fileFormat != null && !fileFormat.isBlank()) ? fileFormat : "DICOM";
        String effectiveReportData = (reportDataJson != null && !reportDataJson.isBlank())
                ? reportDataJson
                : buildUploadReportWithDicomJsonPayload(
                        reportType, studyIuid, draftedTime, draftedBy, reviewedTime, reviewedBy,
                        effectiveGeneratedBy, effectiveFileFormat, dicomFileName);

        String extractedStatus = extractJsonField(responseJson, "status");
        String extractedMessage = extractJsonField(responseJson, "message");
        String extractedReason = extractJsonField(responseJson, "reason");
        boolean isSuccess = (httpStatus >= 200 && httpStatus < 300)
                && !"failure".equalsIgnoreCase(extractedStatus)
                && !"error".equalsIgnoreCase(extractedStatus);
        String effectiveStatus = isSuccess ? "success" : "failure";
        String effectiveMessage = (extractedMessage != null && !extractedMessage.isBlank())
                ? extractedMessage
                : (isSuccess ? "Upload reports and DICOM file successfully" : "Failed to upload the reports");
        String effectiveReason = (extractedReason != null && !extractedReason.isBlank())
                ? extractedReason
                : (!isSuccess ? "HTTP " + httpStatus + " error from RIS server" : null);

        OffsetDateTime now = OffsetDateTime.now();
        String processingLogText = (customProcessingLog != null && !customProcessingLog.isBlank())
                ? customProcessingLog
                : String.format(
                        "[%s] RIS UploadReportServlet DICOM + Report Dispatch [%s]:\n"
                                + "  • Target Endpoint : %s\n"
                                + "  • StudyInstanceUID: %s (studyId=%s, jobId=%s)\n"
                                + "  • AI Agent Name   : generatedBy = \"%s\"\n"
                                + "  • Report Type     : %s (%s)\n"
                                + "  • Sign-Off        : draftedBy=%s @ %s | reviewedBy=%s @ %s\n"
                                + "  • DICOM File (.dcm): %s (%d bytes, path=%s)\n"
                                + "  • DICOM AE Return : %s\n"
                                + "  • PDF Attachment  : %s (%d bytes)\n"
                                + "  • reportData JSON : %s\n"
                                + "  • RIS HTTP Status : HTTP %d -> %s%s",
                        now,
                        effectiveFileFormat,
                        targetEndpointUrl,
                        studyIuid,
                        studyId != null ? studyId : "N/A",
                        jobId != null ? jobId : "N/A",
                        effectiveGeneratedBy,
                        reportType,
                        replacedPrevious ? "Replaced Existing Study Report" : "Created New Report Entry",
                        draftedBy,
                        draftedTime,
                        reviewedBy,
                        reviewedTime,
                        dicomFileName != null ? dicomFileName : "N/A",
                        dicomBytes != null ? dicomBytes.length : 0L,
                        dicomFilePath != null ? dicomFilePath.toAbsolutePath() : "N/A",
                        dicomAeStatus != null ? dicomAeStatus : "N/A",
                        pdfFileName != null ? pdfFileName : "N/A",
                        pdfBytes != null ? pdfBytes.length : 0L,
                        effectiveReportData.replace("\n", " "),
                        httpStatus,
                        responseJson.replace("\n", " "),
                        effectiveReason != null ? "\n  • RIS Error Reason: " + effectiveReason : "");

        record.setSiteId(siteId);
        record.setJobId(jobId);
        record.setStudyId(studyId);
        record.setStudyIuid(studyIuid);
        record.setReportType(reportType);
        record.setDraftedTime(draftedTime);
        record.setDraftedBy(draftedBy);
        record.setReviewedTime(reviewedTime);
        record.setReviewedBy(reviewedBy);
        record.setGeneratedBy(effectiveGeneratedBy);
        record.setReportData(effectiveReportData);
        record.setProcessingLog(processingLogText);
        record.setPdfFileName(pdfFileName != null && !pdfFileName.isBlank() ? pdfFileName : (dicomFileName != null ? dicomFileName : "report.dcm"));
        record.setPdfFilePath(pdfFilePath != null ? pdfFilePath.toAbsolutePath().toString() : (dicomFilePath != null ? dicomFilePath.toAbsolutePath().toString() : ""));
        record.setPdfFileSizeBytes(pdfBytes != null && pdfBytes.length > 0 ? pdfBytes.length : (dicomBytes != null ? dicomBytes.length : 0L));
        record.setFileFormat(effectiveFileFormat);
        record.setDicomFileName(dicomFileName);
        record.setDicomFilePath(dicomFilePath != null ? dicomFilePath.toAbsolutePath().toString() : null);
        record.setDicomFileSizeBytes(dicomBytes != null ? dicomBytes.length : 0L);
        record.setDicomAeStatus(dicomAeStatus);
        record.setTargetEndpointUrl(targetEndpointUrl);
        record.setHttpStatus(httpStatus);
        record.setResponseJson(responseJson);
        record.setReplacedPrevious(replacedPrevious);
        record.setUploadedAt(now);
        record = uploadedReportRepository.save(record);

        auditService.record(
                siteId,
                effectiveGeneratedBy,
                "TELERAD_UPLOAD_SERVLET",
                "RESULT_RETURN",
                "RIS_DICOM_REPORT_UPLOADED",
                "RisUploadedReport",
                record.getId().toString(),
                isSuccess ? "SUCCESS" : "FAILURE",
                "{\"studyIuid\":\"" + escapeJson(studyIuid)
                        + "\",\"studyId\":\"" + (studyId != null ? studyId : "")
                        + "\",\"jobId\":\"" + (jobId != null ? jobId : "")
                        + "\",\"fileFormat\":\"" + escapeJson(effectiveFileFormat)
                        + "\",\"dicomFileName\":\"" + escapeJson(dicomFileName != null ? dicomFileName : "")
                        + "\",\"dicomBytes\":" + record.getDicomFileSizeBytes()
                        + ",\"httpStatus\":" + httpStatus + "}");

        return new UploadReportServletResult(
                httpStatus,
                effectiveStatus,
                effectiveMessage,
                effectiveReason,
                responseJson,
                record.getId(),
                replacedPrevious,
                effectiveReportData,
                effectiveGeneratedBy,
                processingLogText);
    }

    private UploadReportServletResult failureResponse(int httpStatus, String reason) {
        String json = "{\n"
                + "  \"status\": \"failure\",\n"
                + "  \"message\": \"Failed to upload the reports\",\n"
                + "  \"reason\": \"" + escapeJson(reason) + "\"\n"
                + "}";
        return new UploadReportServletResult(
                httpStatus,
                "failure",
                "Failed to upload the reports",
                reason,
                json,
                null,
                false);
    }

    private byte[] buildMultipartFormDataBody(
            String boundary,
            String jsonPartName,
            String jsonPayload,
            String filePartName,
            String pdfFileName,
            byte[] pdfBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String crlf = "\r\n";

        // Part 1: reportData JSON part (Content-Type: application/json)
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + jsonPartName + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: application/json; charset=UTF-8" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));

        // Part 2: PDF file part (Content-Type: application/pdf)
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + filePartName + "\"; filename=\""
                + pdfFileName + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: application/pdf" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(pdfBytes);
        out.write(crlf.getBytes(StandardCharsets.UTF_8));

        // Closing boundary
        out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private byte[] buildMultipartFormDataBodyWithDicom(
            String boundary,
            String jsonPartName,
            String jsonPayload,
            String pdfPartName,
            String pdfFileName,
            byte[] pdfBytes,
            String dicomPartName,
            String dicomFileName,
            byte[] dicomBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String crlf = "\r\n";

        // Part 1: reportData JSON part (Content-Type: application/json)
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + jsonPartName + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: application/json; charset=UTF-8" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));

        // Part 2: Optional PDF file part (Content-Type: application/pdf)
        if (pdfBytes != null && pdfBytes.length > 0 && pdfFileName != null) {
            out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"" + pdfPartName + "\"; filename=\""
                    + pdfFileName + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: application/pdf" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
            out.write(pdfBytes);
            out.write(crlf.getBytes(StandardCharsets.UTF_8));
        }

        // Part 3: DICOM file part (Content-Type: application/dicom)
        if (dicomBytes != null && dicomBytes.length > 0 && dicomFileName != null) {
            out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"" + dicomPartName + "\"; filename=\""
                    + dicomFileName + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: application/dicom" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
            out.write(dicomBytes);
            out.write(crlf.getBytes(StandardCharsets.UTF_8));
        }

        // Closing boundary
        out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    public boolean isValidDicomBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 32) {
            return false;
        }
        // Standard DICOM Part 10 preamble (128 bytes) + "DICM"
        if (bytes.length >= 132
                && bytes[128] == 'D'
                && bytes[129] == 'I'
                && bytes[130] == 'C'
                && bytes[131] == 'M') {
            return true;
        }
        try (DicomInputStream dis = new DicomInputStream(new ByteArrayInputStream(bytes))) {
            Attributes ds = dis.readDataset();
            return ds != null && (ds.containsValue(Tag.SOPInstanceUID)
                    || ds.containsValue(Tag.StudyInstanceUID)
                    || ds.containsValue(Tag.Modality));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean startsWithPdfHeader(byte[] bytes) {
        if (bytes == null || bytes.length < 5) {
            return false;
        }
        return bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String extractJsonField(String json, String fieldName) {
        if (json == null || fieldName == null) {
            return null;
        }
        String key = "\"" + fieldName + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) {
            return null;
        }
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return null;
        }
        StringBuilder unescaped = new StringBuilder();
        boolean escaping = false;
        int pos = firstQuote + 1;
        boolean closed = false;
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (escaping) {
                switch (c) {
                    case '"', '\\', '/' -> unescaped.append(c);
                    case 'n' -> unescaped.append('\n');
                    case 'r' -> unescaped.append('\r');
                    case 't' -> unescaped.append('\t');
                    default -> unescaped.append(c);
                }
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (c == '"') {
                closed = true;
                break;
            } else {
                unescaped.append(c);
            }
            pos++;
        }
        return closed ? unescaped.toString() : null;
    }

    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    public Map<String, Object> toDiagnosticMap(UploadReportServletResult res) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("httpStatus", res.httpStatus());
        map.put("status", res.status());
        map.put("message", res.message());
        if (res.reason() != null) {
            map.put("reason", res.reason());
        }
        if (res.rawResponseJson() != null) {
            map.put("rawResponseJson", res.rawResponseJson());
        }
        if (res.processingLog() != null) {
            map.put("processingLog", res.processingLog());
        }
        map.put("uploadedReportId", res.uploadedReportId());
        map.put("replacedPrevious", res.replacedPrevious());
        if (res.reportData() != null) {
            map.put("reportData", res.reportData());
        }
        if (res.generatedBy() != null) {
            map.put("generatedBy", res.generatedBy());
        }
        return map;
    }
}
