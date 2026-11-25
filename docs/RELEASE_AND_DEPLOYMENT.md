# DICOM Hub – Complete Release, Server Deployment & Maintenance Manual
*(Step-by-Step Guide for Operations, Hospital IT Coordinators & Non-Technical Administrators)*

> **Note:** No programming knowledge is required to use this manual. Every action is broken down into either clicking a button on the GitHub website or copying and pasting ready-made commands into the server terminal.

---

## 1. Plain-English Overview: How the System Works

**DICOM Hub** is packaged using **Docker**, which runs the entire platform inside four isolated, self-contained boxes ("containers") on the hospital server.

### What Are the 4 Containers Running on the Server?

| Container Name | What It Does in Plain English | Port Used |
| :--- | :--- | :--- |
| **`dicomhub-nginx`** | **The Front Door.** Receives web browser visits from hospital staff and routes them to the screen or backend. | **`80`** (Web Browser) |
| **`dicomhub-web-frontend`** | **The Command Centre Screen.** The visual dashboard where staff monitor studies, AI reports, and settings. | Internal (`80`) |
| **`dicomhub-backend-api`** | **The Brain & DICOM Receiver.** Receives X-Ray/CT/MRI images from scanners or PACS, removes patient names (de-identifies), sends to AI, and returns draft reports. | **`11112`** (DICOM Images) & **`8080`** (API) |
| **`dicomhub-postgres`** | **The Filing Cabinet (Database).** Stores all users, routing rules, study logs, and the encrypted patient ID lookup vault. | **`5432`** (Database) |

### Where Is Hospital Data Stored? (4 Protected Storage Vaults)
Docker keeps all hospital data in four permanent storage vaults (**Docker Volumes**) completely separate from the application code. **Upgrading or restarting the application never erases these vaults:**
1. **`postgres_data`** — All database records, settings, user accounts, and audit logs.
2. **`dicom_original_repo`** — The untouched original DICOM images received from scanners/PACS.
3. **`dicom_working_copies`** — Temporary anonymized copies created while sending to an AI vendor.
4. **`dicom_ai_results`** — Returned AI reports and result files.

---

## 2. How to Create a New Release (When Changes Are Ready)

Whenever a new version of DICOM Hub is ready to be published, you assign it a **Version Tag** (such as `v1.0.0`, `v1.0.1`, or `v1.1.0`).

- **Small fix** (bug fix, minor text change): Increase the last number (`v1.0.0` → **`v1.0.1`**)
- **New feature** (new dashboard screen, new AI vendor rule): Increase the middle number (`v1.0.1` → **`v1.1.0`**)
- **Major overhaul**: Increase the first number (`v1.1.0` → **`v2.0.0`**)
*(Always start the version with a lowercase **`v`**.)*

You can create a release in **two ways**:

### Method A: From the GitHub Web Browser (No Terminal Needed)
Use this method if code changes are already on GitHub and you want to build a release using only your web browser:

1. Open your web browser and go to:  
   **https://github.com/raster-labs/DICOM_HUB/actions**
2. On the left side menu, click **"Release & Publish Docker Deployment"**.
3. On the right side of the blue banner, click the **"Run workflow"** button.
4. In the **"Release version tag"** box, type the version number (for example: `v1.0.1`).
5. Click the green **"Run workflow"** button.
6. Refresh the page after 5 seconds. You will see a yellow spinning circle (**In progress**).
7. Wait about **3 to 5 minutes** until the circle turns into a **green checkmark (✓)**.
8. Once green, your release is ready at:  
   **https://github.com/raster-labs/DICOM_HUB/releases**

---

### Method B: From the Developer Computer (One Copy-Paste Command)
Use this method if changes were just made on the developer computer and need to be uploaded and released together:

1. Open the **Terminal** app on the developer computer.
2. Copy and paste these two lines (change `v1.0.1` to your new version number) and press **Enter**:

```bash
cd /Users/pushparaj/projects/DICOMHub
./scripts/release.sh v1.0.1 "Description of what changed in this version"
```

3. Wait about 10 seconds until you see:
   `Release v1.0.1 pushed to GitHub!`
4. GitHub will automatically build the Docker images and publish the release at **https://github.com/raster-labs/DICOM_HUB/releases**.

---

## 3. First-Time Installation on a New Hospital Server

Follow this section only once when setting up a brand-new server for the first time.

### Step 1: Checklist Before You Begin
Make sure the hospital IT team has provided:
- **A Linux Server** (Ubuntu 22.04 LTS or 24.04 LTS recommended, minimum 4 CPU cores, 8 GB RAM, 200+ GB disk space).
- **Server IP Address** (for example: `172.17.1.107`), **Username** (for example: `raster` or `ubuntu`), and **Password**.
- **Network Firewall Ports Open** inside the hospital network:
  - Port **`80`** (TCP) — So hospital computers can open the DICOM Hub web dashboard.
  - Port **`11112`** (TCP) — So X-Ray/CT/MRI scanners and RIS/PACS can send DICOM images to DICOM Hub.

---

### Step 2: Log In to the Hospital Server
1. Open the **Terminal** (on Mac/Linux) or **Command Prompt / PowerShell** (on Windows).
2. Type the following command (replace `username` and `SERVER_IP` with your server's details) and press **Enter**:

```bash
ssh username@SERVER_IP
```
*(If asked `Are you sure you want to continue connecting (yes/no)?`, type `yes` and press **Enter**, then enter the server password.)*

---

### Step 3: Check if Docker Is Installed on the Server
Copy and paste this command into the server terminal and press **Enter**:

```bash
docker --version && docker compose version
```

- **If it prints version numbers** (like `Docker version 27.x` and `Docker Compose version v2.x`), skip to **Step 4**.
- **If it says `command not found`**, install Docker by copying and pasting these 3 lines one by one:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
newgrp docker
```

---

### Step 4: Download and Start DICOM Hub (3 Simple Commands)

#### Option A: Direct Download from GitHub (Recommended)
Copy and paste these commands into the server terminal:

```bash
# 1. Create the installation folder and give your user permission
sudo mkdir -p /opt/dicomhub && sudo chown -R $USER:$USER /opt/dicomhub

# 2. Download the application from GitHub and select version v1.0.0
git clone https://github.com/raster-labs/DICOM_HUB.git /opt/dicomhub
cd /opt/dicomhub
git checkout v1.0.0

# 3. Run the automated installer script
./scripts/deploy.sh v1.0.0 --build
```

**What does `./scripts/deploy.sh` do automatically?**
1. Creates your server configuration file (`/opt/dicomhub/.env`) and automatically generates a unique encryption key (`HUB_PSEUDONYM_AES_KEY_B64`) and database password.
2. Builds/downloads the 4 Docker containers.
3. Starts the database, creates all database tables automatically, starts the DICOM listener and web portal, and verifies system health.

#### Option B: Offline / Zip Bundle Installation (If You Downloaded `dicomhub-deploy-v1.0.0.tar.gz`)
If you downloaded `dicomhub-deploy-v1.0.0.tar.gz` from https://github.com/raster-labs/DICOM_HUB/releases and copied it to the server:

```bash
tar -xzf dicomhub-deploy-v1.0.0.tar.gz
cd dicomhub-deploy-v1.0.0
./scripts/deploy.sh v1.0.0
```

---

### Step 5: Verify the Installation Worked

When `./scripts/deploy.sh` finishes, you will see:

```text
========================================================
  DICOM Hub is UP and HEALTHY!
  - Command Centre UI : http://172.17.1.107:80
  - Backend Health    : http://localhost:80/actuator/health
  - DICOM Storage SCP : AE Title=DICOMHUB_AE, Port=11112
========================================================
```

Now do two quick checks:
1. **Open the Web Dashboard:**  
   Open Chrome or Edge on any computer on the hospital network and go to:  
   `http://<SERVER_IP>` (for example, `http://172.17.1.107`)  
   You should see the **DICOM Hub Digital Monitoring Command Centre**.
2. **Give These 3 Details to the Hospital PACS / Modality Engineer:**
   - **AE Title:** `DICOMHUB_AE`
   - **IP Address:** `<Your Server IP Address>`
   - **Port:** `11112`

---

## 4. How to Upgrade the Server to a Newer Release Later

When a newer version (for example, `v1.0.1`) has been released on GitHub, upgrading the hospital server takes less than 2 minutes. **All hospital data, settings, encryption keys, and stored DICOM studies are 100% preserved.**

### 3 Commands to Upgrade the Server
Log into the hospital server (`ssh username@SERVER_IP`) and copy-paste:

```bash
cd /opt/dicomhub
git fetch --all --tags && git checkout v1.0.1
./scripts/deploy.sh v1.0.1 --build
```

---

### Emergency Rollback: How to Go Back to the Previous Version
If anything looks wrong after an upgrade and you want to immediately switch back to the previous working version (for example, `v1.0.0`), copy and paste:

```bash
cd /opt/dicomhub
git checkout v1.0.0
./scripts/deploy.sh v1.0.0 --build
```

---

## 5. Day-to-Day Maintenance Cheat Sheet (Copy-Paste Commands)

Always run `cd /opt/dicomhub` first after logging into the server.

### 5.1 Check If All 4 Containers Are Running
```bash
cd /opt/dicomhub
docker compose -f docker-compose.prod.yml ps
```
**What you want to see:** All 4 services (`dicomhub-postgres`, `dicomhub-backend-api`, `dicomhub-web-frontend`, `dicomhub-nginx`) showing **`Up`** or **`Up (healthy)`**.

---

### 5.2 Restart the Application (Without Losing Any Data)
```bash
cd /opt/dicomhub
docker compose -f docker-compose.prod.yml restart
```

---

### 5.3 Stop and Start the Application
- **To Stop DICOM Hub temporarily:**
  ```bash
  cd /opt/dicomhub
  docker compose -f docker-compose.prod.yml down
  ```
- **To Start DICOM Hub back up:**
  ```bash
  cd /opt/dicomhub
  docker compose -f docker-compose.prod.yml up -d
  ```
> **WARNING:** Never add `-v` or `--volumes` to `docker compose down`, as `-v` deletes database and storage volumes.

---

### 5.4 View Live Application Logs
Press **`Ctrl + C`** on your keyboard to exit the log view:
```bash
cd /opt/dicomhub
docker compose -f docker-compose.prod.yml logs -f --tail=100 backend-api
```

---

### 5.5 Check Server Hard Drive Space
```bash
df -h
```
If disk usage (`Use%`) is high, safely remove old unused Docker build caches with:
```bash
docker image prune -f && docker builder prune -f
```

---

## 6. Backing Up & Restoring Critical Hospital Data

### Backup Item 1: Save a Copy of the `.env` Master Key File
The file `/opt/dicomhub/.env` holds the master AES-256 encryption key (`HUB_PSEUDONYM_AES_KEY_B64`). Run this command on the server and save the output in a secure password manager off the server:
```bash
cat /opt/dicomhub/.env
```

### Backup Item 2: Back Up the PostgreSQL Database
Copy and paste this command on the server to create a timestamped database backup in `/opt/dicomhub/backups/`:

```bash
mkdir -p /opt/dicomhub/backups
docker exec dicomhub-postgres pg_dump -U dicomhub dicomhub > /opt/dicomhub/backups/dicomhub_db_$(date +%Y%m%d_%H%M%S).sql
ls -lh /opt/dicomhub/backups/
```

#### How to Restore a Database Backup
Replace `FILENAME.sql` with the backup file name:
```bash
cat /opt/dicomhub/backups/FILENAME.sql | docker exec -i dicomhub-postgres psql -U dicomhub -d dicomhub
```

---

## 7. Troubleshooting Guide (Common Problems & Instant Fixes)

| Problem / Symptom | Likely Cause | Exact Fix to Copy-Paste |
| :--- | :--- | :--- |
| **1. Web dashboard (`http://<SERVER-IP>`) does not open.** | Containers are stopped after a server reboot. | `cd /opt/dicomhub && docker compose -f docker-compose.prod.yml up -d` |
| **2. Page says "502 Bad Gateway" right after restart.** | Backend container is still starting (~20–30 seconds). | Wait 30 seconds and refresh, or test with `curl http://localhost/actuator/health` |
| **3. Scanner/PACS says "DICOM Echo Failed" on port 11112.** | Server Linux firewall (`ufw`) is blocking port `11112` or `80`. | `sudo ufw allow 80/tcp && sudo ufw allow 11112/tcp` |
| **4. Error: `Bind for 0.0.0.0:80 failed: port is already allocated`.** | Another program (Apache/Nginx) is using port 80 on the host. | Stop the host web server (`sudo systemctl disable --now apache2 nginx`) OR change `HTTP_PORT=8088` in `/opt/dicomhub/.env` and re-run `./scripts/deploy.sh v1.0.0`. |
| **5. Uploaded DICOM study does not route to AI vendor.** | Study did not match an active routing rule or has burned-in pixel text. | Open the Command Centre Web Portal → check the **Studies** & **Exceptions / Quarantine** tab. |
