---
name: dicom-ps315-deid-audit
description: >-
  Audit de-identified vendor working-copy DICOM files against the DICOM PS3.15 Basic
  Application Level Confidentiality Profile and Acceptance Criterion AC-2 to ensure
  zero real Patient ID, Patient Name, Birth Date, Accession Number, original UIDs, or
  burned-in annotations leave the hospital network.
---

# DICOM PS3.15 De-identification & AC-2 Privacy Audit Skill

Use this skill whenever editing de-identification profiles, `DeidentificationService`, UID remapping, or burned-in text detection (FR-11 to FR-15, AC-2).

## Mandatory Privacy Checklist (AC-2)

1. **Removed Attributes (Must be absent or empty in vendor working copy):**
   - `PatientName (0010,0010)`
   - `PatientBirthDate (0010,0030)`
   - `PatientAddress (0010,1040)`
   - `PatientTelephoneNumbers (0010,2154)`
   - `InstitutionName (0008,0080)`
   - `ReferringPhysicianName (0008,0090)`
   - `PerformingPhysicianName (0008,1050)`
   - Unapproved Private Tags (odd group numbers)

2. **Pseudonymised Attributes (Must NOT equal original values; mapping encrypted in `pseudonym_mappings`):**
   - `PatientID (0010,0020)` -> `PSEUDO-PAT-*`
   - `AccessionNumber (0008,0050)` -> `PSEUDO-ACC-*`
   - `StudyInstanceUID (0020,000D)` -> `2.25.*`
   - `SeriesInstanceUID (0020,000E)` -> `2.25.*`
   - `SOPInstanceUID (0008,0018)` -> `2.25.*`

3. **Preserved Clinical Attributes (Per Vendor Profile):**
   - `PatientAge (0010,1010)`, `PatientSex (0010,0040)`, `PixelSpacing (0028,0030)`, `SliceThickness (0018,0050)`, `KVP (0018,0060)`

4. **Verification Step:**
   - Run [audit_deid_files.sh](./scripts/audit_deid_files.sh) to execute automated PS3.15 and AC-2 compliance checks.
