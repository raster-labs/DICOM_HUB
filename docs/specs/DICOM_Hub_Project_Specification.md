# DICOM Hub – Product Requirements & Technical Development Specification

**Document status:** Initial development draft – pending stakeholder clarification  
**Source:** Client BRD – *BRD – PACS ↔ AI Integration Platform*, Version 0.3  
**Project name:** DICOM Hub  
**Deployment model:** On-premise Linux server using Docker containers  
**Proposed database:** PostgreSQL  
**Application:** Web-based administration and monitoring portal

---

## 1. Project overview

DICOM Hub is a vendor-neutral, on-premise DICOM image management and AI integration platform. It receives medical imaging studies from modalities and/or RIS/PACS, identifies the hospital and source, validates and tracks the studies, and stores the original received DICOM objects in its own managed image repository. The Hub then creates controlled processing copies, applies configured de-identification and privacy controls, and forwards eligible studies to one or more configured AI vendors.

The platform must also receive AI results, validate and re-identify them using securely held on-premise mappings, and return supported DICOM result objects and/or reports to the designated RIS/PACS for the correct hospital and original patient study. The original received images held by the Hub must remain logically separate from vendor-specific de-identified copies and returned AI result objects.

The first deployment is intended for one hospital. The software architecture must be designed so that a future centralised deployment can serve a chain of hospitals, each with separate PACS destinations and logically isolated hospital data, configuration, users, routing and audit records.

The platform must be configurable by an authorised hospital administrator. Adding, changing, pausing or retiring an AI vendor or routing rule should not require a software code change for ordinary supported integrations.

### 1.1 Primary objectives

1. Receive studies from modalities and RIS/PACS.
2. Identify when a study is complete before processing it.
3. Route studies according to configurable study and series criteria.
4. De-identify DICOM data before any external transfer.
5. Forward studies securely to the correct AI vendor(s).
6. Persist jobs and automatically retry after network, Internet, vendor or service outages.
7. Receive and validate AI results.
8. Re-identify results and return them to the correct original PACS study.
9. Provide operational dashboards, audit trails, alerts and reports.
10. Automatically purge eligible image files and transient data according to approved retention policies.

### 1.2 Design principles

- Vendor-neutral and connector-based.
- Privacy controls enforced on-premise before external transfer.
- Original PACS images must never be modified.
- No study should be silently lost.
- Delivery must be traceable at study, vendor and transfer-attempt level.
- Duplicate delivery must be prevented unless an authorised resend is requested.
- Clinical results remain drafts for radiologist review; the system must not auto-sign reports.
- Configuration changes must be permission-controlled, versioned and audited.

---

## 2. Scope

### 2.1 In scope

- DICOM reception from modalities using DICOM C-STORE.
- DICOM reception/forwarding from RIS/PACS, subject to the PACS interface supported.
- Study completion detection.
- DICOM validation and configurable series selection.
- Rule-based routing to one or multiple AI vendors.
- Vendor configuration and connection testing.
- DICOM de-identification and pseudonymisation.
- Persistent outbound queues, retries and recovery.
- Vendor API integration and asynchronous status/result handling.
- Inbound DICOM result reception.
- Result validation, re-identification and forwarding to PACS.
- Dashboard, alerts, audit trail and operational reports.
- Configurable cleanup of eligible data.
- Role-based administration.

### 2.2 Out of scope unless separately approved

- Building, training or clinically validating AI models.
- Replacing the existing RIS, PACS or diagnostic viewer.
- Automatic approval or signing of radiology reports.
- Guaranteeing editable report-template integration without confirmation of the PACS/RIS interface.
- Legal certification of DPDP Act compliance by software alone.
- Vendor-specific image conversion or proprietary workflows not documented in the vendor's onboarding specification.

---

## 3. High-level architecture

```text
       Hospital modalities / RIS-PACS
                    |
       DICOM C-STORE / PACS forwarding
                    |
                    v
              +----------------+
              | DICOM Hub      |
              | Site + source  |
              | identification|
              +----------------+
                    |
          Study completion detection
                    |
          DICOM validation and parsing
                    |
          ORIGINAL DICOM REPOSITORY
          (managed Hub image storage)
                    |
          Routing rule evaluation
                    |
       Vendor-specific working copy
                    |
       De-identification / pseudonymisation
                    |
          Persistent outbound job queue
                    |
          Vendor connector / HTTPS API
             /        |         \
            v         v          v
         Vendor A  Vendor B   Vendor C
            \         |          /
             \---- AI results ---/
                    |
          Result reception and validation
                    |
          Re-identification using secure
          on-premise mapping key
                    |
           DICOM result object creation
                    |
             DICOM C-STORE to designated PACS
                    |
         Correct hospital / original study
         Additional AI series and/or report
```

### 3.1 Deployment editions and hospital isolation

**Initial deployment – single hospital**

- One dedicated Linux server hosts the DICOM Hub containers.
- One hospital/site is configured in the application.
- Multiple modalities, one or more RIS/PACS endpoints and multiple AI vendors may be configured.
- Every received study is associated with the configured hospital, source AE Title and source system.
- AI results are returned only to the explicitly configured destination PACS for that study.

**Future deployment – centralised hospital chain**

The application must be designed for a future centralised Hub serving multiple hospitals. Each hospital must be represented as a separate site/tenant. At minimum, site-level separation is required for:

- Patient/study metadata and original image repository namespaces.
- Source AE Titles and source-system configuration.
- PACS destinations and return routing.
- AI vendor enablement and routing rules.
- De-identification profiles and pseudonym mapping namespaces.
- User access and administrative permissions.
- Audit records, dashboards and reports.
- Retention and cleanup policies.

A user assigned to one hospital must not be able to view or administer another hospital's patient data unless explicitly granted cross-site permissions. A centralised deployment must not be treated as simply one shared PACS destination for all hospitals.

The first release may deploy one site, but database records and APIs should carry a `site_id` (or equivalent tenant identifier) wherever site ownership applies. The site identifier must be enforced in authorization and query filtering, not merely used as a display field.

The single-server deployment described in this document is the initial deployment target. A centralised multi-hospital deployment requires a separate capacity, network, availability and disaster-recovery assessment; the initial i7/16 GB specification must not be assumed sufficient for that future scale.

### 3.2 Image repository and provenance – mandatory design

The Hub must maintain its own managed repository of original received DICOM objects. The repository is the Hub's retained copy for its processing workflow; it does not replace the hospital's legal/clinical PACS archive unless a separate approved requirement explicitly says so.

For each received study and instance, record at least:

- Hospital/site identifier.
- Source type: modality or RIS/PACS.
- Source system name and calling AE Title.
- Receiving Hub AE Title and receipt endpoint.
- Original Study, Series and SOP Instance UIDs.
- Patient/study identifiers as received, protected according to access policy.
- Receipt timestamp and transfer/association details.
- Storage object identifier and checksum.
- Validation status and any duplicate/late-arrival status.

The Hub should preserve the received original DICOM object without applying vendor de-identification to that repository copy. Vendor processing must use a separately generated working copy. The working copy may have pseudonymised identifiers, remapped UIDs, date shifts or other approved changes.

If the same study is received from more than one source (for example, directly from a modality and later from PACS), the Hub must retain source provenance and apply a defined duplicate/reconciliation policy. It must not assume that matching Study Instance UID alone proves that every received instance is byte-identical or complete. Duplicate instances should be detected using SOP Instance UID plus object integrity checks, with conflicts raised for review.

The Hub must support a study lifecycle that distinguishes:
- Received and stored in original repository.
- Validated and ready for routing.
- Copied/de-identified for a particular vendor.
- Uploaded/accepted/processed by that vendor.
- AI result received and validated.
- Result forwarded to the designated PACS.
- Vendor working copy eligible for cleanup.
- Original repository copy eligible for cleanup only under a separate approved Hub retention policy.

No AI vendor transfer may begin until the original received objects and their required provenance records have been durably committed to Hub storage and the database. The exact meaning of durable commit, including checksum and storage acknowledgement, must be implemented and tested.

Supporting services:

- PostgreSQL database.
- DICOM object storage / staging directory.
- Queue worker service.
- Vendor connector services or connector modules.
- Web administration portal.
- Monitoring and alerting service.
- Audit logging service.
- Scheduled retention and cleanup worker.

---

## 4. Functional requirements

### 4.1 DICOM source management

The Hub shall support both direct modality-to-Hub C-STORE and RIS/PACS-to-Hub forwarding. A study may enter through either path. Source attribution must be captured at receipt and must remain available throughout routing, vendor delivery, result reception and PACS return.

The application shall support multiple configured DICOM sources.

For each source, an administrator shall be able to configure:

- Source name and description.
- Source type: modality, RIS/PACS or other DICOM node.
- AE Title.
- IP address / hostname.
- DICOM port.
- Called AE Title.
- Allowed calling AE Titles.
- Transfer syntax policy.
- Enable / disable status.
- Connection and association test status.

The DICOM Hub shall expose a configurable DICOM Storage SCP endpoint. The final list of supported SOP Classes and transfer syntaxes must be confirmed during implementation and tested against the actual modalities and PACS.

**Important:** Modality auto-routing normally requires the modality console or its routing service to be configured to send a copy to the DICOM Hub. PACS fallback routing depends on the PACS vendor's capabilities and interface.

### 4.2 Study reception, original storage and completion

On receipt of DICOM instances, the Hub shall:

1. Validate the DICOM association and incoming object.
2. Extract key study, series and instance attributes.
3. Store the unmodified received object in the managed original DICOM repository, using durable storage and an integrity/checksum record.
4. Record hospital/site, source system, calling AE Title, receipt time, receiving endpoint and object-level status.
5. Update study and series counts.
6. Detect whether more instances are still arriving.
7. Mark the study ready only after the configured quiet period or an explicit completion signal.

The initial proposed quiet period is **120 seconds after the last received instance**, matching the example in the client BRD. It must be configurable by source or use case.

The system must handle late-arriving instances. If additional images arrive after a study has been marked ready or forwarded, the system shall apply a defined late-arrival policy rather than silently ignoring them.

### 4.3 DICOM validation

Before routing, validate at minimum:

- Required DICOM attributes.
- Study Instance UID, Series Instance UID and SOP Instance UID.
- Modality and study description.
- SOP Class and transfer syntax.
- Instance count and series count.
- Pixel data availability/readability where applicable.
- Configured slice count and slice thickness constraints.
- Duplicate SOP Instance UID handling.
- Vendor-specific required fields and image constraints.

Invalid studies must be placed in a visible exception state with a reason. Validation failure must not result in an external transfer.

### 4.4 Configurable routing engine

The administrator shall be able to create routing rules without changing application code.

Supported rule conditions should include:

- Source AE Title / source system.
- Modality.
- Study Description (0008,1030).
- Body Part Examined (0018,0015).
- Protocol Name (0018,1030).
- Series Description (0008,103E).
- Station Name, where appropriate.
- Referring department or other approved attributes.
- Date/time or operating schedule, if required.
- Vendor availability and use-case status.

Rule operators should include exact match, contains, starts with, ends with, wildcard and regular expression only where safely implemented.

Each rule shall define:

- Rule name and description.
- Priority / execution order.
- Conditions and AND/OR grouping.
- Destination vendor and use case.
- Series-selection profile.
- De-identification profile.
- Vendor connector configuration.
- SLA and retry policy.
- Active / inactive status.
- Effective dates, owner and version.
- Behaviour when no rule matches.
- Whether a study can be sent to multiple vendors.

**Example routing rules**

| Priority | Condition | Destination |
|---|---|---|
| 1 | Modality = CT AND Study Description contains `BRAIN` | AI Vendor A – CT Brain |
| 2 | Modality = CT AND Study Description contains `KUB` | AI Vendor B – CT KUB |
| 3 | Modality = MR AND Study Description contains `BRAIN` | AI Vendor C – MRI Brain |

Rules must be tested against sample metadata before activation. Conflicting rules must be visible to the administrator.

### 4.5 Vendor onboarding and configuration

An authorised administrator shall be able to add a vendor through the web application.

Vendor configuration fields:

- Vendor name and code.
- Description and supported use cases.
- API base URL and endpoint paths.
- Authentication type: OAuth2 client credentials, mutual TLS, API key or another approved method.
- Credentials / certificates stored securely.
- Network allow-list details.
- Request format and required metadata.
- DICOM transfer method or vendor-specific upload mechanism.
- Job status mechanism: webhook, polling or both.
- Result delivery method.
- Timeout and retry settings.
- Rate limits and concurrency limits.
- Supported modalities, SOP Classes and transfer syntaxes.
- Required series and image constraints.
- De-identification profile.
- Result/output mapping profile.
- Vendor SLA and retention terms.
- Vendor activation status.

The administrator shall be able to:

- Add, edit, test, activate, pause and retire a vendor.
- Test authentication and connectivity.
- View recent connection/test results.
- Configure multiple use cases for one vendor.
- Configure more than one vendor for a study where authorised.
- Review configuration history and roll back to a previous approved version.

Vendor configuration does not mean every vendor API can be integrated through generic fields alone. A new connector may require development if the vendor has a proprietary API or unsupported workflow. The onboarding process must support connector templates and documented extension points.

### 4.6 De-identification and pseudonymisation

All outbound studies must pass through the Hub's de-identification process before leaving the hospital network.

The implementation shall use the DICOM PS3.15 Basic Application Level Confidentiality Profile as the baseline, with documented options and vendor/use-case-specific profiles.

The de-identification profile shall specify:

- Attributes to remove.
- Attributes to replace with pseudonyms.
- Attributes permitted to remain.
- Date/time handling and date shifting.
- Private tag handling and approved allow-lists.
- UID remapping.
- Pixel-data / burned-in text handling.
- Vendor-specific exceptions approved by the hospital's privacy authority.

Baseline handling from the client BRD:

| DICOM attribute group | Outbound action | Return handling |
|---|---|---|
| Patient Name, Birth Date, Address, phone numbers | Remove | Do not restore |
| Patient ID / Other Patient IDs | Replace with pseudonym | Restore from on-premise mapping |
| Accession Number | Replace with pseudonym | Restore from on-premise mapping |
| Study / Series / SOP Instance UIDs | Replace with new UIDs | Map back to original identifiers |
| Institution / physician names | Remove | Do not restore |
| Study and series dates/times | Shift or retain only as profile-approved | Reverse shift where applicable |
| Age, sex, size, weight | Retain only when approved and clinically needed | Not applicable |
| Pixel spacing, slice thickness, kVp, mAs, contrast | Retain where clinically needed | Not applicable |
| Private tags | Remove unless explicitly allow-listed | Not applicable |
| Burned-in pixel text | Detect; mask or block | Not applicable |

Requirements:

1. No real Patient ID or accession number may be sent to an AI vendor.
2. UID mapping and patient re-identification mappings must remain on-premise.
3. Mapping data must be encrypted and access restricted.
4. De-identification failure must block transfer and raise an alert.
5. The system must log which profile/version was applied.
6. The system must inspect or otherwise address burned-in identifiers. If detection cannot establish safety, the study must be blocked for review.
7. Re-identification must validate the returned identifiers against the original mapping and reject ambiguous or mismatched results.
8. Any exception to the profile must require documented approval.

**Privacy note:** Pseudonymised data remains personal data where it can be linked back using separately held information. The project must not describe pseudonymisation as irreversible anonymisation. DPDP applicability, lawful basis, notices, processor obligations, retention, security safeguards, breach response and cross-border transfer conditions require review and approval by the hospital's DPO and legal team.

### 4.7 Outbound transfer queue

Each vendor delivery must be generated from a vendor-specific working copy derived from the Hub's original repository. The original object must not be overwritten by anonymisation or vendor-specific transformation. The queue must retain references to the original study, working-copy version, de-identification profile version, site, source and destination vendor.

The outbound queue must be persistent and survive:

- Internet disconnection.
- Hospital network outage.
- AI vendor downtime.
- API timeout.
- DICOM transfer failure.
- Hub service/container restart.
- Host restart.

Each vendor delivery shall have its own job record and status.

Suggested states:

- RECEIVED
- WAITING_FOR_COMPLETION
- VALIDATING
- VALIDATION_FAILED
- ROUTING_PENDING
- DEIDENTIFICATION_PENDING
- DEIDENTIFICATION_FAILED
- QUEUED
- UPLOADING
- UPLOADED
- VENDOR_ACCEPTED
- PROCESSING
- RESULT_AVAILABLE
- RESULT_VALIDATION_FAILED
- RETURNING_TO_PACS
- COMPLETED
- RETRY_WAIT
- FAILED
- CANCELLED
- MANUAL_REVIEW

Retry behaviour:

- Exponential backoff with configurable maximum delay.
- Maximum retry count configurable per vendor.
- Respect vendor rate limits and Retry-After responses.
- Retry only transient failures automatically.
- Permanent validation/authentication errors must be escalated.
- Manual retry must be permission-controlled and audited.
- Each attempt must have a unique attempt record.
- Use idempotency keys or equivalent vendor-side duplicate protection where supported.

A study sent to two vendors must have separate delivery states for each vendor. One vendor's failure must not block another vendor's successful delivery.

### 4.8 AI result reception and designated PACS return

The target PACS must be resolved from the original study's hospital/site and configured return-destination policy. Do not select a PACS based only on the vendor that returned the result. A vendor may serve multiple hospitals, and the same vendor may return results for different sites.

The Hub shall support the vendor's agreed result delivery mechanism:

- Vendor webhook callback.
- Vendor API polling as fallback.
- DICOM C-STORE from vendor to Hub.
- Secure file/API delivery where explicitly agreed.

The Hub shall validate incoming results and associate them with the correct outbound job and original study.

Expected DICOM result types from the client BRD:

- DICOM Structured Report (SR), preferably the agreed template such as TID 1500 where supported.
- Grayscale Softcopy Presentation State (GSPS).
- DICOM Segmentation (SEG).
- Secondary Capture where justified.
- Encapsulated PDF as optional supplementary output.

A PDF alone does not satisfy the client's structured-result requirement.

Before PACS forwarding:

1. Validate vendor, job and pseudonym mapping.
2. Validate references to the sent study/series/instances.
3. Re-identify using the on-premise mapping.
4. Restore the appropriate original Study Instance UID and references.
5. Label AI-generated series clearly, for example `AI-generated – not verified`.
6. Preserve original PACS images.
7. Forward the returned objects to the configured PACS destination using DICOM C-STORE or the agreed interface.
8. Record PACS acknowledgement or failure, including the site and destination PACS.

If a result cannot be safely mapped to one original study, it must be quarantined for manual review. It must never be attached to a guessed patient or study.

### 4.9 Report integration

The BRD requires editable draft findings in the RIS/PACS report template, not only AI images.

The report integration method is a stakeholder decision:

- Option A: PACS reads DICOM SR natively.
- Option B: HL7 v2 ORU^R01.
- Option C: HL7 FHIR DiagnosticReport / Observation.
- Option D: PACS vendor API.

Option A is preferred in the BRD if the existing PACS supports it. This capability must be confirmed with the PACS/RIS vendor before committing to report pre-fill delivery.

The radiologist must retain control to accept, edit or reject findings and sign the report. The Hub must not auto-sign.

### 4.10 Dashboard and reports

The dashboard must distinguish the source of the study and the Hub repository lifecycle. At minimum, show counts for studies/instances received directly from modalities versus forwarded by RIS/PACS, original repository storage status, vendor-specific copies and delivery status, and results returned to each designated PACS. In a future multi-hospital deployment, all metrics must be filterable by hospital/site, with cross-site views restricted to authorised users.

The web dashboard shall show operational and management information.

**Overview cards**

- Total studies received.
- Studies received from modalities.
- Studies received/forwarded from RIS-PACS.
- Studies awaiting completion.
- Studies routed.
- Studies uploaded successfully.
- Studies currently processing at vendors.
- Results received.
- Results returned to PACS.
- Failed / retrying / quarantined studies.
- Storage used and available.
- Queue age and oldest pending job.

**Vendor-wise dashboard**

| Metric | Description |
|---|---|
| Studies received by Hub | Count of unique studies received |
| Modality-origin studies | Studies first received from a modality |
| PACS-origin studies | Studies received through PACS forwarding |
| Upload attempts | Total vendor upload attempts |
| Successful uploads | Vendor-confirmed accepted uploads |
| Failed uploads | Failed or rejected uploads |
| Currently queued | Waiting to upload |
| Vendor processing | Accepted by vendor but not completed |
| Results received | Vendor results received |
| Results returned to PACS | Successful PACS delivery |
| Average turnaround | Configurable start/end timestamps |
| Data volume | DICOM bytes transferred |

The dashboard must distinguish a study received by the Hub from a study accepted by the vendor. A local upload completing does not necessarily prove that the vendor accepted or processed the study.

**Filters**

- Date/time range.
- Vendor.
- Use case.
- Modality.
- Study Description.
- Source AE Title / source type.
- Status.
- Hospital/site, if multi-site is required.

**Operational views**

- Study-level lifecycle timeline.
- Vendor job details and attempt history.
- Failure reason and next retry time.
- Manual retry / cancel / quarantine actions according to role.
- Exportable operational reports, subject to access controls.
- Universal search across studies, patient forwarding records, delivery jobs, DICOM nodes, routing rules, users and audit logs with optional fuzzy matching (tolerating scanner typos such as `BRAI Injury` or `Strock Protocol`).

### 4.10.1 Live incoming study and image-count updates (Mandatory Functional Requirement)

The DICOM Hub dashboard must display incoming studies from RIS-PACS (and modalities) in real time:

- Whenever the Hub receives a DICOM image from RIS-PACS (or a modality), the received-image count must increment automatically.
- The dashboard must update in real time without requiring a manual page refresh (via Server-Sent Events / reactive live telemetry stream).
- Display:
  - **Total images received** (unique stored DICOM instances across all studies in the Original DICOM Repository).
  - **Images received today** (unique stored DICOM instances committed today).
  - **Images received per study** (live per-study instance counter).
- Show the source explicitly as **RIS-PACS** (`RIS_PACS`) or **Modality** (`MODALITY`), so administrators can immediately distinguish where images originated.
- Display the study's receiving status: **Receiving** (`RECEIVING`), **Completed** (`COMPLETED`), **Failed** (`FAILED`) or **Incomplete** (`INCOMPLETE`).
- Update the study-level image count as individual DICOM instances arrive.
- **Avoid double-counting retransmitted or duplicate instances:** explicitly distinguish total received transfer events (`received_transfer_events`) and skipped duplicates (`duplicate_instances_count`) from unique stored instances (`instance_count`).
- **Important:** The count must increment **only after** the DICOM instance has been successfully received, validated (including SHA-256 integrity checksum) and durably committed to the Original DICOM Repository — not merely when a transfer request or DICOM association starts.

### 4.11 Alerts and monitoring

The Hub shall generate alerts for:

- DICOM source unavailable.
- No incoming studies from an expected source, if configured.
- Study validation failure.
- De-identification failure.
- Burned-in text detection.
- Vendor API or authentication failure.
- Queue backlog threshold exceeded.
- Retry limit reached.
- Vendor processing SLA breach.
- Result mapping failure.
- PACS delivery failure.
- Low disk space.
- Database or queue worker failure.
- Certificate expiry.

The BRD proposes that IT/Command Centre alerts be raised within five minutes of a failure. Notification channels (email, SMS, ticketing, etc.) must be configured by the hospital.

### 4.12 Audit and access control

Roles should include:

- Super User (`SUPER_USER`).
- Administrator (`ADMINISTRATOR` — System / PACS / IT Administrator with configurable granular permissions).
- Privacy / DPO Reviewer (`AUDITOR`).
- Operations / Command Centre User (`OPERATIONS`).
- Radiologist / Clinical Reviewer (`RADIOLOGIST`), if clinical workflow functions are included.
- Technologist (`TECHNOLOGIST`).

Controls:

- Individual user accounts; no shared accounts.
- Role-based access control.
- Strong authentication and configurable session policy.
- Audit all login, configuration, vendor, rule, profile, manual retry, resend, export and administrative actions.
- Record timestamp, user, action, object, outcome and relevant before/after configuration version.
- Protect audit logs against unauthorised modification.
- Restrict access to patient mapping information to specifically authorised roles.
- Never expose real identifiers in ordinary operational dashboards unless there is a documented need and permission.

### 4.12.1 DICOM configuration access control (Mandatory Functional Requirement)

DICOM configuration settings must be accessible only to authorised **Super Users** or **Administrators**.

| User role | DICOM configuration access |
|---|---|
| **Super User** (`SUPER_USER`) | View, add, edit and delete DICOM configurations |
| **Administrator** (`ADMINISTRATOR`) | View, add, edit and delete DICOM configurations, subject to assigned permissions (`can_view_dicom_config`, `can_create_dicom_config`, `can_edit_dicom_config`, `can_delete_dicom_config`) |
| **Other users** (`RADIOLOGIST`, `TECHNOLOGIST`, `OPERATIONS`, `AUDITOR`, etc.) | No access to DICOM configuration settings |

Configuration access control applies to:

- Local AE Title, IP address and port.
- Modality and RIS-PACS source configurations.
- Destination AE Titles and PACS routing (including on-premise DICOM Router, AI Vendor DICOM Uploader, designated PACS destinations, routing rules and multi-description region mappings).
- DICOM connectivity and association settings (PDU length, association/DIMSE timeouts, quiet period, transfer syntax policies, TLS).
- Vendor DICOM connector settings.

All configuration changes (and any blocked unauthorised configuration access attempts) must be recorded in an immutable audit log, including the **user** (username and role), **timestamp**, **changed setting** (before/after values or setting key) and **action performed** (`CREATE`, `UPDATE`, `DELETE`, `TOGGLE`, `ACCESS_DENIED`).

---

## 5. Data retention and automatic cleanup

The Hub shall provide configurable retention policies for image files and transient processing data.

### 5.1 Cleanup eligibility

Cleanup is applied separately to each vendor's working copy and to the Hub's original repository. A vendor-specific cleanup policy must never delete the original received DICOM objects or another vendor's working copy. Original repository retention is governed by a separate hospital-approved policy.

A study/vendor delivery may be eligible for cleanup only when all required conditions are met:

1. All expected instances for the study have been received, or the study has been explicitly closed under an approved exception process.
2. Validation completed.
3. De-identification completed.
4. The vendor has positively acknowledged successful receipt according to the agreed vendor protocol.
5. The Hub has recorded the vendor job ID or equivalent receipt evidence.
6. The configured delivery completion condition is satisfied.
7. No active retry, result-return or investigation process requires the files.
8. No legal hold, incident hold or administrator retention override applies.

**Important:** “100% uploaded” must be defined precisely. HTTP request completion, vendor API acceptance, vendor job creation and vendor confirmation that all DICOM instances are ingested are different events. The cleanup policy must use the strongest acknowledgement available from that vendor.

### 5.2 Configurable cleanup policy

For each vendor, the administrator shall configure an independent cleanup policy for that vendor's de-identified working copies and transfer artifacts. Policies may differ by vendor and use case. The policy must not cascade to original repository data, pseudonym mapping data, audit records or another vendor's data.

The administrator should be able to configure:

- Daily or weekly cleanup schedule.
- Retention duration after confirmed upload.
- Retention duration after result return.
- Separate retention for original repository objects and de-identified outbound copies.
- Independent per-vendor retention and cleanup schedules for each vendor's working copies and transfer artifacts.
- Separate retention for returned AI result objects.
- Failed, incomplete and quarantined study retention.
- Legal/incident hold.
- Dry-run report before deletion.
- Cleanup history and deletion audit.

The cleanup service must never delete a partially uploaded study or data belonging to a job that is still retrying.

The client BRD NFR-06 proposes that gateway cache be purged within 24 hours of result return. This needs to be reconciled with the requested daily/weekly cleanup and any clinical, operational, legal and audit retention obligations.

### 5.3 Data categories that must be treated separately

- Original received DICOM staging files.
- De-identified outbound DICOM copies.
- Returned AI DICOM result objects.
- Temporary converted images.
- Pseudonym-to-original identifier mapping, partitioned by hospital/site and protected separately.
- Operational job metadata.
- Audit logs.
- Credentials and certificates.

Do not automatically delete the pseudonym mapping key, audit trail or required job metadata when image files are purged. Their retention must be separately approved.

---

## 6. Proposed technology stack

| Layer | Proposed technology | Purpose |
|---|---|---|
| Operating system | Linux | On-premise host |
| Container platform | Docker Engine + Docker Compose | Deployment and service isolation |
| Web frontend | React / equivalent | Administration dashboard |
| Backend API | Python FastAPI or equivalent | Business logic and configuration APIs |
| Database | PostgreSQL | Configuration, jobs, metadata, audit references |
| DICOM networking | DCMTK / dcm4che / pynetdicom or equivalent | DICOM C-STORE and DICOM object processing |
| Queue | PostgreSQL-backed durable queue initially, or dedicated broker after load assessment | Persistent job processing |
| Object storage | Encrypted local filesystem or approved object storage | DICOM staging and result files |
| Reverse proxy | Nginx or equivalent | HTTPS and web routing |
| Monitoring | Prometheus/Grafana or equivalent | Service, host and queue monitoring |
| Logging | Centralised structured container logs | Troubleshooting and audit support |

Technology selection is provisional. The final DICOM library, queue technology and backend framework should be chosen after a proof of concept for DICOM throughput, transfer syntax support, concurrency and operational complexity.

### 6.1 PostgreSQL design principles

The data model must include a hospital/site entity from the first release, even where only one site is configured. Site ownership must be represented on studies, source systems, PACS destinations, vendor delivery jobs, routing rules, de-identification mapping namespaces, users/roles where applicable, retention policies and audit events. Enforce site-level access in the backend and database access patterns.

PostgreSQL should store operational metadata and configuration, not ordinarily the full DICOM pixel data as database blobs.

The database should contain:

- Hospital/site records and site-specific configuration.
- Source systems and AE configurations, linked to a hospital/site.
- PACS destinations and return-routing configuration, linked to a hospital/site.
- Vendor and connector configurations.
- Routing rules and versions.
- De-identification profiles and versions.
- Study, series and instance metadata required for operations, including source provenance and original-repository object references.
- Original DICOM repository object inventory, checksums and storage lifecycle status.
- Pseudonym mapping references, protected separately.
- Outbound jobs and per-vendor delivery records.
- Transfer attempts and vendor job IDs.
- Inbound result records.
- PACS delivery records.
- User, role and permission information.
- Audit events.
- Global and per-vendor cleanup policies and cleanup history, with separate original-repository retention records.

Use database migrations, indexes, constraints and transaction boundaries. Protect credentials using a secrets-management approach rather than storing plaintext credentials in ordinary configuration tables.

---

## 7. Docker deployment architecture

The initial installation is one dedicated Linux server for one hospital. The Docker deployment must persist the original DICOM repository on a dedicated mounted volume separate from the PostgreSQL data volume and separate from temporary vendor working-copy storage. Vendor working copies should be stored in clearly separated per-vendor/per-site directories or object namespaces. Backups must cover the database and original repository consistently; restore procedures must verify that database references and stored objects remain aligned.

Proposed initial container services:

```text
dicom-hub/
├── docker-compose.yml
├── .env.example
├── nginx/
│   └── nginx.conf
├── backend/
├── frontend/
├── dicom-receiver/
├── queue-worker/
├── vendor-connectors/
├── result-receiver/
├── cleanup-worker/
├── monitoring/
├── scripts/
└── docs/
```

Proposed containers:

| Container | Responsibility |
|---|---|
| web-frontend | Web user interface |
| backend-api | Application APIs and business logic |
| dicom-receiver | DICOM Storage SCP |
| queue-worker | Routing and outbound job processing |
| vendor-connectors | Vendor-specific communication |
| result-receiver | Webhooks / result DICOM reception |
| cleanup-worker | Retention and cleanup |
| postgres | Operational database |
| dicom-storage (managed host volume or storage service) | Persistent original repository and segregated working-copy/result storage; may be implemented as mounted volumes rather than a separate container |
| nginx | HTTPS reverse proxy |
| monitoring | Health and metrics services |

Production requirements:

- Persistent volumes for PostgreSQL and DICOM storage.
- Container health checks and restart policies.
- Secrets supplied securely; do not commit credentials to source control.
- Network segmentation and restricted outbound access.
- TLS certificates and renewal monitoring.
- Database backup and tested restoration.
- Separate configuration for development, UAT and production.
- Versioned images and controlled upgrade/rollback process.
- Logs and metrics retained according to policy.

---

## 8. Initial server sizing

The confirmed initial deployment target is one dedicated Linux server for one hospital, with an Intel Core i7 processor and 16 GB RAM as the proposed minimum. This is a pilot/initial sizing assumption and must be validated against actual daily volume, peak throughput, repository retention, concurrent vendor transfers and local de-identification workload. The future centralised multi-hospital option will require a new sizing exercise and may require distributed storage, additional worker nodes or a high-availability architecture.

The proposed minimum from the project owner is:

- Intel Core i7 processor.
- 16 GB RAM.
- Linux operating system.
- Docker deployment.

This is a provisional minimum for development, pilot or low-volume deployment only. It is not yet a validated production specification.

The client BRD requires capacity for peak study volume plus 50% headroom. Final sizing depends on:

- Studies per day and peak studies per hour.
- Average and maximum study size.
- Number of simultaneous modalities.
- Number of vendors and concurrent uploads.
- DICOM transfer syntax and any image conversion.
- Retention period and temporary storage requirement.
- Whether pixel-level burned-in text detection runs locally.
- Number of simultaneous result callbacks and PACS returns.

Before production approval, benchmark CPU, RAM, disk IOPS, network throughput and queue drain rate using representative studies.

### 8.1 Storage sizing

Estimate original repository capacity using:

`Original repository capacity = peak incoming data per day × original retention days × safety factor`

Estimate vendor working-copy capacity separately, taking into account concurrent vendor jobs, per-vendor retention and temporary transfer artifacts. Returned AI results require a separate allowance. The storage design must not count the original repository as disposable staging cache.

Also account for outbound copies, temporary conversion files and returned result objects. The storage plan must define free-space thresholds and behaviour when the disk approaches capacity.

---

## 9. Security, privacy and India DPDP considerations

The client BRD requires no directly identifying patient data to leave the hospital and requires pseudonymisation with the mapping key held on-premise.

The implementation should support the hospital's approved privacy and security controls, including:

- Data inventory and purpose mapping.
- Documented hospital/vendor roles and responsibilities.
- Approved lawful basis and patient notice/consent approach where applicable.
- Data minimisation and purpose limitation.
- Vendor due diligence and Data Processing Agreements.
- Vendor location, subprocessors and onward-transfer disclosures.
- Retention and secure deletion rules.
- Encryption in transit and at rest.
- Access control and audit.
- Incident and personal data breach response workflow.
- Backup, recovery and business continuity.
- Evidence of vendor deletion/retention commitments where required.

The Digital Personal Data Protection Act, 2023 and applicable rules/notifications must be reviewed by the hospital's DPO/legal team for the deployment date and processing arrangement. This specification is an engineering baseline, not a legal opinion or a compliance certification.

**Go-live privacy gate:** No external transfer should be enabled until the hospital approves the applicable privacy profile, vendor agreement, transfer destination, retention, security controls and test evidence.

---

## 10. Non-functional requirements and proposed targets

The following targets are drawn from the client BRD and should be confirmed during design sign-off.

| ID | Requirement | Proposed target |
|---|---|---|
| NFR-01 | AI turnaround | Last image to result visible in PACS: p90 ≤ 15 min; p99 ≤ 30 min unless use-case SLA differs |
| NFR-02 | Throughput | Peak volume across use cases plus 50% headroom |
| NFR-03 | Availability | Gateway 99.5% monthly, 24×7 |
| NFR-04 | Resilience | Queues survive restarts and automatically catch up after outages |
| NFR-05 | Transport security | TLS 1.2+ for external traffic |
| NFR-06 | At-rest security | AES-256 encryption for gateway cache and pseudonym key; cache purge target to be reconciled with retention policy |
| NFR-07 | Access | Role-based access; no shared accounts |
| NFR-08 | Audit | Immutable/tamper-resistant event history |
| NFR-09 | Network | Segmented hospital VLAN; outbound only to whitelisted vendor endpoints |
| NFR-10 | Monitoring | Alert IT/Command Centre within five minutes of failure |
| NFR-11 | Maintainability | Routing, mappings and de-identification profiles configurable |
| NFR-12 | Scalability | Add modalities and AI use cases without redesign |

Additional engineering targets to define:

- Maximum study size and instance count.
- Maximum concurrent DICOM associations.
- Queue capacity and maximum queue age.
- Database backup RPO/RTO.
- Recovery after host failure.
- Supported Linux distributions.
- Supported browsers.
- Audit and metadata retention.
- High availability / disaster recovery requirements.

---

## 11. UAT and acceptance criteria

The client BRD provides the following acceptance tests. They should be retained in the project test plan.

| ID | Test | Pass condition |
|---|---|---|
| AC-1 | Auto-routing | 50 consecutive eligible studies reach the correct AI without manual push; ineligible studies are not sent |
| AC-2 | De-identification | Independent check of 20 sent studies finds no name, real Patient ID, accession, birth date or burned-in text |
| AC-3 | Format | Vendor accepts 100% of valid studies; invalid studies are flagged with a reason |
| AC-4 | Return to PACS | AI SR and overlays appear under the correct original study for 100% of test cases |
| AC-5 | Viewing | Radiologist can toggle overlays and jump from finding to referenced slice |
| AC-6 | Report pre-fill | Findings appear in correct template fields as editable draft text; edits save and sign normally |
| AC-7 | Turnaround | p90 turnaround ≤ 15 minutes across the parallel run |
| AC-8 | Failure handling | Simulated outage queues studies, alerts within five minutes, and catches up without loss or duplicates |
| AC-9 | LLM prompt/reply | Approved prompts contain no identifiers; malformed or identifier-bearing replies are rejected |
| AC-10 | LLM accuracy | Sensitivity and specificity thresholds set by Radiology are met and re-tested after model version changes |

Each use case should complete testing on a test PACS followed by the two-week parallel run described in the BRD.

Additional DICOM Hub tests:

- Duplicate instance reception.
- Late-arriving images after study completion.
- Same study routed to multiple vendors.
- One vendor down while another remains available.
- Vendor accepts upload but delays processing.
- Webhook duplicated or delivered out of order.
- Invalid or mismatched pseudonym mapping.
- PACS unavailable during result return.
- Disk full / low disk condition.
- PostgreSQL restart and host restart.
- Cleanup dry-run and deletion eligibility.
- Cleanup interruption and safe resumption.
- Administrator changes a routing rule while jobs are queued.
- Vendor credentials expire.
- Unauthorized user attempts configuration or patient mapping access.

---

## 12. Proposed development phases

### Phase 1 – Discovery and interface confirmation

- Confirm modality and PACS DICOM connectivity.
- Confirm PACS fallback mechanism.
- Confirm DICOM result support and report-text interface.
- Obtain vendor API specifications and sample input/output studies.
- Agree the privacy/de-identification profile.
- Confirm volume, peak load, retention and hardware.

**Deliverable:** Signed interface specification and solution design.

### Phase 2 – Core DICOM Hub

- Docker foundation.
- PostgreSQL schema and migrations.
- Web login and role management.
- DICOM receiver.
- Study/series/instance tracking.
- Study completion logic.
- DICOM validation.
- Initial dashboard.

**Deliverable:** Hub receives and tracks test studies.

### Phase 3 – Routing and privacy

- Routing rule engine.
- Vendor/use-case configuration.
- De-identification profiles.
- UID and patient pseudonym mapping.
- Burned-in text detection/blocking workflow.
- Audit and privacy event history.

**Deliverable:** Correctly routed, de-identified test studies.

### Phase 4 – Vendor integration and resilient queue

- Vendor connector framework.
- First pilot vendor.
- Authentication and upload.
- Persistent queue and retry handling.
- Vendor job status and monitoring.
- Additional vendor onboarding.

**Deliverable:** End-to-end outbound delivery with outage recovery.

### Phase 5 – Result reception and PACS return

- Webhook/polling/result receiver.
- DICOM SR/GSPS/SEG validation.
- Re-identification.
- PACS C-STORE return.
- Report-template integration if supported and approved.

**Deliverable:** AI results safely returned to the original study.

### Phase 6 – Operations, retention and go-live

- Vendor-wise dashboard and reports.
- Alerts and operational runbooks.
- Cleanup policy and dry-run.
- Backup/restore and security testing.
- UAT and two-week parallel run.
- Production deployment and handover.

**Deliverable:** Production-ready DICOM Hub with administrator documentation.

---

## 13. Key risks and design controls

| Risk | Control |
|---|---|
| Wrong routing due to inconsistent Study Description | Rule priority, test mode, unmatched-study queue and reconciliation |
| Identifiers sent to vendor | On-premise de-identification gate and independent test |
| Burned-in identifiers | Detection, masking or blocking; manual review when uncertain |
| Vendor outage | Durable queue, retry and alerting |
| Duplicate study delivery | Study/vendor idempotency and deliberate resend workflow |
| Wrong patient result association | UID/pseudonym mapping validation; quarantine on mismatch |
| Vendor-specific API differences | Connector abstraction and versioned onboarding |
| PACS cannot pre-fill report | Confirm PACS/RIS support early; agree fallback |
| Premature cleanup | Positive upload evidence, retention rules, holds and dry-run |
| Server undersized | Workload benchmark and 50% headroom test |
| AI output over-trusted | Clear AI draft labels and radiologist review; no auto-sign |

---

## 14. Open decisions requiring client / hospital confirmation

1. Which RIS/PACS product and version are deployed?
2. Can the PACS forward studies to an external DICOM AE by rule?
3. Does the PACS accept DICOM SR, GSPS and SEG under the original Study Instance UID?
4. Which report interface will be used: DICOM SR, HL7 v2, FHIR or PACS API?
5. Which modalities will send directly to the Hub, and who will configure their routing?
6. What are the first pilot AI vendors and their API/interface specifications?
7. Does each vendor support webhook callbacks, polling, DICOM result push, or a combination?
8. Does the vendor confirm receipt of every instance, or only accept an upload/job request?
9. What is the expected daily and peak hourly study volume and average study size?
10. What is the required retention for original staging files, de-identified copies, results, mapping keys, metadata and audit logs?
11. Which privacy/legal basis, notices, DPA terms and vendor data locations have been approved?
12. Is the proposed i7 / 16 GB server intended for pilot only or production?
13. Is one Hub required per hospital/site, or will multiple sites connect to a central Hub?
14. What alert channels and escalation contacts are required?
15. Should a study be routed to multiple AI vendors simultaneously?
16. What should happen when no routing rule matches?
17. How should late-arriving images be handled after a vendor upload has started or completed?

---

## 15. Confirmed project decisions and remaining clarifications

### 15.1 Confirmed by the project owner

| Area | Confirmed decision |
|---|---|
| DICOM sources | Both direct modality C-STORE and RIS/PACS forwarding |
| Vendor outbound interface | REST/API or DICOM transfer, depending on vendor |
| Vendor result interface | Both inbound DICOM C-STORE and API/webhook/polling, depending on vendor |
| PACS return | Configurable per vendor |
| Vendor working-copy cleanup | Independent configurable policy for each vendor |
| Initial deployment | One dedicated Linux server for one hospital; proposed i7 / 16 GB RAM |
| Future deployment | Architecture should allow a centralised Hub for a hospital chain with separate PACS per hospital |
| Original image management | Hub must retain the received original patient images and source provenance before creating vendor-specific copies and forwarding for AI analysis |

### 15.2 Remaining clarifications before implementation

The following items are design gates, not minor configuration details:

- **PACS report integration:** Returning AI DICOM objects is separate from populating editable RIS/PACS report fields.
- **Vendor upload confirmation:** Cleanup must rely on a defined and auditable vendor acceptance/completion event.
- **DPDP privacy:** Pseudonymisation does not automatically make data anonymous. The hospital must approve the legal and privacy model.
- **UID mapping:** Returned DICOM objects must be mapped and validated safely before being associated with the original study.
- **Hardware sizing:** i7/16 GB is the stated initial single-hospital target, not a production capacity guarantee and not a validated centralised multi-hospital specification.
- **Original repository:** Hub-retained original images are distinct from temporary vendor working copies; vendor cleanup must not remove originals.
- **Hospital destination mapping:** Each study must retain its hospital/site identity so results are returned to that site's designated RIS/PACS, not a generic default destination.
- **Retention:** Image cleanup, pseudonym mapping, audit retention and backup retention require separate policies.

---

## 16. Source BRD traceability

This specification is based on the client's *BRD – PACS ↔ AI Integration Platform*, Draft v0.3.

| BRD section | Covered in this specification |
|---|---|
| 1–3: Document control, summary, objectives | Sections 1–2 |
| 4–5: Scope and future workflow | Sections 2–3, including the added repository and deployment-edition design |
| 6.1–6.2: Selection, routing and validation | Sections 4.1–4.3 |
| 6.3: Anonymisation | Section 4.6 |
| 6.4: Transfer and result return | Sections 4.7–4.8 |
| 6.5: Viewing and reporting | Section 4.9 |
| 6.6: Monitoring | Sections 4.10–4.11 |
| 6.7: Multi-vendor onboarding | Section 4.5 |
| 6.8–9: LLM and multimodal AI | Future extension; requires approved use cases and controls |
| 7: Privacy and anonymisation | Sections 4.6 and 9 |
| 8: Interfaces | Sections 4.1, 4.5, 4.8 and 4.9 |
| 10: Non-functional requirements | Section 10 |
| 11: Risks | Section 13 |
| 12: Acceptance criteria | Section 11 |
| 13: Open questions | Section 14 |

**Document note:** This is an initial engineering specification derived from the supplied BRD and the project owner's DICOM Hub description. Items not settled by the BRD are explicitly identified as decisions or questions. Final architecture, compliance controls, interfaces and sizing must be approved before production implementation.


---

## 17. Clarification update history

**Update 1 – Project owner clarification**

This revision incorporates the project owner's decisions:

- Both direct modality C-STORE and RIS/PACS forwarding are required.
- Vendor outbound and inbound interfaces vary by vendor; both API-based and DICOM-based methods are required.
- PACS return behaviour is configurable per vendor.
- Each vendor has an independent cleanup policy.
- Initial deployment is one dedicated Linux server for one hospital (i7 / 16 GB proposed).
- The architecture must be suitable for a future centralised hospital-chain deployment with separate PACS destinations.
- The Hub must retain original received patient images and source provenance before AI forwarding. Vendor-specific de-identified copies are separate from the original repository.

**Update 2 – Mandatory functional requirements for Live Incoming Study Telemetry & DICOM Configuration Access Control**

- **Live incoming study and image-count updates (Section 4.10.1):** The dashboard updates automatically in real time without manual page refresh as instances arrive from RIS-PACS or modalities, displaying Total Images Received, Images Received Today, and Images Received per Study, distinguishing `RIS-PACS` vs. `Modality` origin, displaying study receiving status (`Receiving`, `Completed`, `Failed`, `Incomplete`), avoiding double-counting duplicate/retransmitted instances, and incrementing counts only after each instance is validated and durably committed to the Original DICOM Repository.
- **DICOM configuration access control (Section 4.12.1):** DICOM configuration settings (Local AE Title/IP/Port, Modality & RIS-PACS sources, Destination AE Titles & PACS routing, DICOM connectivity/association settings, Vendor DICOM connector settings) are restricted to `Super User` (full View/Add/Edit/Delete) and `Administrator` (View/Add/Edit/Delete subject to assigned permissions), with zero access for other users, and full audit logging of user, timestamp, changed setting and action performed.
- **Universal search with fuzzy matching:** Global search across studies, jobs, DICOM nodes, routing rules, users and audit records supporting both exact/substring and fuzzy edit-distance matching.

**Architecture consequence:** The Hub is specified as a DICOM image management platform with an AI integration layer, rather than as a stateless forwarding gateway.
