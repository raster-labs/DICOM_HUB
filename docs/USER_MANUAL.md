# DICOM Hub – Complete Operator & Workflow Testing User Manual

> [!IMPORTANT]
> **Live LAN Application URLs:**
> - **Command Centre Web Portal (Angular 19 Light Mode):** http://172.17.0.250:4200 (or http://172.17.2.146:4200)
> - **Unified Spring Boot Portal & REST API:** http://172.17.0.250:8080 (or http://172.17.2.146:8080)
> - **DICOM Storage SCP (`C-ECHO` / `C-STORE`):** `172.17.0.250:11112` · **Called AE Title:** `DICOMHUB_AE`

---

## 1. End-to-End Architecture & Workflow Overview

```mermaid
flowchart LR
    M["1. Modality Console or PACS Forwarder"] -->|DICOM C-STORE to DICOMHUB_AE:11112| OR["2. Immutable Original Repo (SHA-256 Commit)"]
    OR -->|Quiet Period & FR-07 Validation| RE["3. Priority Routing Rule Engine"]
    RE -->|Isolated Vendor Working Copy| DEID["4. DICOM PS3.15 De-Id + FR-14 Burned-In Gate"]
    DEID -->|AES-256 Vault Key Kept On-Prem| Q["5. Durable Outbound Queue & Retry"]
    Q -->|REST / STOW-RS / C-STORE / LLM| AI["6. External AI Vendor / LLM"]
    AI -->|Webhook / Polling Result| VAL["7. Result Validation (FR-19) & Re-Id (FR-20)"]
    VAL -->|Stamp 'AI-generated – not verified' (FR-21)| PACS["8. Site Designated PACS + Editable Report Draft (I-6)"]
```

---

## 2. Top Navigation Bar Controls

At the top right of the Command Centre screen (http://172.17.0.250:4200):

| Control | Options | What It Does |
|---|---|---|
| **Hospital Site** | `KAUVERY-CHN-01 – Chennai Flagship`<br>`KAUVERY-TRY-02 – Trichy Cantonment` | Switches the active hospital tenant (`site_id`). Every study, AI vendor, routing rule, PACS destination, cleanup policy, and audit log is strictly isolated per hospital site. |
| **Role** | `System Administrator`<br>`PACS / IT Administrator`<br>`Privacy / DPO Reviewer`<br>`Command Centre Ops`<br>`Radiologist Reviewer` | Switches your active operator role context for audited actions (`audit_events`). |
| **SCP Badge** | `SCP: DICOMHUB_AE : 11112` | Confirms the live `dcm4che` DICOM C-STORE listener AE Title and port. |
| **Refresh Telemetry** | Button | Polls the latest counts, jobs, results, alerts, and audit trail for the selected hospital site. |

---

## 3. How to Configure an AI Agent / AI Vendor

### Step 3A: Onboard a Traditional Imaging AI Vendor (`Tab 5. Vendors, Sources & PACS`)

1. Click **Tab `5. Vendors, Sources & PACS`** in the workspace tab bar.
2. Locate the **"+ Onboard New AI Vendor / Use Case (FR-33 & FR-34)"** card at the top of Tab 5.
3. Fill in (or use the pre-populated values):
   - **Vendor Code:** Unique short code (e.g., `VENDOR-CHEST-AI`).
   - **Vendor Name:** Vendor organization name (e.g., `ThoraxSight Radiology AI`).
   - **Clinical Use Case:** Clinical workflow name (e.g., `CT Chest Pulmonary Nodule & PE Triage`).
   - **Connector Protocol:**
     - `REST_MULTIPART` — Outbound HTTPS multipart upload (OAuth2 / mTLS / API key).
     - `DICOMWEB_STOW` — Outbound DICOMweb `STOW-RS` (`application/dicom`).
     - `DICOM_CSTORE` — Outbound DICOM `C-STORE` push to a vendor DICOM AE node.
     - `LLM_MULTIMODAL` — Phase 2 Multimodal LLM connector via the Prompt Builder & slice converter.
   - **API / Endpoint URL:** Target endpoint (e.g., `https://api.thoraxsight.example.com/v1/dicom`).
4. Click **"Onboard AI Vendor"**.
   - **What happens automatically:**
     - Links the site's **DICOM PS3.15 De-identification Profile** (`PS315-KAUVERY-STD`).
     - Links the site's **Designated Return PACS** (`KAUVERY_PACS_SCP`).
     - Creates an **independent Per-Vendor Working Copy Cleanup Policy** (`VENDOR_WORKING_COPY`, 24h retention) so this vendor's temporary files are managed separately from all other vendors and from the Original DICOM Repository.
5. In the **Vendors table** immediately below:
   - Click **"Test Connection"** to verify authentication and endpoint reachability.
   - Click **"Promote Live" / "Set Shadow"**:
     - **SHADOW Mode (`FR-36`):** Processes studies and stores AI results in DICOM Hub for clinical validation, but **does not forward results to the radiologist's PACS worklist**.
     - **LIVE Mode:** Forwards re-identified DICOM SR (`TID 1500`) + overlays and editable report drafts directly to the hospital's PACS/RIS.
   - Click **"Pause" / "Activate"** to pause or resume routing to that vendor at any time without code changes.

### Step 3B: Configure & Test Phase 2 Multimodal LLM Agents (`Tab 7. Phase 2 LLM Gate`)

1. Click **Tab `7. Phase 2 LLM Gate`**.
2. Inspect the left panel (**"Approved Multimodal LLM Registry"**) and right panel (**"Versioned Clinical Prompt Templates"**):
   - Only models on Kauvery's approved registry with a **pinned version** (`pinnedVersion`) and **Radiology + Legal approval** can be used (`FR-38`, `FR-44`).
   - Every prompt is built automatically from the approved template (`{{modality}}`, `{{body_part}}`, `{{patient_age}}`, `{{patient_sex}}`, `{{slice_thickness_mm}}`) — no user ever types a free-text prompt per study (`FR-39`).
3. Use the **"AC-9 LLM Prompt & Reply Zero-Identifier Gate Validator"** card at the bottom of Tab 7:
   - Click **"Test Clean Prompt & Reply (Pass AC-9)"** → Verifies that a de-identified CT KUB prompt and valid JSON findings reply pass the gate and generate an `"AI draft (LLM, model and version), not verified"` label.
   - Click **"Test Leaked PHI Injection (Block AC-9)"** → Injects a sample Patient ID (`KH-PAT-998877`) and patient name (`RAMESH KUMAR`) to verify that the privacy gate immediately **blocks** the request and logs a `BLOCKED` security audit event.

---

## 4. How to Configure Forwarding Rules & Report Messages

### Step 4A: Configure Incoming Modality & PACS Sources (`Tab 5. Vendors, Sources & PACS`)

1. Open **Tab `5. Vendors, Sources & PACS`** and look at the bottom-left panel (**"Configured DICOM Sources"**).
2. Pre-configured sources for `KAUVERY-CHN-01`:
   - `KAUVERY_CT01` (`MODALITY`) — Direct CT Scanner Console auto-routing (`120s` quiet period).
   - `KAUVERY_MR01` (`MODALITY`) — Direct MRI Scanner Console auto-routing (`120s` quiet period).
   - `KAUVERY_PACS` (`RIS_PACS`) — Kauvery Central PACS Fallback Forwarder (`60s` quiet period).
3. Click **"Test C-ECHO"** next to any source to verify the DICOM association with that node.

### Step 4B: Create & Test Study Forwarding Rules (`Tab 4. Routing Rules & Sandbox`)

1. Click **Tab `4. Routing Rules & Sandbox`**.
2. **Test a Rule in the Dry-Run Sandbox (Top Card):**
   - Enter sample study attributes:
     - `Modality`: `CT`
     - `Study Description (0008,1030)`: `CT KUB CALCULUS PROTOCOL`
     - `Body Part Examined (0018,0015)`: `ABDOMEN`
     - `Slice Thickness (mm)`: `1.5`
   - Click **"Evaluate Sample Metadata"**.
   - The sandbox banner shows which rule(s) matched (e.g., `Matched 1 rule(s): #2 Rule 2 – CT KUB Auto-Route (Pilot) → VENDOR-KUB-AI`).
3. **Add a New Forwarding Rule (Middle Card – `FR-03`):**
   - Set **Priority** (e.g., `4` — lower numbers evaluate first).
   - Set **Rule Name** (e.g., `Rule 4 – CT Chest AI Triage`).
   - Set **Modality** (e.g., `CT`).
   - Set **Study Desc Pattern** (e.g., `CHEST` or `ANGIO`).
   - Select **Target AI Vendor** from the dropdown (including any new vendor you onboarded in Tab 5).
   - Click **"Save Routing Rule"**.
4. In the **Routing Rules table** at the bottom of Tab 4, use **"Pause" / "Activate"** or **"Delete"** to manage active rules.

### Step 4C: Configure Return PACS & Editable Report Pre-Fill Message (`Tab 5. Vendors, Sources & PACS`)

1. Open **Tab `5. Vendors, Sources & PACS`** and look at the bottom-right panel (**"Designated Site PACS Destinations & Report Pre-Fill Strategy (I-6)"**).
2. Use the dropdown on the PACS destination row (`KAUVERY_PACS_SCP`) to switch the report pre-fill message format at any time:
   - **`DICOM_SR_NATIVE (Option A)`:** Sends a native DICOM Structured Report (`TID 1500`) + GSPS/SEG overlays via DICOM `C-STORE` to PACS under the original `StudyInstanceUID`.
   - **`HL7_V2_ORU (Option B)`:** Dispatches an editable preliminary (`OBX-11 = 'P'`) HL7 v2 `ORU^R01` observation message containing each structured finding (type, location, size in mm, density in HU, confidence) to the RIS/PACS report template.
   - **`FHIR_DIAGNOSTIC_REPORT (Option C)`:** Dispatches a preliminary (`status: "preliminary"`) HL7 FHIR R4 `DiagnosticReport` + `Observation` JSON bundle.
   - **`PACS_VENDOR_API (Option D)`:** Dispatches a JSON draft payload to the PACS vendor's REST API endpoint.

---

## 5. Step-by-Step Workflow Testing Guide (7 UAT Scenarios)

Use the **6 simulator buttons** in the **"Live DICOM C-STORE, PS3.15 Privacy Gate & Outage Simulator"** bar near the top of http://172.17.0.250:4200 to test every acceptance criterion end-to-end:

### Scenario 1: Happy-Path CT KUB Auto-Routing, PS3.15 De-Id & PACS Return (`AC-1`, `AC-2`, `AC-4`, `AC-6`)
1. Click **`+ CT KUB (Auto-Route → SR)`** in the top simulator bar.
2. **Observe Overview Cards & Vendor Matrix:**
   - `Total Studies in Original Repo` (`Modality`), `Studies Routed to AI`, `Vendor-Confirmed Uploads`, and `AI Results Returned to PACS` all increment by `1`.
3. **Verify Original Repository & SHA-256 Provenance (`Tab 1`):**
   - In **Tab `1. Original Repo & Provenance`**, click **"Inspect Provenance & SHA-256"** on the new `CT · CT KUB CALCULUS PROTOCOL` row.
   - Verify the drawer shows the unmodified file path under `storage/original-repo/KAUVERY-CHN-01/...` and its 64-character **SHA-256 checksum**.
4. **Verify Returned AI Finding & Radiologist Review (`Tab 3`):**
   - Click **Tab `3. AI Results & Report Pre-Fill`**.
   - Verify the result carries the mandatory label **`AI-generated – not verified`**, your selected PACS report pre-fill strategy (`DRAFT_SENT_...`), and the structured stone JSON (`renal_calculus`, `LEFT`, `lower_pole`, `6.4 mm`, `840 HU`).
   - Click **"Accept"**, **"Edit Draft"**, or **"Reject"** in the right column to record the radiologist's disposition (`FR-25`, `FR-27`).

### Scenario 2: CT Brain Stroke Triage Auto-Routing (`Rule 1`)
1. Click **`+ CT Brain (Stroke Triage)`** in the top simulator bar.
2. Verify in the **Vendor-Wise Matrix** that **`NeuroScan Stroke AI (VENDOR-NEURO-CT)`** increments its `Routed`, `Vendor Accepted`, and `Results (PACS / Total)` counts.

### Scenario 3: MRI Brain Shadow-Mode Validation (`FR-36`)
1. Click **`+ MRI Brain (Shadow FR-36)`** in the top simulator bar.
2. Open **Tab `3. AI Results & Report Pre-Fill`**.
3. Notice that the result from **`DeepMRI Brain Volumetry (VENDOR-NEURO-MR)`** is received and re-identified, but its PACS & Report status is **`SKIPPED_SHADOW_MODE`** (hidden from the clinical PACS worklist).
4. *(Optional)* Go to **Tab `5. Vendors, Sources & PACS`**, click **"Promote Live"** on `VENDOR-NEURO-MR`, and click **`+ MRI Brain (Shadow FR-36)`** again — the new result will now forward live to PACS!

### Scenario 4: Burned-In Pixel Text Privacy Block (`FR-14`, `FR-15`, `AC-2`)
1. Click **`+ Burned-In Text (FR-14 Block)`** in the top simulator bar.
2. **What is tested:** Simulates a PACS-forwarded study (`sourceType = RIS_PACS`) with `BurnedInAnnotation (0028,0301) = YES`.
3. **Verify Block & Alert:**
   - Look at the 5th overview card (**"Exceptions / Blocked / Retry"**) — it increments by `1`.
   - Open **Tab `2. Durable Outbound Queue`**: the job status is **`DEIDENTIFICATION_FAILED`** with `Working Copy Namespace = Blocked pre-copy` and error `FR-14 Blocked: BurnedInAnnotation (0028,0301) = YES`.
   - Open **Tab `8. Alerts & Audit`**: a **`[CRITICAL · PRIVACY_GATE_FR14]`** alert is active at the top of the tab, proving zero identifiable images left the hospital.

### Scenario 5: Vendor Outage Queueing, Exponential Backoff & Recovery (`FR-17`, `AC-8`)
1. Click **`+ Vendor 503 Outage (AC-8 Retry)`** in the top simulator bar.
2. Open **Tab `2. Durable Outbound Queue`**:
   - Find the job in status **`RETRY_WAIT`** (`Attempts: 1 / 5`) showing `Transient 503 error; scheduled exponential backoff retry in 10s`.
3. Click **"Manual Retry"** on that job row in Tab 2 (or wait 10 seconds and click **"Refresh Telemetry"** after the background `DurableQueueWorkerService` processes it).
4. Verify the job recovers to **`VENDOR_ACCEPTED`** / **`COMPLETED`** without losing or duplicating the study.

### Scenario 6: Pre-Routing DICOM Validation Failure (`FR-07`, `AC-3`)
1. Click **`+ Invalid Slice (FR-07 Block)`** in the top simulator bar.
2. Open **Tab `1. Original Repo & Provenance`**:
   - The study (`CT KUB THICK SLICE INVALID`) shows lifecycle state **`VALIDATION_FAILED`** with reason `FR-07 Validation Failed: SliceThickness (12.0 mm) exceeds maximum supported threshold (10.0 mm)`.
   - Zero outbound vendor jobs are created for invalid studies.

### Scenario 7: Independent Per-Vendor Working Copy Cleanup vs. Protected Original Repository (`Section 5.1 & 5.2`)
1. Open **Tab `6. Per-Vendor Cleanup`**.
2. Notice separate policies for **`VENDOR_WORKING_COPY`** (`AUTO-PURGE WORKING COPIES`) and **`ORIGINAL_REPOSITORY`** (`DRY-RUN AUDIT ONLY (Protected Original Repo)`).
3. On the first `VENDOR_WORKING_COPY` row:
   - Click **"Dry-Run Report"** → Top green banner shows how many completed vendor working copies are eligible for purge without deleting files.
   - Click **"Purge Working Copies Now"** → Deletes the de-identified vendor working copy files from `storage/working-copies/`.
4. Open **Tab `2. Durable Outbound Queue`** → The completed job now shows `PURGED (...)` under `Working Copy Namespace`.
5. Open **Tab `1. Original Repo & Provenance`** and click **"Inspect Provenance & SHA-256"** → The original received DICOM object in `storage/original-repo/` is still **100% intact**.

---

## 6. Testing Multi-Hospital Isolation (`KAUVERY-TRY-02`)

1. In the top header bar, change **Hospital Site** from `KAUVERY-CHN-01` to **`KAUVERY-TRY-02 – Kauvery Hospital – Trichy Cantonment`**.
2. Notice all counters, studies, rules, sources (`KAUVERY_TRY_CT01`), and PACS destinations (`KAUVERY_TRY_PACS_SCP`, configured with `HL7_V2_ORU` report pre-fill) switch to Trichy's isolated tenant namespace.
3. Click **`+ CT KUB (Auto-Route → SR)`** while on `KAUVERY-TRY-02` and check **Tab `3. AI Results & Report Pre-Fill`** — you will see Trichy's result delivered using **`HL7_V2_ORU`** (`DRAFT_SENT_HL7_ORU_R01_PRELIMINARY_STATUS_P`) to `KAUVERY_TRY_PACS_SCP`!
