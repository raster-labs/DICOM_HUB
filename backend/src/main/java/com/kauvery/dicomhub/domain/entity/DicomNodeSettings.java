package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "dicom_node_settings")
public class DicomNodeSettings {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false, unique = true)
    private UUID siteId;

    @Column(name = "local_ae_title", nullable = false)
    private String localAeTitle = "DICOMHUB_AE";

    @Column(name = "bind_ip_address", nullable = false)
    private String bindIpAddress = "0.0.0.0";

    @Column(name = "local_port", nullable = false)
    private int localPort = 11112;

    @Column(name = "max_pdu_length", nullable = false)
    private int maxPduLength = 16384;

    @Column(name = "connect_timeout_ms", nullable = false)
    private int connectTimeoutMs = 3000;

    @Column(name = "association_accept_timeout_ms", nullable = false)
    private int associationAcceptTimeoutMs = 3000;

    @Column(name = "dimse_response_timeout_ms", nullable = false)
    private int dimseResponseTimeoutMs = 5000;

    @Column(name = "idle_timeout_seconds", nullable = false)
    private int idleTimeoutSeconds = 60;

    @Column(name = "max_concurrent_associations", nullable = false)
    private int maxConcurrentAssociations = 32;

    @Column(name = "default_quiet_period_seconds", nullable = false)
    private int defaultQuietPeriodSeconds = 120;

    @Column(name = "incomplete_study_timeout_seconds", nullable = false)
    private int incompleteStudyTimeoutSeconds = 600;

    @Column(name = "accept_unknown_calling_ae", nullable = false)
    private boolean acceptUnknownCallingAe = true;

    @Column(name = "require_storage_commitment", nullable = false)
    private boolean requireStorageCommitment = true;

    @Column(name = "tls_enabled", nullable = false)
    private boolean tlsEnabled = false;

    @Column(name = "transfer_syntax_policy", nullable = false)
    private String transferSyntaxPolicy = "ExplicitVRLittleEndian, ImplicitVRLittleEndian, JPEGLossless";

    @Column(name = "updated_by")
    private String updatedBy = "super.admin";

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getLocalAeTitle() { return localAeTitle; }
    public void setLocalAeTitle(String localAeTitle) { this.localAeTitle = localAeTitle; }
    public String getBindIpAddress() { return bindIpAddress; }
    public void setBindIpAddress(String bindIpAddress) { this.bindIpAddress = bindIpAddress; }
    public int getLocalPort() { return localPort; }
    public void setLocalPort(int localPort) { this.localPort = localPort; }
    public int getMaxPduLength() { return maxPduLength; }
    public void setMaxPduLength(int maxPduLength) { this.maxPduLength = maxPduLength; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getAssociationAcceptTimeoutMs() { return associationAcceptTimeoutMs; }
    public void setAssociationAcceptTimeoutMs(int associationAcceptTimeoutMs) { this.associationAcceptTimeoutMs = associationAcceptTimeoutMs; }
    public int getDimseResponseTimeoutMs() { return dimseResponseTimeoutMs; }
    public void setDimseResponseTimeoutMs(int dimseResponseTimeoutMs) { this.dimseResponseTimeoutMs = dimseResponseTimeoutMs; }
    public int getIdleTimeoutSeconds() { return idleTimeoutSeconds; }
    public void setIdleTimeoutSeconds(int idleTimeoutSeconds) { this.idleTimeoutSeconds = idleTimeoutSeconds; }
    public int getMaxConcurrentAssociations() { return maxConcurrentAssociations; }
    public void setMaxConcurrentAssociations(int maxConcurrentAssociations) { this.maxConcurrentAssociations = maxConcurrentAssociations; }
    public int getDefaultQuietPeriodSeconds() { return defaultQuietPeriodSeconds; }
    public void setDefaultQuietPeriodSeconds(int defaultQuietPeriodSeconds) { this.defaultQuietPeriodSeconds = defaultQuietPeriodSeconds; }
    public int getIncompleteStudyTimeoutSeconds() { return incompleteStudyTimeoutSeconds; }
    public void setIncompleteStudyTimeoutSeconds(int incompleteStudyTimeoutSeconds) { this.incompleteStudyTimeoutSeconds = incompleteStudyTimeoutSeconds; }
    public boolean isAcceptUnknownCallingAe() { return acceptUnknownCallingAe; }
    public void setAcceptUnknownCallingAe(boolean acceptUnknownCallingAe) { this.acceptUnknownCallingAe = acceptUnknownCallingAe; }
    public boolean isRequireStorageCommitment() { return requireStorageCommitment; }
    public void setRequireStorageCommitment(boolean requireStorageCommitment) { this.requireStorageCommitment = requireStorageCommitment; }
    public boolean isTlsEnabled() { return tlsEnabled; }
    public void setTlsEnabled(boolean tlsEnabled) { this.tlsEnabled = tlsEnabled; }
    public String getTransferSyntaxPolicy() { return transferSyntaxPolicy; }
    public void setTransferSyntaxPolicy(String transferSyntaxPolicy) { this.transferSyntaxPolicy = transferSyntaxPolicy; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
