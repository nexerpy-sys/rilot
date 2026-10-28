import json
import logging
import os

try:
    from instagrapi import Client
    from instagrapi.exceptions import LoginRequired, TwoFactorRequired
except ImportError:  # pragma: no cover - fallback for test environments without instagrapi C-deps
    Client = object  # type: ignore

    class LoginRequired(Exception):
        pass

    class TwoFactorRequired(Exception):
        pass


logger = logging.getLogger(__name__)

SESSION_FILE = "session.json"


class SessionExpiredError(RuntimeError):
    """Raised when instagrapi session is expired or requires interactive 2FA in headless CI."""


def bootstrap_session_file_from_env(session_file: str = SESSION_FILE) -> bool:
    """
    If INSTAGRAM_SESSION_JSON is provided via GitHub Actions Secrets, materialize
    session.json on the runner filesystem so instagrapi can reuse session cookies
    without performing a raw password login on every cron cycle.
    """
    raw_json = os.getenv("INSTAGRAM_SESSION_JSON", "").strip()
    if not raw_json:
        return os.path.exists(session_file)
    try:
        parsed = json.loads(raw_json)
        with open(session_file, "w", encoding="utf-8") as f:
            json.dump(parsed, f)
        logger.info("Materialized session.json from INSTAGRAM_SESSION_JSON environment secret.")
        return True
    except Exception as e:
        logger.error(f"INSTAGRAM_SESSION_JSON is not valid JSON: {e}")
        return os.path.exists(session_file)


def get_client(
    username: str,
    password: str,
    non_interactive: bool = False,
    session_file: str = SESSION_FILE,
) -> Client:
    """
    Returns an authenticated instagrapi Client.
    Loads saved session if available, otherwise logs in fresh.
    In headless CI (non_interactive=True), raises SessionExpiredError instead of blocking on input().
    """
    bootstrap_session_file_from_env(session_file)

    cl = Client()
    cl.delay_range = [2, 5]  # random delay between requests (seconds)

    if os.path.exists(session_file):
        try:
            cl.load_settings(session_file)
            cl.login(username, password)
            cl.get_timeline_feed()  # quick check that session is alive
            logger.info("Loaded existing session.")
            return cl
        except LoginRequired:
            logger.warning("Saved session expired, logging in fresh...")
        except Exception as e:
            logger.warning(f"Session load error ({e}), logging in fresh...")

    if not username or not password:
        raise SessionExpiredError(
            "Instagram session expired or missing, and INSTAGRAM_USERNAME / INSTAGRAM_PASSWORD are not set."
        )

    try:
        cl.login(username, password)
        cl.dump_settings(session_file)
        logger.info("Logged in fresh and saved session.")
        return cl
    except TwoFactorRequired as exc:
        if non_interactive or os.getenv("CI", "").lower() == "true":
            raise SessionExpiredError(
                "Instagram 2FA challenge required. Refresh session.json locally and update INSTAGRAM_SESSION_JSON secret."
            ) from exc
        code = input("Enter your 2FA code: ").strip()
        cl.login(username, password, verification_code=code)
        cl.dump_settings(session_file)
        logger.info("Logged in with 2FA and saved session.")
        return cl
    except Exception as exc:
        raise SessionExpiredError(f"Instagram authentication failed: {exc}") from exc
