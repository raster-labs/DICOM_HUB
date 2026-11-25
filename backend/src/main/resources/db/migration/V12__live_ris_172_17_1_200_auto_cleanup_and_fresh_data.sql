-- =============================================================================
-- V12: Live RIS Server Endpoint (172.17.1.200:8080/telerad/UploadReportServlet),
--      Disable Built-in Mock by Default, Frequent Auto-Removal & Retention Settings,
--      and Fresh Study Data Reset (Preserving All Previous Configurations)
-- =============================================================================

-- 1. Create record_retention_settings table for manual & frequent automatic removal of old study records and logs
CREATE TABLE record_retention_settings (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL UNIQUE REFERENCES sites(id),
    auto_remove_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    retention_max_age_minutes INTEGER NOT NULL DEFAULT 60,
    check_interval_minutes INTEGER NOT NULL DEFAULT 5,
    remove_study_logs_and_audits BOOLEAN NOT NULL DEFAULT TRUE,
    remove_disk_files BOOLEAN NOT NULL DEFAULT TRUE,
    protect_active_receiving_studies BOOLEAN NOT NULL DEFAULT TRUE,
    last_auto_run_at TIMESTAMP WITH TIME ZONE,
    last_removed_studies_count INTEGER NOT NULL DEFAULT 0,
    last_removed_logs_count INTEGER NOT NULL DEFAULT 0,
    total_removed_studies_count BIGINT NOT NULL DEFAULT 0,
    total_removed_logs_count BIGINT NOT NULL DEFAULT 0,
    updated_by VARCHAR(128) DEFAULT 'super.admin',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO record_retention_settings (
    id, site_id, auto_remove_enabled, retention_max_age_minutes, check_interval_minutes,
    remove_study_logs_and_audits, remove_disk_files, protect_active_receiving_studies,
    last_removed_studies_count, last_removed_logs_count,
    total_removed_studies_count, total_removed_logs_count, updated_by
) VALUES (
    'eeeeeeee-1111-1111-1111-111111111111',
    '11111111-1111-1111-1111-111111111111',
    TRUE, 60, 5, TRUE, TRUE, TRUE, 0, 0, 0, 0, 'super.admin'
);

-- 2. Default RIS endpoint IP to 172.17.1.200 (only if still pointing to old 172.17.1.100 default)
--    and disable built-in mock so reports are always dispatched live to the configured RIS server
UPDATE ris_report_endpoints
SET host_address = CASE WHEN host_address = '172.17.1.100' THEN '172.17.1.200' ELSE host_address END,
    target_url = CASE
        WHEN target_url = 'http://172.17.1.100:8080/telerad/UploadReportServlet'
        THEN 'http://172.17.1.200:8080/telerad/UploadReportServlet'
        ELSE target_url
    END,
    default_report_type = 'Preliminary Report',
    use_builtin_mock = FALSE;

UPDATE pacs_destinations
SET host_address = CASE WHEN host_address = '172.17.1.100' THEN '172.17.1.200' ELSE host_address END,
    report_endpoint_url = CASE
        WHEN report_endpoint_url = 'http://172.17.1.100:8080/telerad/UploadReportServlet'
        THEN 'http://172.17.1.200:8080/telerad/UploadReportServlet'
        ELSE report_endpoint_url
    END;

-- 3. Remove all old study data and runtime logs while preserving all configurations
DELETE FROM ris_uploaded_reports;
DELETE FROM ai_result_records;
DELETE FROM vendor_transfer_attempts;
DELETE FROM vendor_delivery_jobs;
DELETE FROM pseudonym_mappings;
DELETE FROM instance_records;
DELETE FROM study_records;
DELETE FROM audit_events;
