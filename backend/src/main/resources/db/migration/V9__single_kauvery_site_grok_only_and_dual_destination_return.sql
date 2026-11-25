-- =============================================================================
-- V9: Single Kauvery Hospital Deployment, Purge Non-Grok Demo Data,
--     Lossless Outbound Image Format Configuration (DICOM / Base64 PNG / Base64 JPEG),
--     and Dual Destination Return (PDF via RIS API vs Base64 PNG/JPEG via PACS AE/IP/Port)
-- =============================================================================

-- 1. Add Outbound Image Transfer Format & Return Delivery Mode to vendor_configs
ALTER TABLE vendor_configs ADD COLUMN outbound_image_format VARCHAR(64) NOT NULL DEFAULT 'BASE64_PNG_LOSSLESS';
ALTER TABLE vendor_configs ADD COLUMN return_delivery_mode VARCHAR(64) NOT NULL DEFAULT 'PDF_VIA_RIS_API';

-- 2. Add Returned Image Storage Path & DICOM AE C-STORE Return Status to ai_result_records
ALTER TABLE ai_result_records ADD COLUMN image_storage_path VARCHAR(1024);
ALTER TABLE ai_result_records ADD COLUMN dicom_ae_return_status VARCHAR(128);

-- 3. Clear all accumulated demo/test runtime records so only fresh Grok AI X-Ray test data is kept
DELETE FROM ris_uploaded_reports;
DELETE FROM ai_result_records;
DELETE FROM vendor_transfer_attempts;
DELETE FROM vendor_delivery_jobs;
DELETE FROM pseudonym_mappings;
DELETE FROM instance_records;
DELETE FROM study_records;
DELETE FROM audit_events;

-- 4. Remove second demo site (Trichy: 11111111-2222-2222-2222-222222222222) and all its child configuration rows
DELETE FROM ris_report_endpoints WHERE site_id <> '11111111-1111-1111-1111-111111111111';
DELETE FROM cleanup_policies WHERE site_id <> '11111111-1111-1111-1111-111111111111';
DELETE FROM routing_rules WHERE site_id <> '11111111-1111-1111-1111-111111111111';
DELETE FROM study_region_mappings WHERE site_id <> '11111111-1111-1111-1111-111111111111';
DELETE FROM vendor_configs WHERE site_id <> '11111111-1111-1111-1111-111111111111';
DELETE FROM deid_profiles WHERE site_id <> '11111111-1111-1111-1111-111111111111';
DELETE FROM pacs_destinations WHERE site_id <> '11111111-1111-1111-1111-111111111111';
DELETE FROM dicom_sources WHERE site_id <> '11111111-1111-1111-1111-111111111111';
DELETE FROM dicom_node_settings WHERE site_id <> '11111111-1111-1111-1111-111111111111';
DELETE FROM hub_users WHERE site_id <> '11111111-1111-1111-1111-111111111111';
DELETE FROM llm_prompt_templates WHERE site_id <> '11111111-1111-1111-1111-111111111111';
DELETE FROM llm_approved_models WHERE site_id <> '11111111-1111-1111-1111-111111111111';
DELETE FROM sites WHERE id <> '11111111-1111-1111-1111-111111111111';

-- Update single hospital site name for One Kauvery Hospital deployment
UPDATE sites
SET site_code = 'KAUVERY-HOSP',
    site_name = 'Kauvery Hospital'
WHERE id = '11111111-1111-1111-1111-111111111111';

-- 5. Remove non-Grok demo vendors (UroStone, NeuroScan, DeepMRI) and their demo rules/mappings/sources
DELETE FROM cleanup_policies
WHERE vendor_id IS NOT NULL
  AND vendor_id <> '55555555-5555-5555-5555-555555555555';

DELETE FROM routing_rules
WHERE vendor_id <> '55555555-5555-5555-5555-555555555555';

DELETE FROM study_region_mappings
WHERE target_vendor_id IS NULL
   OR target_vendor_id <> '55555555-5555-5555-5555-555555555555';

DELETE FROM vendor_configs
WHERE id <> '55555555-5555-5555-5555-555555555555';

DELETE FROM dicom_sources
WHERE id IN (
    '22222222-1111-1111-1111-111111111111',
    '22222222-2222-2222-2222-222222222222',
    '22222222-3333-3333-3333-333333333333'
);

-- 6. Update Grok AI X-Ray Vendor, Routing Rule, and Dual-Mode PACS/RIS Destination
UPDATE vendor_configs
SET vendor_name = 'Grok AI Vision (X-Ray Integration)',
    use_case_name = 'X-Ray (CR, DX, RF) Lossless Image Analysis → PDF to RIS API / Base64 Image to PACS AE',
    outbound_image_format = 'BASE64_PNG_LOSSLESS',
    return_delivery_mode = 'PDF_VIA_RIS_API',
    output_report_format = 'ENCAPSULATED_PDF',
    destination_ae_title = 'RASTERIPACS',
    destination_host = '172.17.1.200',
    destination_port = 11112
WHERE id = '55555555-5555-5555-5555-555555555555';

UPDATE routing_rules
SET rule_name = 'Rule 1 – Grok AI X-Ray Auto-Route (CR, DX, RF)',
    priority_order = 1,
    modality = 'CR,DX,RF',
    canonical_region = 'X-Ray Study',
    max_slice_thickness_mm = 10.0
WHERE id = '66666666-5555-5555-5555-555555555555';

UPDATE pacs_destinations
SET destination_name = 'Kauvery PACS & RIS Return Destination (Dual Config: AE/IP/Port + UploadReportServlet)',
    ae_title = 'RASTERIPACS',
    host_address = '172.17.1.200',
    dicom_port = 11112,
    report_integration_strategy = 'TELERAD_UPLOAD_REPORT_SERVLET',
    report_endpoint_url = 'http://172.17.1.100:8080/telerad/UploadReportServlet'
WHERE id = '33333333-1111-1111-1111-111111111111';
