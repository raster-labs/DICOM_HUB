-- =============================================================================
-- V6: Live Incoming Study & Image-Count Telemetry (FR-46),
--     DICOM Configuration Access Control & User Management (FR-47),
--     Local AE & Connectivity Settings, and Configuration Audit Trail
-- =============================================================================

-- 1. Add real-time receiving status & deduplicated transfer event counters to study_records
ALTER TABLE study_records ADD COLUMN receiving_status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE study_records ADD COLUMN received_transfer_events INTEGER NOT NULL DEFAULT 0;
ALTER TABLE study_records ADD COLUMN duplicate_instances_count INTEGER NOT NULL DEFAULT 0;

UPDATE study_records
SET receiving_status = CASE
    WHEN lifecycle_state IN ('RECEIVED', 'WAITING_FOR_COMPLETION') THEN 'RECEIVING'
    WHEN lifecycle_state IN ('VALIDATION_FAILED', 'FAILED', 'EXCEPTION') THEN 'FAILED'
    WHEN lifecycle_state = 'INCOMPLETE' THEN 'INCOMPLETE'
    ELSE 'COMPLETED'
END;

UPDATE study_records
SET received_transfer_events = CASE
    WHEN instance_count > 0 THEN instance_count
    ELSE 1
END
WHERE received_transfer_events = 0;

-- Ensure studies pushed from PACS AE titles (such as TEAMPACS / RASTERPACS) are attributed as RIS_PACS
UPDATE study_records
SET source_type = 'RIS_PACS'
WHERE UPPER(calling_ae_title) LIKE '%PACS%'
   OR UPPER(calling_ae_title) LIKE '%RIS%'
   OR UPPER(calling_ae_title) LIKE '%ARCHIVE%';

-- Seed TEAMPACS and RASTERIPACS as RIS_PACS sources for Chennai site
INSERT INTO dicom_sources (
    id, site_id, source_name, source_type, calling_ae_title, called_ae_title,
    host_address, dicom_port, quiet_period_seconds, active
) VALUES
('22222222-6666-6666-6666-666666666666', '11111111-1111-1111-1111-111111111111',
 'Kauvery TEAMPACS Workstation Forwarder', 'RIS_PACS', 'TEAMPACS', 'DICOMHUB_AE',
 '172.17.0.155', 11112, 60, TRUE),
('22222222-7777-7777-7777-777777777777', '11111111-1111-1111-1111-111111111111',
 'Kauvery RASTERIPACS Central Node', 'RIS_PACS', 'RASTERIPACS', 'DICOMHUB_AE',
 '172.17.1.200', 11112, 60, TRUE);

-- 2. Add changed_setting column to audit_events for granular configuration change audit logging
ALTER TABLE audit_events ADD COLUMN changed_setting VARCHAR(512);

-- 3. Create dicom_node_settings table for Local AE Title, IP, Port & DICOM Connectivity/Association settings
CREATE TABLE dicom_node_settings (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL UNIQUE REFERENCES sites(id),
    local_ae_title VARCHAR(64) NOT NULL DEFAULT 'DICOMHUB_AE',
    bind_ip_address VARCHAR(128) NOT NULL DEFAULT '0.0.0.0',
    local_port INTEGER NOT NULL DEFAULT 11112,
    max_pdu_length INTEGER NOT NULL DEFAULT 16384,
    connect_timeout_ms INTEGER NOT NULL DEFAULT 3000,
    association_accept_timeout_ms INTEGER NOT NULL DEFAULT 3000,
    dimse_response_timeout_ms INTEGER NOT NULL DEFAULT 5000,
    idle_timeout_seconds INTEGER NOT NULL DEFAULT 60,
    max_concurrent_associations INTEGER NOT NULL DEFAULT 32,
    default_quiet_period_seconds INTEGER NOT NULL DEFAULT 120,
    incomplete_study_timeout_seconds INTEGER NOT NULL DEFAULT 600,
    accept_unknown_calling_ae BOOLEAN NOT NULL DEFAULT TRUE,
    require_storage_commitment BOOLEAN NOT NULL DEFAULT TRUE,
    tls_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    transfer_syntax_policy VARCHAR(255) NOT NULL DEFAULT 'ExplicitVRLittleEndian, ImplicitVRLittleEndian, JPEGLossless',
    updated_by VARCHAR(128) DEFAULT 'super.admin',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO dicom_node_settings (
    id, site_id, local_ae_title, bind_ip_address, local_port,
    max_pdu_length, connect_timeout_ms, association_accept_timeout_ms,
    dimse_response_timeout_ms, idle_timeout_seconds, max_concurrent_associations,
    default_quiet_period_seconds, incomplete_study_timeout_seconds,
    accept_unknown_calling_ae, require_storage_commitment, tls_enabled,
    transfer_syntax_policy, updated_by
) VALUES
('bbbbbbbb-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111',
 'DICOMHUB_AE', '0.0.0.0', 11112,
 16384, 3000, 3000, 5000, 60, 32, 120, 600, TRUE, TRUE, FALSE,
 'ExplicitVRLittleEndian, ImplicitVRLittleEndian, JPEGLossless', 'super.admin'),
('bbbbbbbb-2222-2222-2222-222222222222', '11111111-2222-2222-2222-222222222222',
 'DICOMHUB_TRY_AE', '0.0.0.0', 11112,
 16384, 3000, 3000, 5000, 60, 32, 120, 600, TRUE, TRUE, FALSE,
 'ExplicitVRLittleEndian, ImplicitVRLittleEndian, JPEGLossless', 'super.admin');

-- 4. Create hub_users table for User Management & DICOM Configuration Role-Based Access Control
CREATE TABLE hub_users (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    username VARCHAR(64) NOT NULL,
    full_name VARCHAR(128) NOT NULL,
    email VARCHAR(128) NOT NULL,
    department VARCHAR(128),
    user_role VARCHAR(32) NOT NULL, -- SUPER_USER, ADMINISTRATOR, RADIOLOGIST, TECHNOLOGIST, OPERATIONS, AUDITOR
    can_view_dicom_config BOOLEAN NOT NULL DEFAULT FALSE,
    can_create_dicom_config BOOLEAN NOT NULL DEFAULT FALSE,
    can_edit_dicom_config BOOLEAN NOT NULL DEFAULT FALSE,
    can_delete_dicom_config BOOLEAN NOT NULL DEFAULT FALSE,
    can_manage_users BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_hub_users_site_username ON hub_users(site_id, username);

INSERT INTO hub_users (
    id, site_id, username, full_name, email, department, user_role,
    can_view_dicom_config, can_create_dicom_config, can_edit_dicom_config, can_delete_dicom_config,
    can_manage_users, active, last_login_at
) VALUES
('cccccccc-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111',
 'super.admin', 'Dr. Aravind Ramanathan', 'aravind.r@kauveryhospital.com',
 'Enterprise Clinical Informatics', 'SUPER_USER',
 TRUE, TRUE, TRUE, TRUE, TRUE, TRUE, CURRENT_TIMESTAMP),
('cccccccc-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111',
 'pacs.admin', 'Karthik Subramanian', 'karthik.pacs@kauveryhospital.com',
 'PACS & DICOM IT Administration', 'ADMINISTRATOR',
 TRUE, TRUE, TRUE, TRUE, TRUE, TRUE, CURRENT_TIMESTAMP),
('cccccccc-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111',
 'ops.admin', 'Priya Venkatesh', 'priya.ops@kauveryhospital.com',
 'IT Operations (No Delete Permission)', 'ADMINISTRATOR',
 TRUE, TRUE, TRUE, FALSE, FALSE, TRUE, CURRENT_TIMESTAMP),
('cccccccc-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111',
 'dr.sundar', 'Dr. Sundaralingam M', 'dr.sundar@kauveryhospital.com',
 'Department of Diagnostic Radiology', 'RADIOLOGIST',
 FALSE, FALSE, FALSE, FALSE, FALSE, TRUE, CURRENT_TIMESTAMP),
('cccccccc-5555-5555-5555-555555555555', '11111111-1111-1111-1111-111111111111',
 'tech.ramesh', 'Ramesh Kumar', 'ramesh.ct@kauveryhospital.com',
 'CT / MRI Modality Console Team', 'TECHNOLOGIST',
 FALSE, FALSE, FALSE, FALSE, FALSE, TRUE, CURRENT_TIMESTAMP),
('cccccccc-6666-6666-6666-666666666666', '11111111-1111-1111-1111-111111111111',
 'dpo.reviewer', 'Meenakshi Iyer', 'dpo@kauveryhospital.com',
 'Privacy & DPDP Compliance Office', 'AUDITOR',
 FALSE, FALSE, FALSE, FALSE, FALSE, TRUE, CURRENT_TIMESTAMP),
('cccccccc-7777-7777-7777-777777777777', '11111111-2222-2222-2222-222222222222',
 'trichy.super', 'Senthil Nathan', 'senthil.try@kauveryhospital.com',
 'Trichy Site Clinical IT', 'SUPER_USER',
 TRUE, TRUE, TRUE, TRUE, TRUE, TRUE, CURRENT_TIMESTAMP);
