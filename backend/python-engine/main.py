import argparse
import hashlib
import hmac
import json
import logging
import os
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from types import SimpleNamespace
from zoneinfo import ZoneInfo

try:
    import schedule
except ImportError:  # pragma: no cover
    schedule = None  # type: ignore

try:
    from dotenv import load_dotenv
except ImportError:  # pragma: no cover
    def load_dotenv():
        pass

from auth import SessionExpiredError, get_client
from db import (
    already_posted,
    get_activity_by_pk,
    get_all_posted_pks,
    get_caption_usage_map,
    get_last_post_epoch_ms,
    get_posted_count_today_ist,
    get_recent_activity,
    get_upload_state,
    init_db,
    log_run,
    mark_posted,
    record_activity,
    record_caption_used,
    sync_remote_activity_records,
    sync_remote_ambiguous_pks,
    sync_remote_posted_pks,
)
from downloader import cleanup_video, download_authorized_candidate, download_reel
from rewriter import (
    DEFAULT_FIXED_CAPTION_LIBRARY,
    build_caption_for_candidate,
    rewrite_caption,
)
from scraper import (
    discover_and_select_candidates,
    evaluate_candidate_reuse_authorization,
    extract_authorization_source,
    get_top_reels,
    is_strict_boolean_true,
    reel_url,
)
from uploader import post_reel, safe_publish_reel

load_dotenv()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    handlers=[
        logging.FileHandler("automation.log"),
        logging.StreamHandler(),
    ],
)
logger = logging.getLogger(__name__)

IST_ZONE = ZoneInfo("Asia/Kolkata")
REEL_PK_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
LOCK_OWNER_ID_PATTERN = re.compile(r"^[A-Za-z0-9_.:-]{6,128}$")
EXPLICIT_RETRYABLE_STATUSES = frozenset({"DOWNLOAD_FAILED", "UPLOAD_FAILED", "RETRY_DISPATCHED"})

USERNAME = os.getenv("INSTAGRAM_USERNAME", "")
PASSWORD = os.getenv("INSTAGRAM_PASSWORD", "")
HASHTAGS = [h.strip() for h in os.getenv("HASHTAGS", "fitness,calisthenics").split(",") if h.strip()]
POST_INTERVAL_HOURS = int(os.getenv("POST_INTERVAL_HOURS", "6"))
MAX_POSTS_PER_CYCLE = int(os.getenv("MAX_POSTS_PER_CYCLE", "2"))
DELAY_BETWEEN_POSTS = int(os.getenv("DELAY_BETWEEN_POSTS", "60"))
DAILY_POSTING_LIMIT = int(os.getenv("DAILY_POSTING_LIMIT", "6"))
DRY_RUN_MODE = os.getenv("DRY_RUN_MODE", "true").lower() in ("1", "true", "yes")
AUTO_PUBLISH_ENABLED = os.getenv("AUTO_PUBLISH_ENABLED", "false").lower() in ("1", "true", "yes")

# Global client for legacy run_cycle() compatibility
client = None


def is_valid_reel_pk(reel_pk: str | None) -> bool:
    if not isinstance(reel_pk, str):
        return False
    cleaned = reel_pk.strip()
    return bool(REEL_PK_PATTERN.match(cleaned))


def normalize_lock_owner_id(lock_owner_id: str | None) -> str | None:
    if not isinstance(lock_owner_id, str):
        return None
    cleaned = lock_owner_id.strip()
    if not cleaned:
        return None
    if not LOCK_OWNER_ID_PATTERN.match(cleaned):
        return None
    return cleaned


def is_within_ist_posting_windows(
    posting_windows: list[dict],
    now_ist: datetime | None = None,
) -> tuple[bool, str]:
    """
    Checks whether current Asia/Kolkata time falls inside at least one enabled posting window.
    If no windows are configured or enabled, returns (True, "All hours permitted").
    """
    current_ist = now_ist or datetime.now(IST_ZONE)
    enabled_windows = [w for w in posting_windows if w.get("isEnabled", True)]
    if not enabled_windows:
        return True, "No active window restriction configured"

    now_minutes = current_ist.hour * 60 + current_ist.minute
    for w in enabled_windows:
        start_m = int(w.get("startHourIst", 0)) * 60 + int(w.get("startMinuteIst", 0))
        end_m = int(w.get("endHourIst", 23)) * 60 + int(w.get("endMinuteIst", 59))
        label = w.get("label", w.get("id", "window"))
        if start_m <= end_m:
            if start_m <= now_minutes <= end_m:
                return True, f"Inside IST window '{label}' ({start_m // 60:02d}:{start_m % 60:02d}-{end_m // 60:02d}:{end_m % 60:02d} IST)"
        else:
            if now_minutes >= start_m or now_minutes <= end_m:
                return True, f"Inside overnight IST window '{label}'"

    formatted_now = current_ist.strftime("%H:%M IST")
    return False, f"Current time ({formatted_now}) is outside enabled Asia/Kolkata posting windows"


def check_schedule_and_limits(
    schedule_config: dict,
    now_ist: datetime | None = None,
    force_manual: bool = False,
    db_path: str | None = None,
) -> tuple[bool, int, str]:
    """
    Validates Asia/Kolkata schedule window, post interval, and daily posting limit.
    Returns (allowed_to_run, remaining_daily_quota, reason).
    """
    current_ist = now_ist or datetime.now(IST_ZONE)
    daily_limit = max(1, int(schedule_config.get("dailyPostingLimit", DAILY_POSTING_LIMIT)))
    max_per_cycle = max(1, int(schedule_config.get("maxPostsPerCycle", MAX_POSTS_PER_CYCLE)))
    interval_hours = max(1, int(schedule_config.get("postIntervalHours", POST_INTERVAL_HOURS)))

    posted_today = get_posted_count_today_ist(now_ist=current_ist, db_path=db_path)
    remaining_today = max(0, daily_limit - posted_today)
    if remaining_today <= 0:
        return (
            False,
            0,
            f"Daily publishing limit reached ({posted_today}/{daily_limit} posts today in Asia/Kolkata).",
        )

    if not force_manual:
        windows = schedule_config.get("postingWindows", [])
        in_window, win_reason = is_within_ist_posting_windows(windows, now_ist=current_ist)
        if not in_window:
            return False, remaining_today, win_reason

        last_post_ms = get_last_post_epoch_ms(db_path=db_path)
        if last_post_ms is not None:
            elapsed_hours = (current_ist.timestamp() * 1000 - last_post_ms) / (1000.0 * 3600.0)
            if elapsed_hours < interval_hours:
                return (
                    False,
                    remaining_today,
                    f"Minimum interval not yet elapsed ({elapsed_hours:.1f}h < {interval_hours}h).",
                )

    allowed_this_cycle = min(max_per_cycle, remaining_today)
    return True, allowed_this_cycle, f"Schedule verified ({allowed_this_cycle} post slot(s) available)."


def call_apps_script_rpc(
    api_url: str,
    engine_key: str,
    action: str,
    payload: dict | None = None,
    timeout: int = 20,
) -> dict:
    """
    Sends an authenticated JSON RPC POST request to the Google Apps Script Web App (/exec).
    Validates the response envelope (ok, code, message, data, requestId).
    Raises RuntimeError on authentication, transport, or application-level failure.
    """
    clean_url = (api_url or "").strip()
    clean_key = (engine_key or "").strip()
    if not clean_url or not clean_key:
        raise RuntimeError("Missing RILOT_ENGINE_API_URL or RILOT_ENGINE_API_KEY.")

    ts = int(time.time() * 1000)
    nonce = f"eng_{ts}_{os.urandom(8).hex()}"
    req_id = f"req_{ts}_{os.urandom(6).hex()}"
    envelope = {
        "action": action,
        "authToken": clean_key,
        "timestamp": ts,
        "nonce": nonce,
        "requestId": req_id,
        "payload": payload if isinstance(payload, dict) else {},
    }
    body_bytes = json.dumps(envelope).encode("utf-8")
    req = urllib.request.Request(
        clean_url,
        data=body_bytes,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw_text = resp.read().decode("utf-8")
    except Exception as exc:
        safe_err = str(exc).replace(clean_key, "[REDACTED]")
        raise RuntimeError(f"Apps Script RPC transport failed for '{action}': {safe_err}") from exc

    try:
        parsed = json.loads(raw_text)
    except Exception as exc:
        raise RuntimeError(f"Apps Script RPC returned non-JSON response for '{action}'.") from exc

    if not isinstance(parsed, dict) or parsed.get("ok") is not True:
        code = parsed.get("code", "UNKNOWN_ERROR") if isinstance(parsed, dict) else "INVALID_ENVELOPE"
        msg = parsed.get("message", "Unknown Apps Script error") if isinstance(parsed, dict) else "Invalid response"
        safe_msg = str(msg).replace(clean_key, "[REDACTED]")
        raise RuntimeError(f"Apps Script RPC '{action}' failed [{code}]: {safe_msg}")

    return parsed


def fetch_apps_script_config(api_url: str, engine_key: str) -> dict | None:
    """Retrieves active configuration, caption library, activity records, posted PKs, and ambiguous PKs from Google Apps Script."""
    if not api_url or not engine_key:
        return None
    try:
        resp = call_apps_script_rpc(api_url, engine_key, "engine.getConfig", {})
        data = resp.get("data")
        return data if isinstance(data, dict) else None
    except Exception as e:
        safe_err = str(e).replace(engine_key, "[REDACTED]") if engine_key else str(e)
        logger.error(f"Could not fetch configuration from Google Apps Script: {safe_err}")
        return None


def acquire_apps_script_cycle_lock(
    api_url: str,
    engine_key: str,
    lock_owner_id: str | None = None,
    command: str = "SCHEDULED_CYCLE",
    retry_pk: str | None = None,
) -> dict | None:
    """Acquires or verifies a cycle lock in Google Apps Script."""
    if not api_url or not engine_key:
        return None
    try:
        resp = call_apps_script_rpc(
            api_url,
            engine_key,
            "engine.acquireCycle",
            {
                "lockOwnerId": lock_owner_id,
                "command": command,
                "retryPk": retry_pk,
            },
        )
        data = resp.get("data")
        return data if isinstance(data, dict) else None
    except Exception as e:
        safe_err = str(e).replace(engine_key, "[REDACTED]") if engine_key else str(e)
        logger.error(f"Could not acquire cycle lock in Google Apps Script: {safe_err}")
        return None


def fetch_apps_script_retry_target(api_url: str, engine_key: str, reel_pk: str) -> dict | None:
    """Retrieves a specific retry target record and current authorization policy from Google Apps Script."""
    if not api_url or not engine_key or not reel_pk:
        return None
    try:
        resp = call_apps_script_rpc(
            api_url,
            engine_key,
            "engine.getRetryTarget",
            {"reelPk": reel_pk},
        )
        data = resp.get("data")
        return data if isinstance(data, dict) else None
    except Exception as e:
        safe_err = str(e).replace(engine_key, "[REDACTED]") if engine_key else str(e)
        logger.error(f"Could not fetch retry target '{reel_pk}' from Google Apps Script: {safe_err}")
        return None


def report_cycle_to_apps_script(api_url: str, engine_key: str, payload: dict) -> bool:
    """Pushes structured JSON cycle execution results to Google Apps Script."""
    if not api_url or not engine_key:
        return False
    try:
        resp = call_apps_script_rpc(api_url, engine_key, "engine.report", payload)
        return bool(resp.get("ok") is True)
    except Exception as e:
        safe_err = str(e).replace(engine_key, "[REDACTED]") if engine_key else str(e)
        logger.error(f"Failed to report cycle results to Google Apps Script: {safe_err}")
        return False


def execute_targeted_retry(
    ig_client,
    retry_pk: str | None,
    idempotency_token: str | None,
    discovery_cfg: dict,
    hashtag_targets: list[dict],
    schedule_cfg: dict,
    caption_library: list[dict],
    caption_mode: str,
    remote_posted_pks: set[str],
    effective_dry_run: bool,
    effective_auto_publish: bool,
    now_ist: datetime,
    next_run_ist: str,
    lock_owner_id: str | None = None,
    db_path: str | None = None,
) -> dict:
    """
    Executes a targeted retry for a single persisted Reel PK.
    NEVER falls back to an ordinary discovery cycle.
    Validates identifier, existence in persisted activity_history, retryable state,
    IN_FLIGHT_AMBIGUOUS protection, fail-closed reuse authorization revalidation, and idempotency.
    """
    now_ist_str = now_ist.strftime("%Y-%m-%d %H:%M:%S IST")
    clean_pk = (retry_pk or "").strip()
    clean_lock_owner = normalize_lock_owner_id(lock_owner_id)

    def build_retry_response(
        status: str,
        conclusion: str,
        message: str,
        record: dict | None = None,
        processed: int = 0,
        posted: int = 0,
        skips: int = 0,
        errors: int = 0,
        auth_status: str = "OK",
    ) -> dict:
        log_run(status.lower(), f"retry_pk={clean_pk}: {message}", db_path=db_path)
        return {
            "status": status,
            "mode": "TARGETED_RETRY",
            "targetReelPk": clean_pk,
            "lockOwnerId": clean_lock_owner,
            "runState": "DRY_RUN" if effective_dry_run else "ENABLED",
            "authStatus": auth_status,
            "dryRun": effective_dry_run,
            "timestampIst": now_ist_str,
            "lastSuccessfulRunIst": now_ist_str if status == "RETRY_SUCCEEDED" else None,
            "nextScheduledRunIst": next_run_ist,
            "message": message,
            "candidatesDiscovered": 0,
            "qualifyingPoolCount": 0,
            "videosProcessed": processed,
            "successfulPosts": posted,
            "duplicateSkips": skips,
            "errorsCount": errors,
            "workflowConclusion": conclusion,
            "activityRecords": [record] if record else [],
            "postedPks": list(get_all_posted_pks(db_path=db_path)),
        }

    # 1. Validate Reel PK format
    if not is_valid_reel_pk(clean_pk):
        return build_retry_response(
            status="RETRY_REJECTED",
            conclusion="rejected (invalid reel_pk)",
            message=f"Safe retry rejected: invalid or empty Reel PK '{clean_pk}'. Refusing to fall back to discovery.",
            errors=1,
        )

    # 2. Confirm target exists in backend persisted activity records
    persisted = get_activity_by_pk(clean_pk, db_path=db_path)
    if persisted is None:
        return build_retry_response(
            status="RETRY_REJECTED",
            conclusion="rejected (unknown reel_pk)",
            message=f"Safe retry rejected: Reel PK '{clean_pk}' does not exist in persisted activity records.",
            errors=1,
        )

    existing_status = str(persisted.get("outcomeStatus", ""))
    existing_failure = str(persisted.get("failureReason") or "")
    existing_token = str(persisted.get("idempotencyToken") or "")
    provided_token = (idempotency_token or "").strip()

    # 3. Check IN_FLIGHT_AMBIGUOUS upload state before anything else
    upload_state = get_upload_state(clean_pk, db_path=db_path)
    if upload_state == "IN_FLIGHT_AMBIGUOUS" or "IN_FLIGHT_AMBIGUOUS" in existing_failure:
        ambiguous_msg = (
            f"Safe retry blocked (unresolved ambiguous state): Reel {clean_pk} has state "
            f"IN_FLIGHT_AMBIGUOUS from an uncertain prior upload. Manual check required."
        )
        persisted_amb = {
            **persisted,
            "outcomeStatus": "UPLOAD_FAILED",
            "failureReason": ambiguous_msg,
            "timestampIst": now_ist_str,
        }
        record_activity(persisted_amb, db_path=db_path)
        return build_retry_response(
            status="RETRY_UNRESOLVED_AMBIGUOUS",
            conclusion="unresolved (IN_FLIGHT_AMBIGUOUS guard)",
            message=ambiguous_msg,
            record=persisted_amb,
            errors=1,
        )

    # 4. Idempotency check: if the exact same idempotency_token already completed, return idempotent result
    if (
        provided_token
        and provided_token == existing_token
        and existing_status in ("PUBLISHED", "DRY_RUN_VALIDATED")
    ):
        return build_retry_response(
            status="RETRY_SUCCEEDED",
            conclusion=f"idempotent noop ({existing_status})",
            message=f"Idempotent retry already completed for Reel {clean_pk} with token {provided_token}.",
            record=persisted,
            processed=0,
            posted=0,
        )

    # 5. Reject already-published items in posted.db or remote_posted_pks
    if (
        upload_state == "PUBLISHED"
        or clean_pk in remote_posted_pks
        or existing_status == "PUBLISHED"
    ):
        return build_retry_response(
            status="RETRY_REJECTED",
            conclusion="rejected (already published)",
            message=f"Safe retry rejected: Reel {clean_pk} is already published in posted.db.",
            record=persisted,
            skips=1,
        )

    # 6. Permit retries ONLY for explicitly retryable failure states
    if existing_status not in EXPLICIT_RETRYABLE_STATUSES:
        return build_retry_response(
            status="RETRY_REJECTED",
            conclusion=f"rejected (non-retryable state {existing_status})",
            message=(
                f"Safe retry rejected: Reel {clean_pk} has status '{existing_status}', "
                f"which is not an explicitly retryable failure state."
            ),
            record=persisted,
            errors=1,
        )

    # Require a fresh idempotencyToken when retrying a failed record (unless Apps Script already updated it to RETRY_DISPATCHED)
    if existing_status != "RETRY_DISPATCHED" and provided_token and provided_token == existing_token:
        return build_retry_response(
            status="RETRY_REJECTED",
            conclusion="rejected (duplicate idempotency token)",
            message=f"Safe retry rejected: idempotencyToken '{provided_token}' was already used on the failed attempt.",
            record=persisted,
            errors=1,
        )

    # 7. Revalidate current applicable authorization policy (fail-closed):
    # - Never fabricate TARGET_POLICY_GRANT provenance from a previously persisted boolean.
    # - If the hashtag target was removed or its authorization was revoked since the original
    #   attempt, block the retry unless a separate, still-valid explicit license
    #   (authorizedCandidatePks or ownedAccounts) applies.
    tag = str(persisted.get("hashtag", "")).strip().lstrip("#")
    matching_target = next(
        (
            t
            for t in (hashtag_targets if isinstance(hashtag_targets, list) else [])
            if isinstance(t, dict) and str(t.get("tag", "")).strip().lstrip("#").lower() == tag.lower()
        ),
        {
            "tag": tag,
            "isEnabled": False,
            "reuseAuthorized": False,
            "allowAllHashtagCandidatesWhenTargetAuthorized": False,
        },
    )
    persisted_auth_flag = is_strict_boolean_true(persisted.get("reuseAuthorized"))
    # Do NOT construct TARGET_POLICY_GRANT or reuse_authorized=True from persisted boolean
    synthetic_media = SimpleNamespace(
        pk=clean_pk,
        code=persisted.get("shortCode") or clean_pk,
        reuse_authorized=None,
        authorization_source=None,
    )
    target_authorized, auth_reason = evaluate_candidate_reuse_authorization(
        media=synthetic_media,
        reel_pk=clean_pk,
        creator_handle=str(persisted.get("creatorHandle", "")),
        target_config=matching_target,
        discovery_config=discovery_cfg if isinstance(discovery_cfg, dict) else {},
    )
    revalidated_source = extract_authorization_source(auth_reason) if target_authorized else "NONE"
    if not (persisted_auth_flag and target_authorized):
        unauth_msg = (
            f"Safe retry rejected: Reel {clean_pk} is not authorized under current policy ({auth_reason})."
        )
        updated_unauth = {
            **persisted,
            "outcomeStatus": "UNAUTHORIZED_BLOCKED",
            "failureReason": unauth_msg,
            "reuseAuthorized": False,
            "authorizationSource": "NONE",
            "timestampIst": now_ist_str,
        }
        record_activity(updated_unauth, db_path=db_path)
        return build_retry_response(
            status="RETRY_REJECTED",
            conclusion="rejected (reuse unauthorized)",
            message=unauth_msg,
            record=updated_unauth,
            errors=1,
        )

    # 8. Check daily posting limit before executing live retry
    allowed, _, sched_reason = check_schedule_and_limits(
        schedule_config=schedule_cfg,
        now_ist=now_ist,
        force_manual=True,
        db_path=db_path,
    )
    if not allowed:
        return build_retry_response(
            status="RETRY_REJECTED",
            conclusion=f"rejected ({sched_reason})",
            message=f"Safe retry blocked by daily limit: {sched_reason}",
            record=persisted,
            errors=1,
        )

    # 9. Authenticate Instagram client if required
    active_client = ig_client
    if active_client is None and not effective_dry_run:
        try:
            active_client = get_client(USERNAME, PASSWORD, non_interactive=True)
        except SessionExpiredError as auth_err:
            return build_retry_response(
                status="AUTH_EXPIRED",
                conclusion="failed (session expired)",
                message=str(auth_err),
                record=persisted,
                errors=1,
                auth_status="EXPIRED",
            )

    next_token = provided_token or f"{existing_token or f'idem_{clean_pk}'}_r{int(persisted.get('retryCount', 0)) + 1}"
    prefer_ytdlp = bool(discovery_cfg.get("preferYtdlpWithInstagrapiFallback", True))
    target_url = str(
        persisted.get("reelUrl")
        or f"https://www.instagram.com/reel/{persisted.get('shortCode') or clean_pk}/"
    )

    caption_text = str(persisted.get("captionPreview") or "").strip()
    used_cap_id = None
    if not caption_text:
        usage_map = get_caption_usage_map(db_path=db_path)
        caption_text, used_cap_id, _ = build_caption_for_candidate(
            original_caption="",
            hashtag=tag or "reels",
            caption_mode=caption_mode,
            captions=caption_library,
            usage_map=usage_map,
        )

    video_path, downloader_used, dl_error = download_authorized_candidate(
        client=active_client,
        media=synthetic_media,
        url=target_url,
        reuse_authorized=True,
        prefer_ytdlp=prefer_ytdlp,
        dry_run=effective_dry_run,
    )

    next_retry_count = int(persisted.get("retryCount", 0)) + 1
    if dl_error:
        failed_rec = {
            **persisted,
            "downloaderUsed": downloader_used,
            "outcomeStatus": "DOWNLOAD_FAILED",
            "failureReason": dl_error,
            "captionPreview": caption_text,
            "timestampIst": now_ist_str,
            "retryCount": next_retry_count,
            "idempotencyToken": next_token,
            "reuseAuthorized": True,
            "authorizationSource": revalidated_source,
        }
        record_activity(failed_rec, db_path=db_path)
        return build_retry_response(
            status="RETRY_FAILED",
            conclusion="failed (retry download error)",
            message=f"Retry download failed for Reel {clean_pk}: {dl_error}",
            record=failed_rec,
            processed=1,
            errors=1,
        )

    ok, outcome_status, pub_error = safe_publish_reel(
        client=active_client,
        reel_pk=clean_pk,
        video_path=video_path,
        caption=caption_text,
        hashtag=tag,
        original_url=target_url,
        idempotency_token=next_token,
        dry_run=effective_dry_run,
        auto_publish_enabled=effective_auto_publish,
        reuse_authorized=True,
        db_path=db_path,
    )
    cleanup_video(video_path)

    updated_rec = {
        **persisted,
        "downloaderUsed": downloader_used,
        "outcomeStatus": outcome_status,
        "failureReason": pub_error,
        "captionPreview": caption_text,
        "timestampIst": now_ist_str,
        "retryCount": next_retry_count,
        "idempotencyToken": next_token,
        "reuseAuthorized": True,
        "authorizationSource": revalidated_source,
    }
    record_activity(updated_rec, db_path=db_path)

    if ok:
        if used_cap_id is not None:
            record_caption_used(used_cap_id, db_path=db_path)
        posted_cnt = 1 if outcome_status == "PUBLISHED" else 0
        return build_retry_response(
            status="RETRY_SUCCEEDED",
            conclusion=f"success (retry {outcome_status.lower()})",
            message=f"Targeted retry succeeded for Reel {clean_pk} ({outcome_status}).",
            record=updated_rec,
            processed=1,
            posted=posted_cnt,
            errors=0,
        )

    # Check if upload failed with IN_FLIGHT_AMBIGUOUS
    after_state = get_upload_state(clean_pk, db_path=db_path)
    if after_state == "IN_FLIGHT_AMBIGUOUS":
        return build_retry_response(
            status="RETRY_UNRESOLVED_AMBIGUOUS",
            conclusion="unresolved (upload outcome uncertain, locked IN_FLIGHT_AMBIGUOUS)",
            message=pub_error or f"Upload outcome uncertain for Reel {clean_pk}; locked as IN_FLIGHT_AMBIGUOUS.",
            record=updated_rec,
            processed=1,
            errors=1,
        )

    return build_retry_response(
        status="RETRY_FAILED",
        conclusion="failed (retry publish error)",
        message=pub_error or f"Retry publish failed for Reel {clean_pk}.",
        record=updated_rec,
        processed=1,
        errors=1,
    )


def execute_bounded_cycle(
    ig_client=None,
    config_bundle: dict | None = None,
    force_dry_run: bool | None = None,
    force_manual: bool = False,
    command: str | None = None,
    retry_pk: str | None = None,
    idempotency_token: str | None = None,
    lock_owner_id: str | None = None,
    require_remote_config: bool = False,
    db_path: str | None = None,
) -> dict:
    """
    Executes a single bounded automation cycle (or targeted retry when retry_pk or
    command=='SAFE_RETRY' is specified) and returns a structured JSON result dictionary
    for the Google Apps Script backend and Rilot Android dashboard.
    """
    init_db(db_path=db_path)
    now_ist = datetime.now(IST_ZONE)
    now_ist_str = now_ist.strftime("%Y-%m-%d %H:%M:%S IST")
    clean_lock_owner = normalize_lock_owner_id(lock_owner_id)

    has_valid_bundle = isinstance(config_bundle, dict) and bool(config_bundle) and not config_bundle.get("remoteConfigError")
    if (require_remote_config and not has_valid_bundle) or (
        isinstance(config_bundle, dict) and bool(config_bundle.get("remoteConfigError"))
    ):
        err_msg = (
            str(config_bundle.get("remoteConfigError"))
            if isinstance(config_bundle, dict) and config_bundle.get("remoteConfigError")
            else "Remote configuration from Google Apps Script could not be fetched or authenticated. Refusing to fall back to stale/default configuration."
        )
        log_run("remote_config_failed", err_msg, db_path=db_path)
        return {
            "status": "REMOTE_CONFIG_FAILED",
            "lockOwnerId": clean_lock_owner,
            "runState": "PAUSED",
            "authStatus": "ERROR",
            "dryRun": True,
            "timestampIst": now_ist_str,
            "nextScheduledRunIst": (now_ist + timedelta(hours=POST_INTERVAL_HOURS)).strftime("%Y-%m-%d %H:%M:%S IST"),
            "message": err_msg,
            "candidatesDiscovered": 0,
            "qualifyingPoolCount": 0,
            "videosProcessed": 0,
            "successfulPosts": 0,
            "duplicateSkips": 0,
            "errorsCount": 1,
            "workflowConclusion": "failed (remote config fetch/auth failed)",
            "activityRecords": [],
            "postedPks": list(get_all_posted_pks(db_path=db_path)),
        }

    bundle = config_bundle if isinstance(config_bundle, dict) else {}
    discovery_cfg = bundle.get(
        "discoveryConfig",
        {
            "candidateCollectionLimitPerTag": 30,
            "qualifyingPoolSize": 10,
            "contentSource": "RECENT_AND_TOP",
            "selectionStrategy": "QUALIFYING_POOL_WEIGHTED",
            "minViewCountThreshold": 25000,
            "minLikeToViewRatioPercent": 3.2,
            "minDurationSeconds": 6,
            "maxDurationSeconds": 60,
            "recencyWeightPercent": 35,
            "engagementWeightPercent": 40,
            "diversityWeightPercent": 25,
            "excludePreviouslyProcessed": True,
            "avoidSameCreatorInWindow": True,
            "preferYtdlpWithInstagrapiFallback": True,
            "allowAllHashtagCandidatesWhenTargetAuthorized": False,
            "authorizedCreators": [],
            "ownedAccounts": [],
            "authorizedCandidatePks": [],
        },
    )
    schedule_cfg = bundle.get(
        "scheduleConfig",
        {
            "timezoneId": "Asia/Kolkata",
            "postIntervalHours": POST_INTERVAL_HOURS,
            "maxPostsPerCycle": MAX_POSTS_PER_CYCLE,
            "dailyPostingLimit": DAILY_POSTING_LIMIT,
            "delayBetweenPostsSeconds": DELAY_BETWEEN_POSTS,
            "dryRunMode": DRY_RUN_MODE,
            "autoPublishEnabled": AUTO_PUBLISH_ENABLED,
            "postingWindows": [],
        },
    )
    # Fail-closed default: missing reuseAuthorized and allowAllHashtagCandidatesWhenTargetAuthorized default to False
    hashtag_targets = bundle.get(
        "hashtags",
        [
            {
                "tag": h,
                "category": "Fitness & Strength",
                "isEnabled": True,
                "candidateLimitPerRun": discovery_cfg.get("candidateCollectionLimitPerTag", 30) if isinstance(discovery_cfg, dict) else 30,
                "minViewsThreshold": discovery_cfg.get("minViewCountThreshold", 25000) if isinstance(discovery_cfg, dict) else 25000,
                "priorityWeight": 1.0,
                "reuseAuthorized": False,
                "allowAllHashtagCandidatesWhenTargetAuthorized": False,
            }
            for h in HASHTAGS
        ],
    )
    caption_library = bundle.get("captions", DEFAULT_FIXED_CAPTION_LIBRARY)
    caption_mode = bundle.get("captionRewriteMode", "FIXED_LIBRARY_RANDOM")
    remote_posted_pks = set(str(x) for x in bundle.get("postedPks", []))
    sync_remote_posted_pks(remote_posted_pks, db_path=db_path)

    remote_ambiguous_pks = set(str(x) for x in bundle.get("ambiguousPks", []))
    if remote_ambiguous_pks:
        sync_remote_ambiguous_pks(remote_ambiguous_pks, db_path=db_path)

    remote_activity_records = list(bundle.get("activityRecords", []))
    retry_target_rec = bundle.get("retryTarget")
    if isinstance(retry_target_rec, dict) and retry_target_rec.get("reelPk"):
        remote_activity_records.insert(0, retry_target_rec)
    if isinstance(remote_activity_records, list) and remote_activity_records:
        sync_remote_activity_records(remote_activity_records, db_path=db_path)

    effective_dry_run = (
        force_dry_run
        if force_dry_run is not None
        else (bool(schedule_cfg.get("dryRunMode", True)) or not bool(schedule_cfg.get("autoPublishEnabled", False)))
    )
    effective_auto_publish = bool(schedule_cfg.get("autoPublishEnabled", False)) and not effective_dry_run

    interval_hours = max(1, int(schedule_cfg.get("postIntervalHours", POST_INTERVAL_HOURS)))
    next_run_ist = (now_ist + timedelta(hours=interval_hours)).strftime("%Y-%m-%d %H:%M:%S IST")

    # CRITICAL: If a retry was requested via retry_pk OR command == "SAFE_RETRY",
    # NEVER fall back to a normal discovery cycle.
    is_retry_mode = (retry_pk is not None) or (str(command or "").strip().upper() == "SAFE_RETRY")
    if is_retry_mode:
        return execute_targeted_retry(
            ig_client=ig_client,
            retry_pk=retry_pk,
            idempotency_token=idempotency_token,
            discovery_cfg=discovery_cfg,
            hashtag_targets=hashtag_targets,
            schedule_cfg=schedule_cfg,
            caption_library=caption_library,
            caption_mode=caption_mode,
            remote_posted_pks=remote_posted_pks,
            effective_dry_run=effective_dry_run,
            effective_auto_publish=effective_auto_publish,
            now_ist=now_ist,
            next_run_ist=next_run_ist,
            lock_owner_id=clean_lock_owner,
            db_path=db_path,
        )

    # Check if automation is paused in Apps Script backend state (unless manual trigger)
    run_state = bundle.get("runState", "DRY_RUN" if effective_dry_run else "ENABLED")
    if run_state == "PAUSED" and not force_manual:
        log_run("skipped_paused", "Automation state is PAUSED", db_path=db_path)
        return {
            "status": "PAUSED_SKIP",
            "lockOwnerId": clean_lock_owner,
            "runState": "PAUSED",
            "authStatus": "OK",
            "dryRun": effective_dry_run,
            "timestampIst": now_ist_str,
            "nextScheduledRunIst": next_run_ist,
            "candidatesDiscovered": 0,
            "qualifyingPoolCount": 0,
            "videosProcessed": 0,
            "successfulPosts": 0,
            "duplicateSkips": 0,
            "errorsCount": 0,
            "workflowConclusion": "skipped (paused)",
            "activityRecords": [],
            "postedPks": list(get_all_posted_pks(db_path=db_path)),
        }

    allowed, available_slots, sched_reason = check_schedule_and_limits(
        schedule_config=schedule_cfg,
        now_ist=now_ist,
        force_manual=force_manual,
        db_path=db_path,
    )
    if not allowed:
        log_run("skipped_schedule", sched_reason, db_path=db_path)
        return {
            "status": "SCHEDULE_SKIP",
            "lockOwnerId": clean_lock_owner,
            "runState": "DRY_RUN" if effective_dry_run else "ENABLED",
            "authStatus": "OK",
            "dryRun": effective_dry_run,
            "timestampIst": now_ist_str,
            "nextScheduledRunIst": next_run_ist,
            "message": sched_reason,
            "candidatesDiscovered": 0,
            "qualifyingPoolCount": 0,
            "videosProcessed": 0,
            "successfulPosts": 0,
            "duplicateSkips": 0,
            "errorsCount": 0,
            "workflowConclusion": f"skipped ({sched_reason})",
            "activityRecords": [],
            "postedPks": list(get_all_posted_pks(db_path=db_path)),
        }

    # Authenticate Instagram client if not injected
    active_client = ig_client
    if active_client is None:
        try:
            active_client = get_client(USERNAME, PASSWORD, non_interactive=True)
        except SessionExpiredError as auth_err:
            log_run("auth_expired", str(auth_err), db_path=db_path)
            return {
                "status": "AUTH_EXPIRED",
                "lockOwnerId": clean_lock_owner,
                "runState": "PAUSED",
                "authStatus": "EXPIRED",
                "dryRun": effective_dry_run,
                "timestampIst": now_ist_str,
                "nextScheduledRunIst": next_run_ist,
                "message": str(auth_err),
                "candidatesDiscovered": 0,
                "qualifyingPoolCount": 0,
                "videosProcessed": 0,
                "successfulPosts": 0,
                "duplicateSkips": 0,
                "errorsCount": 1,
                "workflowConclusion": "failed (session expired)",
                "activityRecords": [],
                "postedPks": list(get_all_posted_pks(db_path=db_path)),
            }

    excluded_pks = get_all_posted_pks(db_path=db_path) | remote_posted_pks

    selected_candidates, discovery_records = discover_and_select_candidates(
        client=active_client,
        hashtag_targets=hashtag_targets,
        discovery_config=discovery_cfg,
        excluded_pks=excluded_pks,
        max_to_select=available_slots,
    )

    for rec in discovery_records:
        rec["timestampIst"] = now_ist_str
        record_activity(rec, db_path=db_path)

    duplicate_skips = sum(
        1 for r in discovery_records if r.get("outcomeStatus") == "SKIPPED_DUPLICATE_PK"
    )
    qualifying_count = len(selected_candidates) + sum(
        1 for r in discovery_records if r.get("outcomeStatus") == "QUALIFIED_QUEUED"
    )
    total_discovered = len(selected_candidates) + len(discovery_records)

    videos_processed = 0
    successful_posts = 0
    errors_count = 0
    processed_records: list[dict] = []
    delay_sec = max(0, int(schedule_cfg.get("delayBetweenPostsSeconds", DELAY_BETWEEN_POSTS)))
    prefer_ytdlp = bool(discovery_cfg.get("preferYtdlpWithInstagrapiFallback", True)) if isinstance(discovery_cfg, dict) else True

    for idx, cand in enumerate(selected_candidates):
        videos_processed += 1
        usage_map = get_caption_usage_map(db_path=db_path)
        caption_text, used_cap_id, _ = build_caption_for_candidate(
            original_caption=getattr(cand.media, "caption_text", "") or "",
            hashtag=cand.hashtag,
            caption_mode=caption_mode,
            captions=caption_library,
            usage_map=usage_map,
        )

        # Enforce strict fail-closed boolean authorization check
        strict_auth = is_strict_boolean_true(cand.reuse_authorized)
        video_path, downloader_used, dl_error = download_authorized_candidate(
            client=active_client,
            media=cand.media,
            url=cand.reel_url,
            reuse_authorized=strict_auth,
            prefer_ytdlp=prefer_ytdlp,
            dry_run=effective_dry_run,
        )

        if dl_error:
            errors_count += 1
            rec = {
                "reelPk": cand.reel_pk,
                "shortCode": cand.short_code,
                "reelUrl": cand.reel_url,
                "hashtag": cand.hashtag,
                "category": cand.category,
                "creatorHandle": cand.creator_handle,
                "viewCount": cand.view_count,
                "likeCount": cand.like_count,
                "commentCount": cand.comment_count,
                "durationSeconds": cand.duration_seconds,
                "qualityScore": cand.quality_score,
                "selectionReason": cand.selection_reason,
                "downloaderUsed": downloader_used,
                "outcomeStatus": "UNAUTHORIZED_BLOCKED" if not strict_auth else "DOWNLOAD_FAILED",
                "failureReason": dl_error,
                "captionPreview": caption_text,
                "timestampIst": now_ist_str,
                "idempotencyToken": f"idem_{cand.reel_pk}",
                "reuseAuthorized": strict_auth,
                "authorizationSource": cand.authorization_source if strict_auth else "NONE",
            }
            record_activity(rec, db_path=db_path)
            processed_records.append(rec)
            continue

        idem_tok = f"idem_{cand.reel_pk}"
        ok, outcome_status, pub_error = safe_publish_reel(
            client=active_client,
            reel_pk=cand.reel_pk,
            video_path=video_path,
            caption=caption_text,
            hashtag=cand.hashtag,
            original_url=cand.reel_url,
            idempotency_token=idem_tok,
            dry_run=effective_dry_run,
            auto_publish_enabled=effective_auto_publish,
            reuse_authorized=strict_auth,
            db_path=db_path,
        )
        cleanup_video(video_path)

        if ok:
            if used_cap_id is not None:
                record_caption_used(used_cap_id, db_path=db_path)
            if outcome_status == "PUBLISHED":
                successful_posts += 1
        else:
            errors_count += 1

        rec = {
            "reelPk": cand.reel_pk,
            "shortCode": cand.short_code,
            "reelUrl": cand.reel_url,
            "hashtag": cand.hashtag,
            "category": cand.category,
            "creatorHandle": cand.creator_handle,
            "viewCount": cand.view_count,
            "likeCount": cand.like_count,
            "commentCount": cand.comment_count,
            "durationSeconds": cand.duration_seconds,
            "qualityScore": cand.quality_score,
            "selectionReason": cand.selection_reason,
            "downloaderUsed": downloader_used,
            "outcomeStatus": outcome_status,
            "failureReason": pub_error,
            "captionPreview": caption_text,
            "timestampIst": now_ist_str,
            "idempotencyToken": idem_tok,
            "reuseAuthorized": strict_auth,
            "authorizationSource": cand.authorization_source if strict_auth else "NONE",
        }
        record_activity(rec, db_path=db_path)
        processed_records.append(rec)

        if (
            idx < len(selected_candidates) - 1
            and delay_sec > 0
            and not effective_dry_run
        ):
            logger.info(f"Waiting {delay_sec}s before next post...")
            time.sleep(delay_sec)

    conclusion = (
        f"success (dry-run, {videos_processed} validated)"
        if effective_dry_run
        else f"success (posted={successful_posts}, errors={errors_count})"
    )
    log_run("completed", conclusion, db_path=db_path)

    all_cycle_records = processed_records + discovery_records
    return {
        "status": "COMPLETED",
        "lockOwnerId": clean_lock_owner,
        "runState": "DRY_RUN" if effective_dry_run else "ENABLED",
        "authStatus": "OK",
        "dryRun": effective_dry_run,
        "timestampIst": now_ist_str,
        "lastSuccessfulRunIst": now_ist_str,
        "nextScheduledRunIst": next_run_ist,
        "candidatesDiscovered": total_discovered,
        "qualifyingPoolCount": qualifying_count,
        "videosProcessed": videos_processed,
        "successfulPosts": successful_posts,
        "duplicateSkips": duplicate_skips,
        "errorsCount": errors_count,
        "workflowConclusion": conclusion,
        "activityRecords": all_cycle_records[:40],
        "postedPks": list(get_all_posted_pks(db_path=db_path)),
    }


def run_cycle():
    """Original entrypoint preserved for backwards compatibility."""
    global client
    logger.info("=== Starting automation cycle ===")
    log_run("started", f"hashtags={HASHTAGS}")
    posts_this_cycle = 0

    for hashtag in HASHTAGS:
        if posts_this_cycle >= MAX_POSTS_PER_CYCLE:
            logger.info("Reached max posts per cycle, stopping.")
            break

        reels = get_top_reels(client, hashtag, top_n=5)

        for media in reels:
            if posts_this_cycle >= MAX_POSTS_PER_CYCLE:
                break

            if already_posted(media.pk):
                logger.info(f"Skipping already-posted reel {media.pk}")
                continue

            url = reel_url(media)
            logger.info(f"Processing reel: {url}")

            video_path = download_reel(client, media, url)
            if not video_path:
                logger.error(f"Download failed for {url}, skipping.")
                continue

            original_caption = media.caption_text or ""
            new_caption = rewrite_caption(original_caption, [hashtag])

            success = post_reel(client, video_path, new_caption)
            cleanup_video(video_path)

            if success:
                mark_posted(media.pk, hashtag=hashtag, original_url=url)
                posts_this_cycle += 1
                logger.info(f"Posted {posts_this_cycle}/{MAX_POSTS_PER_CYCLE} this cycle.")
                if posts_this_cycle < MAX_POSTS_PER_CYCLE:
                    logger.info(f"Waiting {DELAY_BETWEEN_POSTS}s before next post...")
                    time.sleep(DELAY_BETWEEN_POSTS)

    log_run("completed", f"posted={posts_this_cycle}")
    logger.info(f"=== Cycle complete. Posted {posts_this_cycle} reel(s). ===")


def main():
    parser = argparse.ArgumentParser(
        description="Rilot / InstaAutomation Python Engine (Bounded CI & Daemon Modes)"
    )
    parser.add_argument(
        "--once",
        action="store_true",
        help="Execute exactly one bounded automation cycle and exit (for GitHub Actions).",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Force dry-run mode (discover, score, and validate without uploading).",
    )
    parser.add_argument(
        "--force-manual",
        action="store_true",
        help="Bypass posting window wait when triggered manually via workflow_dispatch.",
    )
    parser.add_argument(
        "--command",
        type=str,
        default=None,
        help="Control command (START_CYCLE, DRY_RUN_CYCLE, SAFE_RETRY).",
    )
    parser.add_argument(
        "--retry-pk",
        type=str,
        default=None,
        help="Target a specific failed candidate PK for safe idempotent retry.",
    )
    parser.add_argument(
        "--idempotency-token",
        type=str,
        default=None,
        help="Idempotency token for retry execution.",
    )
    parser.add_argument(
        "--lock-owner-id",
        type=str,
        default=None,
        help="Cycle lock owner ID issued by Google Apps Script backend.",
    )
    parser.add_argument(
        "--config-json",
        type=str,
        default=None,
        help="Path to JSON configuration bundle retrieved from Google Apps Script.",
    )
    parser.add_argument(
        "--output-json",
        type=str,
        default="cycle_result.json",
        help="Path to write structured JSON results for the dashboard/Apps Script.",
    )
    parser.add_argument(
        "--require-remote-config",
        action="store_true",
        help="Fail closed if remote configuration from Google Apps Script cannot be fetched or verified.",
    )
    parser.add_argument(
        "--fetch-config-to",
        type=str,
        default=None,
        help="Fetch active configuration bundle from Google Apps Script and write to the given JSON path.",
    )
    parser.add_argument(
        "--report-failure",
        action="store_true",
        help="Report a workflow failure telemetry payload to Google Apps Script and exit.",
    )
    parser.add_argument(
        "--failure-message",
        type=str,
        default="GitHub Actions workflow step failed.",
        help="Failure message when --report-failure is used.",
    )
    args = parser.parse_args()

    engine_api_url = os.getenv("RILOT_ENGINE_API_URL", "").strip()
    engine_api_key = os.getenv("RILOT_ENGINE_API_KEY", "").strip()

    if args.fetch_config_to:
        if not engine_api_url or not engine_api_key:
            logger.error("Cannot fetch remote configuration: RILOT_ENGINE_API_URL or RILOT_ENGINE_API_KEY is missing.")
            sys.exit(1)
        bundle = fetch_apps_script_config(engine_api_url, engine_api_key)
        if not isinstance(bundle, dict) or not bundle:
            logger.error("Failed to fetch configuration bundle from Google Apps Script. Failing closed.")
            sys.exit(1)
        if args.retry_pk and is_valid_reel_pk(args.retry_pk):
            retry_data = fetch_apps_script_retry_target(engine_api_url, engine_api_key, args.retry_pk.strip())
            if isinstance(retry_data, dict) and isinstance(retry_data.get("record"), dict):
                bundle["retryTarget"] = retry_data["record"]
        with open(args.fetch_config_to, "w", encoding="utf-8") as f:
            json.dump(bundle, f, indent=2)
        logger.info(f"Saved remote configuration bundle from Google Apps Script to {args.fetch_config_to}")
        sys.exit(0)

    if args.report_failure:
        clean_owner = normalize_lock_owner_id(args.lock_owner_id)
        now_ist_str = datetime.now(IST_ZONE).strftime("%Y-%m-%d %H:%M:%S IST")
        is_retry = bool(args.retry_pk) or str(args.command or "").strip().upper() == "SAFE_RETRY"
        payload = {
            "status": "RETRY_FAILED" if is_retry else "FAILED",
            "mode": "TARGETED_RETRY" if is_retry else "DISCOVERY_CYCLE",
            "targetReelPk": (args.retry_pk or "").strip() or None,
            "lockOwnerId": clean_owner,
            "timestampIst": now_ist_str,
            "workflowConclusion": f"failed ({args.failure_message})",
            "message": args.failure_message,
            "errorsCount": 1,
            "videosProcessed": 0,
            "successfulPosts": 0,
            "activityRecords": [],
        }
        if engine_api_url and engine_api_key:
            reported = report_cycle_to_apps_script(engine_api_url, engine_api_key, payload)
            logger.info(f"Reported failure telemetry to Google Apps Script: {reported}")
        sys.exit(0)

    if args.once:
        config_bundle = None
        if args.config_json:
            if os.path.exists(args.config_json):
                try:
                    with open(args.config_json, "r", encoding="utf-8") as f:
                        config_bundle = json.load(f)
                except Exception as exc:
                    config_bundle = {"remoteConfigError": f"Failed to parse config JSON '{args.config_json}': {exc}"}
            elif args.require_remote_config:
                config_bundle = {"remoteConfigError": f"Required config bundle file '{args.config_json}' was not found."}
        elif engine_api_url or engine_api_key or args.require_remote_config:
            fetched = fetch_apps_script_config(engine_api_url, engine_api_key)
            if isinstance(fetched, dict) and fetched:
                if args.retry_pk and is_valid_reel_pk(args.retry_pk):
                    retry_data = fetch_apps_script_retry_target(engine_api_url, engine_api_key, args.retry_pk.strip())
                    if isinstance(retry_data, dict) and isinstance(retry_data.get("record"), dict):
                        fetched["retryTarget"] = retry_data["record"]
                config_bundle = fetched
            else:
                config_bundle = {
                    "remoteConfigError": "Failed to fetch or authenticate configuration from Google Apps Script."
                }

        force_dry = True if args.dry_run else None
        result = execute_bounded_cycle(
            ig_client=None,
            config_bundle=config_bundle,
            force_dry_run=force_dry,
            force_manual=args.force_manual,
            command=args.command,
            retry_pk=args.retry_pk,
            idempotency_token=args.idempotency_token,
            lock_owner_id=args.lock_owner_id,
            require_remote_config=args.require_remote_config,
        )

        with open(args.output_json, "w", encoding="utf-8") as f:
            json.dump(result, f, indent=2)
        logger.info(f"Wrote bounded cycle JSON report to {args.output_json}")

        if engine_api_url and engine_api_key:
            reported = report_cycle_to_apps_script(engine_api_url, engine_api_key, result)
            logger.info(f"Reported cycle result to Google Apps Script: {reported}")

        if result.get("status") == "AUTH_EXPIRED":
            sys.exit(2)
        if result.get("status") in (
            "REMOTE_CONFIG_FAILED",
            "RETRY_REJECTED",
            "RETRY_FAILED",
            "RETRY_UNRESOLVED_AMBIGUOUS",
        ):
            sys.exit(1)
        sys.exit(0)

    # Legacy continuous daemon mode
    if not USERNAME or not PASSWORD:
        raise SystemExit("ERROR: Set INSTAGRAM_USERNAME and INSTAGRAM_PASSWORD in .env")

    init_db()
    global client
    client = get_client(USERNAME, PASSWORD)

    logger.info(f"Scheduler started. Running every {POST_INTERVAL_HOURS} hour(s).")
    logger.info(f"Monitoring hashtags: {HASHTAGS}")

    run_cycle()
    if schedule is not None:
        schedule.every(POST_INTERVAL_HOURS).hours.do(run_cycle)
        while True:
            schedule.run_pending()
            time.sleep(60)


if __name__ == "__main__":
    main()
