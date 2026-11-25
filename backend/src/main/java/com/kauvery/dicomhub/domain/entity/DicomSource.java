package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "dicom_sources")
public class DicomSource {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    @Column(name = "source_type", nullable = false)
    private String sourceType; // MODALITY, RIS_PACS, OTHER_NODE

    @Column(name = "calling_ae_title", nullable = false)
    private String callingAeTitle;

    @Column(name = "called_ae_title", nullable = false)
    private String calledAeTitle = "DICOMHUB_AE";

    @Column(name = "host_address", nullable = false)
    private String hostAddress;

    @Column(name = "dicom_port", nullable = false)
    private int dicomPort = 104;

    @Column(name = "quiet_period_seconds", nullable = false)
    private int quietPeriodSeconds = 120;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getCallingAeTitle() { return callingAeTitle; }
    public void setCallingAeTitle(String callingAeTitle) { this.callingAeTitle = callingAeTitle; }
    public String getCalledAeTitle() { return calledAeTitle; }
    public void setCalledAeTitle(String calledAeTitle) { this.calledAeTitle = calledAeTitle; }
    public String getHostAddress() { return hostAddress; }
    public void setHostAddress(String hostAddress) { this.hostAddress = hostAddress; }
    public int getDicomPort() { return dicomPort; }
    public void setDicomPort(int dicomPort) { this.dicomPort = dicomPort; }
    public int getQuietPeriodSeconds() { return quietPeriodSeconds; }
    public void setQuietPeriodSeconds(int quietPeriodSeconds) { this.quietPeriodSeconds = quietPeriodSeconds; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
