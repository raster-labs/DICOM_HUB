package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "instance_records")
public class InstanceRecord {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "study_id", nullable = false)
    private UUID studyId;

    @Column(name = "series_instance_uid", nullable = false)
    private String seriesInstanceUid;

    @Column(name = "sop_instance_uid", nullable = false)
    private String sopInstanceUid;

    @Column(name = "sop_class_uid", nullable = false)
    private String sopClassUid;

    @Column(name = "transfer_syntax_uid", nullable = false)
    private String transferSyntaxUid;

    @Column(name = "series_description")
    private String seriesDescription;

    @Column(name = "slice_thickness_mm")
    private Double sliceThicknessMm;

    @Column(name = "burned_in_annotation")
    private String burnedInAnnotation;

    @Column(name = "original_repo_path", nullable = false)
    private String originalRepoPath;

    @Column(name = "sha256_checksum", nullable = false)
    private String sha256Checksum;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "late_arrival", nullable = false)
    private boolean lateArrival;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public UUID getStudyId() { return studyId; }
    public void setStudyId(UUID studyId) { this.studyId = studyId; }
    public String getSeriesInstanceUid() { return seriesInstanceUid; }
    public void setSeriesInstanceUid(String seriesInstanceUid) { this.seriesInstanceUid = seriesInstanceUid; }
    public String getSopInstanceUid() { return sopInstanceUid; }
    public void setSopInstanceUid(String sopInstanceUid) { this.sopInstanceUid = sopInstanceUid; }
    public String getSopClassUid() { return sopClassUid; }
    public void setSopClassUid(String sopClassUid) { this.sopClassUid = sopClassUid; }
    public String getTransferSyntaxUid() { return transferSyntaxUid; }
    public void setTransferSyntaxUid(String transferSyntaxUid) { this.transferSyntaxUid = transferSyntaxUid; }
    public String getSeriesDescription() { return seriesDescription; }
    public void setSeriesDescription(String seriesDescription) { this.seriesDescription = seriesDescription; }
    public Double getSliceThicknessMm() { return sliceThicknessMm; }
    public void setSliceThicknessMm(Double sliceThicknessMm) { this.sliceThicknessMm = sliceThicknessMm; }
    public String getBurnedInAnnotation() { return burnedInAnnotation; }
    public void setBurnedInAnnotation(String burnedInAnnotation) { this.burnedInAnnotation = burnedInAnnotation; }
    public String getOriginalRepoPath() { return originalRepoPath; }
    public void setOriginalRepoPath(String originalRepoPath) { this.originalRepoPath = originalRepoPath; }
    public String getSha256Checksum() { return sha256Checksum; }
    public void setSha256Checksum(String sha256Checksum) { this.sha256Checksum = sha256Checksum; }
    public long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }
    public int getDuplicateCount() { return duplicateCount; }
    public void setDuplicateCount(int duplicateCount) { this.duplicateCount = duplicateCount; }
    public boolean isLateArrival() { return lateArrival; }
    public void setLateArrival(boolean lateArrival) { this.lateArrival = lateArrival; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime receivedAt) { this.receivedAt = receivedAt; }
}
