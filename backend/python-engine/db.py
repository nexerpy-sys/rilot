import sqlite3
import time
from contextlib import contextmanager
from datetime import datetime
from zoneinfo import ZoneInfo

DB_PATH = "posted.db"
IST_ZONE = ZoneInfo("Asia/Kolkata")


@contextmanager
def _conn(db_path: str | None = None):
    target_path = db_path or DB_PATH
    con = sqlite3.connect(target_path)
    con.row_factory = sqlite3.Row
    try:
        yield con
        con.commit()
    finally:
        con.close()


def init_db(db_path: str | None = None):
    with _conn(db_path) as con:
        con.execute("""
            CREATE TABLE IF NOT EXISTS posted (
                pk                TEXT PRIMARY KEY,
                hashtag           TEXT,
                original_url      TEXT,
                upload_state      TEXT DEFAULT 'PUBLISHED',
                idempotency_token TEXT,
                posted_epoch_ms   INTEGER,
                posted_at         DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)
        existing_cols = {
            row["name"]
            for row in con.execute("PRAGMA table_info(posted)").fetchall()
        }
        if "upload_state" not in existing_cols:
            con.execute("ALTER TABLE posted ADD COLUMN upload_state TEXT DEFAULT 'PUBLISHED'")
        if "idempotency_token" not in existing_cols:
            con.execute("ALTER TABLE posted ADD COLUMN idempotency_token TEXT")
        if "posted_epoch_ms" not in existing_cols:
            con.execute("ALTER TABLE posted ADD COLUMN posted_epoch_ms INTEGER")

        con.execute("""
            CREATE TABLE IF NOT EXISTS run_log (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
                status      TEXT,
                message     TEXT
            )
        """)

        con.execute("""
            CREATE TABLE IF NOT EXISTS activity_history (
                reel_pk            TEXT PRIMARY KEY,
                short_code         TEXT,
                reel_url           TEXT,
                hashtag            TEXT,
                category           TEXT,
                creator_handle     TEXT,
                view_count         INTEGER,
                like_count         INTEGER,
                comment_count      INTEGER,
                duration_seconds   INTEGER,
                quality_score      REAL,
                selection_reason   TEXT,
                downloader_used    TEXT,
                outcome_status     TEXT,
                failure_reason     TEXT,
                caption_preview    TEXT,
                timestamp_ist      TEXT,
                timestamp_epoch_ms INTEGER,
                retry_count        INTEGER DEFAULT 0,
                idempotency_token  TEXT,
                reuse_authorized   INTEGER DEFAULT 0,
                authorization_source TEXT DEFAULT 'NONE'
            )
        """)
        act_cols = {
            row["name"]
            for row in con.execute("PRAGMA table_info(activity_history)").fetchall()
        }
        if "reuse_authorized" not in act_cols:
            con.execute("ALTER TABLE activity_history ADD COLUMN reuse_authorized INTEGER DEFAULT 0")
        if "authorization_source" not in act_cols:
            con.execute("ALTER TABLE activity_history ADD COLUMN authorization_source TEXT DEFAULT 'NONE'")

        con.execute("""
            CREATE TABLE IF NOT EXISTS caption_usage (
                caption_id         INTEGER PRIMARY KEY,
                title              TEXT,
                usage_count        INTEGER DEFAULT 0,
                last_used_epoch_ms INTEGER
            )
        """)


def already_posted(reel_pk: str, db_path: str | None = None) -> bool:
    with _conn(db_path) as con:
        row = con.execute(
            "SELECT upload_state FROM posted WHERE pk = ?", (str(reel_pk),)
        ).fetchone()
        return row is not None


def get_upload_state(reel_pk: str, db_path: str | None = None) -> str | None:
    with _conn(db_path) as con:
        row = con.execute(
            "SELECT upload_state FROM posted WHERE pk = ?", (str(reel_pk),)
        ).fetchone()
        return row["upload_state"] if row else None


def mark_upload_in_flight(
    reel_pk: str,
    hashtag: str = "",
    original_url: str = "",
    idempotency_token: str = "",
    db_path: str | None = None,
):
    """
    Marks a candidate PK as IN_FLIGHT_AMBIGUOUS prior to invoking clip_upload.
    If the process crashes or experiences an ambiguous network timeout after media
    bytes are sent, retries will see IN_FLIGHT_AMBIGUOUS and refuse blind duplicate re-uploads.
    """
    now_ms = int(time.time() * 1000)
    with _conn(db_path) as con:
        con.execute(
            """
            INSERT OR REPLACE INTO posted
            (pk, hashtag, original_url, upload_state, idempotency_token, posted_epoch_ms)
            VALUES (?, ?, ?, 'IN_FLIGHT_AMBIGUOUS', ?, ?)
            """,
            (str(reel_pk), hashtag, original_url, idempotency_token, now_ms),
        )


def clear_in_flight_if_confirmed_unposted(reel_pk: str, db_path: str | None = None):
    """
    Clears the IN_FLIGHT_AMBIGUOUS lock ONLY when an exception occurs deterministically
    before upload commit (e.g., local file missing or pre-upload validation error).
    """
    with _conn(db_path) as con:
        con.execute(
            "DELETE FROM posted WHERE pk = ? AND upload_state = 'IN_FLIGHT_AMBIGUOUS'",
            (str(reel_pk),),
        )


def mark_posted(
    reel_pk: str,
    hashtag: str = "",
    original_url: str = "",
    idempotency_token: str = "",
    db_path: str | None = None,
):
    now_ms = int(time.time() * 1000)
    with _conn(db_path) as con:
        con.execute(
            """
            INSERT OR REPLACE INTO posted
            (pk, hashtag, original_url, upload_state, idempotency_token, posted_epoch_ms)
            VALUES (?, ?, ?, 'PUBLISHED', ?, ?)
            """,
            (str(reel_pk), hashtag, original_url, idempotency_token or f"idem_{reel_pk}", now_ms),
        )


def log_run(status: str, message: str, db_path: str | None = None):
    with _conn(db_path) as con:
        con.execute(
            "INSERT INTO run_log (status, message) VALUES (?, ?)",
            (status, message),
        )


def get_all_posted_pks(db_path: str | None = None) -> set[str]:
    with _conn(db_path) as con:
        rows = con.execute("SELECT pk FROM posted").fetchall()
        return {str(r["pk"]) for r in rows}


def sync_remote_posted_pks(pks: list[str] | set[str], db_path: str | None = None):
    if not pks:
        return
    now_ms = int(time.time() * 1000)
    with _conn(db_path) as con:
        for pk in pks:
            con.execute(
                """
                INSERT OR IGNORE INTO posted
                (pk, hashtag, original_url, upload_state, idempotency_token, posted_epoch_ms)
                VALUES (?, 'remote_sync', '', 'PUBLISHED', ?, ?)
                """,
                (str(pk), f"idem_{pk}", now_ms),
            )


def sync_remote_ambiguous_pks(pks: list[str] | set[str], db_path: str | None = None):
    """
    Syncs IN_FLIGHT_AMBIGUOUS PKs from Google Apps Script ProcessedItems into local posted.db
    so uncertain in-flight uploads are never blindly re-uploaded across ephemeral CI runners.
    """
    if not pks:
        return
    now_ms = int(time.time() * 1000)
    with _conn(db_path) as con:
        for pk in pks:
            con.execute(
                """
                INSERT OR IGNORE INTO posted
                (pk, hashtag, original_url, upload_state, idempotency_token, posted_epoch_ms)
                VALUES (?, 'remote_sync', '', 'IN_FLIGHT_AMBIGUOUS', ?, ?)
                """,
                (str(pk), f"idem_{pk}", now_ms),
            )


def sync_remote_activity_records(records: list[dict], db_path: str | None = None):
    """
    Syncs persisted backend activity records from the Google Apps Script configuration bundle into
    local activity_history without overwriting newer local records or fabricating authorization.
    """
    if not isinstance(records, list):
        return
    for rec in records:
        if not isinstance(rec, dict) or not rec.get("reelPk"):
            continue
        existing = get_activity_by_pk(str(rec["reelPk"]), db_path=db_path)
        if existing is None:
            record_activity(rec, db_path=db_path)


def get_posted_count_today_ist(now_ist: datetime | None = None, db_path: str | None = None) -> int:
    current_ist = now_ist or datetime.now(IST_ZONE)
    start_of_day_ist = current_ist.replace(hour=0, minute=0, second=0, microsecond=0)
    start_epoch_ms = int(start_of_day_ist.timestamp() * 1000)
    with _conn(db_path) as con:
        row = con.execute(
            """
            SELECT COUNT(*) AS cnt FROM posted
            WHERE upload_state = 'PUBLISHED' AND posted_epoch_ms >= ?
            """,
            (start_epoch_ms,),
        ).fetchone()
        return int(row["cnt"]) if row else 0


def get_last_post_epoch_ms(db_path: str | None = None) -> int | None:
    with _conn(db_path) as con:
        row = con.execute(
            """
            SELECT MAX(posted_epoch_ms) AS last_ms FROM posted
            WHERE upload_state = 'PUBLISHED'
            """
        ).fetchone()
        return int(row["last_ms"]) if row and row["last_ms"] is not None else None


def record_activity(record: dict, db_path: str | None = None):
    now_ist_str = datetime.now(IST_ZONE).strftime("%Y-%m-%d %H:%M:%S IST")
    now_ms = int(time.time() * 1000)
    reel_pk = str(record["reelPk"])
    # Fail-closed authorization persistence: only strict boolean True is stored as 1
    reuse_auth_int = 1 if record.get("reuseAuthorized") is True else 0
    auth_source = (
        str(record.get("authorizationSource") or "NONE").strip()
        if reuse_auth_int == 1
        else "NONE"
    )
    with _conn(db_path) as con:
        con.execute(
            """
            INSERT OR REPLACE INTO activity_history (
                reel_pk, short_code, reel_url, hashtag, category, creator_handle,
                view_count, like_count, comment_count, duration_seconds,
                quality_score, selection_reason, downloader_used, outcome_status,
                failure_reason, caption_preview, timestamp_ist, timestamp_epoch_ms,
                retry_count, idempotency_token, reuse_authorized, authorization_source
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                reel_pk,
                record.get("shortCode", ""),
                record.get("reelUrl", ""),
                record.get("hashtag", ""),
                record.get("category", "General"),
                record.get("creatorHandle", "@creator"),
                int(record.get("viewCount", 0)),
                int(record.get("likeCount", 0)),
                int(record.get("commentCount", 0)),
                int(record.get("durationSeconds", 15)),
                float(record.get("qualityScore", 0.0)),
                record.get("selectionReason", ""),
                record.get("downloaderUsed", "none"),
                record.get("outcomeStatus", "QUALIFIED_QUEUED"),
                record.get("failureReason"),
                record.get("captionPreview", ""),
                record.get("timestampIst", now_ist_str),
                int(record.get("timestampEpochMs", now_ms)),
                int(record.get("retryCount", 0)),
                record.get("idempotencyToken", f"idem_{reel_pk}"),
                reuse_auth_int,
                auth_source,
            ),
        )


def _row_to_activity_dict(r: sqlite3.Row) -> dict:
    keys = r.keys()
    return {
        "reelPk": str(r["reel_pk"]),
        "shortCode": r["short_code"] or "",
        "reelUrl": r["reel_url"] or "",
        "hashtag": r["hashtag"] or "",
        "category": r["category"] or "General",
        "creatorHandle": r["creator_handle"] or "@creator",
        "viewCount": int(r["view_count"] or 0),
        "likeCount": int(r["like_count"] or 0),
        "commentCount": int(r["comment_count"] or 0),
        "durationSeconds": int(r["duration_seconds"] or 0),
        "qualityScore": float(r["quality_score"] or 0.0),
        "selectionReason": r["selection_reason"] or "",
        "downloaderUsed": r["downloader_used"] or "none",
        "outcomeStatus": r["outcome_status"] or "QUALIFIED_QUEUED",
        "failureReason": r["failure_reason"],
        "captionPreview": r["caption_preview"] or "",
        "timestampIst": r["timestamp_ist"] or "",
        "timestampEpochMs": int(r["timestamp_epoch_ms"] or 0),
        "retryCount": int(r["retry_count"] or 0),
        "idempotencyToken": r["idempotency_token"] or f"idem_{r['reel_pk']}",
        "reuseAuthorized": bool(r["reuse_authorized"] == 1),
        "authorizationSource": (r["authorization_source"] or "NONE")
        if "authorization_source" in keys
        else "NONE",
    }


def get_activity_by_pk(reel_pk: str, db_path: str | None = None) -> dict | None:
    if not reel_pk:
        return None
    with _conn(db_path) as con:
        row = con.execute(
            "SELECT * FROM activity_history WHERE reel_pk = ? LIMIT 1",
            (str(reel_pk),),
        ).fetchone()
        return _row_to_activity_dict(row) if row else None


def get_recent_activity(limit: int = 50, db_path: str | None = None) -> list[dict]:
    with _conn(db_path) as con:
        rows = con.execute(
            """
            SELECT * FROM activity_history
            ORDER BY timestamp_epoch_ms DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
        return [_row_to_activity_dict(r) for r in rows]


def get_caption_usage_map(db_path: str | None = None) -> dict[int, dict]:
    with _conn(db_path) as con:
        rows = con.execute("SELECT * FROM caption_usage").fetchall()
        return {
            int(r["caption_id"]): {
                "usageCount": int(r["usage_count"] or 0),
                "lastUsedEpochMs": r["last_used_epoch_ms"],
            }
            for r in rows
        }


def record_caption_used(caption_id: int, title: str = "", db_path: str | None = None):
    now_ms = int(time.time() * 1000)
    with _conn(db_path) as con:
        existing = con.execute(
            "SELECT usage_count FROM caption_usage WHERE caption_id = ?",
            (int(caption_id),),
        ).fetchone()
        next_count = (int(existing["usage_count"]) + 1) if existing else 1
        con.execute(
            """
            INSERT OR REPLACE INTO caption_usage (caption_id, title, usage_count, last_used_epoch_ms)
            VALUES (?, ?, ?, ?)
            """,
            (int(caption_id), title, next_count, now_ms),
        )
