package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "routing_rules")
public class RoutingRule {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(name = "priority_order", nullable = false)
    private int priorityOrder;

    @Column(name = "modality", nullable = false)
    private String modality;

    @Column(name = "canonical_region")
    private String canonicalRegion;

    @Column(name = "study_description_pattern", nullable = false)
    private String studyDescriptionPattern;

    @Column(name = "body_part_pattern")
    private String bodyPartPattern;

    @Column(name = "match_operator", nullable = false)
    private String matchOperator = "CONTAINS";

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    @Column(name = "required_series_pattern")
    private String requiredSeriesPattern;

    @Column(name = "max_slice_thickness_mm")
    private Double maxSliceThicknessMm;

    @Column(name = "allow_multi_vendor", nullable = false)
    private boolean allowMultiVendor = true;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "rule_version", nullable = false)
    private int ruleVersion = 1;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public int getPriorityOrder() { return priorityOrder; }
    public void setPriorityOrder(int priorityOrder) { this.priorityOrder = priorityOrder; }
    public String getModality() { return modality; }
    public void setModality(String modality) { this.modality = modality; }
    public String getCanonicalRegion() { return canonicalRegion; }
    public void setCanonicalRegion(String canonicalRegion) { this.canonicalRegion = canonicalRegion; }
    public String getStudyDescriptionPattern() { return studyDescriptionPattern; }
    public void setStudyDescriptionPattern(String studyDescriptionPattern) { this.studyDescriptionPattern = studyDescriptionPattern; }
    public String getBodyPartPattern() { return bodyPartPattern; }
    public void setBodyPartPattern(String bodyPartPattern) { this.bodyPartPattern = bodyPartPattern; }
    public String getMatchOperator() { return matchOperator; }
    public void setMatchOperator(String matchOperator) { this.matchOperator = matchOperator; }
    public UUID getVendorId() { return vendorId; }
    public void setVendorId(UUID vendorId) { this.vendorId = vendorId; }
    public String getRequiredSeriesPattern() { return requiredSeriesPattern; }
    public void setRequiredSeriesPattern(String requiredSeriesPattern) { this.requiredSeriesPattern = requiredSeriesPattern; }
    public Double getMaxSliceThicknessMm() { return maxSliceThicknessMm; }
    public void setMaxSliceThicknessMm(Double maxSliceThicknessMm) { this.maxSliceThicknessMm = maxSliceThicknessMm; }
    public boolean isAllowMultiVendor() { return allowMultiVendor; }
    public void setAllowMultiVendor(boolean allowMultiVendor) { this.allowMultiVendor = allowMultiVendor; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(int ruleVersion) { this.ruleVersion = ruleVersion; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
