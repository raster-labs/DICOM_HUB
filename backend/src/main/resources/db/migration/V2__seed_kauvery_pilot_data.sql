-- =============================================================================
-- V2: Seed Initial Kauvery Hospital Pilot Site, Sources, Vendors, and Rules
-- =============================================================================

INSERT INTO sites (id, site_code, site_name, city, active)
VALUES ('11111111-1111-1111-1111-111111111111', 'KAUVERY-CHN-01', 'Kauvery Hospital – Chennai Flagship', 'Chennai', TRUE);

INSERT INTO dicom_sources (id, site_id, source_name, source_type, calling_ae_title, called_ae_title, host_address, dicom_port, quiet_period_seconds, active)
VALUES
('22222222-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'CT Scanner Console 1 (Auto-Route)', 'MODALITY', 'KAUVERY_CT01', 'DICOMHUB_AE', '10.10.20.11', 104, 120, TRUE),
('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'MRI Scanner Console 1 (Auto-Route)', 'MODALITY', 'KAUVERY_MR01', 'DICOMHUB_AE', '10.10.20.12', 104, 120, TRUE),
('22222222-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', 'Kauvery Central PACS Fallback Forwarder', 'RIS_PACS', 'KAUVERY_PACS', 'DICOMHUB_AE', '10.10.10.50', 11112, 60, TRUE);

INSERT INTO pacs_destinations (id, site_id, destination_name, ae_title, host_address, dicom_port, report_integration_strategy, report_endpoint_url, is_default_for_site, active)
VALUES
('33333333-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'Kauvery Primary PACS Archive & RIS', 'KAUVERY_PACS_SCP', '10.10.10.50', 11112, 'DICOM_SR_NATIVE', 'http://10.10.10.50:8080/ris/api/v1/draft-reports', TRUE, TRUE);

INSERT INTO deid_profiles (id, site_id, profile_code, profile_name, profile_version, retain_patient_age_sex, retain_device_acquisition_params, date_shift_days, block_on_burned_in_text, strip_private_tags, active)
VALUES
('44444444-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'PS315-KAUVERY-STD', 'DICOM PS3.15 Basic Confidentiality + Clinical Acquisition Retention', 1, TRUE, TRUE, -42, TRUE, TRUE, TRUE);

INSERT INTO vendor_configs (id, site_id, vendor_code, vendor_name, use_case_name, connector_type, api_base_url, auth_type, deid_profile_id, pacs_destination_id, sla_turnaround_minutes, max_retry_attempts, acknowledgement_mode, shadow_mode, active, config_version)
VALUES
('55555555-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'VENDOR-KUB-AI', 'UroStone AI Analytics', 'CT KUB Renal & Ureteric Calculus Detection', 'REST_MULTIPART', 'https://api.urostone-ai.example.com/v1/studies', 'OAUTH2_CLIENT_CREDENTIALS', '44444444-1111-1111-1111-111111111111', '33333333-1111-1111-1111-111111111111', 15, 5, 'ALL_INSTANCES_INGESTED', FALSE, TRUE, 1),
('55555555-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'VENDOR-NEURO-CT', 'NeuroScan Stroke AI', 'CT Brain ICH & Midline Shift Triage', 'REST_MULTIPART', 'https://api.neuroscan-ai.example.com/v2/dicom', 'MTLS', '44444444-1111-1111-1111-111111111111', '33333333-1111-1111-1111-111111111111', 10, 5, 'ALL_INSTANCES_INGESTED', FALSE, TRUE, 1),
('55555555-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', 'VENDOR-NEURO-MR', 'DeepMRI Brain Volumetry', 'MRI Brain Lesion & Volumetry', 'DICOMWEB_STOW', 'https://stow.deepmri.example.com/dicomweb', 'OAUTH2_CLIENT_CREDENTIALS', '44444444-1111-1111-1111-111111111111', '33333333-1111-1111-1111-111111111111', 15, 5, 'ALL_INSTANCES_INGESTED', TRUE, TRUE, 1);

INSERT INTO routing_rules (id, site_id, rule_name, priority_order, modality, study_description_pattern, body_part_pattern, match_operator, vendor_id, required_series_pattern, max_slice_thickness_mm, allow_multi_vendor, active, rule_version)
VALUES
('66666666-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'Rule 1 – CT Brain Auto-Route', 1, 'CT', 'BRAIN', 'HEAD', 'CONTAINS', '55555555-2222-2222-2222-222222222222', 'THIN', 5.0, TRUE, TRUE, 1),
('66666666-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'Rule 2 – CT KUB Auto-Route (Pilot)', 2, 'CT', 'KUB', 'ABDOMEN', 'CONTAINS', '55555555-1111-1111-1111-111111111111', 'AXIAL', 3.0, TRUE, TRUE, 1),
('66666666-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', 'Rule 3 – MRI Brain Shadow Mode', 3, 'MR', 'BRAIN', 'HEAD', 'CONTAINS', '55555555-3333-3333-3333-333333333333', 'FLAIR', 5.0, TRUE, TRUE, 1);

INSERT INTO cleanup_policies (id, site_id, vendor_id, target_category, retention_hours_after_upload, retention_hours_after_return, schedule_cron, dry_run_only, legal_hold_active, active)
VALUES
('77777777-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', '55555555-1111-1111-1111-111111111111', 'VENDOR_WORKING_COPY', 24, 24, '0 0 2 * * *', FALSE, FALSE, TRUE),
('77777777-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', '55555555-2222-2222-2222-222222222222', 'VENDOR_WORKING_COPY', 24, 24, '0 30 2 * * *', FALSE, FALSE, TRUE),
('77777777-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', NULL, 'ORIGINAL_REPOSITORY', 168, 168, '0 0 3 * * 0', TRUE, FALSE, TRUE);
