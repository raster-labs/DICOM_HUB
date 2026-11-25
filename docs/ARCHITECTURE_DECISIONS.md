# DICOM Hub – Architecture Decision Record (ADR)

This document records the core technical decisions confirmed during the architectural alignment interview (`/grill-me`).

## ADR-01: Primary Tech Stack – Spring Boot + dcm4che + Angular 19

- **Backend:** Java 25 LTS + Spring Boot 3.4 + Apache Maven (`mvn`).
- **DICOM Engine:** `org.dcm4che:dcm4che-core`, `dcm4che-net`, `dcm4che-deident` (v5.33.x via `https://maven.dcm4che.org/`).
- **Database & Migrations:** PostgreSQL 16 + Spring Data JPA + Flyway schema migrations.
- **Frontend:** Angular 19+ (Standalone Components, Signals, Reactive Forms) + Tailwind CSS + Angular Material.

## ADR-02: Service Packaging & Runtime Roles

- **Modular Spring Boot Application:** A unified Spring Boot Maven build containing shared domain entities, multi-tenant `site_id` enforcement, `dcm4che` services, de-identification engine, and vendor connectors.
- **Role-Based Execution via Spring Profiles (`hub.role`):**
  - `all` (Local development default): runs REST API, DICOM Storage SCP receiver, Queue Worker, Result Receiver, and Cleanup Worker in one JVM process.
  - `backend-api`: serves REST APIs for the Angular Command Centre Portal.
  - `dicom-receiver`: runs the `dcm4che` Storage SCP listener for incoming Modality and PACS C-STORE associations.
  - `queue-worker`: polls and processes study completion timers, de-identification jobs, and outbound vendor delivery jobs.
  - `result-receiver`: handles vendor webhooks, result polling, re-identification, and PACS C-STORE / Report return.
  - `cleanup-worker`: enforces per-vendor working-copy cleanup and original repository retention policies with dry-run support.

## ADR-03: Durable Queue & State Machine

- **PostgreSQL-Backed Durable Queue:** Uses `SELECT ... FOR UPDATE SKIP LOCKED` on `vendor_delivery_jobs` and `study_records` with explicit state machine transitions (`RECEIVED`, `WAITING_FOR_COMPLETION`, `VALIDATING`, `VALIDATION_FAILED`, `ROUTING_PENDING`, `DEIDENTIFICATION_PENDING`, `DEIDENTIFICATION_FAILED`, `QUEUED`, `UPLOADING`, `UPLOADED`, `VENDOR_ACCEPTED`, `PROCESSING`, `RESULT_AVAILABLE`, `RESULT_VALIDATION_FAILED`, `RETURNING_TO_PACS`, `COMPLETED`, `RETRY_WAIT`, `FAILED`, `CANCELLED`, `MANUAL_REVIEW`).
- **Zero Volatile Broker Dependency:** Eliminates Redis/RabbitMQ failure modes on the single-server deployment (Intel Core i7 / 16 GB RAM minimum) while guaranteeing ACID consistency with audit logs and study provenance.

## ADR-04: Multi-Tenancy (`site_id`) & Storage Separation

- **First-Class `site_id` Isolation:** Every operational table (`sites`, `dicom_sources`, `pacs_destinations`, `study_records`, `series_records`, `instance_records`, `routing_rules`, `deid_profiles`, `pseudonym_mappings`, `vendor_delivery_jobs`, `ai_result_records`, `cleanup_policies`, `audit_events`) carries `site_id`.
- **Three Physically Segregated Storage Namespaces:**
  1. `original-repo/{site_code}/{study_uid}/{series_uid}/{sop_uid}.dcm` — Immutable received DICOM object with SHA-256 checksum. Never modified by de-identification or deleted by vendor cleanup.
  2. `working-copies/{site_code}/{vendor_code}/{job_id}/{pseudo_sop_uid}.dcm` — Vendor-specific de-identified copy governed by that vendor's independent cleanup policy.
  3. `ai-results/{site_code}/{vendor_code}/{job_id}/` — Returned DICOM SR (TID 1500), GSPS, SEG, and optional Encapsulated PDF objects.

## ADR-05: Privacy, De-identification (PS3.15) & Phase 2 LLM Stubs

- **On-Premise De-identification Gate:** Baseline DICOM PS3.15 Basic Application Level Confidentiality Profile with AES-256-GCM encrypted pseudonym vault on-premise.
- **Burned-In Text Guard (FR-14):** Tag checks (`BurnedInAnnotation (0028,0301)`, `RecognizableVisualFeatures (0028,0302)`, Secondary Capture SOP class heuristic) + pluggable `BurnedInTextInspector` SPI for pixel OCR inspection.
- **Multimodal LLM Phase 2 Extension:** Database schema and Java SPI interfaces (`LlmPromptTemplate`, `LlmConnectorSpi`, `SliceWindowConverter`) are included in the core architecture while Phase 1 prioritizes traditional REST/DICOM AI vendor connectors.

## ADR-06: Pluggable Report Pre-fill Strategy (Interface I-6)

- `ReportIntegrationStrategy` SPI supports configurable per-PACS report pre-fill delivery:
  - **Option A (Default):** Native DICOM SR (TID 1500) via DICOM C-STORE to PACS.
  - **Option B:** HL7 v2 `ORU^R01` MLLP/REST adapter.
  - **Option C:** HL7 FHIR R4 `DiagnosticReport` + `Observation` adapter.
  - **Option D:** Vendor-specific PACS REST API adapter.
