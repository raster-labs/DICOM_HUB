package com.kauvery.dicomhub.api;

import com.kauvery.dicomhub.config.HubProperties;
import com.kauvery.dicomhub.report.RisTeleradUploadReportService;
import com.kauvery.dicomhub.report.RisTeleradUploadReportService.UploadReportServletResult;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * Exposes the exact Telerad / PACS UploadReportServlet endpoint specified in
 * Upload_Report_API_PACS_Integration_Updated.pdf:
 *   POST /telerad/UploadReportServlet
 *   POST /api/v1/mock-ris/telerad/UploadReportServlet
 *
 * Accepts multipart/form-data containing:
 *   - Headers: username, password (or HTTP Basic Authorization)
 *   - JSON part (reportData / json / data / any application/json part)
 *   - PDF file part (pdfFile, Content-Type: application/pdf, max 15 MB)
 */
@RestController
@CrossOrigin(origins = "*")
public class TeleradUploadReportServletController {

    private final HubProperties hubProperties;
    private final RisTeleradUploadReportService risUploadService;

    public TeleradUploadReportServletController(
            HubProperties hubProperties,
            RisTeleradUploadReportService risUploadService) {
        this.hubProperties = hubProperties;
        this.risUploadService = risUploadService;
    }

    @PostMapping(
            value = {"/telerad/UploadReportServlet", "/api/v1/mock-ris/telerad/UploadReportServlet"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> handleUploadReportServlet(
            @RequestParam(value = "siteId", required = false) UUID siteId,
            @RequestParam(value = "allowTestStudy", required = false, defaultValue = "false") boolean allowTestStudy,
            @RequestParam(value = "generatedBy", required = false) String generatedByParam,
            @RequestHeader(value = "username", required = false) String usernameHeader,
            @RequestHeader(value = "password", required = false) String passwordHeader,
            @RequestHeader(value = "generatedBy", required = false) String generatedByHeader,
            @RequestHeader(value = "X-Generated-By", required = false) String xGeneratedByHeader,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest servletRequest) throws IOException {

        UUID resolvedSiteId = siteId != null ? siteId : hubProperties.defaultSiteId();

        // Extract username & password from explicit headers or Basic Authorization header
        String effectiveUser = usernameHeader;
        String effectivePass = passwordHeader;
        if ((effectiveUser == null || effectiveUser.isBlank() || effectivePass == null || effectivePass.isBlank())
                && authHeader != null && authHeader.startsWith("Basic ")) {
            try {
                String decoded = new String(
                        Base64.getDecoder().decode(authHeader.substring(6).trim()),
                        StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon > 0) {
                    if (effectiveUser == null || effectiveUser.isBlank()) {
                        effectiveUser = decoded.substring(0, colon);
                    }
                    if (effectivePass == null || effectivePass.isBlank()) {
                        effectivePass = decoded.substring(colon + 1);
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        String jsonPartContent = null;
        byte[] pdfBytes = null;
        String pdfFileName = "report.pdf";
        byte[] dicomBytes = null;
        String dicomFileName = "ai-result.dcm";

        if (servletRequest instanceof MultipartHttpServletRequest multipartReq) {
            // Check file parts first
            Map<String, MultipartFile> fileMap = multipartReq.getFileMap();
            for (Map.Entry<String, MultipartFile> entry : fileMap.entrySet()) {
                String partName = entry.getKey();
                MultipartFile mf = entry.getValue();
                String contentType = mf.getContentType() != null ? mf.getContentType().toLowerCase() : "";
                String origName = mf.getOriginalFilename() != null ? mf.getOriginalFilename() : "";
                if ("dicomFile".equalsIgnoreCase(partName)
                        || "dcmFile".equalsIgnoreCase(partName)
                        || contentType.contains("dicom")
                        || origName.toLowerCase().endsWith(".dcm")) {
                    dicomBytes = mf.getBytes();
                    if (!origName.isBlank()) {
                        dicomFileName = origName;
                    }
                } else if ("pdfFile".equalsIgnoreCase(partName)
                        || contentType.contains("pdf")
                        || origName.toLowerCase().endsWith(".pdf")) {
                    pdfBytes = mf.getBytes();
                    if (!origName.isBlank()) {
                        pdfFileName = origName;
                    }
                } else if (jsonPartContent == null
                        && (contentType.contains("json")
                                || "reportData".equalsIgnoreCase(partName)
                                || "json".equalsIgnoreCase(partName)
                                || "data".equalsIgnoreCase(partName))) {
                    jsonPartContent = new String(mf.getBytes(), StandardCharsets.UTF_8);
                }
            }

            // Also check parameter map for JSON string part
            if (jsonPartContent == null) {
                for (String candidateKey : new String[]{"reportData", "json", "data", "payload"}) {
                    String val = multipartReq.getParameter(candidateKey);
                    if (val != null && !val.isBlank()) {
                        jsonPartContent = val;
                        break;
                    }
                }
            }
            if (jsonPartContent == null) {
                for (Map.Entry<String, String[]> param : multipartReq.getParameterMap().entrySet()) {
                    if (param.getValue() != null && param.getValue().length > 0) {
                        String val = param.getValue()[0];
                        if (val != null && val.trim().startsWith("{")) {
                            jsonPartContent = val;
                            break;
                        }
                    }
                }
            }
        }

        String effectiveGeneratedBy = (generatedByParam != null && !generatedByParam.isBlank())
                ? generatedByParam.trim()
                : (generatedByHeader != null && !generatedByHeader.isBlank()
                        ? generatedByHeader.trim()
                        : (xGeneratedByHeader != null && !xGeneratedByHeader.isBlank() ? xGeneratedByHeader.trim() : null));
        if (effectiveGeneratedBy == null && servletRequest instanceof MultipartHttpServletRequest mpReq) {
            MultipartFile genPart = mpReq.getFile("generatedBy");
            if (genPart != null) {
                effectiveGeneratedBy = new String(genPart.getBytes(), StandardCharsets.UTF_8).trim();
            } else if (mpReq.getParameter("generatedBy") != null) {
                effectiveGeneratedBy = mpReq.getParameter("generatedBy").trim();
            }
        }

        UploadReportServletResult result;
        if (dicomBytes != null && dicomBytes.length > 0) {
            result = risUploadService.handleMockUploadReportServletWithDicom(
                    resolvedSiteId,
                    null,
                    null,
                    effectiveUser,
                    effectivePass,
                    jsonPartContent,
                    effectiveGeneratedBy,
                    pdfBytes != null ? pdfFileName : null,
                    null,
                    pdfBytes,
                    dicomFileName,
                    null,
                    dicomBytes,
                    null,
                    allowTestStudy);
        } else {
            result = risUploadService.handleMockUploadReportServlet(
                    resolvedSiteId,
                    null,
                    null,
                    effectiveUser,
                    effectivePass,
                    jsonPartContent,
                    effectiveGeneratedBy,
                    pdfFileName,
                    null,
                    pdfBytes,
                    allowTestStudy);
        }

        return ResponseEntity.status(result.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.rawResponseJson());
    }
}
