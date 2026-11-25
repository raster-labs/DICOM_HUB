---
name: vendor-connector-scaffold
description: >-
  Onboard or scaffold a new AI vendor connector, configure its de-identification profile,
  series selection filter, authentication (OAuth2 / mTLS / API Key), webhook/polling result
  handlers, and independent working-copy cleanup policy.
---

# AI Vendor Connector Onboarding & Scaffolding Skill

Use this skill when adding a new AI vendor integration, configuring vendor use cases (FR-32 to FR-37), or implementing a custom `VendorConnector` adapter.

## Steps to Onboard a Vendor

1. **Determine if the Vendor Uses a Standard or Custom Protocol:**
   - **Standard REST / DICOMweb / C-STORE:** Can be onboarded purely via configuration (`POST /api/v1/vendors`) without code changes (FR-34).
   - **Custom Proprietary REST Workflow:** Implement a Spring `@Component` adopting `VendorConnector` using [VendorConnectorTemplate.java](./resources/VendorConnectorTemplate.java).

2. **Required Vendor Onboarding Checklist (Specification Section 4.5):**
   - Unique `vendorCode` and `siteId` enablement.
   - Outbound authentication (`OAUTH2_CLIENT_CREDENTIALS`, `MTLS`, `API_KEY`) with secrets stored via environment/vault reference, never plaintext in logs.
   - Assigned `DeidProfile` (PS3.15 baseline + approved clinical tag retention).
   - Series filter rules (required `Modality`, `SeriesDescription` pattern, max slice thickness).
   - Positive receipt acknowledgement mode (`JOB_ID_CREATED` vs. `ALL_INSTANCES_INGESTED`) to prevent premature working-copy cleanup.
   - Result return verification (DICOM SR TID 1500 + GSPS/SEG overlays; standalone PDF rejected per FR-19).
   - Shadow Mode (`shadowMode = true`) initially enabled until AC-1 through AC-8 pass on the test set (FR-36).
