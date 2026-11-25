-- =============================================================================
-- V3: Seed Multi-Hospital Tenant Isolation (Trichy Site) & Phase 2 LLM Registry
-- =============================================================================

INSERT INTO sites (id, site_code, site_name, city, active)
VALUES ('11111111-2222-2222-2222-222222222222', 'KAUVERY-TRY-02', 'Kauvery Hospital – Trichy Cantonment', 'Tiruchirappalli', TRUE);

INSERT INTO dicom_sources (id, site_id, source_name, source_type, calling_ae_title, called_ae_title, host_address, dicom_port, quiet_period_seconds, active)
VALUES
('22222222-4444-4444-4444-444444444444', '11111111-2222-2222-2222-222222222222', 'Trichy CT Console 1 (Auto-Route)', 'MODALITY', 'KAUVERY_TRY_CT01', 'DICOMHUB_AE', '10.20.20.11', 104, 120, TRUE),
('22222222-5555-5555-5555-555555555555', '11111111-2222-2222-2222-222222222222', 'Trichy PACS Forwarder', 'RIS_PACS', 'KAUVERY_TRY_PACS', 'DICOMHUB_AE', '10.20.10.50', 11112, 60, TRUE);

INSERT INTO pacs_destinations (id, site_id, destination_name, ae_title, host_address, dicom_port, report_integration_strategy, report_endpoint_url, is_default_for_site, active)
VALUES
('33333333-2222-2222-2222-222222222222', '11111111-2222-2222-2222-222222222222', 'Kauvery Trichy PACS & HL7 RIS', 'KAUVERY_TRY_PACS_SCP', '10.20.10.50', 11112, 'HL7_V2_ORU', 'mllp://10.20.10.50:2575', TRUE, TRUE);

INSERT INTO deid_profiles (id, site_id, profile_code, profile_name, profile_version, retain_patient_age_sex, retain_device_acquisition_params, date_shift_days, block_on_burned_in_text, strip_private_tags, active)
VALUES
('44444444-2222-2222-2222-222222222222', '11111111-2222-2222-2222-222222222222', 'PS315-KAUVERY-TRY', 'DICOM PS3.15 Basic Confidentiality – Trichy Site', 1, TRUE, TRUE, -60, TRUE, TRUE, TRUE);

INSERT INTO vendor_configs (id, site_id, vendor_code, vendor_name, use_case_name, connector_type, api_base_url, auth_type, deid_profile_id, pacs_destination_id, sla_turnaround_minutes, max_retry_attempts, acknowledgement_mode, shadow_mode, active, config_version)
VALUES
('55555555-4444-4444-4444-444444444444', '11111111-2222-2222-2222-222222222222', 'VENDOR-KUB-AI', 'UroStone AI Analytics (Trichy)', 'CT KUB Renal & Ureteric Calculus Detection', 'REST_MULTIPART', 'https://api.urostone-ai.example.com/v1/studies', 'OAUTH2_CLIENT_CREDENTIALS', '44444444-2222-2222-2222-222222222222', '33333333-2222-2222-2222-222222222222', 15, 5, 'ALL_INSTANCES_INGESTED', FALSE, TRUE, 1);

INSERT INTO routing_rules (id, site_id, rule_name, priority_order, modality, study_description_pattern, body_part_pattern, match_operator, vendor_id, required_series_pattern, max_slice_thickness_mm, allow_multi_vendor, active, rule_version)
VALUES
('66666666-4444-4444-4444-444444444444', '11111111-2222-2222-2222-222222222222', 'Trichy Rule 1 – CT KUB Auto-Route', 1, 'CT', 'KUB', 'ABDOMEN', 'CONTAINS', '55555555-4444-4444-4444-444444444444', 'AXIAL', 3.0, TRUE, TRUE, 1);

INSERT INTO cleanup_policies (id, site_id, vendor_id, target_category, retention_hours_after_upload, retention_hours_after_return, schedule_cron, dry_run_only, legal_hold_active, active)
VALUES
('77777777-4444-4444-4444-444444444444', '11111111-2222-2222-2222-222222222222', '55555555-4444-4444-4444-444444444444', 'VENDOR_WORKING_COPY', 24, 24, '0 0 2 * * *', FALSE, FALSE, TRUE);

-- Phase 2 Multimodal LLM Approved Registry & Versioned Prompt Templates (BRD Section 6.8, FR-38 to FR-45)
INSERT INTO llm_approved_models (id, site_id, provider_name, model_identifier, pinned_version, cdsco_clearance_status, india_region_hosted, approved_by_radiology, approved_by_legal, active)
VALUES
('88888888-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'Kauvery Approved Clinical LLM Gateway (Mumbai Region)', 'med-gemini-pro-vision-in', '2026.03-pinned', 'DRAFT_ASSIST_ONLY', TRUE, TRUE, TRUE, TRUE),
('88888888-2222-2222-2222-222222222222', '11111111-2222-2222-2222-222222222222', 'Kauvery Trichy Clinical LLM Gateway (Mumbai Region)', 'med-gemini-pro-vision-in', '2026.03-pinned', 'DRAFT_ASSIST_ONLY', TRUE, TRUE, TRUE, TRUE);

INSERT INTO llm_prompt_templates (id, site_id, template_code, use_case_name, template_version, window_preset, max_slices, prompt_body, findings_json_schema, shadow_mode, approved_by)
VALUES
('99999999-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'LLM-CT-KUB-V1', 'CT KUB Structured Calculus Draft Assist', 1, 'ABDOMEN_SOFT_TISSUE_W400_L40', 16,
 'Analyze the key axial non-contrast CT KUB slices for modality={{modality}}, bodyPart={{bodyPart}}, patientAge={{patientAge}}, patientSex={{patientSex}}, sliceThicknessMm={{sliceThicknessMm}}. Return strictly valid JSON matching the schema with renal/ureteric calculus location, size_mm, density_hu, hydronephrosis status, and confidence.',
 '{"required":["findings","impression_draft"],"properties":{"findings":{"type":"array"},"impression_draft":{"type":"string"}}}',
 TRUE, 'Dr. S. Raghavan (Head of Radiology)'),
('99999999-2222-2222-2222-222222222222', '11111111-2222-2222-2222-222222222222', 'LLM-CT-KUB-TRY-V1', 'CT KUB Structured Calculus Draft Assist (Trichy)', 1, 'ABDOMEN_SOFT_TISSUE_W400_L40', 16,
 'Analyze the key axial non-contrast CT KUB slices for modality={{modality}}, bodyPart={{bodyPart}}, patientAge={{patientAge}}, patientSex={{patientSex}}, sliceThicknessMm={{sliceThicknessMm}}. Return strictly valid JSON matching the schema with renal/ureteric calculus location, size_mm, density_hu, hydronephrosis status, and confidence.',
 '{"required":["findings","impression_draft"],"properties":{"findings":{"type":"array"},"impression_draft":{"type":"string"}}}',
 TRUE, 'Dr. K. Sundaram (Trichy Radiology Lead)');
