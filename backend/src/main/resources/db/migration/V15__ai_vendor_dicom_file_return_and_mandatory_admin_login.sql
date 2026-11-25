-- =============================================================================
-- V15: AI Vendor DICOM (.dcm) File Return & RIS Application DICOM Support,
--      plus Mandatory Login with admin / admin Credentials
-- =============================================================================

-- 1. Add password column to hub_users and seed mandatory admin / admin SUPER_USER account
ALTER TABLE hub_users ADD COLUMN IF NOT EXISTS password VARCHAR(255) NOT NULL DEFAULT 'admin';

INSERT INTO hub_users (
    id, site_id, username, password, full_name, email, department, user_role,
    can_view_dicom_config, can_create_dicom_config, can_edit_dicom_config, can_delete_dicom_config,
    can_manage_users, active, last_login_at
)
SELECT
    'cccccccc-0000-0000-0000-000000000000',
    '11111111-1111-1111-1111-111111111111',
    'admin',
    'admin',
    'System Administrator',
    'admin@kauveryhospital.com',
    'Enterprise Clinical Informatics & PACS Administration',
    'SUPER_USER',
    TRUE, TRUE, TRUE, TRUE, TRUE, TRUE, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM hub_users
    WHERE site_id = '11111111-1111-1111-1111-111111111111'
      AND LOWER(username) = 'admin'
);

UPDATE hub_users
SET password = 'admin',
    user_role = 'SUPER_USER',
    can_view_dicom_config = TRUE,
    can_create_dicom_config = TRUE,
    can_edit_dicom_config = TRUE,
    can_delete_dicom_config = TRUE,
    can_manage_users = TRUE,
    active = TRUE
WHERE site_id = '11111111-1111-1111-1111-111111111111'
  AND LOWER(username) = 'admin';

-- 2. Extend ai_result_records to track AI vendor returned DICOM (.dcm) files and RIS DICOM delivery
ALTER TABLE ai_result_records ADD COLUMN IF NOT EXISTS dicom_storage_path VARCHAR(1024);
ALTER TABLE ai_result_records ADD COLUMN IF NOT EXISTS dicom_file_name VARCHAR(255);
ALTER TABLE ai_result_records ADD COLUMN IF NOT EXISTS dicom_file_size_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_result_records ADD COLUMN IF NOT EXISTS ris_dicom_upload_status VARCHAR(64);

-- 3. Extend ris_uploaded_reports to support DICOM (.dcm) and dual PDF + DICOM uploads in RIS
ALTER TABLE ris_uploaded_reports ADD COLUMN IF NOT EXISTS file_format VARCHAR(32) NOT NULL DEFAULT 'PDF';
ALTER TABLE ris_uploaded_reports ADD COLUMN IF NOT EXISTS dicom_file_name VARCHAR(255);
ALTER TABLE ris_uploaded_reports ADD COLUMN IF NOT EXISTS dicom_file_path VARCHAR(1024);
ALTER TABLE ris_uploaded_reports ADD COLUMN IF NOT EXISTS dicom_file_size_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ris_uploaded_reports ADD COLUMN IF NOT EXISTS dicom_ae_status VARCHAR(128);

-- 4. Extend ris_report_endpoints with DICOM file multipart and RIS/PACS DICOM AE forwarding options
ALTER TABLE ris_report_endpoints ADD COLUMN IF NOT EXISTS dicom_part_name VARCHAR(64) NOT NULL DEFAULT 'dicomFile';
ALTER TABLE ris_report_endpoints ADD COLUMN IF NOT EXISTS support_dicom_upload BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE ris_report_endpoints ADD COLUMN IF NOT EXISTS forward_dicom_to_pacs_ae BOOLEAN NOT NULL DEFAULT TRUE;
