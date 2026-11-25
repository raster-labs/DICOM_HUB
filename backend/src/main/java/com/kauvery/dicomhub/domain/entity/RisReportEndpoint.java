package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ris_report_endpoints")
public class RisReportEndpoint {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "endpoint_name", nullable = false)
    private String endpointName = "Kauvery Telerad / RIS UploadReportServlet";

    @Column(name = "host_address", nullable = false)
    private String hostAddress = "172.17.1.200";

    @Column(name = "port", nullable = false)
    private Integer port = 8080;

    @Column(name = "context_path", nullable = false)
    private String contextPath = "/telerad/UploadReportServlet";

    @Column(name = "target_url", nullable = false)
    private String targetUrl = "http://172.17.1.200:8080/telerad/UploadReportServlet";

    @Column(name = "auth_username", nullable = false)
    private String authUsername = "sonocare";

    @Column(name = "auth_password", nullable = false)
    private String authPassword = "sonocare123";

    @Column(name = "json_part_name", nullable = false)
    private String jsonPartName = "reportData";

    @Column(name = "file_part_name", nullable = false)
    private String filePartName = "pdfFile";

    @Column(name = "dicom_part_name", nullable = false)
    private String dicomPartName = "dicomFile";

    @Column(name = "support_dicom_upload", nullable = false)
    private boolean supportDicomUpload = true;

    @Column(name = "forward_dicom_to_pacs_ae", nullable = false)
    private boolean forwardDicomToPacsAe = true;

    @Column(name = "default_report_type", nullable = false)
    private String defaultReportType = "Preliminary Report";

    @Column(name = "use_builtin_mock", nullable = false)
    private boolean useBuiltinMock = false;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "updated_by")
    private String updatedBy = "super.admin";

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getEndpointName() { return endpointName; }
    public void setEndpointName(String endpointName) { this.endpointName = endpointName; }
    public String getHostAddress() { return hostAddress; }
    public void setHostAddress(String hostAddress) { this.hostAddress = hostAddress; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getContextPath() { return contextPath; }
    public void setContextPath(String contextPath) { this.contextPath = contextPath; }
    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
    public String getAuthUsername() { return authUsername; }
    public void setAuthUsername(String authUsername) { this.authUsername = authUsername; }
    public String getAuthPassword() { return authPassword; }
    public void setAuthPassword(String authPassword) { this.authPassword = authPassword; }
    public String getJsonPartName() { return jsonPartName; }
    public void setJsonPartName(String jsonPartName) { this.jsonPartName = jsonPartName; }
    public String getFilePartName() { return filePartName; }
    public void setFilePartName(String filePartName) { this.filePartName = filePartName; }
    public String getDicomPartName() { return dicomPartName; }
    public void setDicomPartName(String dicomPartName) { this.dicomPartName = dicomPartName; }
    public boolean isSupportDicomUpload() { return supportDicomUpload; }
    public void setSupportDicomUpload(boolean supportDicomUpload) { this.supportDicomUpload = supportDicomUpload; }
    public boolean isForwardDicomToPacsAe() { return forwardDicomToPacsAe; }
    public void setForwardDicomToPacsAe(boolean forwardDicomToPacsAe) { this.forwardDicomToPacsAe = forwardDicomToPacsAe; }
    public String getDefaultReportType() { return defaultReportType; }
    public void setDefaultReportType(String defaultReportType) { this.defaultReportType = defaultReportType; }
    public boolean isUseBuiltinMock() { return useBuiltinMock; }
    public void setUseBuiltinMock(boolean useBuiltinMock) { this.useBuiltinMock = useBuiltinMock; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
