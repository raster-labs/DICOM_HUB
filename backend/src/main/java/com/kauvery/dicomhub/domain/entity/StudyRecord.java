package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "study_records")
public class StudyRecord {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "source_type", nullable = false)
    private String sourceType; // MODALITY, RIS_PACS

    @Column(name = "calling_ae_title", nullable = false)
    private String callingAeTitle;

    @Column(name = "called_ae_title", nullable = false)
    private String calledAeTitle;

    @Column(name = "study_instance_uid", nullable = false)
    private String studyInstanceUid;

    @Column(name = "patient_id_hash", nullable = false)
    private String patientIdHash;

    @Column(name = "accession_hash")
    private String accessionHash;

    @Column(name = "patient_id")
    private String patientId;

    @Column(name = "patient_name")
    private String patientName;

    @Column(name = "patient_age")
    private String patientAge;

    @Column(name = "patient_sex")
    private String patientSex;

    @Column(name = "accession_number")
    private String accessionNumber;

    @Column(name = "canonical_region")
    private String canonicalRegion;

    @Column(name = "modality", nullable = false)
    private String modality;

    @Column(name = "study_description")
    private String studyDescription;

    @Column(name = "body_part_examined")
    private String bodyPartExamined;

    @Column(name = "protocol_name")
    private String protocolName;

    @Column(name = "series_count", nullable = false)
    private int seriesCount;

    @Column(name = "instance_count", nullable = false)
    private int instanceCount;

    @Column(name = "received_transfer_events", nullable = false)
    private int receivedTransferEvents;

    @Column(name = "duplicate_instances_count", nullable = false)
    private int duplicateInstancesCount;

    @Column(name = "receiving_status", nullable = false)
    private String receivingStatus = "RECEIVING";

    @Column(name = "total_bytes", nullable = false)
    private long totalBytes;

    @Column(name = "lifecycle_state", nullable = false)
    private String lifecycleState;

    @Column(name = "validation_error")
    private String validationError;

    @Column(name = "last_instance_received_at", nullable = false)
    private OffsetDateTime lastInstanceReceivedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public UUID getSourceId() { return sourceId; }
    public void setSourceId(UUID sourceId) { this.sourceId = sourceId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getCallingAeTitle() { return callingAeTitle; }
    public void setCallingAeTitle(String callingAeTitle) { this.callingAeTitle = callingAeTitle; }
    public String getCalledAeTitle() { return calledAeTitle; }
    public void setCalledAeTitle(String calledAeTitle) { this.calledAeTitle = calledAeTitle; }
    public String getStudyInstanceUid() { return studyInstanceUid; }
    public void setStudyInstanceUid(String studyInstanceUid) { this.studyInstanceUid = studyInstanceUid; }
    public String getPatientIdHash() { return patientIdHash; }
    public void setPatientIdHash(String patientIdHash) { this.patientIdHash = patientIdHash; }
    public String getAccessionHash() { return accessionHash; }
    public void setAccessionHash(String accessionHash) { this.accessionHash = accessionHash; }
    public String getPatientId() { return patientId; }
    public void setPatientId(String patientId) { this.patientId = patientId; }
    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }
    public String getPatientAge() { return patientAge; }
    public void setPatientAge(String patientAge) { this.patientAge = patientAge; }
    public String getPatientSex() { return patientSex; }
    public void setPatientSex(String patientSex) { this.patientSex = patientSex; }
    public String getAccessionNumber() { return accessionNumber; }
    public void setAccessionNumber(String accessionNumber) { this.accessionNumber = accessionNumber; }
    public String getCanonicalRegion() { return canonicalRegion; }
    public void setCanonicalRegion(String canonicalRegion) { this.canonicalRegion = canonicalRegion; }
    public String getModality() { return modality; }
    public void setModality(String modality) { this.modality = modality; }
    public String getStudyDescription() { return studyDescription; }
    public void setStudyDescription(String studyDescription) { this.studyDescription = studyDescription; }
    public String getBodyPartExamined() { return bodyPartExamined; }
    public void setBodyPartExamined(String bodyPartExamined) { this.bodyPartExamined = bodyPartExamined; }
    public String getProtocolName() { return protocolName; }
    public void setProtocolName(String protocolName) { this.protocolName = protocolName; }
    public int getSeriesCount() { return seriesCount; }
    public void setSeriesCount(int seriesCount) { this.seriesCount = seriesCount; }
    public int getInstanceCount() { return instanceCount; }
    public void setInstanceCount(int instanceCount) { this.instanceCount = instanceCount; }
    public int getReceivedTransferEvents() { return receivedTransferEvents; }
    public void setReceivedTransferEvents(int receivedTransferEvents) { this.receivedTransferEvents = receivedTransferEvents; }
    public int getDuplicateInstancesCount() { return duplicateInstancesCount; }
    public void setDuplicateInstancesCount(int duplicateInstancesCount) { this.duplicateInstancesCount = duplicateInstancesCount; }
    public String getReceivingStatus() { return receivingStatus; }
    public void setReceivingStatus(String receivingStatus) { this.receivingStatus = receivingStatus; }
    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }
    public String getLifecycleState() { return lifecycleState; }
    public void setLifecycleState(String lifecycleState) { this.lifecycleState = lifecycleState; }
    public String getValidationError() { return validationError; }
    public void setValidationError(String validationError) { this.validationError = validationError; }
    public OffsetDateTime getLastInstanceReceivedAt() { return lastInstanceReceivedAt; }
    public void setLastInstanceReceivedAt(OffsetDateTime lastInstanceReceivedAt) { this.lastInstanceReceivedAt = lastInstanceReceivedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
