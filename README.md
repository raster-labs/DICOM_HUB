# DICOM Hub – Vendor-Neutral PACS ↔ AI Integration Platform

DICOM Hub is an on-premise, multi-tenant DICOM image management and AI orchestration platform built with **Spring Boot 3.4 (Java 25 LTS) + `dcm4che` + PostgreSQL 16 + Flyway + Angular 19**.

## Key Capabilities

- **DICOM Storage SCP (`DICOMHUB_AE`):** Receives studies directly from modalities (`C-STORE`) and RIS/PACS fallback forwarding, capturing full source provenance (`source_type`, `calling_ae_title`) per hospital `site_id`.
- **Immutable Original DICOM Repository:** Stores unmodified received DICOM instances with SHA-256 checksums under `storage/original-repo/{site_code}/` before any outbound AI processing begins.
- **Zero-Code Configurable Routing Engine:** Matches studies by modality, study description, body part, and slice thickness to one or multiple AI vendors in priority order.
- **On-Premise DICOM PS3.15 Privacy Gate:** Creates isolated vendor working copies (`storage/working-copies/{site_code}/{vendor_code}/`), removes direct identifiers, replaces Patient ID / Accession / UIDs with pseudonyms backed by an on-premise AES-256-GCM encrypted vault, and blocks studies with burned-in pixel text (FR-14).
- **Resilient Outbound Queue & PACS Return:** Persists delivery jobs and transfer attempts in PostgreSQL with exponential backoff, validates returned DICOM SR (TID 1500) / GSPS / SEG objects (rejecting standalone PDFs per FR-19), stamps `"AI-generated – not verified"` (FR-21), restores original identifiers, and dispatches editable report drafts via pluggable strategies (`DICOM_SR_NATIVE`, `HL7_V2_ORU`, `FHIR_DIAGNOSTIC_REPORT`, `PACS_VENDOR_API`).
- **Independent Per-Vendor Cleanup:** Purges eligible vendor working copies after positive vendor receipt without ever deleting the Original DICOM Repository, pseudonym mappings, or audit history.

## Repository Structure

```text
DICOMHub/
├── AGENTS.md                        # Workspace engineering constitution & guardrails
├── .agents/
│   ├── agents/                      # 4 specialized subagents (DICOM, Privacy, Vendor, UI)
│   └── skills/                      # 5 operational skills & executable helper scripts
├── docs/
│   ├── ARCHITECTURE_DECISIONS.md    # Confirmed stack & architectural decisions
│   └── specs/                       # Client BRD v0.3 & Technical Development Specification
├── backend/                         # Spring Boot 3.4 + dcm4che 5.33 + Flyway + PostgreSQL
├── frontend/                        # Angular 19 + Tailwind CSS Command Centre Portal
├── nginx/                           # Reverse proxy configuration
├── monitoring/                      # Prometheus telemetry configuration
├── docker-compose.yml               # Multi-container on-premise deployment
└── Makefile                         # Developer shortcut commands
```

## Quick Start

```bash
# 1. Verify local developer toolchain
make healthcheck

# 2. Run DICOM PS3.15 De-identification & AC-2 Privacy Audit
make audit-deid

# 3. Run End-to-End DICOM C-STORE, Outage Recovery & Cleanup Simulation
make sim-cstore

# 4. Run full backend test suite
make test-backend
```
