# Rilot Backend Setup Guide (Google Apps Script + Google Sheets + GitHub Actions)

This guide explains how to deploy and connect the zero-cost **Rilot** backend using only your **Google Account** (**Google Sheets + Google Apps Script**) and your **GitHub Account** (`nexerpy-sys/rilot` on the `main` branch). No Cloudflare account, VPS, rented server, credit card, or paid hosting is required.

```text
Android Rilot App
   │  (HTTPS POST JSON RPC /exec + RILOT_APP_API_KEY)
   ▼
Google Apps Script Web App  ◄────►  Google Sheets (8 Persistent Tabs)
   │  (GitHub REST workflow_dispatch + GITHUB_PAT)
   ▼
GitHub Actions (nexerpy-sys/rilot @ main)
   │  (Runs backend/python-engine/main.py --once)
   ▼
Google Apps Script Web App  (engine.getConfig / engine.report + RILOT_ENGINE_API_KEY)
```

---

## Step 1: Create the Google Sheet

1. Sign in to Google Drive or Google Sheets and create a new spreadsheet named **`Rilot Backend Store`**.
2. Copy the **Spreadsheet ID** from the browser address bar:
   - Example URL: `https://docs.google.com/spreadsheets/d/1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789/edit`
   - Spreadsheet ID: `1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789`
3. You do not need to create the tabs manually—running `initializeRilotSpreadsheet()` in Step 2 automatically creates and formats all 8 required tabs (`Settings`, `Captions`, `Activity`, `ProcessedItems`, `Requests`, `Cycles`, `Nonces`, and `RateLimits`) with `Asia/Kolkata` timezone defaults, `dryRunMode = true`, and `autoPublishEnabled = false`.

---

## Step 2: Create the Google Apps Script Project

1. Inside your **`Rilot Backend Store`** Google Sheet, click **Extensions → Apps Script** (or create a standalone script at `https://script.google.com`).
2. In the Apps Script editor, open **Project Settings** (the gear icon on the left) and check **Show "appsscript.json" manifest file in editor**.
3. Copy the files from `backend/google-apps-script/` in this repository into your Apps Script project:
   - Replace `appsscript.json` with `backend/google-apps-script/appsscript.json` (sets `"timeZone": "Asia/Kolkata"`, `"executeAs": "USER_DEPLOYING"`, `"access": "ANYONE_ANONYMOUS"`, and least-privilege OAuth scopes).
   - Create script files matching each `.gs` file in `backend/google-apps-script/`:
     - `Validation.gs`
     - `Auth.gs`
     - `Storage.gs`
     - `GitHubDispatch.gs`
     - `EngineApi.gs`
     - `Code.gs`
4. Save all files (`Ctrl+S` / `Cmd+S`), select `initializeRilotSpreadsheet` in the toolbar dropdown, and click **Run** once to grant spreadsheet/external-request permissions and initialize all 8 Google Sheets tabs.

---

## Step 3: Set Apps Script Properties

1. Generate two distinct 32-byte random hex keys on your computer (one for the Android app, one for the GitHub Actions Python engine):
   ```bash
   openssl rand -hex 32   # Use this for RILOT_APP_API_KEY
   openssl rand -hex 32   # Use this for RILOT_ENGINE_API_KEY
   ```
2. In the Apps Script editor, go to **Project Settings → Script Properties → Add script property** and configure these exact 8 properties:

| Property Key | Required Value | Purpose |
| :--- | :--- | :--- |
| `RILOT_SPREADSHEET_ID` | Your Google Sheet ID from Step 1 | Binds storage to your `Rilot Backend Store` spreadsheet |
| `RILOT_APP_API_KEY` | Your first random 32-byte hex secret | Authenticates Android app requests (`app.*` actions) |
| `RILOT_ENGINE_API_KEY` | Your second random 32-byte hex secret | Authenticates GitHub Actions Python engine requests (`engine.*` actions) |
| `GITHUB_OWNER` | `nexerpy-sys` | Explicit GitHub owner locked server-side |
| `GITHUB_REPO` | `rilot` | Explicit GitHub repository locked server-side |
| `GITHUB_BRANCH` | `main` | Active GitHub branch for `workflow_dispatch` |
| `GITHUB_WORKFLOW_FILE` | `insta-automation-cycle.yml` | Approved workflow file in `.github/workflows/` |
| `GITHUB_PAT` | Your fine-grained GitHub Personal Access Token | Server-side token used by `GitHubDispatch.gs` |

> **Important Security Note**: Changing the GitHub repository display field inside the Android app does **not** alter the dispatch target. The deployed Google Apps Script Web App strictly dispatches only to the `GITHUB_OWNER`, `GITHUB_REPO`, `GITHUB_BRANCH`, and `GITHUB_WORKFLOW_FILE` configured in Script Properties and rejects any client attempt to override them.

---

## Step 4: Deploy the Google Apps Script Web App

1. In the top-right of the Apps Script editor, click **Deploy → New deployment**.
2. Click the gear icon next to **Select type** and choose **Web app**.
3. Set the deployment configuration:
   - **Description**: `Rilot Production Gateway`
   - **Execute as**: **Me** (`USER_DEPLOYING`)
   - **Who has access**: **Anyone** (`ANYONE_ANONYMOUS` — required so the Android app and GitHub Actions can send unauthenticated-at-Google-OAuth-layer HTTP `POST` requests while `Auth.gs` enforces `RILOT_APP_API_KEY` and `RILOT_ENGINE_API_KEY` in the JSON body).
4. Click **Deploy** and copy the resulting **Web app URL** ending in `/exec`:
   ```text
   https://script.google.com/macros/s/YOUR_DEPLOYMENT_ID/exec
   ```
   *(Never use the `/dev` test URL in Android or GitHub Actions; always use the `/exec` URL.)*

---

## Step 5: Create a Fine-Grained GitHub Personal Access Token (`GITHUB_PAT`)

1. In GitHub, open **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**.
2. Configure the token:
   - **Token name**: `rilot-apps-script-dispatcher`
   - **Repository access**: **Only select repositories** → select **`nexerpy-sys/rilot`**
   - **Repository permissions**:
     - **Actions**: **Read and write** (required to trigger `workflow_dispatch`)
     - **Contents**: **Read-only**
3. Generate the token and paste it into the `GITHUB_PAT` Script Property in Step 3.

---

## Step 6: Configure GitHub Actions Repository Secrets

In your GitHub repository **`nexerpy-sys/rilot`**, navigate to **Settings → Secrets and variables → Actions → New repository secret** and add:

| GitHub Secret Name | Value |
| :--- | :--- |
| `RILOT_ENGINE_API_URL` | Your deployed Apps Script Web App `/exec` URL from Step 4 |
| `RILOT_ENGINE_API_KEY` | Must match `RILOT_ENGINE_API_KEY` in Apps Script Properties |
| `INSTAGRAM_USERNAME` | Your Instagram account username |
| `INSTAGRAM_PASSWORD` | Your Instagram account password |
| `IG_SESSION_JSON_B64` | Base64-encoded `session.json` generated locally once via `instagrapi` (`base64 -w 0 session.json`) |

---

## Step 7: Connect the Android Rilot App

1. Open the **Rilot** Android app.
2. On the **Dashboard**, tap the **Apps Script API Contract** chip (or open the hamburger menu → **About Rilot** → **Inspect Google Apps Script & API Contract**).
3. Select **Live Apps Script API**, enter:
   - **Apps Script Web App URL (`/exec`)**: `https://script.google.com/macros/s/YOUR_DEPLOYMENT_ID/exec`
   - **Rilot App API Key (`RILOT_APP_API_KEY`)**: Your `RILOT_APP_API_KEY` from Step 3 (stored encrypted on-device via Android Keystore).
4. Tap **Test Connection** to verify:
   - `Apps Script Reachable: YES`
   - `Spreadsheet Initialized: YES (8 tabs)`
   - `GitHub Dispatch Config Valid: YES (nexerpy-sys/rilot@main (insta-automation-cycle.yml))`
5. Tap **Save Endpoint**.

---

## Step 8: Safe Testing & Live Verification Sequence

Always verify your setup in this exact order before enabling live publishing:

1. **Connection Test**: Tap **Test Connection** in the Android app and confirm all checks pass.
2. **Settings Persistence Test**: Update a hashtag or posting window in the Android app, inspect your `Rilot Backend Store` Google Sheet (`Settings` tab), and confirm the updated JSON and `Asia/Kolkata` timestamp appear.
3. **Dry-Run Workflow Dispatch**: Keep **Dry-Run Mode ON** and **Auto-Publish OFF** (the default), then tap **Start Cycle** on the Rilot Dashboard.
4. **Verify GitHub Actions & Engine Report**: Open **Actions** in `nexerpy-sys/rilot` on GitHub, confirm `Rilot Instagram Automation Cycle` runs on `main`, fetches config via `engine.getConfig`, validates candidates without calling `clip_upload()`, and reports results back to the `Activity` and `Cycles` sheet tabs via `engine.report`.
5. **Enable Live Publishing (Optional)**: Only after steps 1–4 succeed and your `Authorized Creators` / `Owned Accounts` / `Authorized Candidate PKs` are configured, mark **Backend Testing Verified** in the Schedule screen and disable Dry-Run Mode.

> **Implications of Bulk Hashtag Authorization (`allowAllHashtagCandidatesWhenTargetAuthorized`)**:
> By default, Rilot is strictly **fail-closed** (`reuseAuthorized = false` and `allowAllHashtagCandidatesWhenTargetAuthorized = false`). Even if a hashtag target has `reuseAuthorized = true`, third-party reels discovered under that hashtag are **blocked** unless the creator is in `ownedAccounts` or `authorizedCreators`, or the Reel PK is in `authorizedCandidatePks`. Enabling `allowAllHashtagCandidatesWhenTargetAuthorized = true` grants blanket permission to download/repost any qualifying candidate under an authorized hashtag target—keep it `false` unless you have explicit rights for that hashtag campaign.
