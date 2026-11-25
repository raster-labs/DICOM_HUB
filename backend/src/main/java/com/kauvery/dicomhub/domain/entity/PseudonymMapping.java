package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "pseudonym_mappings")
public class PseudonymMapping {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "study_id", nullable = false)
    private UUID studyId;

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    @Column(name = "pseudo_patient_id", nullable = false)
    private String pseudoPatientId;

    @Column(name = "pseudo_accession_number", nullable = false)
    private String pseudoAccessionNumber;

    @Column(name = "pseudo_study_uid", nullable = false, unique = true)
    private String pseudoStudyUid;

    @Column(name = "encrypted_real_patient_id", nullable = false)
    private String encryptedRealPatientId;

    @Column(name = "encrypted_real_accession", nullable = false)
    private String encryptedRealAccession;

    @Column(name = "original_study_uid", nullable = false)
    private String originalStudyUid;

    @Column(name = "date_shift_days", nullable = false)
    private int dateShiftDays;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public UUID getStudyId() { return studyId; }
    public void setStudyId(UUID studyId) { this.studyId = studyId; }
    public UUID getVendorId() { return vendorId; }
    public void setVendorId(UUID vendorId) { this.vendorId = vendorId; }
    public String getPseudoPatientId() { return pseudoPatientId; }
    public void setPseudoPatientId(String pseudoPatientId) { this.pseudoPatientId = pseudoPatientId; }
    public String getPseudoAccessionNumber() { return pseudoAccessionNumber; }
    public void setPseudoAccessionNumber(String pseudoAccessionNumber) { this.pseudoAccessionNumber = pseudoAccessionNumber; }
    public String getPseudoStudyUid() { return pseudoStudyUid; }
    public void setPseudoStudyUid(String pseudoStudyUid) { this.pseudoStudyUid = pseudoStudyUid; }
    public String getEncryptedRealPatientId() { return encryptedRealPatientId; }
    public void setEncryptedRealPatientId(String encryptedRealPatientId) { this.encryptedRealPatientId = encryptedRealPatientId; }
    public String getEncryptedRealAccession() { return encryptedRealAccession; }
    public void setEncryptedRealAccession(String encryptedRealAccession) { this.encryptedRealAccession = encryptedRealAccession; }
    public String getOriginalStudyUid() { return originalStudyUid; }
    public void setOriginalStudyUid(String originalStudyUid) { this.originalStudyUid = originalStudyUid; }
    public int getDateShiftDays() { return dateShiftDays; }
    public void setDateShiftDays(int dateShiftDays) { this.dateShiftDays = dateShiftDays; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
