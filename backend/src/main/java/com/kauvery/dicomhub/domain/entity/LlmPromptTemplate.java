package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "llm_prompt_templates")
public class LlmPromptTemplate {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "template_code", nullable = false)
    private String templateCode;

    @Column(name = "use_case_name", nullable = false)
    private String useCaseName;

    @Column(name = "template_version", nullable = false)
    private int templateVersion = 1;

    @Column(name = "window_preset", nullable = false)
    private String windowPreset = "SOFT_TISSUE";

    @Column(name = "max_slices", nullable = false)
    private int maxSlices = 16;

    @Column(name = "prompt_body", nullable = false, columnDefinition = "TEXT")
    private String promptBody;

    @Column(name = "findings_json_schema", nullable = false, columnDefinition = "TEXT")
    private String findingsJsonSchema;

    @Column(name = "shadow_mode", nullable = false)
    private boolean shadowMode = true;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public String getUseCaseName() { return useCaseName; }
    public void setUseCaseName(String useCaseName) { this.useCaseName = useCaseName; }
    public int getTemplateVersion() { return templateVersion; }
    public void setTemplateVersion(int templateVersion) { this.templateVersion = templateVersion; }
    public String getWindowPreset() { return windowPreset; }
    public void setWindowPreset(String windowPreset) { this.windowPreset = windowPreset; }
    public int getMaxSlices() { return maxSlices; }
    public void setMaxSlices(int maxSlices) { this.maxSlices = maxSlices; }
    public String getPromptBody() { return promptBody; }
    public void setPromptBody(String promptBody) { this.promptBody = promptBody; }
    public String getFindingsJsonSchema() { return findingsJsonSchema; }
    public void setFindingsJsonSchema(String findingsJsonSchema) { this.findingsJsonSchema = findingsJsonSchema; }
    public boolean isShadowMode() { return shadowMode; }
    public void setShadowMode(boolean shadowMode) { this.shadowMode = shadowMode; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
