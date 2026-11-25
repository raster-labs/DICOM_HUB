package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ai_result_records")
public class AiResultRecord {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "study_id", nullable = false)
    private UUID studyId;

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    @Column(name = "result_type", nullable = false)
    private String resultType;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Column(name = "series_description_label", nullable = false)
    private String seriesDescriptionLabel = "AI-generated – not verified";

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Column(name = "pdf_storage_path")
    private String pdfStoragePath;

    @Column(name = "image_storage_path")
    private String imageStoragePath;

    @Column(name = "dicom_storage_path")
    private String dicomStoragePath;

    @Column(name = "dicom_file_name")
    private String dicomFileName;

    @Column(name = "dicom_file_size_bytes", nullable = false)
    private long dicomFileSizeBytes;

    @Column(name = "ris_dicom_upload_status")
    private String risDicomUploadStatus;

    @Column(name = "dicom_ae_return_status")
    private String dicomAeReturnStatus;

    @Column(name = "findings_json", nullable = false)
    private String findingsJson;

    @Column(name = "reidentified", nullable = false)
    private boolean reidentified;

    @Column(name = "pacs_forwarded", nullable = false)
    private boolean pacsForwarded;

    @Column(name = "report_prefill_strategy")
    private String reportPrefillStrategy;

    @Column(name = "report_prefill_status")
    private String reportPrefillStatus;

    @Column(name = "ris_upload_status")
    private String risUploadStatus;

    @Column(name = "ris_upload_response", columnDefinition = "TEXT")
    private String risUploadResponse;

    @Column(name = "generated_by")
    private String generatedBy;

    @Column(name = "report_data", columnDefinition = "TEXT")
    private String reportData;

    @Column(name = "radiologist_disposition")
    private String radiologistDisposition = "PENDING_REVIEW";

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }
    public UUID getStudyId() { return studyId; }
    public void setStudyId(UUID studyId) { this.studyId = studyId; }
    public UUID getVendorId() { return vendorId; }
    public void setVendorId(UUID vendorId) { this.vendorId = vendorId; }
    public String getResultType() { return resultType; }
    public void setResultType(String resultType) { this.resultType = resultType; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public String getSeriesDescriptionLabel() { return seriesDescriptionLabel; }
    public void setSeriesDescriptionLabel(String seriesDescriptionLabel) { this.seriesDescriptionLabel = seriesDescriptionLabel; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getPdfStoragePath() { return pdfStoragePath; }
    public void setPdfStoragePath(String pdfStoragePath) { this.pdfStoragePath = pdfStoragePath; }
    public String getImageStoragePath() { return imageStoragePath; }
    public void setImageStoragePath(String imageStoragePath) { this.imageStoragePath = imageStoragePath; }
    public String getDicomStoragePath() { return dicomStoragePath; }
    public void setDicomStoragePath(String dicomStoragePath) { this.dicomStoragePath = dicomStoragePath; }
    public String getDicomFileName() { return dicomFileName; }
    public void setDicomFileName(String dicomFileName) { this.dicomFileName = dicomFileName; }
    public long getDicomFileSizeBytes() { return dicomFileSizeBytes; }
    public void setDicomFileSizeBytes(long dicomFileSizeBytes) { this.dicomFileSizeBytes = dicomFileSizeBytes; }
    public String getRisDicomUploadStatus() { return risDicomUploadStatus; }
    public void setRisDicomUploadStatus(String risDicomUploadStatus) { this.risDicomUploadStatus = risDicomUploadStatus; }
    public String getDicomAeReturnStatus() { return dicomAeReturnStatus; }
    public void setDicomAeReturnStatus(String dicomAeReturnStatus) { this.dicomAeReturnStatus = dicomAeReturnStatus; }
    public String getFindingsJson() { return findingsJson; }
    public void setFindingsJson(String findingsJson) { this.findingsJson = findingsJson; }
    public boolean isReidentified() { return reidentified; }
    public void setReidentified(boolean reidentified) { this.reidentified = reidentified; }
    public boolean isPacsForwarded() { return pacsForwarded; }
    public void setPacsForwarded(boolean pacsForwarded) { this.pacsForwarded = pacsForwarded; }
    public String getReportPrefillStrategy() { return reportPrefillStrategy; }
    public void setReportPrefillStrategy(String reportPrefillStrategy) { this.reportPrefillStrategy = reportPrefillStrategy; }
    public String getReportPrefillStatus() { return reportPrefillStatus; }
    public void setReportPrefillStatus(String reportPrefillStatus) { this.reportPrefillStatus = reportPrefillStatus; }
    public String getRisUploadStatus() { return risUploadStatus; }
    public void setRisUploadStatus(String risUploadStatus) { this.risUploadStatus = risUploadStatus; }
    public String getRisUploadResponse() { return risUploadResponse; }
    public void setRisUploadResponse(String risUploadResponse) { this.risUploadResponse = risUploadResponse; }
    public String getGeneratedBy() {
        return (generatedBy != null && !generatedBy.isBlank()) ? generatedBy : "Grok AI Vision Agent";
    }
    public void setGeneratedBy(String generatedBy) { this.generatedBy = generatedBy; }
    public String getReportData() {
        if (reportData != null && !reportData.isBlank()) {
            return reportData;
        }
        String ts = (receivedAt != null ? receivedAt : OffsetDateTime.now())
                .toLocalDateTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return """
                {
                  "reportType": "Preliminary Report",
                  "studyId": "%s",
                  "draftedTime": "%s",
                  "draftedBy": "sonocare",
                  "reviewedTime": "%s",
                  "reviewedBy": "sonocare",
                  "generatedBy": "%s"
                }""".formatted(
                studyId != null ? studyId : "",
                ts,
                ts,
                escapeJson(getGeneratedBy()));
    }
    public void setReportData(String reportData) { this.reportData = reportData; }

    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
    public String getRadiologistDisposition() { return radiologistDisposition; }
    public void setRadiologistDisposition(String radiologistDisposition) { this.radiologistDisposition = radiologistDisposition; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime receivedAt) { this.receivedAt = receivedAt; }
}
