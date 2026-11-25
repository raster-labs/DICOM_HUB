-- =============================================================================
-- V14: Sync On-Premise DICOM Router Study Descriptions to Routing Rules & Region Mappings,
--      and Clean Self-Looped Pseudonymized C-STORE Studies
-- =============================================================================

-- 1. Remove any self-looped pseudonymized working-copy study records that were ingested via loopback C-STORE
DELETE FROM ris_uploaded_reports
WHERE study_id IN (
    SELECT id FROM study_records
    WHERE calling_ae_title = 'DICOMHUB_AE'
      AND patient_id LIKE 'PSEUDO-PAT-%'
);

DELETE FROM ai_result_records
WHERE study_id IN (
    SELECT id FROM study_records
    WHERE calling_ae_title = 'DICOMHUB_AE'
      AND patient_id LIKE 'PSEUDO-PAT-%'
);

DELETE FROM vendor_transfer_attempts
WHERE job_id IN (
    SELECT j.id
    FROM vendor_delivery_jobs j
    JOIN study_records s ON s.id = j.study_id
    WHERE s.calling_ae_title = 'DICOMHUB_AE'
      AND s.patient_id LIKE 'PSEUDO-PAT-%'
);

DELETE FROM vendor_delivery_jobs
WHERE study_id IN (
    SELECT id FROM study_records
    WHERE calling_ae_title = 'DICOMHUB_AE'
      AND patient_id LIKE 'PSEUDO-PAT-%'
);

DELETE FROM pseudonym_mappings
WHERE study_id IN (
    SELECT id FROM study_records
    WHERE calling_ae_title = 'DICOMHUB_AE'
      AND patient_id LIKE 'PSEUDO-PAT-%'
);

DELETE FROM instance_records
WHERE study_id IN (
    SELECT id FROM study_records
    WHERE calling_ae_title = 'DICOMHUB_AE'
      AND patient_id LIKE 'PSEUDO-PAT-%'
);

DELETE FROM study_records
WHERE calling_ae_title = 'DICOMHUB_AE'
  AND patient_id LIKE 'PSEUDO-PAT-%';

-- 2. Sync On-Premise DICOM Router (55555555-7777-7777-7777-777777777777) study descriptions to region mappings and routing rules
UPDATE study_region_mappings
SET study_descriptions = 'Brain_CT, HEAD, Brain_ Stroke, BRAIN, *'
WHERE target_vendor_id = '55555555-7777-7777-7777-777777777777';

UPDATE routing_rules
SET study_description_pattern = 'Brain_CT, HEAD, Brain_ Stroke, BRAIN, *',
    body_part_pattern = NULL,
    max_slice_thickness_mm = NULL
WHERE vendor_id = '55555555-7777-7777-7777-777777777777';
