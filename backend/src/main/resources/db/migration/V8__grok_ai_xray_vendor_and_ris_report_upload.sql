-- =============================================================================
-- V7: Grok AI Vision X-Ray (CR, DX, RF) Vendor API Configuration,
--     Re-identified Clinical PDF Report Generation, and RIS / Telerad
--     UploadReportServlet Multipart Endpoint Integration
-- =============================================================================

-- 1. Extend vendor_configs with AI Vendor REST / Model API details
ALTER TABLE vendor_configs ADD COLUMN api_key_secret VARCHAR(512);
ALTER TABLE vendor_configs ADD COLUMN ai_model_name VARCHAR(128) NOT NULL DEFAULT 'grok-2-vision-1212';
ALTER TABLE vendor_configs ADD COLUMN execution_mode VARCHAR(64) NOT NULL DEFAULT 'MOCK_OR_LIVE_FALLBACK';
ALTER TABLE vendor_configs ADD COLUMN system_prompt_template TEXT;
ALTER TABLE vendor_configs ADD COLUMN output_report_format VARCHAR(64) NOT NULL DEFAULT 'ENCAPSULATED_PDF';

UPDATE vendor_configs
SET system_prompt_template = 'Analyze the de-identified diagnostic medical image for modality={{modality}}, bodyPart={{bodyPart}}, studyDescription={{studyDescription}}, patientAge={{patientAge}}, patientSex={{patientSex}}. Return strictly valid JSON with keys: findings (array of objects with region, observation, severity, confidence), anatomical_summary (string), impression_draft (string), and urgency_level (ROUTINE, URGENT, CRITICAL).'
WHERE system_prompt_template IS NULL;

-- 2. Extend ai_result_records with PDF report storage and RIS UploadReportServlet delivery telemetry
ALTER TABLE ai_result_records ADD COLUMN pdf_storage_path VARCHAR(1024);
ALTER TABLE ai_result_records ADD COLUMN ris_upload_status VARCHAR(64);
ALTER TABLE ai_result_records ADD COLUMN ris_upload_response TEXT;

-- 3. Create ris_report_endpoints table for configurable RIS / Telerad UploadReportServlet integration per site
CREATE TABLE ris_report_endpoints (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    endpoint_name VARCHAR(128) NOT NULL,
    host_address VARCHAR(128) NOT NULL DEFAULT '172.17.1.100',
    port INTEGER NOT NULL DEFAULT 8080,
    context_path VARCHAR(255) NOT NULL DEFAULT '/telerad/UploadReportServlet',
    target_url VARCHAR(512) NOT NULL DEFAULT 'http://172.17.1.100:8080/telerad/UploadReportServlet',
    auth_username VARCHAR(128) NOT NULL DEFAULT 'sonocare',
    auth_password VARCHAR(255) NOT NULL DEFAULT 'sonocare123',
    json_part_name VARCHAR(64) NOT NULL DEFAULT 'reportData',
    file_part_name VARCHAR(64) NOT NULL DEFAULT 'pdfFile',
    default_report_type VARCHAR(64) NOT NULL DEFAULT 'Study Report',
    use_builtin_mock BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by VARCHAR(128) DEFAULT 'super.admin',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_ris_report_endpoints_site ON ris_report_endpoints(site_id, active);

-- 4. Create ris_uploaded_reports table to store & track PDF reports uploaded via UploadReportServlet
CREATE TABLE ris_uploaded_reports (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    job_id UUID REFERENCES vendor_delivery_jobs(id),
    study_id UUID REFERENCES study_records(id),
    study_iuid VARCHAR(128) NOT NULL,
    report_type VARCHAR(64) NOT NULL,
    drafted_time VARCHAR(32) NOT NULL,
    drafted_by VARCHAR(128) NOT NULL,
    reviewed_time VARCHAR(32) NOT NULL,
    reviewed_by VARCHAR(128) NOT NULL,
    pdf_file_name VARCHAR(255) NOT NULL,
    pdf_file_path VARCHAR(1024) NOT NULL,
    pdf_file_size_bytes BIGINT NOT NULL,
    target_endpoint_url VARCHAR(512) NOT NULL,
    http_status INTEGER NOT NULL,
    response_json TEXT NOT NULL,
    replaced_previous BOOLEAN NOT NULL DEFAULT FALSE,
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_ris_uploaded_reports_site_study ON ris_uploaded_reports(site_id, study_iuid);

-- 5. Seed RIS / Telerad UploadReportServlet endpoint config for Chennai & Trichy sites
INSERT INTO ris_report_endpoints (
    id, site_id, endpoint_name, host_address, port, context_path, target_url,
    auth_username, auth_password, json_part_name, file_part_name, default_report_type,
    use_builtin_mock, active, updated_by
) VALUES
('dddddddd-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111',
 'Kauvery Telerad / RIS UploadReportServlet (Chennai)', '172.17.1.100', 8080,
 '/telerad/UploadReportServlet', 'http://172.17.1.100:8080/telerad/UploadReportServlet',
 'sonocare', 'sonocare123', 'reportData', 'pdfFile', 'Study Report',
 TRUE, TRUE, 'super.admin'),
('dddddddd-2222-2222-2222-222222222222', '11111111-2222-2222-2222-222222222222',
 'Kauvery Telerad / RIS UploadReportServlet (Trichy)', '10.20.10.50', 8080,
 '/telerad/UploadReportServlet', 'http://10.20.10.50:8080/telerad/UploadReportServlet',
 'sonocare', 'sonocare123', 'reportData', 'pdfFile', 'Study Report',
 TRUE, TRUE, 'trichy.super');

-- 6. Seed Digital X-Ray Console Source for Chennai Site
INSERT INTO dicom_sources (
    id, site_id, source_name, source_type, calling_ae_title, called_ae_title,
    host_address, dicom_port, quiet_period_seconds, active
) VALUES
('22222222-8888-8888-8888-888888888888', '11111111-1111-1111-1111-111111111111',
 'Digital X-Ray / DR / CR / RF Console (Auto-Route)', 'MODALITY', 'KAUVERY_XR01', 'DICOMHUB_AE',
 '10.10.20.15', 104, 30, TRUE);

-- 7. Seed Grok AI X-Ray Vendor Configuration for Chennai & Trichy sites
INSERT INTO vendor_configs (
    id, site_id, vendor_code, vendor_name, use_case_name, connector_type,
    destination_type, destination_ae_title, destination_host, destination_port,
    api_base_url, auth_type, api_key_secret, ai_model_name, execution_mode,
    system_prompt_template, output_report_format,
    deid_profile_id, pacs_destination_id, sla_turnaround_minutes, max_retry_attempts,
    acknowledgement_mode, shadow_mode, active, config_version
) VALUES
('55555555-5555-5555-5555-555555555555', '11111111-1111-1111-1111-111111111111',
 'VENDOR-GROK-XRAY', 'Grok AI Vision (X-Ray PDF Report Generator)',
 'X-Ray (CR, DX, RF) Automated AI Findings & RIS PDF Report Delivery',
 'GROK_AI_VISION', 'AI_REST_API', 'GROK_XRAY_AI', 'api.x.ai', 443,
 'https://api.x.ai/v1/chat/completions', 'BEARER_API_KEY', 'xai-mock-key-configure-in-ui',
 'grok-2-vision-1212', 'MOCK_OR_LIVE_FALLBACK',
 'You are an expert radiology draft assistant analyzing a de-identified X-Ray image (modality={{modality}}, bodyPart={{bodyPart}}, studyDescription={{studyDescription}}, patientAge={{patientAge}}, patientSex={{patientSex}}). Inspect lung fields, cardiac silhouette, costophrenic angles, diaphragm, bones/skeletal structures, and soft tissues. Return strictly valid JSON with keys: findings (array of {region, observation, severity, confidence}), anatomical_summary (string), impression_draft (string), and urgency_level (ROUTINE, URGENT, CRITICAL).',
 'ENCAPSULATED_PDF',
 '44444444-1111-1111-1111-111111111111', '33333333-1111-1111-1111-111111111111',
 5, 5, 'ALL_INSTANCES_INGESTED', FALSE, TRUE, 1),
('55555555-6666-6666-6666-666666666666', '11111111-2222-2222-2222-222222222222',
 'VENDOR-GROK-XRAY', 'Grok AI Vision (X-Ray PDF Report Generator - Trichy)',
 'X-Ray (CR, DX, RF) Automated AI Findings & RIS PDF Report Delivery',
 'GROK_AI_VISION', 'AI_REST_API', 'GROK_XRAY_TRY', 'api.x.ai', 443,
 'https://api.x.ai/v1/chat/completions', 'BEARER_API_KEY', 'xai-mock-key-configure-in-ui',
 'grok-2-vision-1212', 'MOCK_OR_LIVE_FALLBACK',
 'You are an expert radiology draft assistant analyzing a de-identified X-Ray image (modality={{modality}}, bodyPart={{bodyPart}}, studyDescription={{studyDescription}}, patientAge={{patientAge}}, patientSex={{patientSex}}). Return strictly valid JSON with keys: findings, anatomical_summary, impression_draft, and urgency_level.',
 'ENCAPSULATED_PDF',
 '44444444-2222-2222-2222-222222222222', '33333333-2222-2222-2222-222222222222',
 5, 5, 'ALL_INSTANCES_INGESTED', FALSE, TRUE, 1);

-- 8. Seed Editable Study Region Mapping & Routing Rule for X-Ray (CR, DX, RF)
INSERT INTO study_region_mappings (
    id, site_id, canonical_region, modality, body_part, study_descriptions, target_vendor_id, active
) VALUES
('aaaaaaaa-5555-5555-5555-555555555555', '11111111-1111-1111-1111-111111111111',
 'X-Ray Study', 'CR,DX,RF', 'CHEST,ABDOMEN,SPINE,EXTREMITY',
 'X-RAY, XRAY, CXR, CHEST, CHEST PA, CHEST AP, KUB X-RAY, ABDOMEN X-RAY, SPINE X-RAY, PELVIS, EXTREMITY, FLUORO, RF, CR, DX, *',
 '55555555-5555-5555-5555-555555555555', TRUE),
('aaaaaaaa-6666-6666-6666-666666666666', '11111111-2222-2222-2222-222222222222',
 'X-Ray Study', 'CR,DX,RF', 'CHEST,ABDOMEN,SPINE,EXTREMITY',
 'X-RAY, XRAY, CXR, CHEST, CHEST PA, CHEST AP, KUB X-RAY, ABDOMEN X-RAY, SPINE X-RAY, FLUORO, *',
 '55555555-6666-6666-6666-666666666666', TRUE);

INSERT INTO routing_rules (
    id, site_id, rule_name, priority_order, modality, canonical_region,
    study_description_pattern, body_part_pattern, match_operator, vendor_id,
    required_series_pattern, max_slice_thickness_mm, allow_multi_vendor, active, rule_version
) VALUES
('66666666-5555-5555-5555-555555555555', '11111111-1111-1111-1111-111111111111',
 'Rule 4 – Grok AI X-Ray Auto-Route (CR, DX, RF)', 10, 'CR,DX,RF', 'X-Ray Study',
 'X-RAY, XRAY, CXR, CHEST, CHEST PA, CHEST AP, KUB, ABDOMEN, SPINE, PELVIS, EXTREMITY, FLUORO, *',
 NULL, 'CONTAINS', '55555555-5555-5555-5555-555555555555',
 NULL, NULL, TRUE, TRUE, 1),
('66666666-6666-6666-6666-666666666666', '11111111-2222-2222-2222-222222222222',
 'Trichy Rule 2 – Grok AI X-Ray Auto-Route (CR, DX, RF)', 10, 'CR,DX,RF', 'X-Ray Study',
 'X-RAY, XRAY, CXR, CHEST, CHEST PA, CHEST AP, KUB, ABDOMEN, SPINE, FLUORO, *',
 NULL, 'CONTAINS', '55555555-6666-6666-6666-666666666666',
 NULL, NULL, TRUE, TRUE, 1);

-- 9. Seed Independent Cleanup Policy for Grok AI Vision
INSERT INTO cleanup_policies (
    id, site_id, vendor_id, target_category, retention_hours_after_upload,
    retention_hours_after_return, schedule_cron, dry_run_only, legal_hold_active, active
) VALUES
('77777777-5555-5555-5555-555555555555', '11111111-1111-1111-1111-111111111111',
 '55555555-5555-5555-5555-555555555555', 'VENDOR_WORKING_COPY', 24, 24, '0 15 2 * * *', FALSE, FALSE, TRUE),
('77777777-6666-6666-6666-666666666666', '11111111-2222-2222-2222-222222222222',
 '55555555-6666-6666-6666-666666666666', 'VENDOR_WORKING_COPY', 24, 24, '0 15 2 * * *', FALSE, FALSE, TRUE);
