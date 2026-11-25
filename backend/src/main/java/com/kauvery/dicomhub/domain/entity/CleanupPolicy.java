package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "cleanup_policies")
public class CleanupPolicy {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "vendor_id")
    private UUID vendorId;

    @Column(name = "target_category", nullable = false)
    private String targetCategory;

    @Column(name = "retention_hours_after_upload", nullable = false)
    private int retentionHoursAfterUpload = 24;

    @Column(name = "retention_hours_after_return", nullable = false)
    private int retentionHoursAfterReturn = 24;

    @Column(name = "schedule_cron", nullable = false)
    private String scheduleCron = "0 0 2 * * *";

    @Column(name = "dry_run_only", nullable = false)
    private boolean dryRunOnly = false;

    @Column(name = "legal_hold_active", nullable = false)
    private boolean legalHoldActive = false;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public UUID getVendorId() { return vendorId; }
    public void setVendorId(UUID vendorId) { this.vendorId = vendorId; }
    public String getTargetCategory() { return targetCategory; }
    public void setTargetCategory(String targetCategory) { this.targetCategory = targetCategory; }
    public int getRetentionHoursAfterUpload() { return retentionHoursAfterUpload; }
    public void setRetentionHoursAfterUpload(int retentionHoursAfterUpload) { this.retentionHoursAfterUpload = retentionHoursAfterUpload; }
    public int getRetentionHoursAfterReturn() { return retentionHoursAfterReturn; }
    public void setRetentionHoursAfterReturn(int retentionHoursAfterReturn) { this.retentionHoursAfterReturn = retentionHoursAfterReturn; }
    public String getScheduleCron() { return scheduleCron; }
    public void setScheduleCron(String scheduleCron) { this.scheduleCron = scheduleCron; }
    public boolean isDryRunOnly() { return dryRunOnly; }
    public void setDryRunOnly(boolean dryRunOnly) { this.dryRunOnly = dryRunOnly; }
    public boolean isLegalHoldActive() { return legalHoldActive; }
    public void setLegalHoldActive(boolean legalHoldActive) { this.legalHoldActive = legalHoldActive; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
