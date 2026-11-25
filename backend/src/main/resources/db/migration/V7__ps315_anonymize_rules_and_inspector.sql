-- =============================================================================
-- V7: DICOM PS3.15 Basic Application Level Confidentiality Profile (FR-11 to FR-15)
--     Configurable Anonymize Rule Policies:
--     - Date/Time Policy (SHIFT_DATES, KEEP_DATES, REMOVE_DATES)
--     - Private Tag Allow-List (comma-separated hex tags or creator names)
--     - Burned-In Text Action (BLOCK_STUDY, MASK_PIXEL_REGION)
-- =============================================================================

ALTER TABLE deid_profiles ADD COLUMN date_time_policy VARCHAR(32) NOT NULL DEFAULT 'SHIFT_DATES';
ALTER TABLE deid_profiles ADD COLUMN private_tag_allow_list VARCHAR(1024) NOT NULL DEFAULT '';
ALTER TABLE deid_profiles ADD COLUMN burned_in_text_action VARCHAR(32) NOT NULL DEFAULT 'BLOCK_STUDY';

UPDATE deid_profiles
SET date_time_policy = CASE WHEN date_shift_days = 0 THEN 'KEEP_DATES' ELSE 'SHIFT_DATES' END,
    private_tag_allow_list = '',
    burned_in_text_action = CASE WHEN block_on_burned_in_text THEN 'BLOCK_STUDY' ELSE 'MASK_PIXEL_REGION' END;
