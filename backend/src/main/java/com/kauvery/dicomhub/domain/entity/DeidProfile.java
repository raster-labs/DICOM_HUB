package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "deid_profiles")
public class DeidProfile {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "profile_code", nullable = false)
    private String profileCode;

    @Column(name = "profile_name", nullable = false)
    private String profileName;

    @Column(name = "profile_version", nullable = false)
    private int profileVersion = 1;

    @Column(name = "retain_patient_age_sex", nullable = false)
    private boolean retainPatientAgeSex = true;

    @Column(name = "retain_device_acquisition_params", nullable = false)
    private boolean retainDeviceAcquisitionParams = true;

    @Column(name = "date_shift_days", nullable = false)
    private int dateShiftDays = -30;

    @Column(name = "date_time_policy", nullable = false)
    private String dateTimePolicy = "SHIFT_DATES";

    @Column(name = "block_on_burned_in_text", nullable = false)
    private boolean blockOnBurnedInText = true;

    @Column(name = "burned_in_text_action", nullable = false)
    private String burnedInTextAction = "BLOCK_STUDY";

    @Column(name = "strip_private_tags", nullable = false)
    private boolean stripPrivateTags = true;

    @Column(name = "private_tag_allow_list", nullable = false)
    private String privateTagAllowList = "";

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getProfileCode() { return profileCode; }
    public void setProfileCode(String profileCode) { this.profileCode = profileCode; }
    public String getProfileName() { return profileName; }
    public void setProfileName(String profileName) { this.profileName = profileName; }
    public int getProfileVersion() { return profileVersion; }
    public void setProfileVersion(int profileVersion) { this.profileVersion = profileVersion; }
    public boolean isRetainPatientAgeSex() { return retainPatientAgeSex; }
    public void setRetainPatientAgeSex(boolean retainPatientAgeSex) { this.retainPatientAgeSex = retainPatientAgeSex; }
    public boolean isRetainDeviceAcquisitionParams() { return retainDeviceAcquisitionParams; }
    public void setRetainDeviceAcquisitionParams(boolean retainDeviceAcquisitionParams) { this.retainDeviceAcquisitionParams = retainDeviceAcquisitionParams; }
    public int getDateShiftDays() { return dateShiftDays; }
    public void setDateShiftDays(int dateShiftDays) { this.dateShiftDays = dateShiftDays; }
    public String getDateTimePolicy() { return dateTimePolicy; }
    public void setDateTimePolicy(String dateTimePolicy) { this.dateTimePolicy = dateTimePolicy != null ? dateTimePolicy : "SHIFT_DATES"; }
    public boolean isBlockOnBurnedInText() { return blockOnBurnedInText; }
    public void setBlockOnBurnedInText(boolean blockOnBurnedInText) { this.blockOnBurnedInText = blockOnBurnedInText; }
    public String getBurnedInTextAction() { return burnedInTextAction; }
    public void setBurnedInTextAction(String burnedInTextAction) {
        this.burnedInTextAction = burnedInTextAction != null ? burnedInTextAction : "BLOCK_STUDY";
        this.blockOnBurnedInText = !"MASK_PIXEL_REGION".equalsIgnoreCase(this.burnedInTextAction);
    }
    public boolean isStripPrivateTags() { return stripPrivateTags; }
    public void setStripPrivateTags(boolean stripPrivateTags) { this.stripPrivateTags = stripPrivateTags; }
    public String getPrivateTagAllowList() { return privateTagAllowList; }
    public void setPrivateTagAllowList(String privateTagAllowList) { this.privateTagAllowList = privateTagAllowList != null ? privateTagAllowList : ""; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
