---
name: dicom-cstore-simulator
description: >-
  Generate synthetic DICOM studies (CT KUB, CT Brain, MRI Brain, plus invalid or burned-in
  annotation test studies) and push them via DICOM C-STORE SCU or REST test endpoints to
  verify DICOM Hub reception, original repository provenance, quiet-period completion, and routing.
---

# DICOM C-STORE Simulator & Test Study Generator

Use this skill when testing DICOM C-STORE reception (`dicom-receiver`), study completion detection (FR-05), duplicate instance handling, burned-in text blocking (FR-14), or auto-routing rules (AC-1).

## Procedures

1. **Run the Java dcm4che C-STORE Test Suite or CLI Simulator:**
   - Execute the helper script to generate synthetic DICOM instances and send them via `dcm4che` C-STORE SCU to the local DICOM Hub SCP (`DICOMHUB_AE` on port `11112`):
     [send_test_study.sh](./scripts/send_test_study.sh)
2. **Test Scenarios Supported:**
   - `ct-kub`: Valid 5-slice CT KUB study (`Modality=CT`, `StudyDescription=CT KUB PLAIN`, `SliceThickness=1.5`) -> matches CT KUB AI vendor rule.
   - `ct-brain`: Valid CT Brain study (`Modality=CT`, `StudyDescription=CT BRAIN NON CONTRAST`) -> matches CT Brain AI vendor rule.
   - `burned-in`: Study with `BurnedInAnnotation (0028,0301) = YES` -> triggers FR-14 privacy block (`DEIDENTIFICATION_FAILED` / `MANUAL_REVIEW`).
   - `duplicate`: Sends an identical `SOPInstanceUID` twice to verify SHA-256 idempotent deduplication.
3. **Verify Reception & Provenance:**
   - Check that the unmodified files exist under `storage/original-repo/{site_code}/{study_uid}/` with matching SHA-256 checksums in `instance_records`.
   - Confirm via `GET /api/v1/studies?siteId=...` that `sourceType` (`MODALITY` or `RIS_PACS`) and `callingAeTitle` are recorded.
