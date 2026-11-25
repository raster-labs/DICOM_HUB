-- =============================================================================
-- V1: Initial Multi-Tenant DICOM Hub Schema
-- Covers Specification Sections 3.1, 3.2, 4.1-4.12, 5.1-5.3, 6.1 & Phase 2 LLMs
-- =============================================================================

CREATE TABLE sites (
    id UUID PRIMARY KEY,
    site_code VARCHAR(64) NOT NULL UNIQUE,
    site_name VARCHAR(255) NOT NULL,
    city VARCHAR(128),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE dicom_sources (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    source_name VARCHAR(128) NOT NULL,
    source_type VARCHAR(32) NOT NULL, -- MODALITY, RIS_PACS, OTHER_NODE
    calling_ae_title VARCHAR(64) NOT NULL,
    called_ae_title VARCHAR(64) NOT NULL DEFAULT 'DICOMHUB_AE',
    host_address VARCHAR(128) NOT NULL,
    dicom_port INTEGER NOT NULL DEFAULT 104,
    quiet_period_seconds INTEGER NOT NULL DEFAULT 120,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_dicom_sources_site ON dicom_sources(site_id, calling_ae_title);

CREATE TABLE pacs_destinations (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    destination_name VARCHAR(128) NOT NULL,
    ae_title VARCHAR(64) NOT NULL,
    host_address VARCHAR(128) NOT NULL,
    dicom_port INTEGER NOT NULL DEFAULT 11112,
    report_integration_strategy VARCHAR(64) NOT NULL DEFAULT 'DICOM_SR_NATIVE', -- DICOM_SR_NATIVE, HL7_V2_ORU, FHIR_DIAGNOSTIC_REPORT, PACS_VENDOR_API
    report_endpoint_url VARCHAR(512),
    is_default_for_site BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_pacs_dest_site ON pacs_destinations(site_id);

CREATE TABLE deid_profiles (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    profile_code VARCHAR(64) NOT NULL,
    profile_name VARCHAR(128) NOT NULL,
    profile_version INTEGER NOT NULL DEFAULT 1,
    retain_patient_age_sex BOOLEAN NOT NULL DEFAULT TRUE,
    retain_device_acquisition_params BOOLEAN NOT NULL DEFAULT TRUE,
    date_shift_days INTEGER NOT NULL DEFAULT -30,
    block_on_burned_in_text BOOLEAN NOT NULL DEFAULT TRUE,
    strip_private_tags BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_deid_profiles_site ON deid_profiles(site_id, profile_code);

CREATE TABLE vendor_configs (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    vendor_code VARCHAR(64) NOT NULL,
    vendor_name VARCHAR(128) NOT NULL,
    use_case_name VARCHAR(128) NOT NULL,
    connector_type VARCHAR(64) NOT NULL DEFAULT 'REST_MULTIPART', -- REST_MULTIPART, DICOMWEB_STOW, DICOM_CSTORE, LLM_MULTIMODAL
    api_base_url VARCHAR(512) NOT NULL,
    auth_type VARCHAR(64) NOT NULL DEFAULT 'OAUTH2_CLIENT_CREDENTIALS',
    deid_profile_id UUID NOT NULL REFERENCES deid_profiles(id),
    pacs_destination_id UUID REFERENCES pacs_destinations(id),
    sla_turnaround_minutes INTEGER NOT NULL DEFAULT 15,
    max_retry_attempts INTEGER NOT NULL DEFAULT 5,
    acknowledgement_mode VARCHAR(64) NOT NULL DEFAULT 'ALL_INSTANCES_INGESTED',
    shadow_mode BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    config_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_vendor_configs_site ON vendor_configs(site_id, vendor_code);

CREATE TABLE routing_rules (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    rule_name VARCHAR(128) NOT NULL,
    priority_order INTEGER NOT NULL,
    modality VARCHAR(32) NOT NULL,
    study_description_pattern VARCHAR(255) NOT NULL,
    body_part_pattern VARCHAR(128),
    match_operator VARCHAR(32) NOT NULL DEFAULT 'CONTAINS', -- EQUALS, CONTAINS, STARTS_WITH, REGEX
    vendor_id UUID NOT NULL REFERENCES vendor_configs(id),
    required_series_pattern VARCHAR(255),
    max_slice_thickness_mm DOUBLE PRECISION,
    allow_multi_vendor BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    rule_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_routing_rules_site_prio ON routing_rules(site_id, active, priority_order);

CREATE TABLE study_records (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    source_id UUID REFERENCES dicom_sources(id),
    source_type VARCHAR(32) NOT NULL, -- MODALITY, RIS_PACS
    calling_ae_title VARCHAR(64) NOT NULL,
    called_ae_title VARCHAR(64) NOT NULL,
    study_instance_uid VARCHAR(128) NOT NULL,
    patient_id_hash VARCHAR(128) NOT NULL,
    accession_hash VARCHAR(128),
    modality VARCHAR(32) NOT NULL,
    study_description VARCHAR(255),
    body_part_examined VARCHAR(128),
    protocol_name VARCHAR(255),
    series_count INTEGER NOT NULL DEFAULT 0,
    instance_count INTEGER NOT NULL DEFAULT 0,
    total_bytes BIGINT NOT NULL DEFAULT 0,
    lifecycle_state VARCHAR(64) NOT NULL, -- RECEIVED, WAITING_FOR_COMPLETION, VALIDATING, VALIDATION_FAILED, ROUTING_PENDING, ROUTED, EXCEPTION
    validation_error VARCHAR(512),
    last_instance_received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_study_records_site_uid ON study_records(site_id, study_instance_uid);
CREATE INDEX idx_study_records_site_state ON study_records(site_id, lifecycle_state);

CREATE TABLE instance_records (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    study_id UUID NOT NULL REFERENCES study_records(id),
    series_instance_uid VARCHAR(128) NOT NULL,
    sop_instance_uid VARCHAR(128) NOT NULL,
    sop_class_uid VARCHAR(128) NOT NULL,
    transfer_syntax_uid VARCHAR(128) NOT NULL,
    series_description VARCHAR(255),
    slice_thickness_mm DOUBLE PRECISION,
    burned_in_annotation VARCHAR(16),
    original_repo_path VARCHAR(1024) NOT NULL,
    sha256_checksum VARCHAR(64) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    duplicate_count INTEGER NOT NULL DEFAULT 0,
    late_arrival BOOLEAN NOT NULL DEFAULT FALSE,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_instance_records_site_sop ON instance_records(site_id, sop_instance_uid);

CREATE TABLE pseudonym_mappings (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    study_id UUID NOT NULL REFERENCES study_records(id),
    vendor_id UUID NOT NULL REFERENCES vendor_configs(id),
    pseudo_patient_id VARCHAR(128) NOT NULL,
    pseudo_accession_number VARCHAR(128) NOT NULL,
    pseudo_study_uid VARCHAR(128) NOT NULL UNIQUE,
    encrypted_real_patient_id VARCHAR(512) NOT NULL,
    encrypted_real_accession VARCHAR(512) NOT NULL,
    original_study_uid VARCHAR(128) NOT NULL,
    date_shift_days INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_pseudonym_site_pseudo_uid ON pseudonym_mappings(site_id, pseudo_study_uid);

CREATE TABLE vendor_delivery_jobs (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    study_id UUID NOT NULL REFERENCES study_records(id),
    vendor_id UUID NOT NULL REFERENCES vendor_configs(id),
    routing_rule_id UUID REFERENCES routing_rules(id),
    deid_profile_id UUID NOT NULL REFERENCES deid_profiles(id),
    status VARCHAR(64) NOT NULL, -- DEIDENTIFICATION_PENDING, DEIDENTIFICATION_FAILED, QUEUED, UPLOADING, UPLOADED, VENDOR_ACCEPTED, PROCESSING, RESULT_AVAILABLE, RESULT_VALIDATION_FAILED, RETURNING_TO_PACS, COMPLETED, RETRY_WAIT, FAILED, CANCELLED, MANUAL_REVIEW
    working_copy_dir VARCHAR(1024),
    vendor_job_id VARCHAR(128),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    next_retry_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(1024),
    uploaded_bytes BIGINT NOT NULL DEFAULT 0,
    vendor_accepted_at TIMESTAMP WITH TIME ZONE,
    result_received_at TIMESTAMP WITH TIME ZONE,
    pacs_returned_at TIMESTAMP WITH TIME ZONE,
    working_copy_cleaned_at TIMESTAMP WITH TIME ZONE,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_jobs_site_status ON vendor_delivery_jobs(site_id, status, next_retry_at);

CREATE TABLE vendor_transfer_attempts (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    job_id UUID NOT NULL REFERENCES vendor_delivery_jobs(id),
    attempt_number INTEGER NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    outcome VARCHAR(32) NOT NULL, -- SUCCESS, TRANSIENT_ERROR, PERMANENT_ERROR
    status_code VARCHAR(32),
    response_summary VARCHAR(1024),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_attempts_job ON vendor_transfer_attempts(site_id, job_id);

CREATE TABLE ai_result_records (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    job_id UUID NOT NULL REFERENCES vendor_delivery_jobs(id),
    study_id UUID NOT NULL REFERENCES study_records(id),
    vendor_id UUID NOT NULL REFERENCES vendor_configs(id),
    result_type VARCHAR(64) NOT NULL, -- DICOM_SR_TID1500, GSPS, DICOM_SEG, SECONDARY_CAPTURE, ENCAPSULATED_PDF
    model_name VARCHAR(128) NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    series_description_label VARCHAR(255) NOT NULL DEFAULT 'AI-generated – not verified',
    storage_path VARCHAR(1024) NOT NULL,
    findings_json TEXT NOT NULL,
    reidentified BOOLEAN NOT NULL DEFAULT FALSE,
    pacs_forwarded BOOLEAN NOT NULL DEFAULT FALSE,
    report_prefill_strategy VARCHAR(64),
    report_prefill_status VARCHAR(64), -- DRAFT_SENT, SKIPPED_SHADOW_MODE, FAILED
    radiologist_disposition VARCHAR(32) DEFAULT 'PENDING_REVIEW', -- PENDING_REVIEW, ACCEPTED, EDITED, REJECTED
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_ai_results_site_study ON ai_result_records(site_id, study_id);

CREATE TABLE cleanup_policies (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    vendor_id UUID REFERENCES vendor_configs(id),
    target_category VARCHAR(64) NOT NULL, -- VENDOR_WORKING_COPY, ORIGINAL_REPOSITORY, AI_RESULT_OBJECTS
    retention_hours_after_upload INTEGER NOT NULL DEFAULT 24,
    retention_hours_after_return INTEGER NOT NULL DEFAULT 24,
    schedule_cron VARCHAR(64) NOT NULL DEFAULT '0 0 2 * * *',
    dry_run_only BOOLEAN NOT NULL DEFAULT FALSE,
    legal_hold_active BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_cleanup_policies_site ON cleanup_policies(site_id, target_category);

-- Phase 2 Multimodal LLM Extension Tables (BRD Section 6.8 & 9)
CREATE TABLE llm_approved_models (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    provider_name VARCHAR(128) NOT NULL,
    model_identifier VARCHAR(128) NOT NULL,
    pinned_version VARCHAR(64) NOT NULL,
    cdsco_clearance_status VARCHAR(64) NOT NULL DEFAULT 'DRAFT_ASSIST_ONLY',
    india_region_hosted BOOLEAN NOT NULL DEFAULT TRUE,
    approved_by_radiology BOOLEAN NOT NULL DEFAULT FALSE,
    approved_by_legal BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE llm_prompt_templates (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    template_code VARCHAR(64) NOT NULL,
    use_case_name VARCHAR(128) NOT NULL,
    template_version INTEGER NOT NULL DEFAULT 1,
    window_preset VARCHAR(64) NOT NULL DEFAULT 'SOFT_TISSUE',
    max_slices INTEGER NOT NULL DEFAULT 16,
    prompt_body TEXT NOT NULL,
    findings_json_schema TEXT NOT NULL,
    shadow_mode BOOLEAN NOT NULL DEFAULT TRUE,
    approved_by VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES sites(id),
    actor_username VARCHAR(128) NOT NULL,
    actor_role VARCHAR(64) NOT NULL,
    event_category VARCHAR(64) NOT NULL, -- STUDY_LIFECYCLE, DEIDENTIFICATION, VENDOR_TRANSFER, RESULT_RETURN, CONFIG_CHANGE, CLEANUP, SECURITY
    action VARCHAR(128) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(128) NOT NULL,
    outcome VARCHAR(32) NOT NULL, -- SUCCESS, BLOCKED, FAILURE
    details_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_events_site_time ON audit_events(site_id, created_at DESC);
