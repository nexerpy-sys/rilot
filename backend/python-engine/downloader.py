import logging
import os
from typing import Any

try:
    import yt_dlp
except ImportError:  # pragma: no cover
    yt_dlp = None  # type: ignore

try:
    from instagrapi import Client
    from instagrapi.types import Media
except ImportError:  # pragma: no cover
    Client = Any  # type: ignore
    Media = Any  # type: ignore

logger = logging.getLogger(__name__)

DOWNLOAD_DIR = "downloads"


def download_reel_ytdlp(url: str) -> str | None:
    """Download a reel via yt-dlp. Returns local file path or None on failure."""
    if yt_dlp is None:
        logger.error("yt-dlp module is not installed.")
        return None

    os.makedirs(DOWNLOAD_DIR, exist_ok=True)

    opts = {
        "outtmpl": os.path.join(DOWNLOAD_DIR, "%(id)s.%(ext)s"),
        "format": "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
        "merge_output_format": "mp4",
        "quiet": True,
        "no_warnings": True,
    }

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            path = ydl.prepare_filename(info)
            if not os.path.exists(path):
                path = path.rsplit(".", 1)[0] + ".mp4"
            logger.info(f"Downloaded via yt-dlp: {path}")
            return path
    except Exception as e:
        logger.error(f"yt-dlp download failed for {url}: {e}")
        return None


def download_reel_instagrapi(client: Client, media: Media) -> str | None:
    """Fallback: download via instagrapi's built-in clip_download."""
    os.makedirs(DOWNLOAD_DIR, exist_ok=True)
    try:
        pk = getattr(media, "pk", None) if media is not None else None
        if not pk:
            logger.error("instagrapi download failed: media.pk is missing.")
            return None
        path = client.clip_download(pk, folder=DOWNLOAD_DIR)
        logger.info(f"Downloaded via instagrapi: {path}")
        return str(path)
    except Exception as e:
        logger.error(f"instagrapi download failed: {e}")
        return None


def download_reel(client: Client, media: Media, url: str) -> str | None:
    """Try yt-dlp first, fall back to instagrapi."""
    path = download_reel_ytdlp(url)
    if path and os.path.exists(path):
        return path
    logger.warning("yt-dlp failed, trying instagrapi fallback...")
    return download_reel_instagrapi(client, media)


def download_authorized_candidate(
    client: Client,
    media: Media,
    url: str,
    reuse_authorized: Any = False,
    prefer_ytdlp: bool = True,
    dry_run: bool = False,
) -> tuple[str | None, str, str | None]:
    """
    Downloads a Reel candidate ONLY when reuse_authorized is strictly boolean True.
    Fail-closed: missing, None, or non-boolean reuse_authorized values are rejected
    in BOTH dry-run and live modes.
    Returns (video_path, downloader_used, failure_reason).
    """
    if reuse_authorized is not True:
        msg = "Download blocked: candidate reuse is not explicitly authorized (fail-closed)."
        logger.warning(msg)
        return None, "blocked (reuse unauthorized)", msg

    media_auth_flag = getattr(media, "reuse_authorized", None) if media is not None else None
    if media_auth_flag is not None and media_auth_flag is not True:
        msg = "Download blocked: media reuse_authorized attribute is not strictly True (fail-closed)."
        logger.warning(msg)
        return None, "blocked (reuse unauthorized)", msg

    if not isinstance(url, str) or not url.startswith("https://www.instagram.com/reel/"):
        msg = f"Download blocked: invalid or untrusted Reel URL '{url}'."
        logger.error(msg)
        return None, "blocked (invalid url)", msg

    if dry_run:
        logger.info(f"[Dry-Run] Validated authorized candidate URL without downloading video bytes: {url}")
        return None, "dry-run validated (no media download)", None

    if prefer_ytdlp:
        path = download_reel_ytdlp(url)
        if path and os.path.exists(path):
            return path, "yt-dlp (mp4 merge)", None
        logger.warning("yt-dlp failed, trying instagrapi clip_download fallback...")
        fallback_path = download_reel_instagrapi(client, media)
        if fallback_path and os.path.exists(fallback_path):
            return fallback_path, "instagrapi clip_download (fallback)", None
        return None, "yt-dlp + instagrapi failed", "Both yt-dlp and instagrapi clip_download failed."
    else:
        path = download_reel_instagrapi(client, media)
        if path and os.path.exists(path):
            return path, "instagrapi clip_download", None
        return None, "instagrapi failed", "instagrapi clip_download failed."


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
