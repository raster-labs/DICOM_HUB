---
name: multi-tenant-db-migration
description: >-
  Author and audit Flyway PostgreSQL schema migrations and Spring Data JPA entities
  to enforce site_id hospital tenant isolation, Original Repository vs. Working Copy
  separation, durable job state constraints, and append-only audit logs.
---

# Multi-Tenant Database Migration & Schema Audit Skill

Use this skill whenever adding or modifying Flyway SQL migrations (`backend/src/main/resources/db/migration/V*.sql`) or JPA entities (`backend/src/main/java/com/kauvery/dicomhub/domain/entity/`).

## Schema Guardrails (Specification Sections 3.1, 3.2, 5.1, 6.1)

1. **Mandatory `site_id` Column & Foreign Key:**
   - Every operational table (`dicom_sources`, `pacs_destinations`, `study_records`, `series_records`, `instance_records`, `routing_rules`, `deid_profiles`, `pseudonym_mappings`, `vendor_delivery_jobs`, `ai_result_records`, `cleanup_policies`, `audit_events`) MUST have a `site_id UUID NOT NULL REFERENCES sites(id)` column and an index starting with `site_id`.
2. **No DICOM Pixel Blobs in PostgreSQL:**
   - Store filesystem/object storage paths (`original_repo_path`, `working_copy_dir`, `result_storage_path`) and `sha256_checksum` in PostgreSQL — never raw DICOM binaries.
3. **Retention & Cascade Safety:**
   - Never put `ON DELETE CASCADE` from a vendor job cleanup onto `study_records`, `instance_records`, `pseudonym_mappings`, or `audit_events`.
4. **Verification Script:**
   - Run [check_migrations.sh](./scripts/check_migrations.sh) to verify Flyway migration naming, `site_id` enforcement, and JPA schema validation.
