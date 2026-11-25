# BRD – PACS ↔ AI Integration Platform (vendor-agnostic)

## 1. Document control

| Item | Detail |
|---|---|
| Document | Business Requirements Document (BRD) |
| Project | Automated PACS ↔ AI integration for radiology (Digital Monitoring Command Centre) |
| Version | 0.3 – Draft for review |
| Applies to | Every AI vendor and imaging use case integrated with Kauvery PACS |
| Inputs | Today’s manual AI workflow at Kauvery; a discovery call with a current AI vendor, used as reference only |
| Reviewers needed | Head of Radiology, Kauvery IT / PACS admin, Data Protection Officer, modality engineers |

## 2. Executive summary

Require Raster to build one integration layer that sends each eligible study to the right AI automatically or manually and brings the results back into PACS as viewable findings and editable report text, for any AI vendor.

Today, AI analysis starts by hand. Staff select each case and push it to a vendor’s DICOM node on a Kauvery server, where the vendor’s own software anonymises it and sends it out by API. Results return as PDF images, and doctors retype the findings.

This BRD replaces that with a vendor-agnostic platform. Routing rules decide which AI receives which study, one Gateway handles de-identification and transfer, and every vendor returns results in a standard Kauvery defines. Adding a new AI then means a rule and a vendor onboarding, not a new pipeline.

## 3. Business objectives and success metrics

| # | Objective | Metric | Proposed target |
|---|---|---|---|
| O1 | Remove manual case selection and push | Eligible studies auto-routed to the correct AI without staff action | ≥ 98% |
| O2 | Faster AI availability for reporting | Time from last image acquired to AI result visible in PACS | ≤ 15 min (p90), unless a use case’s SLA says otherwise |
| O3 | Protect patient privacy | Studies leaving Kauvery with direct identifiers, any vendor | 0 |
| O4 | Findings usable in the report | AI findings pre-filled into the PACS/RIS template as editable text | 100% of processed studies |
| O5 | Add new AI quickly | Time to bring a new AI use case live once contract and DPA are signed | ≤ 4 weeks |
| O6 | Stop informal result channels | Results delivered by WhatsApp or PDF screenshots | 0 after go-live |
| O7 | Traceability | Studies with complete audit trail (sent, processed, returned, accepted/edited) | 100% |
| O8 | Clinical acceptance | Radiologists rating AI pre-fill as useful in a 4-week review per use case | ≥ 80% |

## 4. Scope

### In scope
- Rule-based routing of eligible studies to the right AI, from the modality (auto-routing on the console) or from PACS (rule-based forwarding), replacing manual push.
- Several AI vendors and use cases running at once, each with its own routing rules, de-identification profile and output mapping, including general-purpose multimodal LLMs connected through the same gateway with automatically generated or editable prompts.
- One on-premise gateway, controlled by Kauvery, that receives, validates, de-identifies, sends and tracks studies for every vendor.
- Pseudonymisation with a re-identification key held only on Kauvery premises.
- Secure API transfer to each vendor and asynchronous return of results.
- Re-identification of results and storage in PACS under the original Study Instance UID.
- AI findings viewable in the PACS viewer and mapped into the report template as editable draft text.
- A standard vendor onboarding pack: interface spec, output standard, test set, DPA and acceptance tests.
- Status tracking, alerts and a full audit log for the Command Centre.
- Retiring WhatsApp and PDF-image result delivery.

### Out of scope
- Building, training or clinically validating any vendor’s AI model.
- Choosing or procuring AI vendors, and commercial terms.
- Replacing or upgrading the PACS, RIS or viewer.
- Automatic report sign-off: a radiologist always reviews and signs.
- Non-imaging AI, such as lab or vitals analytics.

## 6. Functional requirements (FR-01 to FR-45)

### 6.1 Study selection and routing
- **FR-01 (Must):** Each modality auto-routes eligible studies to the gateway via DICOM C-STORE, using rules configured on the console.
- **FR-02 (Should):** PACS can forward studies to the gateway by rule, as a fallback where modality routing is not possible.
- **FR-03 (Must):** Routing rules (study description, body part, protocol name, series description) can be changed by IT without code changes.
- **FR-04 (Should):** A radiologist can send any study to AI on demand from the PACS worklist.
- **FR-05 (Must):** The gateway waits until a study is complete (no new images for a set time, e.g. 120 s) before processing.
- **FR-06 (Must):** A study is not sent twice unless a user re-sends it deliberately.

### 6.2 Validation and format conversion
- **FR-07 (Must):** The gateway checks each study for required DICOM tags, transfer syntax, slice count and slice thickness, and flags studies the model cannot accept.
- **FR-08 (Should):** Only the series the model needs (as stated in that vendor’s onboarding spec) are sent.
- **FR-09 (Must):** Conversion to each model’s input format happens on the vendor’s platform by default; the gateway sends standard DICOM unless required otherwise.
- **FR-10 (Must):** Original images in PACS are never altered.

### 6.3 Anonymisation
- **FR-11 (Must):** Studies are de-identified on Kauvery premises before leaving the network, to the DICOM PS3.15 Basic Application Level Confidentiality Profile.
- **FR-12 (Must):** Patient ID, accession number and all UIDs are replaced with pseudonyms; the mapping key stays encrypted on-premise. No vendor ever receives the real Patient ID.
- **FR-13 (Must):** Clinically needed, non-identifying attributes are kept (age, sex, pixel spacing, slice thickness, kVp), as set in each vendor’s de-identification profile.
- **FR-14 (Must):** Burned-in text on images is detected and the study blocked or masked.
- **FR-15 (Must):** If de-identification fails, the study is not sent and an alert is raised.

### 6.4 Transfer to AI and result return
- **FR-16 (Must):** The gateway sends studies to each vendor’s API over TLS 1.2+ with authenticated clients, and receives a job ID.
- **FR-17 (Must):** Failed sends retry with back-off; the queue survives a gateway restart.
- **FR-18 (Should):** Each vendor notifies the gateway by webhook when results are ready (polling as fallback).
- **FR-19 (Must):** Each vendor returns DICOM results: a Structured Report with findings, and overlays (GSPS, segmentation or secondary capture) marking each finding. A PDF alone does not meet this requirement; an encapsulated PDF is optional.
- **FR-20 (Must):** The gateway re-identifies results and stores them in PACS under the original Study Instance UID.
- **FR-21 (Must):** Every AI object is labelled “AI-generated – not verified” in its series description.

### 6.5 Viewing and reporting
- **FR-22 (Must):** The doctor sees AI highlights as an overlay on the original images in the PACS viewer and can switch them on or off.
- **FR-23 (Should):** The viewer lists each finding with location, size (mm), density (HU) and confidence.
- **FR-24 (Must):** Structured findings are mapped into the fields of the matching report template in PACS/RIS, for each use case.
- **FR-25 (Must):** Findings arrive as editable draft text; the radiologist accepts, edits or rejects each one and signs. Nothing is auto-signed.
- **FR-26 (Should):** Template mappings are configurable per study type and template version.
- **FR-27 (Should):** The system records whether each AI finding was accepted, edited or rejected.
- **FR-28 (Should):** The worklist flags studies with AI results ready, and raises priority for critical findings.

### 6.6 Monitoring and channels
- **FR-29 (Should):** A Command Centre dashboard shows each study’s status: queued, sent, processing, returned, failed.
- **FR-30 (Must):** Failures and turnaround breaches alert IT and the Command Centre.
- **FR-31 (Must):** Result delivery by WhatsApp and PDF image stops at go-live.

### 6.7 Multi-vendor routing and onboarding
- **FR-32 (Must):** Routing rules map each study to one or more AI use cases by modality, body part, study description and protocol; one study can go to several AIs.
- **FR-33 (Must):** Each AI use case has its own configuration: vendor endpoint, credentials, de-identification profile, series filter, output mapping and SLA.
- **FR-34 (Must):** A new AI vendor or use case can be added, paused or retired by configuration, without changing gateway code.
- **FR-35 (Should):** Vendors return results in the Kauvery output standard; where one cannot, the gateway converts its format before storing in PACS.
- **FR-36 (Should):** A new use case runs in shadow mode, with results hidden from doctors, on a test set before going live.
- **FR-37 (Must):** Every result shows which vendor, model and model version produced it.

### 6.8 LLM and multimodal AI connection (Phase 2 Extension)
- **FR-38 to FR-45:** Approved LLM registry, Prompt Builder from versioned templates, key series/slice selection + CT window preset + JPEG/PNG conversion, JSON findings schema validation + PII scan + single retry, connector draft review, pinned model version, monthly accept/edit/reject reporting.

### 6.9 Live incoming study and image-count updates (Mandatory Functional Requirement)
- **FR-46 (Must):** The DICOM Hub dashboard must display incoming studies from RIS-PACS (and Modality) in real time without requiring a manual page refresh:
  - Whenever the Hub receives a DICOM image from RIS-PACS, the received-image count must increment automatically **after** the DICOM instance has been successfully received, validated (SHA-256 verified) and durably committed to the Original DICOM Repository — not merely when a transfer request starts.
  - Display **total images received**, **images received today**, and **images received per study**.
  - Explicitly display the study source as **RIS-PACS** (`RIS_PACS`) or **Modality** (`MODALITY`) so administrators can distinguish where images originated.
  - Display the study's receiving status: **Receiving**, **Completed**, **Failed** or **Incomplete**.
  - Update the study-level image count live as individual DICOM instances arrive.
  - Avoid double-counting retransmitted or duplicate instances; distinguish total received transfer events from unique stored instances.

### 6.10 DICOM configuration access control & user management (Mandatory Functional Requirement)
- **FR-47 (Must):** DICOM configuration settings must be accessible only to authorised **Super Users** or **Administrators**:
  - **Super User (`SUPER_USER`):** View, add, edit and delete all DICOM configurations and manage users.
  - **Administrator (`ADMINISTRATOR`):** View, add, edit and delete DICOM configurations, subject to assigned granular permissions (`can_view_dicom_config`, `can_create_dicom_config`, `can_edit_dicom_config`, `can_delete_dicom_config`).
  - **Other users (`RADIOLOGIST`, `TECHNOLOGIST`, `OPERATIONS`, `AUDITOR`):** No access to DICOM configuration settings.
  - Configuration access covers: Local AE Title, IP address and port; Modality and RIS-PACS source configurations; Destination AE Titles and PACS routing; DICOM connectivity and association settings; and Vendor DICOM connector settings.
  - All configuration changes must be recorded in an immutable audit log, including the **user**, **timestamp**, **changed setting** and **action performed**.

### 6.11 Universal search with fuzzy matching
- **FR-48 (Must):** Provide a universal search capability with an optional fuzzy matching mode across studies, patient forwarding logs, delivery jobs, DICOM nodes, routing rules, users and audit events.
