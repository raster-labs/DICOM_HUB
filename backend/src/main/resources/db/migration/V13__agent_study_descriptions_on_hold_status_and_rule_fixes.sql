-- =============================================================================
-- V13: AI Agent & Vendor Configuration Study Descriptions (with * Wildcard) & Modality,
--      On Hold Receiving Status for Unmatched Studies, and Router/Vendor Isolation
-- =============================================================================

-- 1. Add modality and study_descriptions (supporting * wildcard and comma-separated study descriptions) to vendor_configs
ALTER TABLE vendor_configs ADD COLUMN IF NOT EXISTS modality VARCHAR(128) NOT NULL DEFAULT 'CR,DX,RF';
ALTER TABLE vendor_configs ADD COLUMN IF NOT EXISTS study_descriptions VARCHAR(1024) NOT NULL DEFAULT 'X-RAY, XRAY, CXR, CHEST, CHEST PA, CHEST AP, KUB X-RAY, ABDOMEN X-RAY, SPINE X-RAY, PELVIS, EXTREMITY, FLUORO, RF, CR, DX, *';

-- 2. If VENDOR-GROK-XRAY (55555555-5555-5555-5555-555555555555) was overwritten by a DICOM_ROUTER creation
--    due to pre-filled form defaults, preserve that DICOM_ROUTER as a separate destination vendor and restore Grok AI
INSERT INTO vendor_configs (
    id, site_id, vendor_code, vendor_name, agent_name, use_case_name, connector_type,
    destination_type, destination_ae_title, destination_host, destination_port,
    api_base_url, auth_type, api_key_secret, ai_model_name, execution_mode,
    system_prompt_template, output_report_format, outbound_image_format, return_delivery_mode,
    modality, study_descriptions,
    deid_profile_id, pacs_destination_id, sla_turnaround_minutes, max_retry_attempts,
    acknowledgement_mode, shadow_mode, active, config_version
)
SELECT
    '55555555-7777-7777-7777-777777777777',
    site_id,
    'VENDOR-DICOM-ROUTER-' || UPPER(REGEXP_REPLACE(COALESCE(destination_ae_title, 'ROUTER'), '[^A-Za-z0-9]', '', 'g')),
    'On-Prem DICOM Router (' || COALESCE(destination_ae_title, 'MAYAM') || ')',
    COALESCE(destination_ae_title, 'MAYAM') || ' DICOM Router',
    'Rule-Based On-Premise DICOM Router C-STORE Forwarding',
    'DICOM_CSTORE',
    'DICOM_ROUTER',
    COALESCE(destination_ae_title, 'MAYAM'),
    COALESCE(destination_host, '172.17.0.155'),
    COALESCE(destination_port, 11112),
    'dicom://' || COALESCE(destination_ae_title, 'MAYAM') || '@' || COALESCE(destination_host, '172.17.0.155') || ':' || COALESCE(destination_port, 11112),
    'NONE',
    NULL,
    'DICOM_CSTORE_ROUTER',
    'LIVE_DICOM_CSTORE',
    NULL,
    'DICOM_NATIVE',
    'DICOM_NATIVE',
    'BASE64_PNG_VIA_DICOM_AE',
    'CT',
    'Brain_CT, HEAD, Brain_ Stroke, BRAIN, *',
    deid_profile_id,
    pacs_destination_id,
    sla_turnaround_minutes,
    max_retry_attempts,
    acknowledgement_mode,
    FALSE,
    TRUE,
    1
FROM vendor_configs
WHERE id = '55555555-5555-5555-5555-555555555555'
  AND destination_type = 'DICOM_ROUTER'
  AND NOT EXISTS (SELECT 1 FROM vendor_configs WHERE id = '55555555-7777-7777-7777-777777777777');

-- Link any custom CT / Brain region mappings and rules to the separated On-Prem DICOM Router if it was created
UPDATE study_region_mappings
SET target_vendor_id = '55555555-7777-7777-7777-777777777777'
WHERE id <> 'aaaaaaaa-5555-5555-5555-555555555555'
  AND target_vendor_id = '55555555-5555-5555-5555-555555555555'
  AND EXISTS (SELECT 1 FROM vendor_configs WHERE id = '55555555-7777-7777-7777-777777777777');

UPDATE routing_rules
SET vendor_id = '55555555-7777-7777-7777-777777777777',
    max_slice_thickness_mm = NULL
WHERE id <> '66666666-5555-5555-5555-555555555555'
  AND vendor_id = '55555555-5555-5555-5555-555555555555'
  AND EXISTS (SELECT 1 FROM vendor_configs WHERE id = '55555555-7777-7777-7777-777777777777');

-- Restore VENDOR-GROK-XRAY (55555555-5555-5555-5555-555555555555) to Grok AI Vision REST configuration
UPDATE vendor_configs
SET vendor_code = 'VENDOR-GROK-XRAY',
    vendor_name = 'Grok AI Vision (X-Ray Integration)',
    agent_name = COALESCE(NULLIF(agent_name, ''), 'Grok AI Vision Agent'),
    use_case_name = 'X-Ray (CR, DX, RF) Lossless Image Analysis → PDF to RIS API / Base64 Image to PACS AE',
    connector_type = 'GROK_AI_VISION',
    destination_type = 'AI_REST_API',
    destination_ae_title = 'RASTERIPACS',
    destination_host = 'api.x.ai',
    destination_port = 443,
    api_base_url = 'https://api.x.ai/v1/chat/completions',
    auth_type = 'BEARER_API_KEY',
    ai_model_name = COALESCE(NULLIF(ai_model_name, ''), 'grok-2-vision-1212'),
    execution_mode = 'MOCK_OR_LIVE_FALLBACK',
    outbound_image_format = 'BASE64_PNG_LOSSLESS',
    return_delivery_mode = 'PDF_VIA_RIS_API',
    output_report_format = 'ENCAPSULATED_PDF',
    modality = 'CR,DX,RF',
    study_descriptions = 'X-RAY, XRAY, CXR, CHEST, CHEST PA, CHEST AP, KUB X-RAY, ABDOMEN X-RAY, SPINE X-RAY, PELVIS, EXTREMITY, FLUORO, RF, CR, DX, *'
WHERE id = '55555555-5555-5555-5555-555555555555';

-- 3. Remove orphan preview-only delivery jobs created for unmatched studies and mark unmatched studies as ON_HOLD
DELETE FROM vendor_transfer_attempts
WHERE job_id IN (
    SELECT j.id
    FROM vendor_delivery_jobs j
    JOIN study_records s ON s.id = j.study_id
    WHERE j.routing_rule_id IS NULL
      AND s.lifecycle_state = 'UNMATCHED_NO_RULE'
      AND j.status IN ('DEIDENTIFICATION_PENDING', 'DEIDENTIFICATION_FAILED')
);

DELETE FROM vendor_delivery_jobs
WHERE routing_rule_id IS NULL
  AND status IN ('DEIDENTIFICATION_PENDING', 'DEIDENTIFICATION_FAILED')
  AND study_id IN (
      SELECT id FROM study_records WHERE lifecycle_state = 'UNMATCHED_NO_RULE'
  );

UPDATE study_records
SET receiving_status = 'ON_HOLD'
WHERE lifecycle_state = 'UNMATCHED_NO_RULE'
  AND receiving_status NOT IN ('RECEIVING', 'FAILED', 'INCOMPLETE');
