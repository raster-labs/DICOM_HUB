package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "pacs_destinations")
public class PacsDestination {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "destination_name", nullable = false)
    private String destinationName;

    @Column(name = "ae_title", nullable = false)
    private String aeTitle;

    @Column(name = "host_address", nullable = false)
    private String hostAddress;

    @Column(name = "dicom_port", nullable = false)
    private int dicomPort = 11112;

    @Column(name = "report_integration_strategy", nullable = false)
    private String reportIntegrationStrategy = "DICOM_SR_NATIVE";

    @Column(name = "report_endpoint_url")
    private String reportEndpointUrl;

    @Column(name = "is_default_for_site", nullable = false)
    private boolean defaultForSite = true;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getDestinationName() { return destinationName; }
    public void setDestinationName(String destinationName) { this.destinationName = destinationName; }
    public String getAeTitle() { return aeTitle; }
    public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }
    public String getHostAddress() { return hostAddress; }
    public void setHostAddress(String hostAddress) { this.hostAddress = hostAddress; }
    public int getDicomPort() { return dicomPort; }
    public void setDicomPort(int dicomPort) { this.dicomPort = dicomPort; }
    public String getReportIntegrationStrategy() { return reportIntegrationStrategy; }
    public void setReportIntegrationStrategy(String reportIntegrationStrategy) { this.reportIntegrationStrategy = reportIntegrationStrategy; }
    public String getReportEndpointUrl() { return reportEndpointUrl; }
    public void setReportEndpointUrl(String reportEndpointUrl) { this.reportEndpointUrl = reportEndpointUrl; }
    public boolean isDefaultForSite() { return defaultForSite; }
    public void setDefaultForSite(boolean defaultForSite) { this.defaultForSite = defaultForSite; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
