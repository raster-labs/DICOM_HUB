-- =============================================================================
-- V5: Real Scanner Series Filter Cleanup & Study Description Fallback
--     - Clear hardcoded 'THIN'/'AXIAL'/'FLAIR' required_series_pattern on default
--       seeded rules so real scanner series descriptions (e.g. "2.0", "1.5",
--       "Head 1.0") are filtered by max_slice_thickness_mm without false rejection.
--     - Backfill blank study_description from body_part_examined or protocol_name
--       on existing study_records.
-- =============================================================================

UPDATE routing_rules
SET required_series_pattern = NULL
WHERE id IN (
    '66666666-1111-1111-1111-111111111111',
    '66666666-2222-2222-2222-222222222222',
    '66666666-3333-3333-3333-333333333333',
    '66666666-4444-4444-4444-444444444444'
);

UPDATE study_records
SET study_description = CASE
    WHEN body_part_examined IS NOT NULL AND TRIM(body_part_examined) <> '' THEN TRIM(body_part_examined)
    WHEN protocol_name IS NOT NULL AND TRIM(protocol_name) <> '' THEN TRIM(protocol_name)
    ELSE study_description
END
WHERE study_description IS NULL OR TRIM(study_description) = '';

UPDATE study_records
SET canonical_region = CASE
    WHEN UPPER(modality) IN ('MR', 'MRI') THEN 'MRI Brain'
    ELSE 'CT Brain'
END
WHERE (canonical_region IS NULL OR canonical_region IN ('CT Study', 'MR Study', ''))
  AND (UPPER(COALESCE(study_description, '')) LIKE '%BRAIN%'
       OR UPPER(COALESCE(body_part_examined, '')) LIKE '%BRAIN%'
       OR UPPER(COALESCE(protocol_name, '')) LIKE '%BRAIN%');
