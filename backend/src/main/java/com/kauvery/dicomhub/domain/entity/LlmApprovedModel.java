package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "llm_approved_models")
public class LlmApprovedModel {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "provider_name", nullable = false)
    private String providerName;

    @Column(name = "model_identifier", nullable = false)
    private String modelIdentifier;

    @Column(name = "pinned_version", nullable = false)
    private String pinnedVersion;

    @Column(name = "cdsco_clearance_status", nullable = false)
    private String cdscoClearanceStatus = "DRAFT_ASSIST_ONLY";

    @Column(name = "india_region_hosted", nullable = false)
    private boolean indiaRegionHosted = true;

    @Column(name = "approved_by_radiology", nullable = false)
    private boolean approvedByRadiology = false;

    @Column(name = "approved_by_legal", nullable = false)
    private boolean approvedByLegal = false;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }
    public String getModelIdentifier() { return modelIdentifier; }
    public void setModelIdentifier(String modelIdentifier) { this.modelIdentifier = modelIdentifier; }
    public String getPinnedVersion() { return pinnedVersion; }
    public void setPinnedVersion(String pinnedVersion) { this.pinnedVersion = pinnedVersion; }
    public String getCdscoClearanceStatus() { return cdscoClearanceStatus; }
    public void setCdscoClearanceStatus(String cdscoClearanceStatus) { this.cdscoClearanceStatus = cdscoClearanceStatus; }
    public boolean isIndiaRegionHosted() { return indiaRegionHosted; }
    public void setIndiaRegionHosted(boolean indiaRegionHosted) { this.indiaRegionHosted = indiaRegionHosted; }
    public boolean isApprovedByRadiology() { return approvedByRadiology; }
    public void setApprovedByRadiology(boolean approvedByRadiology) { this.approvedByRadiology = approvedByRadiology; }
    public boolean isApprovedByLegal() { return approvedByLegal; }
    public void setApprovedByLegal(boolean approvedByLegal) { this.approvedByLegal = approvedByLegal; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
