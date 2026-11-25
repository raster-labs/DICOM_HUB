package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "record_retention_settings")
public class RecordRetentionSettings {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false, unique = true)
    private UUID siteId;

    @Column(name = "auto_remove_enabled", nullable = false)
    private boolean autoRemoveEnabled = true;

    @Column(name = "retention_max_age_minutes", nullable = false)
    private int retentionMaxAgeMinutes = 60;

    @Column(name = "check_interval_minutes", nullable = false)
    private int checkIntervalMinutes = 5;

    @Column(name = "remove_study_logs_and_audits", nullable = false)
    private boolean removeStudyLogsAndAudits = true;

    @Column(name = "remove_disk_files", nullable = false)
    private boolean removeDiskFiles = true;

    @Column(name = "protect_active_receiving_studies", nullable = false)
    private boolean protectActiveReceivingStudies = true;

    @Column(name = "last_auto_run_at")
    private OffsetDateTime lastAutoRunAt;

    @Column(name = "last_removed_studies_count", nullable = false)
    private int lastRemovedStudiesCount = 0;

    @Column(name = "last_removed_logs_count", nullable = false)
    private int lastRemovedLogsCount = 0;

    @Column(name = "total_removed_studies_count", nullable = false)
    private long totalRemovedStudiesCount = 0L;

    @Column(name = "total_removed_logs_count", nullable = false)
    private long totalRemovedLogsCount = 0L;

    @Column(name = "updated_by")
    private String updatedBy = "super.admin";

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public boolean isAutoRemoveEnabled() { return autoRemoveEnabled; }
    public void setAutoRemoveEnabled(boolean autoRemoveEnabled) { this.autoRemoveEnabled = autoRemoveEnabled; }
    public int getRetentionMaxAgeMinutes() { return retentionMaxAgeMinutes; }
    public void setRetentionMaxAgeMinutes(int retentionMaxAgeMinutes) { this.retentionMaxAgeMinutes = retentionMaxAgeMinutes; }
    public int getCheckIntervalMinutes() { return checkIntervalMinutes; }
    public void setCheckIntervalMinutes(int checkIntervalMinutes) { this.checkIntervalMinutes = checkIntervalMinutes; }
    public boolean isRemoveStudyLogsAndAudits() { return removeStudyLogsAndAudits; }
    public void setRemoveStudyLogsAndAudits(boolean removeStudyLogsAndAudits) { this.removeStudyLogsAndAudits = removeStudyLogsAndAudits; }
    public boolean isRemoveDiskFiles() { return removeDiskFiles; }
    public void setRemoveDiskFiles(boolean removeDiskFiles) { this.removeDiskFiles = removeDiskFiles; }
    public boolean isProtectActiveReceivingStudies() { return protectActiveReceivingStudies; }
    public void setProtectActiveReceivingStudies(boolean protectActiveReceivingStudies) { this.protectActiveReceivingStudies = protectActiveReceivingStudies; }
    public OffsetDateTime getLastAutoRunAt() { return lastAutoRunAt; }
    public void setLastAutoRunAt(OffsetDateTime lastAutoRunAt) { this.lastAutoRunAt = lastAutoRunAt; }
    public int getLastRemovedStudiesCount() { return lastRemovedStudiesCount; }
    public void setLastRemovedStudiesCount(int lastRemovedStudiesCount) { this.lastRemovedStudiesCount = lastRemovedStudiesCount; }
    public int getLastRemovedLogsCount() { return lastRemovedLogsCount; }
    public void setLastRemovedLogsCount(int lastRemovedLogsCount) { this.lastRemovedLogsCount = lastRemovedLogsCount; }
    public long getTotalRemovedStudiesCount() { return totalRemovedStudiesCount; }
    public void setTotalRemovedStudiesCount(long totalRemovedStudiesCount) { this.totalRemovedStudiesCount = totalRemovedStudiesCount; }
    public long getTotalRemovedLogsCount() { return totalRemovedLogsCount; }
    public void setTotalRemovedLogsCount(long totalRemovedLogsCount) { this.totalRemovedLogsCount = totalRemovedLogsCount; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
