package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "vendor_configs")
public class VendorConfig {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "vendor_code", nullable = false)
    private String vendorCode;

    @Column(name = "vendor_name", nullable = false)
    private String vendorName;

    @Column(name = "agent_name", nullable = false)
    private String agentName = "Grok AI Vision Agent";

    @Column(name = "use_case_name", nullable = false)
    private String useCaseName;

    @Column(name = "connector_type", nullable = false)
    private String connectorType = "REST_MULTIPART";

    @Column(name = "destination_type", nullable = false)
    private String destinationType = "DICOM_UPLOADER";

    @Column(name = "destination_ae_title", nullable = false)
    private String destinationAeTitle = "KAUVERY_AI_UPL";

    @Column(name = "destination_host", nullable = false)
    private String destinationHost = "10.10.30.20";

    @Column(name = "destination_port", nullable = false)
    private Integer destinationPort = 11115;

    @Column(name = "api_base_url", nullable = false)
    private String apiBaseUrl;

    @Column(name = "auth_type", nullable = false)
    private String authType = "OAUTH2_CLIENT_CREDENTIALS";

    @Column(name = "api_key_secret")
    private String apiKeySecret;

    @Column(name = "ai_model_name", nullable = false)
    private String aiModelName = "grok-2-vision-1212";

    @Column(name = "execution_mode", nullable = false)
    private String executionMode = "MOCK_OR_LIVE_FALLBACK";

    @Column(name = "system_prompt_template", columnDefinition = "TEXT")
    private String systemPromptTemplate;

    @Column(name = "output_report_format", nullable = false)
    private String outputReportFormat = "ENCAPSULATED_PDF";

    @Column(name = "outbound_image_format", nullable = false)
    private String outboundImageFormat = "BASE64_PNG_LOSSLESS";

    @Column(name = "return_delivery_mode", nullable = false)
    private String returnDeliveryMode = "PDF_VIA_RIS_API";

    @Column(name = "modality", nullable = false)
    private String modality = "CR,DX,RF";

    @Column(name = "study_descriptions", nullable = false, length = 1024)
    private String studyDescriptions;

    @Column(name = "deid_profile_id", nullable = false)
    private UUID deidProfileId;

    @Column(name = "pacs_destination_id")
    private UUID pacsDestinationId;

    @Column(name = "sla_turnaround_minutes", nullable = false)
    private int slaTurnaroundMinutes = 15;

    @Column(name = "max_retry_attempts", nullable = false)
    private int maxRetryAttempts = 5;

    @Column(name = "acknowledgement_mode", nullable = false)
    private String acknowledgementMode = "ALL_INSTANCES_INGESTED";

    @Column(name = "shadow_mode", nullable = false)
    private boolean shadowMode = false;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "config_version", nullable = false)
    private int configVersion = 1;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getVendorCode() { return vendorCode; }
    public void setVendorCode(String vendorCode) { this.vendorCode = vendorCode; }
    public String getVendorName() { return vendorName; }
    public void setVendorName(String vendorName) { this.vendorName = vendorName; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getName() { return resolveEffectiveAgentName(); }
    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.agentName = name.trim();
        }
    }
    public String resolveEffectiveAgentName() {
        if (agentName != null && !agentName.isBlank()) {
            return agentName.trim();
        }
        if (vendorName != null && !vendorName.isBlank()) {
            return vendorName.trim();
        }
        return "Grok AI Vision Agent";
    }
    public String getUseCaseName() { return useCaseName; }
    public void setUseCaseName(String useCaseName) { this.useCaseName = useCaseName; }
    public String getConnectorType() { return connectorType; }
    public void setConnectorType(String connectorType) { this.connectorType = connectorType; }
    public String getDestinationType() { return destinationType; }
    public void setDestinationType(String destinationType) { this.destinationType = destinationType; }
    public String getDestinationAeTitle() { return destinationAeTitle; }
    public void setDestinationAeTitle(String destinationAeTitle) { this.destinationAeTitle = destinationAeTitle; }
    public String getDestinationHost() { return destinationHost; }
    public void setDestinationHost(String destinationHost) { this.destinationHost = destinationHost; }
    public Integer getDestinationPort() { return destinationPort; }
    public void setDestinationPort(Integer destinationPort) { this.destinationPort = destinationPort; }
    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public String getApiKeySecret() { return apiKeySecret; }
    public void setApiKeySecret(String apiKeySecret) { this.apiKeySecret = apiKeySecret; }
    public String getAiModelName() { return aiModelName; }
    public void setAiModelName(String aiModelName) { this.aiModelName = aiModelName; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }
    public String getSystemPromptTemplate() { return systemPromptTemplate; }
    public void setSystemPromptTemplate(String systemPromptTemplate) { this.systemPromptTemplate = systemPromptTemplate; }
    public String getOutputReportFormat() { return outputReportFormat; }
    public void setOutputReportFormat(String outputReportFormat) { this.outputReportFormat = outputReportFormat; }
    public String getOutboundImageFormat() { return outboundImageFormat; }
    public void setOutboundImageFormat(String outboundImageFormat) { this.outboundImageFormat = outboundImageFormat; }
    public String getReturnDeliveryMode() { return returnDeliveryMode; }
    public void setReturnDeliveryMode(String returnDeliveryMode) { this.returnDeliveryMode = returnDeliveryMode; }
    public String getModality() { return modality; }
    public void setModality(String modality) { this.modality = modality; }
    public String getStudyDescriptions() { return studyDescriptions; }
    public void setStudyDescriptions(String studyDescriptions) { this.studyDescriptions = studyDescriptions; }
    public UUID getDeidProfileId() { return deidProfileId; }
    public void setDeidProfileId(UUID deidProfileId) { this.deidProfileId = deidProfileId; }
    public UUID getPacsDestinationId() { return pacsDestinationId; }
    public void setPacsDestinationId(UUID pacsDestinationId) { this.pacsDestinationId = pacsDestinationId; }
    public int getSlaTurnaroundMinutes() { return slaTurnaroundMinutes; }
    public void setSlaTurnaroundMinutes(int slaTurnaroundMinutes) { this.slaTurnaroundMinutes = slaTurnaroundMinutes; }
    public int getMaxRetryAttempts() { return maxRetryAttempts; }
    public void setMaxRetryAttempts(int maxRetryAttempts) { this.maxRetryAttempts = maxRetryAttempts; }
    public String getAcknowledgementMode() { return acknowledgementMode; }
    public void setAcknowledgementMode(String acknowledgementMode) { this.acknowledgementMode = acknowledgementMode; }
    public boolean isShadowMode() { return shadowMode; }
    public void setShadowMode(boolean shadowMode) { this.shadowMode = shadowMode; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getConfigVersion() { return configVersion; }
    public void setConfigVersion(int configVersion) { this.configVersion = configVersion; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
