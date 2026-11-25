package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "vendor_delivery_jobs")
public class VendorDeliveryJob {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "study_id", nullable = false)
    private UUID studyId;

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    @Column(name = "routing_rule_id")
    private UUID routingRuleId;

    @Column(name = "deid_profile_id", nullable = false)
    private UUID deidProfileId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "working_copy_dir")
    private String workingCopyDir;

    @Column(name = "vendor_job_id")
    private String vendorJobId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "next_retry_at")
    private OffsetDateTime nextRetryAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "uploaded_bytes", nullable = false)
    private long uploadedBytes;

    @Column(name = "selected_instances", nullable = false)
    private int selectedInstances;

    @Column(name = "transferred_instances", nullable = false)
    private int transferredInstances;

    @Column(name = "vendor_accepted_at")
    private OffsetDateTime vendorAcceptedAt;

    @Column(name = "result_received_at")
    private OffsetDateTime resultReceivedAt;

    @Column(name = "pacs_returned_at")
    private OffsetDateTime pacsReturnedAt;

    @Column(name = "working_copy_cleaned_at")
    private OffsetDateTime workingCopyCleanedAt;

    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public UUID getStudyId() { return studyId; }
    public void setStudyId(UUID studyId) { this.studyId = studyId; }
    public UUID getVendorId() { return vendorId; }
    public void setVendorId(UUID vendorId) { this.vendorId = vendorId; }
    public UUID getRoutingRuleId() { return routingRuleId; }
    public void setRoutingRuleId(UUID routingRuleId) { this.routingRuleId = routingRuleId; }
    public UUID getDeidProfileId() { return deidProfileId; }
    public void setDeidProfileId(UUID deidProfileId) { this.deidProfileId = deidProfileId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getWorkingCopyDir() { return workingCopyDir; }
    public void setWorkingCopyDir(String workingCopyDir) { this.workingCopyDir = workingCopyDir; }
    public String getVendorJobId() { return vendorJobId; }
    public void setVendorJobId(String vendorJobId) { this.vendorJobId = vendorJobId; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public OffsetDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(OffsetDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public long getUploadedBytes() { return uploadedBytes; }
    public void setUploadedBytes(long uploadedBytes) { this.uploadedBytes = uploadedBytes; }
    public int getSelectedInstances() { return selectedInstances; }
    public void setSelectedInstances(int selectedInstances) { this.selectedInstances = selectedInstances; }
    public int getTransferredInstances() { return transferredInstances; }
    public void setTransferredInstances(int transferredInstances) { this.transferredInstances = transferredInstances; }
    public OffsetDateTime getVendorAcceptedAt() { return vendorAcceptedAt; }
    public void setVendorAcceptedAt(OffsetDateTime vendorAcceptedAt) { this.vendorAcceptedAt = vendorAcceptedAt; }
    public OffsetDateTime getResultReceivedAt() { return resultReceivedAt; }
    public void setResultReceivedAt(OffsetDateTime resultReceivedAt) { this.resultReceivedAt = resultReceivedAt; }
    public OffsetDateTime getPacsReturnedAt() { return pacsReturnedAt; }
    public void setPacsReturnedAt(OffsetDateTime pacsReturnedAt) { this.pacsReturnedAt = pacsReturnedAt; }
    public OffsetDateTime getWorkingCopyCleanedAt() { return workingCopyCleanedAt; }
    public void setWorkingCopyCleanedAt(OffsetDateTime workingCopyCleanedAt) { this.workingCopyCleanedAt = workingCopyCleanedAt; }
    public boolean isLegalHold() { return legalHold; }
    public void setLegalHold(boolean legalHold) { this.legalHold = legalHold; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
