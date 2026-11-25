# DICOM Hub – Release Management & Docker Server Deployment Guide

This guide covers the simplest end-to-end steps to cut a versioned release on GitHub (`https://github.com/raster-labs/DICOM_HUB`) and deploy or upgrade **DICOM Hub** on an on-premise hospital server using Docker.

---

## 1. Architecture Overview (Production Docker Stack)

`docker-compose.prod.yml` runs the production stack with strict volume isolation (Specification Section 7 & `AGENTS.md` invariants):

| Container | Image / Role | Exposed Ports | Persistent Volumes |
| :--- | :--- | :--- | :--- |
| `dicomhub-postgres` | `postgres:16-alpine` | `5432` | `postgres_data` (Operational DB & encrypted pseudonym vault) |
| `dicomhub-backend-api` | `ghcr.io/raster-labs/dicomhub-backend:<version>` (`HUB_ROLE=all`) | `8080` (REST/SSE), `11112` (DICOM C-STORE SCP) | `dicom_original_repo`, `dicom_working_copies`, `dicom_ai_results` |
| `dicomhub-web-frontend` | `ghcr.io/raster-labs/dicomhub-frontend:<version>` | `80` (internal) | Stateless Angular 19 Command Centre SPA |
| `dicomhub-nginx` | `nginx:1.27-alpine` | `80` (HTTP + SSE proxy) | `./nginx/nginx.conf` (`client_max_body_size 500M`) |

---

## 2. How to Create a New Release (Developer / Release Manager)

Whenever you are ready to publish a new version (e.g., `v1.0.0`, `v1.1.0`):

### Step 1: Run the One-Command Release Script
From your development machine inside the `DICOMHub` repository:

```bash
./scripts/release.sh v1.0.0 "Initial production release"
# or using Make:
make release VERSION=v1.0.0
```

### What happens automatically on GitHub:
1. Your code and version tag (`v1.0.0`) are pushed to `https://github.com/raster-labs/DICOM_HUB`.
2. GitHub Actions (`.github/workflows/release.yml`) automatically:
   - Builds and publishes the backend image: `ghcr.io/raster-labs/dicomhub-backend:v1.0.0` (and `:latest`)
   - Builds and publishes the frontend image: `ghcr.io/raster-labs/dicomhub-frontend:v1.0.0` (and `:latest`)
   - Packages `dicomhub-deploy-v1.0.0.tar.gz` and publishes a **GitHub Release** with auto-generated release notes.

---

## 3. How to Deploy on the Target Server (Simplest Steps)

### Prerequisites on the Hospital Server
- Linux server (Ubuntu 22.04/24.04 LTS or RHEL 9) with **Docker** and **Docker Compose v2** (`docker compose version`)
- Open ports:
  - `80/tcp` — Command Centre Web Portal & REST API
  - `11112/tcp` — DICOM Storage SCP (`DICOMHUB_AE` for Modality / RIS-PACS C-STORE)

### Option A: Git Clone + One-Command Deploy (Recommended)

Run these 3 commands on the deployment server:

```bash
# 1. Clone the repository (or pull latest tag)
git clone https://github.com/raster-labs/DICOM_HUB.git /opt/dicomhub
cd /opt/dicomhub

# 2. Checkout the desired release tag (e.g. v1.0.0)
git checkout v1.0.0

# 3. Run the automated deployment script
./scripts/deploy.sh v1.0.0
```

> **Note:** If the server does not have access to pull from `ghcr.io` (air-gapped or private registry without login), simply pass `--build` and Docker will build the images directly on the server:
> ```bash
> ./scripts/deploy.sh v1.0.0 --build
> ```

### Option B: Deploy from GitHub Release Bundle (`dicomhub-deploy-vX.Y.Z.tar.gz`)

If you prefer not to clone the full source code on the server, download `dicomhub-deploy-v1.0.0.tar.gz` from the GitHub Releases page:

```bash
tar -xzf dicomhub-deploy-v1.0.0.tar.gz
cd dicomhub-deploy-v1.0.0
./scripts/deploy.sh v1.0.0
```

---

## 4. Upgrading to a Newer Release on the Server

To upgrade an existing server to a new release (e.g. `v1.1.0`) with zero data loss (all PostgreSQL data and Original DICOM Repository volumes are preserved):

```bash
cd /opt/dicomhub
git fetch --all --tags
git checkout v1.1.0
./scripts/deploy.sh v1.1.0
```

---

## 5. Useful Day-2 Operations & Verification Commands

```bash
# Check container status
docker compose -f docker-compose.prod.yml ps

# Tail application logs
docker compose -f docker-compose.prod.yml logs -f backend-api

# Check backend health endpoint
curl -s http://localhost/actuator/health

# Stop stack (volumes are preserved)
docker compose -f docker-compose.prod.yml down
```
