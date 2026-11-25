package com.kauvery.dicomhub.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "hub_users")
public class HubUser {
    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "password", nullable = false)
    private String password = "admin";

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "department")
    private String department;

    @Column(name = "user_role", nullable = false)
    private String userRole; // SUPER_USER, ADMINISTRATOR, RADIOLOGIST, TECHNOLOGIST, OPERATIONS, AUDITOR

    @Column(name = "can_view_dicom_config", nullable = false)
    private boolean canViewDicomConfig;

    @Column(name = "can_create_dicom_config", nullable = false)
    private boolean canCreateDicomConfig;

    @Column(name = "can_edit_dicom_config", nullable = false)
    private boolean canEditDicomConfig;

    @Column(name = "can_delete_dicom_config", nullable = false)
    private boolean canDeleteDicomConfig;

    @Column(name = "can_manage_users", nullable = false)
    private boolean canManageUsers;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getUserRole() { return userRole; }
    public void setUserRole(String userRole) { this.userRole = userRole; }
    public boolean isCanViewDicomConfig() { return canViewDicomConfig; }
    public void setCanViewDicomConfig(boolean canViewDicomConfig) { this.canViewDicomConfig = canViewDicomConfig; }
    public boolean isCanCreateDicomConfig() { return canCreateDicomConfig; }
    public void setCanCreateDicomConfig(boolean canCreateDicomConfig) { this.canCreateDicomConfig = canCreateDicomConfig; }
    public boolean isCanEditDicomConfig() { return canEditDicomConfig; }
    public void setCanEditDicomConfig(boolean canEditDicomConfig) { this.canEditDicomConfig = canEditDicomConfig; }
    public boolean isCanDeleteDicomConfig() { return canDeleteDicomConfig; }
    public void setCanDeleteDicomConfig(boolean canDeleteDicomConfig) { this.canDeleteDicomConfig = canDeleteDicomConfig; }
    public boolean isCanManageUsers() { return canManageUsers; }
    public void setCanManageUsers(boolean canManageUsers) { this.canManageUsers = canManageUsers; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(OffsetDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
