-- =============================================================================
-- V11: Hardcode reportType property value as 'Preliminary Report' when sending
--      processed PDF report files to the RIS / Telerad UploadReportServlet API
-- =============================================================================

UPDATE ris_report_endpoints
SET default_report_type = 'Preliminary Report';

UPDATE ris_uploaded_reports
SET report_type = 'Preliminary Report',
    report_data = REPLACE(report_data, '"reportType": "Study Report"', '"reportType": "Preliminary Report"'),
    processing_log = REPLACE(processing_log, 'Study Report', 'Preliminary Report')
WHERE report_type = 'Study Report'
   OR report_data LIKE '%"reportType": "Study Report"%';

UPDATE ai_result_records
SET report_data = REPLACE(report_data, '"reportType": "Study Report"', '"reportType": "Preliminary Report"')
WHERE report_data LIKE '%"reportType": "Study Report"%';
