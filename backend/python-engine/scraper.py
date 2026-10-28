import logging
import math
import random
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

try:
    from instagrapi import Client
    from instagrapi.types import Media
except ImportError:  # pragma: no cover
    Client = Any  # type: ignore
    Media = Any  # type: ignore

logger = logging.getLogger(__name__)

VALID_AUTHORIZATION_SOURCES = frozenset(
    {"ORIGINAL_ACCOUNT", "CREATOR_ALLOWLIST", "EXPLICIT_LICENSE", "TARGET_POLICY_GRANT"}
)


def is_strict_boolean_true(value: Any) -> bool:
    """
    Fail-closed boolean check: returns True ONLY if value is strictly the boolean True.
    Missing (None), strings ("true", "yes", "1"), numbers (1), or malformed objects
    always return False.
    """
    return value is True


def normalize_handle(handle: Any) -> str:
    if not isinstance(handle, str):
        return ""
    cleaned = handle.strip().lstrip("@").lower()
    return cleaned if cleaned and cleaned != "creator" else ""


def _extract_string_set(raw_list: Any, transform=None) -> tuple[bool, set[str]]:
    if raw_list is None:
        return True, set()
    if not isinstance(raw_list, list):
        return False, set()
    result: set[str] = set()
    for item in raw_list:
        if isinstance(item, bool) or not isinstance(item, (str, int)):
            return False, set()
        val = transform(str(item)) if transform else str(item).strip()
        if val:
            result.add(val)
    return True, result


def evaluate_candidate_reuse_authorization(
    media: Any,
    reel_pk: str,
    creator_handle: str,
    target_config: dict,
    discovery_config: dict,
) -> tuple[bool, str]:
    """
    Fail-closed authorization policy for candidate content:
    - Public visibility, hashtag membership, view counts, and engagement metrics
      NEVER grant permission to download or repost third-party content.
    - Missing, None, or non-boolean reuseAuthorized values default to False.
    - A hashtag-level reuseAuthorized=True does NOT authorize third-party candidates
      merely because creator and candidate allowlists are empty; broad hashtag-level
      authorization requires an explicit allowAllHashtagCandidatesWhenTargetAuthorized=True policy.
    - Otherwise requires creator ownership (ownedAccounts), an allowlisted creator
      (authorizedCreators + target reuseAuthorized=True), an explicitly licensed candidate PK
      (authorizedCandidatePks), or verifiable media-level authorization provenance.
    - Returns (is_authorized, authorization_reason).
    """
    if not isinstance(target_config, dict) or not isinstance(discovery_config, dict):
        return False, "Unauthorized: malformed target or discovery configuration (fail-closed)"

    raw_is_enabled = target_config.get("isEnabled", True)
    if raw_is_enabled is not None and not isinstance(raw_is_enabled, bool):
        return False, "Unauthorized: target isEnabled is malformed / non-boolean (fail-closed)"
    target_enabled = raw_is_enabled is not False

    # 1. Check if the candidate media object carries an explicit negative or malformed flag
    media_auth_attr = getattr(media, "reuse_authorized", None)
    if media_auth_attr is not None and not is_strict_boolean_true(media_auth_attr):
        return False, "Unauthorized: candidate reuse_authorized attribute is not strictly True (fail-closed)"

    # 2. Validate global and target reuseAuthorized flags (must be strict boolean if present)
    raw_target_auth = target_config.get("reuseAuthorized", False)
    if raw_target_auth is not None and not isinstance(raw_target_auth, bool):
        return False, "Unauthorized: target reuseAuthorized is malformed / non-boolean (fail-closed)"
    target_auth = target_enabled and is_strict_boolean_true(raw_target_auth)

    global_auth = discovery_config.get("reuseAuthorized", None)
    if global_auth is not None and not is_strict_boolean_true(global_auth):
        return False, "Unauthorized: global discoveryConfig.reuseAuthorized is not True (fail-closed)"

    # 3. Validate explicit bulk hashtag authorization policy (defaults to False)
    disc_bulk = discovery_config.get("allowAllHashtagCandidatesWhenTargetAuthorized", None)
    target_bulk = target_config.get("allowAllHashtagCandidatesWhenTargetAuthorized", None)
    if disc_bulk is not None and not isinstance(disc_bulk, bool):
        return (
            False,
            "Unauthorized: discoveryConfig.allowAllHashtagCandidatesWhenTargetAuthorized must be a strict boolean (fail-closed)",
        )
    if target_bulk is not None and not isinstance(target_bulk, bool):
        return (
            False,
            "Unauthorized: targetConfig.allowAllHashtagCandidatesWhenTargetAuthorized must be a strict boolean (fail-closed)",
        )
    if disc_bulk is not None and target_bulk is not None:
        allow_all_for_target = is_strict_boolean_true(disc_bulk) and is_strict_boolean_true(target_bulk)
    elif disc_bulk is not None:
        allow_all_for_target = is_strict_boolean_true(disc_bulk)
    elif target_bulk is not None:
        allow_all_for_target = is_strict_boolean_true(target_bulk)
    else:
        allow_all_for_target = False

    # 4. Validate and extract creator ownership, creator allowlist, and explicit candidate license lists
    norm_creator = normalize_handle(creator_handle)

    ok_own_d, own_d = _extract_string_set(discovery_config.get("ownedAccounts"), normalize_handle)
    ok_own_t, own_t = _extract_string_set(target_config.get("ownedAccounts"), normalize_handle)
    if not (ok_own_d and ok_own_t):
        return False, "Unauthorized: malformed ownedAccounts configuration (fail-closed)"
    own_handles = own_d | own_t

    ok_allow_d, allow_d = _extract_string_set(discovery_config.get("authorizedCreators"), normalize_handle)
    ok_allow_t, allow_t = _extract_string_set(target_config.get("authorizedCreators"), normalize_handle)
    if not (ok_allow_d and ok_allow_t):
        return False, "Unauthorized: malformed authorizedCreators configuration (fail-closed)"
    allowlist_handles = allow_d | allow_t

    ok_pks_d, pks_d = _extract_string_set(discovery_config.get("authorizedCandidatePks"))
    ok_pks_t, pks_t = _extract_string_set(target_config.get("authorizedCandidatePks"))
    if not (ok_pks_d and ok_pks_t):
        return False, "Unauthorized: malformed authorizedCandidatePks configuration (fail-closed)"
    authorized_pks = pks_d | pks_t

    clean_pk = str(reel_pk).strip() if reel_pk is not None else ""

    # Case A: Candidate belongs to the operator's own original account
    if norm_creator and norm_creator in own_handles:
        return True, f"Authorized (original owned account @{norm_creator}) [ORIGINAL_ACCOUNT]"

    # Case B: Specific candidate PK is explicitly licensed (valid independent of hashtag-wide policy)
    if clean_pk and clean_pk in authorized_pks:
        return True, f"Authorized (explicitly licensed candidate PK {clean_pk}) [EXPLICIT_LICENSE]"

    # Case C: Creator is explicitly on the creator allowlist AND target allows reuse
    if norm_creator and norm_creator in allowlist_handles and target_auth:
        return True, f"Authorized (allowlisted creator @{norm_creator}) [CREATOR_ALLOWLIST]"

    # Case D: Media object carries explicit verifiable authorization metadata
    media_auth_source = getattr(media, "authorization_source", None)
    if is_strict_boolean_true(media_auth_attr) and isinstance(media_auth_source, str):
        if media_auth_source in ("ORIGINAL_ACCOUNT", "EXPLICIT_LICENSE"):
            return True, f"Authorized ({media_auth_source}) [{media_auth_source}]"
        if media_auth_source == "CREATOR_ALLOWLIST" and target_auth and norm_creator in allowlist_handles:
            return True, "Authorized (CREATOR_ALLOWLIST) [CREATOR_ALLOWLIST]"
        if media_auth_source == "TARGET_POLICY_GRANT" and target_auth and allow_all_for_target:
            return True, "Authorized (TARGET_POLICY_GRANT) [TARGET_POLICY_GRANT]"

    # Case E: Target config explicitly grants reuseAuthorized=True AND explicit bulk policy is enabled
    if target_auth and allow_all_for_target:
        return (
            True,
            "Authorized (explicit allowAllHashtagCandidatesWhenTargetAuthorized=True bulk policy) [TARGET_POLICY_GRANT]",
        )

    return (
        False,
        "Discoverable only: missing explicit reuseAuthorized=True, creator allowlist, explicit license, or bulk policy permission (fail-closed)",
    )


def extract_authorization_source(auth_reason: str) -> str:
    for src in VALID_AUTHORIZATION_SOURCES:
        if f"[{src}]" in auth_reason or f"({src})" in auth_reason:
            return src
    return "NONE"


@dataclass
class ScoredCandidate:
    media: Any
    reel_pk: str
    short_code: str
    reel_url: str
    hashtag: str
    category: str
    creator_handle: str
    view_count: int
    like_count: int
    comment_count: int
    duration_seconds: int
    quality_score: float
    selection_reason: str
    source_feed: str
    reuse_authorized: bool = False
    authorization_reason: str = "Unauthorized (fail-closed default)"
    authorization_source: str = "NONE"


def get_top_reels(client: Client, hashtag: str, top_n: int = 2) -> list[Media]:
    """
    Original entrypoint preserved:
    Fetch trending reels for a hashtag, ranked by view count.
    Returns up to top_n Media objects.
    """
    logger.info(f"Fetching top reels for #{hashtag}")
    try:
        medias = client.hashtag_medias_top(hashtag, amount=30)
    except Exception as e:
        logger.error(f"Failed to fetch medias for #{hashtag}: {e}")
        return []

    reels = [m for m in medias if getattr(m, "media_type", None) == 2]

    if not reels:
        logger.warning(f"No reels found for #{hashtag}")
        return []

    ranked = sorted(reels, key=lambda m: getattr(m, "view_count", 0) or 0, reverse=True)
    top = ranked[:top_n]

    for r in top:
        views = getattr(r, "view_count", 0) or 0
        likes = getattr(r, "like_count", 0) or 0
        code = getattr(r, "code", "")
        logger.info(
            f"  Reel {r.pk} | views={views:,} | "
            f"likes={likes} | code={code}"
        )

    return top


def reel_url(media: Media) -> str:
    return f"https://www.instagram.com/reel/{media.code}/"


def fetch_accessible_hashtag_reels(
    client: Client,
    hashtag: str,
    collection_limit: int = 30,
    content_source: str = "RECENT_AND_TOP",
) -> list[tuple[Any, str]]:
    """
    Collects candidate Reels from accessible Instagram hashtag sources
    (hashtag_medias_top and/or hashtag_medias_recent) up to collection_limit,
    independently of the publishing limit.
    Returns list of (Media, source_feed_name) deduplicated by media.pk.
    """
    clean_tag = hashtag.strip().lstrip("#")
    limit = max(1, min(int(collection_limit), 100))
    collected: list[tuple[Any, str]] = []
    seen_pks: set[str] = set()

    fetch_top = content_source in ("RECENT_AND_TOP", "TOP_TRENDING_ONLY")
    fetch_recent = content_source in ("RECENT_AND_TOP", "RECENT_ONLY")

    top_amount = limit if not fetch_recent else max(1, (limit + 1) // 2)
    recent_amount = limit if not fetch_top else max(1, limit - top_amount)

    if fetch_top and hasattr(client, "hashtag_medias_top"):
        try:
            top_medias = client.hashtag_medias_top(clean_tag, amount=top_amount) or []
            for m in top_medias:
                pk_str = str(getattr(m, "pk", ""))
                if getattr(m, "media_type", None) == 2 and pk_str and pk_str not in seen_pks:
                    seen_pks.add(pk_str)
                    collected.append((m, "hashtag_medias_top"))
        except Exception as e:
            logger.warning(f"hashtag_medias_top unavailable for #{clean_tag}: {e}")

    if fetch_recent and hasattr(client, "hashtag_medias_recent"):
        try:
            recent_medias = client.hashtag_medias_recent(clean_tag, amount=recent_amount) or []
            for m in recent_medias:
                pk_str = str(getattr(m, "pk", ""))
                if getattr(m, "media_type", None) == 2 and pk_str and pk_str not in seen_pks:
                    seen_pks.add(pk_str)
                    collected.append((m, "hashtag_medias_recent"))
        except Exception as e:
            logger.warning(f"hashtag_medias_recent unavailable for #{clean_tag}: {e}")

    return collected[:limit]


def compute_candidate_score(
    media: Any,
    hashtag: str,
    category: str,
    priority_weight: float,
    discovery_config: dict,
    now_utc: datetime | None = None,
) -> tuple[float, str, dict]:
    """
    Scores a Reel candidate using:
    1. View count & View velocity (views/hour when taken_at and view_count are measurable; never fabricated)
    2. Engagement signal (like-to-view ratio + comment engagement, or raw engagement if views hidden)
    3. Recency signal (freshness based on taken_at timestamp)
    4. Relevance & hashtag priority weight
    """
    current_time = now_utc or datetime.now(timezone.utc)

    raw_views = getattr(media, "view_count", None)
    if raw_views is None:
        raw_views = getattr(media, "play_count", None)
    views_measurable = raw_views is not None and int(raw_views) > 0
    view_count = int(raw_views) if views_measurable else 0

    raw_likes = getattr(media, "like_count", 0)
    like_count = max(0, int(raw_likes or 0))

    raw_comments = getattr(media, "comment_count", 0)
    comment_count = max(0, int(raw_comments or 0))

    raw_duration = getattr(media, "video_duration", None)
    duration_seconds = int(round(float(raw_duration))) if raw_duration else 18

    taken_at = getattr(media, "taken_at", None)
    age_hours: float | None = None
    if isinstance(taken_at, datetime):
        if taken_at.tzinfo is None:
            taken_at = taken_at.replace(tzinfo=timezone.utc)
        age_hours = max(0.25, (current_time - taken_at).total_seconds() / 3600.0)

    if age_hours is not None:
        recency_score = 100.0 * math.exp(-age_hours / 96.0)
    else:
        recency_score = 50.0

    if views_measurable:
        like_ratio_pct = (like_count / max(view_count, 1)) * 100.0
        weighted_eng_pct = ((like_count + comment_count * 2.5) / max(view_count, 1)) * 100.0
        ratio_score = min(100.0, (weighted_eng_pct / 8.0) * 100.0)
        volume_score = min(100.0, (math.log10(max(view_count, 10)) / 6.0) * 100.0)

        if age_hours is not None:
            views_per_hour = view_count / age_hours
            velocity_score = min(100.0, (math.log10(max(views_per_hour, 1.0)) / 4.5) * 100.0)
            engagement_score = 0.45 * ratio_score + 0.30 * velocity_score + 0.25 * volume_score
        else:
            views_per_hour = None
            engagement_score = 0.60 * ratio_score + 0.40 * volume_score
    else:
        like_ratio_pct = 0.0
        views_per_hour = None
        engagement_score = min(
            100.0, (math.log10(max(like_count + comment_count * 3, 1)) / 4.0) * 100.0
        )

    caption_text = (getattr(media, "caption_text", "") or "").lower()
    tag_in_caption = f"#{hashtag.lower()}" in caption_text
    relevance_score = min(100.0, (75.0 + (15.0 if tag_in_caption else 0.0)) * float(priority_weight))

    w_rec = max(0, int(discovery_config.get("recencyWeightPercent", 35)))
    w_eng = max(0, int(discovery_config.get("engagementWeightPercent", 40)))
    w_div = max(0, int(discovery_config.get("diversityWeightPercent", 25)))
    total_w = max(1, w_rec + w_eng + w_div)

    composite = (
        (recency_score * w_rec)
        + (engagement_score * w_eng)
        + (relevance_score * w_div)
    ) / total_w
    final_score = round(min(99.9, max(1.0, composite)), 1)

    velocity_note = (
        f"velocity={int(views_per_hour):,}/hr"
        if views_per_hour is not None
        else "velocity=unmeasured"
    )
    views_note = (
        f"views={view_count:,} ({like_ratio_pct:.1f}% L/V)"
        if views_measurable
        else f"views=unreported (likes={like_count:,})"
    )
    reason = f"Score {final_score} [{views_note}, {velocity_note}, cat={category}]"

    metrics = {
        "view_count": view_count,
        "views_measurable": views_measurable,
        "like_count": like_count,
        "comment_count": comment_count,
        "like_ratio_pct": round(like_ratio_pct, 2),
        "duration_seconds": duration_seconds,
    }
    return final_score, reason, metrics


def discover_and_select_candidates(
    client: Client,
    hashtag_targets: list[dict],
    discovery_config: dict,
    excluded_pks: set[str],
    max_to_select: int = 2,
    rng_seed: int | None = None,
) -> tuple[list[ScoredCandidate], list[dict]]:
    """
    Discovers candidates across enabled hashtag targets, filters out duplicates and
    sub-threshold clips, enforces fail-closed content reuse authorization, forms the
    authorized qualifying pool, and selects up to max_to_select candidates.

    Discoverable candidates that lack explicit reuse authorization are recorded in
    activity_records with outcomeStatus='UNAUTHORIZED_BLOCKED' and are NEVER added
    to the downloadable/publishable candidate pool.
    """
    rng = random.Random(rng_seed) if rng_seed is not None else random.Random()

    content_source = discovery_config.get("contentSource", "RECENT_AND_TOP")
    strategy = discovery_config.get("selectionStrategy", "QUALIFYING_POOL_WEIGHTED")
    global_limit = int(discovery_config.get("candidateCollectionLimitPerTag", 30))
    qualifying_pool_size = max(1, int(discovery_config.get("qualifyingPoolSize", 10)))
    global_min_views = int(discovery_config.get("minViewCountThreshold", 25000))
    min_like_ratio = float(discovery_config.get("minLikeToViewRatioPercent", 3.2))
    min_duration = int(discovery_config.get("minDurationSeconds", 6))
    max_duration = int(discovery_config.get("maxDurationSeconds", 60))
    exclude_processed = bool(discovery_config.get("excludePreviouslyProcessed", True))
    avoid_same_creator = bool(discovery_config.get("avoidSameCreatorInWindow", True))

    qualifying: list[ScoredCandidate] = []
    activity_records: list[dict] = []
    seen_in_cycle: set[str] = set()

    for target in hashtag_targets:
        if not isinstance(target, dict) or not target.get("isEnabled", True):
            continue
        tag = str(target.get("tag", "")).strip().lstrip("#")
        if not tag:
            continue
        category = str(target.get("category", "General"))
        tag_limit = int(target.get("candidateLimitPerRun", global_limit))
        tag_min_views = max(global_min_views, int(target.get("minViewsThreshold", global_min_views)))
        priority_weight = float(target.get("priorityWeight", 1.0))

        fetched = fetch_accessible_hashtag_reels(
            client=client,
            hashtag=tag,
            collection_limit=tag_limit,
            content_source=content_source,
        )

        for media, source_feed in fetched:
            pk_str = str(getattr(media, "pk", ""))
            if not pk_str or pk_str in seen_in_cycle:
                continue
            seen_in_cycle.add(pk_str)

            code = str(getattr(media, "code", "") or pk_str)
            url = reel_url(media)
            user_obj = getattr(media, "user", None)
            creator_handle = (
                f"@{getattr(user_obj, 'username', 'creator')}"
                if user_obj and getattr(user_obj, "username", None)
                else "@creator"
            )

            score, reason, metrics = compute_candidate_score(
                media=media,
                hashtag=tag,
                category=category,
                priority_weight=priority_weight,
                discovery_config=discovery_config,
            )

            is_authorized, auth_reason = evaluate_candidate_reuse_authorization(
                media=media,
                reel_pk=pk_str,
                creator_handle=creator_handle,
                target_config=target,
                discovery_config=discovery_config,
            )
            auth_source = extract_authorization_source(auth_reason) if is_authorized else "NONE"

            # 1. Check duplicate exclusion
            if exclude_processed and pk_str in excluded_pks:
                activity_records.append(
                    {
                        "reelPk": pk_str,
                        "shortCode": code,
                        "reelUrl": url,
                        "hashtag": tag,
                        "category": category,
                        "creatorHandle": creator_handle,
                        "viewCount": metrics["view_count"],
                        "likeCount": metrics["like_count"],
                        "commentCount": metrics["comment_count"],
                        "durationSeconds": metrics["duration_seconds"],
                        "qualityScore": score,
                        "selectionReason": f"Excluded: already in posted.db ({source_feed})",
                        "downloaderUsed": "none (skipped duplicate)",
                        "outcomeStatus": "SKIPPED_DUPLICATE_PK",
                        "failureReason": "Duplicate PK present in posted.db",
                        "captionPreview": "",
                        "reuseAuthorized": is_authorized,
                        "authorizationSource": auth_source,
                    }
                )
                continue

            # 2. Check quality thresholds
            dur = metrics["duration_seconds"]
            views_ok = (not metrics["views_measurable"]) or (metrics["view_count"] >= tag_min_views)
            ratio_ok = (not metrics["views_measurable"]) or (metrics["like_ratio_pct"] >= min_like_ratio)
            dur_ok = min_duration <= dur <= max_duration

            if not (views_ok and ratio_ok and dur_ok):
                filter_reasons = []
                if not views_ok:
                    filter_reasons.append(f"views {metrics['view_count']:,} < {tag_min_views:,}")
                if not ratio_ok:
                    filter_reasons.append(f"L/V {metrics['like_ratio_pct']:.1f}% < {min_like_ratio:.1f}%")
                if not dur_ok:
                    filter_reasons.append(f"duration {dur}s outside {min_duration}-{max_duration}s")
                fail_str = "; ".join(filter_reasons)
                activity_records.append(
                    {
                        "reelPk": pk_str,
                        "shortCode": code,
                        "reelUrl": url,
                        "hashtag": tag,
                        "category": category,
                        "creatorHandle": creator_handle,
                        "viewCount": metrics["view_count"],
                        "likeCount": metrics["like_count"],
                        "commentCount": metrics["comment_count"],
                        "durationSeconds": dur,
                        "qualityScore": score,
                        "selectionReason": f"Filtered ({fail_str})",
                        "downloaderUsed": "none (filtered)",
                        "outcomeStatus": "FILTERED_QUALITY",
                        "failureReason": fail_str,
                        "captionPreview": "",
                        "reuseAuthorized": is_authorized,
                        "authorizationSource": auth_source,
                    }
                )
                continue

            # 3. Enforce fail-closed content reuse authorization
            # Discoverable candidates without explicit authorization are logged as UNAUTHORIZED_BLOCKED
            # and excluded from the downloadable/publishable qualifying pool.
            if not is_authorized:
                activity_records.append(
                    {
                        "reelPk": pk_str,
                        "shortCode": code,
                        "reelUrl": url,
                        "hashtag": tag,
                        "category": category,
                        "creatorHandle": creator_handle,
                        "viewCount": metrics["view_count"],
                        "likeCount": metrics["like_count"],
                        "commentCount": metrics["comment_count"],
                        "durationSeconds": dur,
                        "qualityScore": score,
                        "selectionReason": f"{reason} — {auth_reason}",
                        "downloaderUsed": "blocked (reuse unauthorized)",
                        "outcomeStatus": "UNAUTHORIZED_BLOCKED",
                        "failureReason": auth_reason,
                        "captionPreview": "",
                        "reuseAuthorized": False,
                        "authorizationSource": "NONE",
                    }
                )
                continue

            qualifying.append(
                ScoredCandidate(
                    media=media,
                    reel_pk=pk_str,
                    short_code=code,
                    reel_url=url,
                    hashtag=tag,
                    category=category,
                    creator_handle=creator_handle,
                    view_count=metrics["view_count"],
                    like_count=metrics["like_count"],
                    comment_count=metrics["comment_count"],
                    duration_seconds=dur,
                    quality_score=score,
                    selection_reason=f"{reason} via {source_feed} [{auth_reason}]",
                    source_feed=source_feed,
                    reuse_authorized=True,
                    authorization_reason=auth_reason,
                    authorization_source=auth_source,
                )
            )

    qualifying.sort(key=lambda c: c.quality_score, reverse=True)
    pool = qualifying[:qualifying_pool_size]

    selected: list[ScoredCandidate] = []
    used_creators: set[str] = set()
    used_categories: set[str] = set()
    remaining_pool = list(pool)

    while remaining_pool and len(selected) < max_to_select:
        eligible = [
            c
            for c in remaining_pool
            if (not avoid_same_creator or c.creator_handle not in used_creators)
        ]
        if not eligible:
            eligible = list(remaining_pool)

        diverse_subset = [c for c in eligible if c.category not in used_categories]
        working_set = diverse_subset if diverse_subset else eligible

        if strategy == "DIVERSE_CATEGORY_ROTATION":
            chosen = working_set[0]
        elif strategy == "ENGAGEMENT_VELOCITY":
            chosen = max(
                working_set,
                key=lambda c: (c.like_count + c.comment_count * 2) / max(c.view_count, 100),
            )
        else:
            weights = [max(1.0, c.quality_score) ** 2 for c in working_set]
            chosen = rng.choices(working_set, weights=weights, k=1)[0]

        selected.append(chosen)
        used_creators.add(chosen.creator_handle)
        used_categories.add(chosen.category)
        remaining_pool = [c for c in remaining_pool if c.reel_pk != chosen.reel_pk]

    selected_pks = {c.reel_pk for c in selected}
    for c in pool:
        if c.reel_pk not in selected_pks:
            activity_records.append(
                {
                    "reelPk": c.reel_pk,
                    "shortCode": c.short_code,
                    "reelUrl": c.reel_url,
                    "hashtag": c.hashtag,
                    "category": c.category,
                    "creatorHandle": c.creator_handle,
                    "viewCount": c.view_count,
                    "likeCount": c.like_count,
                    "commentCount": c.comment_count,
                    "durationSeconds": c.duration_seconds,
                    "qualityScore": c.quality_score,
                    "selectionReason": f"Qualified in pool ({c.selection_reason})",
                    "downloaderUsed": "queued (not selected this cycle)",
                    "outcomeStatus": "QUALIFIED_QUEUED",
                    "failureReason": None,
                    "captionPreview": "",
                    "reuseAuthorized": True,
                    "authorizationSource": c.authorization_source,
                }
            )

    return selected, activity_records
