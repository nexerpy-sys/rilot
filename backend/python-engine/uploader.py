import logging
import os
from typing import Any

from db import (
    clear_in_flight_if_confirmed_unposted,
    get_upload_state,
    mark_posted,
    mark_upload_in_flight,
)

try:
    from instagrapi import Client
except ImportError:  # pragma: no cover
    Client = Any  # type: ignore

logger = logging.getLogger(__name__)


def post_reel(client: Client, video_path: str, caption: str) -> bool:
    """Original entrypoint preserved: Upload a video as an Instagram Reel. Returns True on success."""
    if not os.path.exists(video_path):
        logger.error(f"Video file not found: {video_path}")
        return False

    try:
        media = client.clip_upload(path=video_path, caption=caption)
        if media and getattr(media, "pk", None):
            logger.info(f"Posted reel successfully: {media.pk} | code={getattr(media, 'code', '')}")
            return True
        logger.error("clip_upload returned empty media object; refusing to confirm post success.")
        return False
    except Exception as e:
        logger.error(f"Failed to upload reel: {e}")
        return False


def safe_publish_reel(
    client: Client,
    reel_pk: str,
    video_path: str | None,
    caption: str,
    hashtag: str,
    original_url: str,
    idempotency_token: str,
    dry_run: bool = True,
    auto_publish_enabled: bool = False,
    reuse_authorized: Any = False,
    db_path: str | None = None,
) -> tuple[bool, str, str | None]:
    """
    Safely executes or simulates Reel publishing with fail-closed authorization,
    duplicate exclusion, and ambiguous-failure guards:
    1. Rejects any candidate where reuse_authorized is not strictly boolean True
       in BOTH dry-run and live modes.
    2. Checks posted.db for existing PUBLISHED or IN_FLIGHT_AMBIGUOUS state.
    3. Honors dry_run and auto_publish_enabled flags (never uploads when dry_run=True or auto_publish_enabled=False).
    4. Records an IN_FLIGHT_AMBIGUOUS lock before calling clip_upload so ambiguous network drops
       during upload cannot trigger blind duplicate re-posts on retry.
    5. Only marks PUBLISHED after confirming returned Media.pk from Instagram.

    Returns (succeeded_or_validated, outcome_status, failure_reason).
    """
    if reuse_authorized is not True:
        msg = f"Publish blocked: Reel {reel_pk} does not have explicit reuseAuthorized=True permission (fail-closed)."
        logger.warning(msg)
        return False, "UNAUTHORIZED_BLOCKED", msg

    existing_state = get_upload_state(reel_pk, db_path=db_path)
    if existing_state == "PUBLISHED":
        msg = f"Duplicate guard blocked upload: Reel {reel_pk} is already marked PUBLISHED in posted.db."
        logger.warning(msg)
        return False, "SKIPPED_DUPLICATE_PK", msg

    if existing_state == "IN_FLIGHT_AMBIGUOUS":
        msg = (
            f"Ambiguous upload guard blocked retry: Reel {reel_pk} has state IN_FLIGHT_AMBIGUOUS "
            f"from a prior interrupted upload. Manual verification required to prevent duplicate post."
        )
        logger.error(msg)
        return False, "UPLOAD_FAILED", msg

    if dry_run or not auto_publish_enabled:
        logger.info(
            f"[Dry-Run / AutoPublish=False] Validated authorized candidate {reel_pk} without calling clip_upload."
        )
        return True, "DRY_RUN_VALIDATED", None

    if not video_path or not os.path.exists(video_path):
        clear_in_flight_if_confirmed_unposted(reel_pk, db_path=db_path)
        msg = f"Video file not found prior to upload: {video_path}"
        logger.error(msg)
        return False, "UPLOAD_FAILED", msg

    # Acquire pre-upload lock to protect against ambiguous mid-upload network timeouts
    mark_upload_in_flight(
        reel_pk=reel_pk,
        hashtag=hashtag,
        original_url=original_url,
        idempotency_token=idempotency_token,
        db_path=db_path,
    )

    try:
        uploaded_media = client.clip_upload(path=video_path, caption=caption)
        uploaded_pk = getattr(uploaded_media, "pk", None) if uploaded_media else None
        if not uploaded_pk:
            return (
                False,
                "UPLOAD_FAILED",
                "clip_upload returned no media PK; upload state kept as IN_FLIGHT_AMBIGUOUS to prevent duplicate retry.",
            )

        mark_posted(
            reel_pk=reel_pk,
            hashtag=hashtag,
            original_url=original_url,
            idempotency_token=idempotency_token,
            db_path=db_path,
        )
        logger.info(
            f"Confirmed live Reel upload: source_pk={reel_pk} -> uploaded_pk={uploaded_pk}"
        )
        return True, "PUBLISHED", None
    except Exception as exc:
        logger.error(f"clip_upload exception for {reel_pk}: {exc}")
        return (
            False,
            "UPLOAD_FAILED",
            f"clip_upload error (marked IN_FLIGHT_AMBIGUOUS until verified): {exc}",
        )


def cleanup_video(video_path: str | None):
    """Delete the local video file after posting."""
    if not video_path:
        return
    try:
        if os.path.exists(video_path):
            os.remove(video_path)
            logger.info(f"Cleaned up: {video_path}")
    except Exception as e:
        logger.warning(f"Could not delete {video_path}: {e}")
