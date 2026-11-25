package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "study_region_mappings")
public class StudyRegionMapping {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "canonical_region", nullable = false)
    private String canonicalRegion;

    @Column(name = "modality", nullable = false)
    private String modality;

    @Column(name = "body_part", nullable = false)
    private String bodyPart = "HEAD";

    @Column(name = "study_descriptions", nullable = false, length = 1024)
    private String studyDescriptions;

    @Column(name = "target_vendor_id")
    private UUID targetVendorId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getCanonicalRegion() { return canonicalRegion; }
    public void setCanonicalRegion(String canonicalRegion) { this.canonicalRegion = canonicalRegion; }
    public String getModality() { return modality; }
    public void setModality(String modality) { this.modality = modality; }
    public String getBodyPart() { return bodyPart; }
    public void setBodyPart(String bodyPart) { this.bodyPart = bodyPart; }
    public String getStudyDescriptions() { return studyDescriptions; }
    public void setStudyDescriptions(String studyDescriptions) { this.studyDescriptions = studyDescriptions; }
    public UUID getTargetVendorId() { return targetVendorId; }
    public void setTargetVendorId(UUID targetVendorId) { this.targetVendorId = targetVendorId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
