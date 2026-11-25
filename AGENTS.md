# DICOM Hub – Workspace Agent Rules & Engineering Constitution

You are working on **DICOM Hub**, a vendor-neutral, on-premise DICOM image management and AI integration platform for hospitals (starting with Kauvery Hospital single-site deployment, architected for multi-hospital chain isolation).

Always read and obey these engineering invariants when designing, modifying, or testing any part of the system.

---

## 1. Non-Negotiable Architecture & Privacy Invariants

1. **Multi-Tenant `site_id` Isolation Everywhere:**
   - Every operational database entity, API endpoint, file path namespace, routing rule, pseudonym vault entry, and audit event MUST include and filter by `site_id` (Hospital/Site tenant identifier).
   - Never return AI results to a hardcoded default PACS. Always resolve the target PACS from the original study's `site_id` and configured return destination policy.

2. **Immutable Original DICOM Repository vs. Vendor Working Copies:**
   - Upon DICOM C-STORE reception (from a Modality or RIS/PACS), store the unmodified received DICOM object in the **Original DICOM Repository** (`storage/original-repo/{site_code}/{study_uid}/{series_uid}/{sop_uid}.dcm`) with a verified SHA-256 checksum and full source provenance (`source_type`, `calling_ae_title`, `called_ae_title`, `source_id`) BEFORE any routing or outbound job starts.
   - Never modify original DICOM objects in place.
   - Create an isolated **Vendor Working Copy** (`storage/working-copies/{site_code}/{vendor_code}/{job_id}/`) for each matched AI vendor delivery job, and apply de-identification to the working copy only.
   - A vendor's cleanup policy may ONLY delete that vendor's working copy and transfer artifacts after verified vendor acceptance/completion (`VENDOR_ACCEPTED` or `COMPLETED`). It must NEVER delete the Original DICOM Repository, another vendor's working copy, pseudonym mappings, or audit logs.

3. **Zero PHI / Real Identifier Leakage (DICOM PS3.15 Baseline):**
   - No real `PatientID (0010,0020)`, `PatientName (0010,0010)`, `PatientBirthDate (0010,0030)`, `AccessionNumber (0008,0050)`, `InstitutionName (0008,0080)`, physician names, or original `StudyInstanceUID` / `SeriesInstanceUID` / `SOPInstanceUID` may ever leave the hospital network to an external AI vendor.
   - Replace `PatientID`, `AccessionNumber`, and all UIDs with deterministic-per-scope or random pseudonyms/UUID-based UIDs (`2.25.<uuid-int>`), storing the reversible lookup encrypted (AES-256-GCM) in the on-premise `pseudonym_mappings` table.
   - Retain clinically required non-identifying tags (`PatientAge`, `PatientSex`, `PixelSpacing`, `SliceThickness`, `KVP`, `XRayTubeCurrent`, `ContrastBolusAgent`) as permitted by the vendor's `DeidProfile`.
   - Enforce burned-in pixel annotation detection (FR-14): inspect `BurnedInAnnotation (0028,0301)`, SOP Class heuristics, and the `BurnedInTextInspector` pipeline. If burned-in PHI is detected or safety cannot be established, transition the job to `DEIDENTIFICATION_FAILED` / `MANUAL_REVIEW` and raise an alert.

4. **Safe Re-identification & Clinical Draft Labeling:**
   - On AI result return (DICOM SR TID 1500, GSPS, SEG, or optional Encapsulated PDF), verify the vendor job and pseudonym mapping before restoring the original `StudyInstanceUID`, `PatientID`, and `AccessionNumber`.
   - If a returned result cannot be unambiguously matched to a single original study and site, quarantine it (`RESULT_VALIDATION_FAILED` / `MANUAL_REVIEW`). Never guess a patient or study.
   - Every AI-generated series/object sent to PACS MUST carry the label `"AI-generated – not verified"` (or `"AI draft (LLM, model and version), not verified"` for Phase 2 LLM connectors) in `SeriesDescription (0008,103E)`.
   - Never auto-sign radiology reports. All report template pre-fills (via `ReportIntegrationStrategy`: DICOM SR, HL7 v2 `ORU^R01`, FHIR R4 `DiagnosticReport`, or PACS API) are strictly editable drafts.

5. **Durable PostgreSQL State Machine Queue:**
   - All asynchronous jobs (`vendor_delivery_jobs`) use PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED` with explicit states (`RECEIVED`, `WAITING_FOR_COMPLETION`, `VALIDATING`, `VALIDATION_FAILED`, `ROUTING_PENDING`, `DEIDENTIFICATION_PENDING`, `DEIDENTIFICATION_FAILED`, `QUEUED`, `UPLOADING`, `UPLOADED`, `VENDOR_ACCEPTED`, `PROCESSING`, `RESULT_AVAILABLE`, `RESULT_VALIDATION_FAILED`, `RETURNING_TO_PACS`, `COMPLETED`, `RETRY_WAIT`, `FAILED`, `CANCELLED`, `MANUAL_REVIEW`).
   - Study completion uses a configurable quiet period (default `120` seconds since the last received instance) and explicitly handles late-arriving instances via a configurable late-arrival policy.

---

## 2. Technology Stack & Code Conventions

- **Backend (`backend/`):**
  - Java 25 LTS (`/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`) + Spring Boot 3.4 + Maven.
  - DICOM library: `org.dcm4che:dcm4che-core`, `org.dcm4che:dcm4che-net`, `org.dcm4che:dcm4che-deident`.
  - Database: PostgreSQL 16 + Spring Data JPA + Flyway (`backend/src/main/resources/db/migration/V*.sql`).
  - Role activation via `hub.role` property (`all`, `backend-api`, `dicom-receiver`, `queue-worker`, `result-receiver`, `cleanup-worker`).
- **Frontend (`frontend/`):**
  - Angular 19+ (Standalone Components, Signals, Reactive Forms) + Tailwind CSS + Angular Material.
  - Command Centre Dashboard must clearly distinguish **Modality-origin studies** vs. **PACS-origin studies** and **Hub-received studies** vs. **Vendor-accepted studies**.
- **Testing & Verification:**
  - Always verify backend changes with `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn test` in `backend/`.
  - Use the workspace skills in `.agents/skills/` for DICOM C-STORE simulation, PS3.15 de-identification auditing, vendor onboarding, and database migration checks.
