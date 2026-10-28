# Rilot Python Automation Engine (`backend/python-engine/`)

This directory contains the Rilot Instagram Reel automation engine for **`nexerpy-sys/rilot`** (`main` branch), integrated with the **Rilot Android Control App**, **Google Apps Script Web App + Google Sheets**, and **GitHub Actions**.

## Key Capabilities
- **Bounded CI Execution (`--once`)**: Executes a single bounded cycle (`discover -> filter -> rank -> download -> caption -> upload -> report`) and exits cleanly for GitHub Actions.
- **Google Apps Script JSON RPC Integration**:
  - `engine.getConfig`: Retrieves active configuration, caption library, recent activity, `postedPks`, and `ambiguousPks` from Google Sheets via the Apps Script Web App.
  - `engine.getRetryTarget`: Retrieves persisted activity records directly by `reelPk` for targeted safe retries.
  - `engine.acquireCycle`: Acquires or verifies cycle lock ownership (`lockOwnerId`).
  - `engine.report`: Reports structured JSON cycle execution outcomes and releases the active lock only when `lockOwnerId` matches.
- **Fail-Closed Remote Configuration**: When running in CI (`--require-remote-config`), if authentication fails or configuration cannot be retrieved from Google Apps Script, the engine exits safely without falling back to stale/default configuration or publishing content.
- **Multi-Signal Candidate Selection (`scraper.py`)**: Collects up to `N` candidates per hashtag, filters out non-video media, duration outliers, low-view/low-engagement clips, and already-posted PKs (`posted.db`), then selects from a qualifying pool using composite scoring (`40% Engagement + 35% Recency + 25% Category Diversity`).
- **Fixed Caption Library (`rewriter.py`)**: Uses the 10 curated caption templates and rotates least-recently-used captions while injecting source hashtags.
- **Dual Downloader Strategy (`downloader.py`)**: Attempts `yt-dlp` first with automatic fallback to `instagrapi.clip_download`.
- **Idempotent Safe Uploader (`uploader.py`)**: Checks `posted.db` and `IN_FLIGHT_AMBIGUOUS` upload locks before calling `cl.clip_upload()`, supporting `DRY_RUN_MODE=true` by default.

## CLI Usage

```bash
# Run a single bounded dry-run cycle locally
python main.py --once --dry-run --output-json cycle_result.json

# Fetch remote configuration from Google Apps Script Web App
python main.py --fetch-config-to /tmp/rilot_bundle.json

# Run a single bounded cycle using configuration bundle from Google Apps Script
python main.py --once --require-remote-config --config-json /tmp/rilot_bundle.json --output-json cycle_result.json

# Run a safe idempotent retry for a failed candidate PK
python main.py --once --require-remote-config --command SAFE_RETRY --retry-pk 33190288121 --idempotency-token retry_33190288121_1
```
