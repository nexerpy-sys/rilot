import logging
import os
import random

try:
    import anthropic
except ImportError:  # pragma: no cover
    anthropic = None  # type: ignore

logger = logging.getLogger(__name__)

_client = None

DEFAULT_FIXED_CAPTION_LIBRARY = [
    {
        "id": 1,
        "title": "Gym Gravity Glitch Copypasta",
        "body": (
            "I asked the gym manager to turn down the gravity by 15% today because my "
            "central nervous system was still buffering from Tuesday. Somehow the bar still "
            "moved faster than my Wi-Fi."
        ),
        "category": "VIRAL_COPYPASTA",
        "isAbsurdCopypasta": True,
        "hashtagTemplate": "#calisthenics #gymhumor #streetworkout #reelsviral #fitnessmotivation",
        "usageCount": 0,
        "lastUsedEpochMs": None,
    },
    {
        "id": 2,
        "title": "Pre-Workout Time Traveler Copypasta",
        "body": (
            "Took 1.5 scoops of pre-workout at 6:42 PM IST and accidentally unlocked "
            "frame-perfect muscle-ups in a parallel timeline. Doctors hate this one simple physics exploit."
        ),
        "category": "VIRAL_COPYPASTA",
        "isAbsurdCopypasta": True,
        "hashtagTemplate": "#streetworkout #calisthenicsworkout #viralreels #gymtok #bodyweight",
        "usageCount": 0,
        "lastUsedEpochMs": None,
    },
    {
        "id": 3,
        "title": "Discipline Over Mood",
        "body": (
            "Consistency doesn't care if you're tired, busy, or unmotivated. "
            "Show up, put the reps in, and let the results speak louder than excuses."
        ),
        "category": "MOTIVATIONAL",
        "isAbsurdCopypasta": False,
        "hashtagTemplate": "#fitness #discipline #mindset #workoutroutine #strength",
        "usageCount": 0,
        "lastUsedEpochMs": None,
    },
    {
        "id": 4,
        "title": "Form Check Engagement Bait",
        "body": (
            "Rate the control on a scale of 1-10. Most people rush the negative phase "
            "and wonder why their strength plateaus."
        ),
        "category": "QUESTION_ENGAGEMENT",
        "isAbsurdCopypasta": False,
        "hashtagTemplate": "#formcheck #calisthenics #strengthconditioning #fitindia #reels",
        "usageCount": 0,
        "lastUsedEpochMs": None,
    },
]


def _get_client():
    global _client
    if anthropic is None:
        raise RuntimeError("anthropic package is not installed.")
    if _client is None:
        _client = anthropic.Anthropic()
    return _client


def rewrite_caption(original: str, hashtags: list[str]) -> str:
    """
    Original entrypoint preserved:
    Use Claude to rewrite an Instagram caption so it's fresh and unique.
    Always appends the provided hashtags.
    """
    tag_string = " ".join(f"#{h.lstrip('#')}" for h in hashtags if h.strip())

    if not original.strip():
        prompt = (
            f"Write a short, engaging Instagram Reel caption (2-3 sentences max). "
            f"Make it motivational, relatable, and trendy. "
            f"End with these hashtags on a new line: {tag_string}"
        )
    else:
        prompt = (
            f"Rewrite this Instagram Reel caption in a fresh, engaging way. "
            f"Keep the same topic and energy but use completely different words. "
            f"Keep it short (2-3 sentences max). "
            f"End with these hashtags on a new line: {tag_string}\n\n"
            f"Original caption:\n{original[:500]}"
        )

    try:
        response = _get_client().messages.create(
            model="claude-sonnet-4-6",
            max_tokens=300,
            messages=[{"role": "user", "content": prompt}],
        )
        new_caption = response.content[0].text.strip()
        logger.info(f"Rewritten caption: {new_caption[:80]}...")
        return new_caption
    except Exception as e:
        logger.error(f"Caption rewrite failed: {e}")
        fallback = (original[:400] + "\n\n" + tag_string).strip()
        return fallback


def select_non_repeating_caption(
    captions: list[dict],
    usage_map: dict[int, dict] | None = None,
    recent_exclude_count: int = 2,
    category_filter: str | None = None,
    rng_seed: int | None = None,
) -> dict | None:
    """
    Selects a caption from the library while excluding the most recently used
    templates (anti-repeat rotation) and preferring lower usage counts.
    """
    library = captions if captions else DEFAULT_FIXED_CAPTION_LIBRARY
    if not library:
        return None

    usage_lookup = usage_map or {}
    enriched = []
    for idx, item in enumerate(library):
        cid = int(item.get("id") or (idx + 1))
        u_info = usage_lookup.get(cid, {})
        last_used = u_info.get("lastUsedEpochMs", item.get("lastUsedEpochMs"))
        u_count = int(u_info.get("usageCount", item.get("usageCount", 0)) or 0)
        enriched.append(
            {
                **item,
                "id": cid,
                "lastUsedEpochMs": last_used,
                "usageCount": u_count,
            }
        )

    if category_filter:
        filtered = [c for c in enriched if c.get("category") == category_filter]
        pool = filtered if filtered else enriched
    else:
        pool = enriched

    if len(pool) <= 1:
        return pool[0]

    sorted_by_recent = sorted(
        [c for c in pool if c.get("lastUsedEpochMs") is not None],
        key=lambda c: int(c["lastUsedEpochMs"]),
        reverse=True,
    )
    safe_exclude = min(max(0, recent_exclude_count), len(pool) - 1)
    excluded_ids = {c["id"] for c in sorted_by_recent[:safe_exclude]}

    eligible = [c for c in pool if c["id"] not in excluded_ids]
    if not eligible:
        eligible = pool

    min_usage = min(c["usageCount"] for c in eligible)
    least_used_tier = [c for c in eligible if c["usageCount"] <= min_usage + 1]
    candidate_tier = least_used_tier if least_used_tier else eligible

    rng = random.Random(rng_seed) if rng_seed is not None else random.Random()
    return rng.choice(candidate_tier)


def build_caption_for_candidate(
    original_caption: str,
    hashtag: str,
    caption_mode: str = "FIXED_LIBRARY_RANDOM",
    captions: list[dict] | None = None,
    usage_map: dict[int, dict] | None = None,
    rng_seed: int | None = None,
) -> tuple[str, int | None, str]:
    """
    Produces the final caption text according to caption_mode:
    - FIXED_LIBRARY_RANDOM: selects non-repeating fixed/copypasta caption + hashtag template
    - HYBRID_TEMPLATE_PLUS_AI: combines fixed copypasta body with dynamic hashtags
    - AI_CLAUDE_SONNET_OPTIONAL: uses Claude rewrite if ANTHROPIC_API_KEY is present, else fixed library
    Returns (final_caption, used_caption_id, caption_source_description).
    """
    clean_tag = hashtag.strip().lstrip("#")

    if caption_mode == "AI_CLAUDE_SONNET_OPTIONAL" and os.getenv("ANTHROPIC_API_KEY", "").strip():
        rewritten = rewrite_caption(original_caption, [clean_tag, "reels", "viral"])
        return rewritten, None, "AI_CLAUDE_SONNET_4_6"

    chosen = select_non_repeating_caption(
        captions=captions or DEFAULT_FIXED_CAPTION_LIBRARY,
        usage_map=usage_map,
        recent_exclude_count=2,
        rng_seed=rng_seed,
    )
    if not chosen:
        fallback_text = f"Unmatched consistency.\n\n#{clean_tag} #reels #viral"
        return fallback_text, None, "FALLBACK_STATIC"

    body = str(chosen.get("body", "")).strip()
    tag_template = str(chosen.get("hashtagTemplate", "#reels #viral")).strip()
    primary_tag = f"#{clean_tag}"
    if primary_tag.lower() not in tag_template.lower():
        tags_line = f"{primary_tag} {tag_template}".strip()
    else:
        tags_line = tag_template

    final_caption = f"{body}\n\n{tags_line}".strip()
    return final_caption, int(chosen["id"]), str(chosen.get("title", "Fixed Library"))
