package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ris_uploaded_reports")
public class RisUploadedReport {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "study_id")
    private UUID studyId;

    @Column(name = "study_iuid", nullable = false)
    private String studyIuid;

    @Column(name = "report_type", nullable = false)
    private String reportType;

    @Column(name = "drafted_time", nullable = false)
    private String draftedTime;

    @Column(name = "drafted_by", nullable = false)
    private String draftedBy;

    @Column(name = "reviewed_time", nullable = false)
    private String reviewedTime;

    @Column(name = "reviewed_by", nullable = false)
    private String reviewedBy;

    @Column(name = "generated_by", nullable = false)
    private String generatedBy = "Grok AI Vision Agent";

    @Column(name = "report_data", columnDefinition = "TEXT")
    private String reportData;

    @Column(name = "processing_log", columnDefinition = "TEXT")
    private String processingLog;

    @Column(name = "pdf_file_name", nullable = false)
    private String pdfFileName;

    @Column(name = "pdf_file_path", nullable = false)
    private String pdfFilePath;

    @Column(name = "pdf_file_size_bytes", nullable = false)
    private long pdfFileSizeBytes;

    @Column(name = "file_format", nullable = false)
    private String fileFormat = "PDF";

    @Column(name = "dicom_file_name")
    private String dicomFileName;

    @Column(name = "dicom_file_path")
    private String dicomFilePath;

    @Column(name = "dicom_file_size_bytes", nullable = false)
    private long dicomFileSizeBytes;

    @Column(name = "dicom_ae_status")
    private String dicomAeStatus;

    @Column(name = "target_endpoint_url", nullable = false)
    private String targetEndpointUrl;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @Column(name = "response_json", nullable = false, columnDefinition = "TEXT")
    private String responseJson;

    @Column(name = "replaced_previous", nullable = false)
    private boolean replacedPrevious;

    @Column(name = "uploaded_at", nullable = false)
    private OffsetDateTime uploadedAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }
    public UUID getStudyId() { return studyId; }
    public void setStudyId(UUID studyId) { this.studyId = studyId; }
    public String getStudyIuid() { return studyIuid; }
    public void setStudyIuid(String studyIuid) { this.studyIuid = studyIuid; }
    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }
    public String getDraftedTime() { return draftedTime; }
    public void setDraftedTime(String draftedTime) { this.draftedTime = draftedTime; }
    public String getDraftedBy() { return draftedBy; }
    public void setDraftedBy(String draftedBy) { this.draftedBy = draftedBy; }
    public String getReviewedTime() { return reviewedTime; }
    public void setReviewedTime(String reviewedTime) { this.reviewedTime = reviewedTime; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public String getGeneratedBy() {
        return (generatedBy != null && !generatedBy.isBlank()) ? generatedBy : "Grok AI Vision Agent";
    }
    public void setGeneratedBy(String generatedBy) { this.generatedBy = generatedBy; }
    public String getReportData() {
        if (reportData != null && !reportData.isBlank()) {
            return reportData;
        }
        return """
                {
                  "reportType": "%s",
                  "studyIuid": "%s",
                  "draftedTime": "%s",
                  "draftedBy": "%s",
                  "reviewedTime": "%s",
                  "reviewedBy": "%s",
                  "generatedBy": "%s"
                }""".formatted(
                escapeJson(reportType != null ? reportType : "Preliminary Report"),
                escapeJson(studyIuid != null ? studyIuid : ""),
                escapeJson(draftedTime != null ? draftedTime : ""),
                escapeJson(draftedBy != null ? draftedBy : "sonocare"),
                escapeJson(reviewedTime != null ? reviewedTime : ""),
                escapeJson(reviewedBy != null ? reviewedBy : "sonocare"),
                escapeJson(getGeneratedBy()));
    }
    public void setReportData(String reportData) { this.reportData = reportData; }

    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
    public String getProcessingLog() {
        if (processingLog != null && !processingLog.isBlank()) {
            return processingLog;
        }
        return """
                [%s] RIS UploadReportServlet Dispatch:
                  • Target Endpoint : %s
                  • StudyInstanceUID: %s (studyId=%s, jobId=%s)
                  • AI Agent Name   : generatedBy = "%s"
                  • Report Type     : %s
                  • Sign-Off        : draftedBy=%s @ %s | reviewedBy=%s @ %s
                  • PDF Attachment  : %s (%d bytes, path=%s)
                  • reportData JSON : %s
                  • RIS HTTP Status : HTTP %d -> %s""".formatted(
                uploadedAt != null ? uploadedAt : OffsetDateTime.now(),
                targetEndpointUrl != null ? targetEndpointUrl : "",
                studyIuid != null ? studyIuid : "",
                studyId,
                jobId,
                getGeneratedBy(),
                reportType != null ? reportType : "Preliminary Report",
                draftedBy != null ? draftedBy : "sonocare",
                draftedTime != null ? draftedTime : "",
                reviewedBy != null ? reviewedBy : "sonocare",
                reviewedTime != null ? reviewedTime : "",
                pdfFileName != null ? pdfFileName : "",
                pdfFileSizeBytes,
                pdfFilePath != null ? pdfFilePath : "",
                getReportData().replace("\n", " "),
                httpStatus,
                responseJson != null ? responseJson.replace("\n", " ") : "");
    }
    public void setProcessingLog(String processingLog) { this.processingLog = processingLog; }
    public String getPdfFileName() { return pdfFileName; }
    public void setPdfFileName(String pdfFileName) { this.pdfFileName = pdfFileName; }
    public String getPdfFilePath() { return pdfFilePath; }
    public void setPdfFilePath(String pdfFilePath) { this.pdfFilePath = pdfFilePath; }
    public long getPdfFileSizeBytes() { return pdfFileSizeBytes; }
    public void setPdfFileSizeBytes(long pdfFileSizeBytes) { this.pdfFileSizeBytes = pdfFileSizeBytes; }
    public String getFileFormat() { return fileFormat != null && !fileFormat.isBlank() ? fileFormat : "PDF"; }
    public void setFileFormat(String fileFormat) { this.fileFormat = (fileFormat != null && !fileFormat.isBlank()) ? fileFormat : "PDF"; }
    public String getDicomFileName() { return dicomFileName; }
    public void setDicomFileName(String dicomFileName) { this.dicomFileName = dicomFileName; }
    public String getDicomFilePath() { return dicomFilePath; }
    public void setDicomFilePath(String dicomFilePath) { this.dicomFilePath = dicomFilePath; }
    public long getDicomFileSizeBytes() { return dicomFileSizeBytes; }
    public void setDicomFileSizeBytes(long dicomFileSizeBytes) { this.dicomFileSizeBytes = dicomFileSizeBytes; }
    public String getDicomAeStatus() { return dicomAeStatus; }
    public void setDicomAeStatus(String dicomAeStatus) { this.dicomAeStatus = dicomAeStatus; }
    public String getTargetEndpointUrl() { return targetEndpointUrl; }
    public void setTargetEndpointUrl(String targetEndpointUrl) { this.targetEndpointUrl = targetEndpointUrl; }
    public int getHttpStatus() { return httpStatus; }
    public void setHttpStatus(int httpStatus) { this.httpStatus = httpStatus; }
    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }
    public String getResponseMessage() {
        if (responseJson == null || responseJson.isBlank()) {
            return httpStatus == 200 ? "Upload reports successfully" : ("HTTP " + httpStatus);
        }
        String msg = com.kauvery.dicomhub.report.RisTeleradUploadReportService.extractJsonField(responseJson, "message");
        String reason = com.kauvery.dicomhub.report.RisTeleradUploadReportService.extractJsonField(responseJson, "reason");
        if (msg != null && !msg.isBlank() && reason != null && !reason.isBlank()) {
            return msg + " — Reason: " + reason;
        }
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        if (msg != null && !msg.isBlank()) {
            return msg;
        }
        return responseJson.replace("\n", " ").trim();
    }
    public boolean isReplacedPrevious() { return replacedPrevious; }
    public void setReplacedPrevious(boolean replacedPrevious) { this.replacedPrevious = replacedPrevious; }
    public OffsetDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(OffsetDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
}
