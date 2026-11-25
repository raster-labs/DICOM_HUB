-- =============================================================================
-- V10: AI Agent Name (generatedBy) in Agent Configuration,
--      reportData & generatedBy properties on RIS PDF Upload & AI Results,
--      and Per-Study Processing & RIS Delivery Log Telemetry
-- =============================================================================

-- 1. Add agent_name (sent in generatedBy to RIS) to vendor_configs
ALTER TABLE vendor_configs ADD COLUMN agent_name VARCHAR(255) NOT NULL DEFAULT 'Grok AI Vision Agent';

UPDATE vendor_configs
SET agent_name = 'Grok AI Vision Agent'
WHERE id = '55555555-5555-5555-5555-555555555555';

-- 2. Add generated_by, report_data, and processing_log to ris_uploaded_reports
ALTER TABLE ris_uploaded_reports ADD COLUMN generated_by VARCHAR(255) NOT NULL DEFAULT 'Grok AI Vision Agent';
ALTER TABLE ris_uploaded_reports ADD COLUMN report_data TEXT;
ALTER TABLE ris_uploaded_reports ADD COLUMN processing_log TEXT;

-- 3. Add generated_by and report_data to ai_result_records
ALTER TABLE ai_result_records ADD COLUMN generated_by VARCHAR(255);
ALTER TABLE ai_result_records ADD COLUMN report_data TEXT;
