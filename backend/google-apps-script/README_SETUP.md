# Rilot Google Apps Script Backend (`backend/google-apps-script/`)

This directory contains the complete Google Apps Script Web App backend that replaces the legacy Cloudflare Worker + D1 gateway.

## Architecture Overview

`Android Rilot App` → `Google Apps Script Web App (HTTPS /exec)` → `GitHub Actions (insta-automation-cycle.yml)` → `Python Engine (backend/python-engine/)` → `Google Apps Script (engine.report)` → `Google Sheets`.

- **Google Apps Script** authenticates requests (`RILOT_APP_API_KEY` vs `RILOT_ENGINE_API_KEY`), validates schemas and fail-closed authorization policies, persists state in Google Sheets, and dispatches GitHub Actions workflows via `UrlFetchApp`.
- **Google Sheets** stores persistent settings, captions, activity history, deduplicated Reel PKs (`ProcessedItems`), idempotency records (`Requests`), cycle locks (`Cycles`), replay nonces (`Nonces`), and rate-limit buckets (`RateLimits`).
- **GitHub Actions** runs the bounded Python engine (`--once`). Google Apps Script never downloads videos, scrapes Instagram, or runs long-lived loops.

## Source Files

1. `appsscript.json` — Apps Script project manifest (`Asia/Kolkata` timezone, V8 runtime, `spreadsheets` + `script.external_request` scopes).
2. `Validation.gs` — Request envelope validation, fail-closed candidate authorization evaluation, GitHub dispatch target validation, client override detection, and sensitive secret redaction.
3. `Auth.gs` — Constant-time key comparison, role separation (`app.*` vs `engine.*`), timestamp replay window verification, durable nonce deduplication, and per-role rate limiting.
4. `Storage.gs` — Google Sheets persistence layer (`SheetsStorage`) with `LockService.getScriptLock()` concurrency protection and idempotent 8-tab initialization.
5. `GitHubDispatch.gs` — Validated GitHub Actions `workflow_dispatch` caller using `UrlFetchApp`.
6. `EngineApi.gs` — Handlers for `engine.getConfig`, `engine.acquireCycle`, `engine.heartbeat`, `engine.getRetryTarget`, and `engine.report`.
7. `Code.gs` — Web App entry points (`doPost`, `doGet`), manual spreadsheet initializer (`initializeRilotSpreadsheet`), and `app.*` action handlers.

## Required Script Properties

Configure these in **Project Settings (gear icon) → Script Properties**:

| Property | Purpose |
|---|---|
| `RILOT_APP_API_KEY` | Personal API key entered into the Rilot Android app connection settings. Must be distinct from `RILOT_ENGINE_API_KEY` and `GITHUB_PAT`. |
| `RILOT_ENGINE_API_KEY` | API key used exclusively by GitHub Actions (`RILOT_ENGINE_API_KEY` secret) and the Python engine. |
| `GITHUB_PAT` | Fine-grained GitHub Personal Access Token scoped only to `nexerpy-sys/rilot` with **Actions: Read and write** permission. Never enter this into the Android app. |
| `GITHUB_OWNER` | `nexerpy-sys` |
| `GITHUB_REPO` | `rilot` |
| `GITHUB_BRANCH` | `main` |
| `GITHUB_WORKFLOW_FILE` | `insta-automation-cycle.yml` |
| `RILOT_SPREADSHEET_ID` | The ID from your Google Sheet URL (`https://docs.google.com/spreadsheets/d/<SPREADSHEET_ID>/edit`). |

See the root `/BACKEND_SETUP_GUIDE.md` for complete step-by-step setup, deployment, dry-run verification, and troubleshooting instructions.
