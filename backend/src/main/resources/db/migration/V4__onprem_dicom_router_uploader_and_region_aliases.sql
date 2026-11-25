-- =============================================================================
-- V4: On-Premise AI Vendor DICOM Uploader / DICOM Router Destination Config,
--     Forwarded Patient & Image Transfer Counters, and Multi-Study Description
--     Region Alias Mappings (CT Brain / MRI Brain: HEAD Protocol, BRAI Injury,
--     Strock Protocol, Stroke Protocol)
-- =============================================================================

-- 1. Add On-Premise DICOM Destination fields (Destination Type, AE Title, IP Address, Port) to vendor_configs
ALTER TABLE vendor_configs ADD COLUMN destination_type VARCHAR(64) NOT NULL DEFAULT 'DICOM_UPLOADER';
ALTER TABLE vendor_configs ADD COLUMN destination_ae_title VARCHAR(64) NOT NULL DEFAULT 'KAUVERY_AI_UPL';
ALTER TABLE vendor_configs ADD COLUMN destination_host VARCHAR(128) NOT NULL DEFAULT '10.10.30.20';
ALTER TABLE vendor_configs ADD COLUMN destination_port INTEGER NOT NULL DEFAULT 11115;

UPDATE vendor_configs
SET destination_type = 'DICOM_ROUTER',
    destination_ae_title = 'KAUVERY_ROUTER1',
    destination_host = '10.10.30.10',
    destination_port = 11114
WHERE id = '55555555-1111-1111-1111-111111111111';

UPDATE vendor_configs
SET destination_type = 'DICOM_UPLOADER',
    destination_ae_title = 'NEURO_UPLOADER',
    destination_host = '10.10.30.21',
    destination_port = 11115
WHERE id = '55555555-2222-2222-2222-222222222222';

UPDATE vendor_configs
SET destination_type = 'DICOM_UPLOADER',
    destination_ae_title = 'DEEPMRI_UPL_AE',
    destination_host = '10.10.30.22',
    destination_port = 11116
WHERE id = '55555555-3333-3333-3333-333333333333';

UPDATE vendor_configs
SET destination_type = 'DICOM_ROUTER',
    destination_ae_title = 'KAUVERY_TRY_RTR',
    destination_host = '10.20.30.10',
    destination_port = 11114
WHERE id = '55555555-4444-4444-4444-444444444444';

-- 2. Add On-Premise Patient Details & Canonical Study Region to study_records for Command Centre tracking
ALTER TABLE study_records ADD COLUMN patient_id VARCHAR(128);
ALTER TABLE study_records ADD COLUMN patient_name VARCHAR(255);
ALTER TABLE study_records ADD COLUMN patient_age VARCHAR(32);
ALTER TABLE study_records ADD COLUMN patient_sex VARCHAR(16);
ALTER TABLE study_records ADD COLUMN accession_number VARCHAR(128);
ALTER TABLE study_records ADD COLUMN canonical_region VARCHAR(128);

-- 3. Add Image Transfer Counters to vendor_delivery_jobs
ALTER TABLE vendor_delivery_jobs ADD COLUMN selected_instances INTEGER NOT NULL DEFAULT 0;
ALTER TABLE vendor_delivery_jobs ADD COLUMN transferred_instances INTEGER NOT NULL DEFAULT 0;

-- 4. Add canonical_region to routing_rules and expand CT Brain & MRI Brain rules with multiple study descriptions
ALTER TABLE routing_rules ADD COLUMN canonical_region VARCHAR(128);

UPDATE routing_rules
SET canonical_region = 'CT Brain',
    study_description_pattern = 'BRAIN, HEAD PROTOCOL, BRAI INJURY, BRAIN INJURY, STROCK PROTOCOL, STROKE PROTOCOL, NCCT BRAIN',
    body_part_pattern = 'HEAD, BRAIN',
    required_series_pattern = 'THIN, AXIAL, BRAIN, HEAD, STROKE, STROCK'
WHERE id = '66666666-1111-1111-1111-111111111111';

UPDATE routing_rules
SET canonical_region = 'CT KUB'
WHERE id = '66666666-2222-2222-2222-222222222222';

UPDATE routing_rules
SET canonical_region = 'MRI Brain',
    study_description_pattern = 'BRAIN, HEAD PROTOCOL, BRAI INJURY, BRAIN INJURY, STROCK PROTOCOL, STROKE PROTOCOL, MRI BRAIN',
    body_part_pattern = 'HEAD, BRAIN',
    required_series_pattern = 'FLAIR, AXIAL, BRAIN, HEAD, STROKE, STROCK, DWI, T1, T2'
WHERE id = '66666666-3333-3333-3333-333333333333';

UPDATE routing_rules
SET canonical_region = 'CT KUB'
WHERE id = '66666666-4444-4444-4444-444444444444';

-- 5. Create study_region_mappings table for configuring multiple study descriptions per anatomical region
CREATE TABLE study_region_mappings (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    canonical_region VARCHAR(128) NOT NULL,
    modality VARCHAR(32) NOT NULL,
    body_part VARCHAR(128) NOT NULL DEFAULT 'HEAD',
    study_descriptions VARCHAR(1024) NOT NULL,
    target_vendor_id UUID REFERENCES vendor_configs(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_study_region_mappings_site ON study_region_mappings(site_id, active);

INSERT INTO study_region_mappings (id, site_id, canonical_region, modality, body_part, study_descriptions, target_vendor_id, active)
VALUES
('aaaaaaaa-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'CT Brain', 'CT', 'HEAD',
 'CT BRAIN, HEAD PROTOCOL, BRAI INJURY, BRAIN INJURY, STROCK PROTOCOL, STROKE PROTOCOL, NCCT BRAIN, HEAD TRAUMA',
 '55555555-2222-2222-2222-222222222222', TRUE),
('aaaaaaaa-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'MRI Brain', 'MR', 'HEAD',
 'MRI BRAIN, MR BRAIN, HEAD PROTOCOL, BRAI INJURY, BRAIN INJURY, STROCK PROTOCOL, STROKE PROTOCOL, BRAIN STROKE FLAIR',
 '55555555-3333-3333-3333-333333333333', TRUE),
('aaaaaaaa-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', 'CT KUB', 'CT', 'ABDOMEN',
 'CT KUB, KUB CALCULUS PROTOCOL, RENAL STONE PROTOCOL, UROLITHIASIS PROTOCOL',
 '55555555-1111-1111-1111-111111111111', TRUE),
('aaaaaaaa-4444-4444-4444-444444444444', '11111111-2222-2222-2222-222222222222', 'CT KUB', 'CT', 'ABDOMEN',
 'CT KUB, KUB TRICHY PROTOCOL, RENAL STONE PROTOCOL',
 '55555555-4444-4444-4444-444444444444', TRUE);
