import os
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from db import (
    already_posted,
    get_activity_by_pk,
    get_caption_usage_map,
    get_posted_count_today_ist,
    get_recent_activity,
    get_upload_state,
    init_db,
    mark_posted,
    mark_upload_in_flight,
    record_activity,
    record_caption_used,
)
from downloader import download_authorized_candidate
from main import (
    call_apps_script_rpc,
    check_schedule_and_limits,
    execute_bounded_cycle,
    fetch_apps_script_config,
    is_within_ist_posting_windows,
    report_cycle_to_apps_script,
)
from rewriter import (
    DEFAULT_FIXED_CAPTION_LIBRARY,
    build_caption_for_candidate,
    select_non_repeating_caption,
)
from scraper import (
    compute_candidate_score,
    discover_and_select_candidates,
    evaluate_candidate_reuse_authorization,
)
from uploader import safe_publish_reel

IST_ZONE = ZoneInfo("Asia/Kolkata")


class DummyUser:
    def __init__(self, username: str):
        self.username = username


class DummyMedia:
    def __init__(
        self,
        pk: str,
        code: str,
        view_count: int | None = 80000,
        like_count: int = 5200,
        comment_count: int = 180,
        video_duration: float = 18.0,
        username: str = "athlete_one",
        caption_text: str = "Weighted pullups #calisthenics",
        age_hours: float = 4.0,
        media_type: int = 2,
        reuse_authorized=None,
        authorization_source=None,
    ):
        self.pk = pk
        self.code = code
        self.view_count = view_count
        self.like_count = like_count
        self.comment_count = comment_count
        self.video_duration = video_duration
        self.user = DummyUser(username)
        self.caption_text = caption_text
        self.taken_at = datetime.now(timezone.utc) - timedelta(hours=age_hours)
        self.media_type = media_type
        if reuse_authorized is not None:
            self.reuse_authorized = reuse_authorized
        if authorization_source is not None:
            self.authorization_source = authorization_source


class DummyInstaClient:
    def __init__(self, top_medias=None, recent_medias=None, upload_pk="999999"):
        self._top = top_medias or []
        self._recent = recent_medias or []
        self._upload_pk = upload_pk
        self.upload_calls = 0
        self.top_scrape_calls = 0
        self.recent_scrape_calls = 0

    def hashtag_medias_top(self, hashtag: str, amount: int = 30):
        self.top_scrape_calls += 1
        return self._top[:amount]

    def hashtag_medias_recent(self, hashtag: str, amount: int = 30):
        self.recent_scrape_calls += 1
        return self._recent[:amount]

    def clip_upload(self, path: str, caption: str):
        self.upload_calls += 1
        if not self._upload_pk:
            return None
        return DummyMedia(pk=self._upload_pk, code="UP_CODE")


class PythonEngineUnitTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = os.path.join(self.temp_dir.name, "test_posted.db")
        init_db(self.db_path)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_candidate_scoring_and_unreported_views_not_fabricated(self):
        cfg = {
            "recencyWeightPercent": 35,
            "engagementWeightPercent": 40,
            "diversityWeightPercent": 25,
        }
        m_measured = DummyMedia(
            pk="101",
            code="C101",
            view_count=120000,
            like_count=8400,
            comment_count=310,
            age_hours=3.0,
        )
        score1, reason1, metrics1 = compute_candidate_score(
            m_measured, "calisthenics", "Strength", 1.0, cfg
        )
        self.assertGreater(score1, 70.0)
        self.assertTrue(metrics1["views_measurable"])
        self.assertEqual(metrics1["view_count"], 120000)
        self.assertIn("velocity=", reason1)

        # Unreported view count must not fabricate views
        m_hidden = DummyMedia(
            pk="102",
            code="C102",
            view_count=None,
            like_count=1500,
            comment_count=90,
            age_hours=2.0,
        )
        score2, reason2, metrics2 = compute_candidate_score(
            m_hidden, "calisthenics", "Strength", 1.0, cfg
        )
        self.assertFalse(metrics2["views_measurable"])
        self.assertEqual(metrics2["view_count"], 0)
        self.assertIn("views=unreported", reason2)
        self.assertGreater(score2, 30.0)

    def test_discovery_excludes_duplicates_and_enforces_diversity(self):
        m1 = DummyMedia("201", "A201", view_count=95000, like_count=6000, username="creator_a")
        m2 = DummyMedia("202", "A202", view_count=90000, like_count=5800, username="creator_a")
        m3 = DummyMedia("203", "A203", view_count=85000, like_count=5500, username="creator_b")
        m_dup = DummyMedia("204", "A204", view_count=150000, like_count=9900, username="creator_c")
        m_low = DummyMedia("205", "A205", view_count=4000, like_count=50, username="creator_d")

        client = DummyInstaClient(top_medias=[m1, m2, m_dup], recent_medias=[m3, m_low])
        targets = [
            {
                "tag": "calisthenics",
                "category": "Bodyweight",
                "isEnabled": True,
                "candidateLimitPerRun": 20,
                "minViewsThreshold": 25000,
                "priorityWeight": 1.0,
                "reuseAuthorized": True,
            }
        ]
        cfg = {
            "candidateCollectionLimitPerTag": 20,
            "qualifyingPoolSize": 10,
            "contentSource": "RECENT_AND_TOP",
            "selectionStrategy": "DIVERSE_CATEGORY_ROTATION",
            "minViewCountThreshold": 25000,
            "minLikeToViewRatioPercent": 3.0,
            "minDurationSeconds": 6,
            "maxDurationSeconds": 60,
            "excludePreviouslyProcessed": True,
            "avoidSameCreatorInWindow": True,
            "authorizedCreators": ["creator_a", "creator_b", "creator_c", "creator_d"],
        }

        selected, records = discover_and_select_candidates(
            client=client,
            hashtag_targets=targets,
            discovery_config=cfg,
            excluded_pks={"204"},
            max_to_select=2,
            rng_seed=42,
        )

        selected_pks = [c.reel_pk for c in selected]
        self.assertEqual(len(selected_pks), 2)
        self.assertNotIn("204", selected_pks)  # Excluded duplicate
        self.assertNotIn("205", selected_pks)  # Filtered low views
        creators = {c.creator_handle for c in selected}
        self.assertEqual(creators, {"@creator_a", "@creator_b"})

        outcomes = {r["reelPk"]: r["outcomeStatus"] for r in records}
        self.assertEqual(outcomes.get("204"), "SKIPPED_DUPLICATE_PK")
        self.assertEqual(outcomes.get("205"), "FILTERED_QUALITY")

    def test_caption_rotation_prevents_recent_repeats(self):
        record_caption_used(1, "Cap 1", db_path=self.db_path)
        record_caption_used(2, "Cap 2", db_path=self.db_path)
        usage_map = get_caption_usage_map(db_path=self.db_path)

        chosen = select_non_repeating_caption(
            captions=DEFAULT_FIXED_CAPTION_LIBRARY,
            usage_map=usage_map,
            recent_exclude_count=2,
            rng_seed=7,
        )
        self.assertIsNotNone(chosen)
        self.assertNotIn(chosen["id"], {1, 2})

        text, cid, _ = build_caption_for_candidate(
            original_caption="Original text",
            hashtag="calisthenics",
            caption_mode="FIXED_LIBRARY_RANDOM",
            captions=DEFAULT_FIXED_CAPTION_LIBRARY,
            usage_map=usage_map,
            rng_seed=7,
        )
        self.assertNotIn(cid, {1, 2})
        self.assertIn("#calisthenics", text.lower())

    def test_schedule_limits_and_ist_windows(self):
        windows = [
            {
                "id": "win_morning",
                "label": "Morning Window",
                "startHourIst": 8,
                "startMinuteIst": 30,
                "endHourIst": 11,
                "endMinuteIst": 0,
                "isEnabled": True,
            }
        ]
        inside_time = datetime(2026, 10, 28, 9, 15, tzinfo=IST_ZONE)
        outside_time = datetime(2026, 10, 28, 14, 0, tzinfo=IST_ZONE)

        ok_in, _ = is_within_ist_posting_windows(windows, now_ist=inside_time)
        ok_out, _ = is_within_ist_posting_windows(windows, now_ist=outside_time)
        self.assertTrue(ok_in)
        self.assertFalse(ok_out)

        sched_cfg = {
            "dailyPostingLimit": 2,
            "maxPostsPerCycle": 2,
            "postIntervalHours": 6,
            "postingWindows": windows,
        }
        mark_posted("901", "fitness", "https://instagram.com/reel/901/", db_path=self.db_path)
        mark_posted("902", "fitness", "https://instagram.com/reel/902/", db_path=self.db_path)
        self.assertEqual(get_posted_count_today_ist(db_path=self.db_path), 2)

        allowed, slots, reason = check_schedule_and_limits(
            sched_cfg, now_ist=inside_time, force_manual=True, db_path=self.db_path
        )
        self.assertFalse(allowed)
        self.assertEqual(slots, 0)
        self.assertIn("Daily publishing limit reached", reason)

    def test_retry_safety_and_ambiguous_upload_guard(self):
        client = DummyInstaClient(upload_pk="777888")
        video_file = os.path.join(self.temp_dir.name, "clip.mp4")
        with open(video_file, "wb") as f:
            f.write(b"fake_mp4_bytes")

        # 1. Dry-run mode with explicit authorization validates without invoking clip_upload
        ok_dry, status_dry, _ = safe_publish_reel(
            client=client,
            reel_pk="501",
            video_path=video_file,
            caption="Test caption",
            hashtag="fitness",
            original_url="https://www.instagram.com/reel/501/",
            idempotency_token="idem_501",
            dry_run=True,
            auto_publish_enabled=False,
            reuse_authorized=True,
            db_path=self.db_path,
        )
        self.assertTrue(ok_dry)
        self.assertEqual(status_dry, "DRY_RUN_VALIDATED")
        self.assertEqual(client.upload_calls, 0)
        self.assertFalse(already_posted("501", db_path=self.db_path))

        # 2. Ambiguous in-flight state blocks blind retry
        mark_upload_in_flight(
            "502", "fitness", "https://www.instagram.com/reel/502/", "idem_502", db_path=self.db_path
        )
        self.assertEqual(get_upload_state("502", db_path=self.db_path), "IN_FLIGHT_AMBIGUOUS")

        ok_amb, status_amb, err_amb = safe_publish_reel(
            client=client,
            reel_pk="502",
            video_path=video_file,
            caption="Test caption",
            hashtag="fitness",
            original_url="https://www.instagram.com/reel/502/",
            idempotency_token="idem_502_r1",
            dry_run=False,
            auto_publish_enabled=True,
            reuse_authorized=True,
            db_path=self.db_path,
        )
        self.assertFalse(ok_amb)
        self.assertEqual(status_amb, "UPLOAD_FAILED")
        self.assertIn("IN_FLIGHT_AMBIGUOUS", err_amb)
        self.assertEqual(client.upload_calls, 0)

        # 3. Unauthorized reuse blocks download in BOTH live and dry-run modes
        dl_path, dl_used, dl_err = download_authorized_candidate(
            client=client,
            media=DummyMedia("503", "C503"),
            url="https://www.instagram.com/reel/C503/",
            reuse_authorized=False,
            dry_run=False,
        )
        self.assertIsNone(dl_path)
        self.assertIn("blocked", dl_used)
        self.assertIsNotNone(dl_err)

    def test_fail_closed_authorization_blocks_missing_malformed_and_popular_unapproved_content(self):
        # Missing reuseAuthorized defaults to False (fail-closed)
        m_viral = DummyMedia(
            "801",
            "V801",
            view_count=2_500_000,
            like_count=310_000,
            comment_count=12_000,
            username="famous_unapproved_creator",
        )
        # Missing reuseAuthorized in target_config
        ok_missing, reason_missing = evaluate_candidate_reuse_authorization(
            media=m_viral,
            reel_pk="801",
            creator_handle="@famous_unapproved_creator",
            target_config={"tag": "calisthenics"},
            discovery_config={},
        )
        self.assertFalse(ok_missing)
        self.assertIn("fail-closed", reason_missing)

        # Malformed non-boolean values ("true", 1, "yes", "unknown") must be rejected
        for bad_val in ("true", "TRUE", "yes", 1, "unknown", {}, ["true"]):
            ok_bad, _ = evaluate_candidate_reuse_authorization(
                media=m_viral,
                reel_pk="801",
                creator_handle="@famous_unapproved_creator",
                target_config={"tag": "calisthenics", "reuseAuthorized": bad_val},
                discovery_config={},
            )
            self.assertFalse(ok_bad, f"Expected fail-closed False for reuseAuthorized={bad_val!r}")

            # Downloader must also reject non-boolean values in both dry-run and live mode
            _, dl_used_dry, dl_err_dry = download_authorized_candidate(
                client=DummyInstaClient(),
                media=m_viral,
                url="https://www.instagram.com/reel/V801/",
                reuse_authorized=bad_val,
                dry_run=True,
            )
            self.assertIn("blocked", dl_used_dry)
            self.assertIsNotNone(dl_err_dry)

            # Uploader must also reject non-boolean values in both dry-run and live mode
            pub_ok, pub_status, _ = safe_publish_reel(
                client=DummyInstaClient(),
                reel_pk="801",
                video_path=None,
                caption="Test",
                hashtag="calisthenics",
                original_url="https://www.instagram.com/reel/V801/",
                idempotency_token="idem_801",
                dry_run=True,
                auto_publish_enabled=False,
                reuse_authorized=bad_val,
                db_path=self.db_path,
            )
            self.assertFalse(pub_ok)
            self.assertEqual(pub_status, "UNAUTHORIZED_BLOCKED")

        # Discovery cycle with missing reuseAuthorized must NOT select or validate any candidates
        client = DummyInstaClient(top_medias=[m_viral])
        res_unauth = execute_bounded_cycle(
            ig_client=client,
            config_bundle={
                "runState": "DRY_RUN",
                "hashtags": [
                    {
                        "tag": "calisthenics",
                        "category": "Strength",
                        "isEnabled": True,
                        "candidateLimitPerRun": 10,
                        "minViewsThreshold": 25000,
                        "priorityWeight": 1.0,
                        # reuseAuthorized intentionally omitted
                    }
                ],
            },
            force_dry_run=True,
            force_manual=True,
            db_path=self.db_path,
        )
        self.assertEqual(res_unauth["videosProcessed"], 0)
        self.assertEqual(res_unauth["qualifyingPoolCount"], 0)
        act = get_activity_by_pk("801", db_path=self.db_path)
        self.assertIsNotNone(act)
        self.assertEqual(act["outcomeStatus"], "UNAUTHORIZED_BLOCKED")
        self.assertFalse(act["reuseAuthorized"])

        # Candidate from allowlisted creator or owned account is authorized
        m_owned = DummyMedia("802", "V802", view_count=90000, like_count=6000, username="my_studio_account")
        ok_owned, reason_owned = evaluate_candidate_reuse_authorization(
            media=m_owned,
            reel_pk="802",
            creator_handle="@my_studio_account",
            target_config={"tag": "calisthenics", "reuseAuthorized": False},
            discovery_config={"ownedAccounts": ["@my_studio_account"]},
        )
        self.assertTrue(ok_owned)
        self.assertIn("original owned account", reason_owned)

    def test_targeted_retry_never_falls_back_to_discovery_and_validates_target_state(self):
        # Seed client with unrelated trending reels that would be picked if discovery ran
        unrelated_media = DummyMedia("9999", "UNRELATED", view_count=500000, like_count=40000)
        client = DummyInstaClient(top_medias=[unrelated_media], recent_medias=[unrelated_media])

        # Seed persisted activity records in posted.db
        record_activity(
            {
                "reelPk": "701",
                "shortCode": "FAIL701",
                "reelUrl": "https://www.instagram.com/reel/FAIL701/",
                "hashtag": "calisthenics",
                "category": "Strength",
                "creatorHandle": "@approved_athlete",
                "viewCount": 95000,
                "likeCount": 6400,
                "commentCount": 190,
                "durationSeconds": 18,
                "qualityScore": 91.0,
                "selectionReason": "Authorized pool candidate",
                "downloaderUsed": "yt-dlp failed",
                "outcomeStatus": "DOWNLOAD_FAILED",
                "failureReason": "Temporary CDN timeout",
                "captionPreview": "Gravity glitch #calisthenics",
                "retryCount": 0,
                "idempotencyToken": "idem_701",
                "reuseAuthorized": True,
            },
            db_path=self.db_path,
        )
        record_activity(
            {
                "reelPk": "702",
                "shortCode": "PUB702",
                "reelUrl": "https://www.instagram.com/reel/PUB702/",
                "hashtag": "calisthenics",
                "category": "Strength",
                "creatorHandle": "@approved_athlete",
                "viewCount": 105000,
                "likeCount": 7400,
                "commentCount": 210,
                "durationSeconds": 20,
                "qualityScore": 93.0,
                "selectionReason": "Authorized pool candidate",
                "downloaderUsed": "yt-dlp",
                "outcomeStatus": "PUBLISHED",
                "failureReason": None,
                "captionPreview": "Posted #calisthenics",
                "retryCount": 0,
                "idempotencyToken": "idem_702",
                "reuseAuthorized": True,
            },
            db_path=self.db_path,
        )
        mark_posted("702", "calisthenics", "https://www.instagram.com/reel/PUB702/", "idem_702", db_path=self.db_path)

        record_activity(
            {
                "reelPk": "703",
                "shortCode": "UNAUTH703",
                "reelUrl": "https://www.instagram.com/reel/UNAUTH703/",
                "hashtag": "calisthenics",
                "category": "Strength",
                "creatorHandle": "@unapproved_user",
                "viewCount": 88000,
                "likeCount": 5100,
                "commentCount": 120,
                "durationSeconds": 16,
                "qualityScore": 86.0,
                "selectionReason": "Unapproved",
                "downloaderUsed": "yt-dlp failed",
                "outcomeStatus": "DOWNLOAD_FAILED",
                "failureReason": "Failed",
                "captionPreview": "",
                "retryCount": 0,
                "idempotencyToken": "idem_703",
                "reuseAuthorized": False,
            },
            db_path=self.db_path,
        )

        record_activity(
            {
                "reelPk": "704",
                "shortCode": "AMB704",
                "reelUrl": "https://www.instagram.com/reel/AMB704/",
                "hashtag": "calisthenics",
                "category": "Strength",
                "creatorHandle": "@approved_athlete",
                "viewCount": 92000,
                "likeCount": 6000,
                "commentCount": 150,
                "durationSeconds": 19,
                "qualityScore": 89.5,
                "selectionReason": "Authorized",
                "downloaderUsed": "yt-dlp",
                "outcomeStatus": "UPLOAD_FAILED",
                "failureReason": "clip_upload timeout (IN_FLIGHT_AMBIGUOUS)",
                "captionPreview": "Caption",
                "retryCount": 0,
                "idempotencyToken": "idem_704",
                "reuseAuthorized": True,
            },
            db_path=self.db_path,
        )
        mark_upload_in_flight("704", "calisthenics", "https://www.instagram.com/reel/AMB704/", "idem_704", db_path=self.db_path)

        bundle = {
            "runState": "DRY_RUN",
            "discoveryConfig": {
                "authorizedCreators": ["approved_athlete"],
            },
            "hashtags": [
                {
                    "tag": "calisthenics",
                    "category": "Strength",
                    "isEnabled": True,
                    "reuseAuthorized": True,
                }
            ],
        }

        # 1. Retry unknown PK -> RETRY_REJECTED, zero scrape calls
        res_unknown = execute_bounded_cycle(
            ig_client=client,
            config_bundle=bundle,
            force_dry_run=True,
            retry_pk="non_existent_pk_404",
            idempotency_token="idem_404_r1",
            db_path=self.db_path,
        )
        self.assertEqual(res_unknown["status"], "RETRY_REJECTED")
        self.assertEqual(client.top_scrape_calls, 0)
        self.assertEqual(client.recent_scrape_calls, 0)
        self.assertIsNone(get_activity_by_pk("9999", db_path=self.db_path))

        # 2. SAFE_RETRY command with empty retry_pk -> RETRY_REJECTED, never falls back to discovery
        res_empty = execute_bounded_cycle(
            ig_client=client,
            config_bundle=bundle,
            force_dry_run=True,
            command="SAFE_RETRY",
            retry_pk="",
            db_path=self.db_path,
        )
        self.assertEqual(res_empty["status"], "RETRY_REJECTED")
        self.assertEqual(client.top_scrape_calls, 0)

        # 3. Retry already-published PK 702 -> RETRY_REJECTED
        res_pub = execute_bounded_cycle(
            ig_client=client,
            config_bundle=bundle,
            force_dry_run=True,
            retry_pk="702",
            idempotency_token="idem_702_r1",
            db_path=self.db_path,
        )
        self.assertEqual(res_pub["status"], "RETRY_REJECTED")
        self.assertEqual(res_pub["duplicateSkips"], 1)

        # 4. Retry unauthorized PK 703 -> RETRY_REJECTED
        res_unauth = execute_bounded_cycle(
            ig_client=client,
            config_bundle=bundle,
            force_dry_run=True,
            retry_pk="703",
            idempotency_token="idem_703_r1",
            db_path=self.db_path,
        )
        self.assertEqual(res_unauth["status"], "RETRY_REJECTED")
        self.assertEqual(get_activity_by_pk("703", db_path=self.db_path)["outcomeStatus"], "UNAUTHORIZED_BLOCKED")

        # 5. Retry IN_FLIGHT_AMBIGUOUS PK 704 -> RETRY_UNRESOLVED_AMBIGUOUS
        res_amb = execute_bounded_cycle(
            ig_client=client,
            config_bundle=bundle,
            force_dry_run=True,
            retry_pk="704",
            idempotency_token="idem_704_r1",
            db_path=self.db_path,
        )
        self.assertEqual(res_amb["status"], "RETRY_UNRESOLVED_AMBIGUOUS")
        self.assertEqual(client.upload_calls, 0)

        # 6. Retry valid retryable & authorized PK 701 -> RETRY_SUCCEEDED (processes ONLY 701)
        res_ok = execute_bounded_cycle(
            ig_client=client,
            config_bundle=bundle,
            force_dry_run=True,
            retry_pk="701",
            idempotency_token="idem_701_r1",
            db_path=self.db_path,
        )
        self.assertEqual(res_ok["status"], "RETRY_SUCCEEDED")
        self.assertEqual(res_ok["targetReelPk"], "701")
        self.assertEqual(res_ok["videosProcessed"], 1)
        self.assertEqual(len(res_ok["activityRecords"]), 1)
        self.assertEqual(res_ok["activityRecords"][0]["reelPk"], "701")
        self.assertEqual(res_ok["activityRecords"][0]["outcomeStatus"], "DRY_RUN_VALIDATED")
        self.assertEqual(client.top_scrape_calls, 0)
        self.assertEqual(client.recent_scrape_calls, 0)

        # 7. Idempotent repeat call with the same completed token returns RETRY_SUCCEEDED noop
        res_idem = execute_bounded_cycle(
            ig_client=client,
            config_bundle=bundle,
            force_dry_run=True,
            retry_pk="701",
            idempotency_token="idem_701_r1",
            db_path=self.db_path,
        )
        self.assertEqual(res_idem["status"], "RETRY_SUCCEEDED")
        self.assertEqual(res_idem["videosProcessed"], 0)

        # 8. Calling retry with a new token on the now-DRY_RUN_VALIDATED PK 701 is rejected
        res_dup_retry = execute_bounded_cycle(
            ig_client=client,
            config_bundle=bundle,
            force_dry_run=True,
            retry_pk="701",
            idempotency_token="idem_701_r2",
            db_path=self.db_path,
        )
        self.assertEqual(res_dup_retry["status"], "RETRY_REJECTED")

    def test_bounded_cycle_produces_structured_json_results(self):
        m1 = DummyMedia("601", "R601", view_count=110000, like_count=7500, username="calisthenics_pro")
        m2 = DummyMedia("602", "R602", view_count=95000, like_count=6200, username="street_athlete")
        client = DummyInstaClient(top_medias=[m1], recent_medias=[m2])

        result = execute_bounded_cycle(
            ig_client=client,
            config_bundle={
                "runState": "DRY_RUN",
                "discoveryConfig": {
                    "authorizedCreators": ["calisthenics_pro", "street_athlete"],
                },
                "hashtags": [
                    {
                        "tag": "calisthenics",
                        "category": "Fitness & Strength",
                        "isEnabled": True,
                        "candidateLimitPerRun": 30,
                        "minViewsThreshold": 25000,
                        "priorityWeight": 1.0,
                        "reuseAuthorized": True,
                    }
                ],
                "scheduleConfig": {
                    "dailyPostingLimit": 6,
                    "maxPostsPerCycle": 2,
                    "postIntervalHours": 6,
                    "dryRunMode": True,
                    "autoPublishEnabled": False,
                    "postingWindows": [],
                },
            },
            force_dry_run=True,
            force_manual=True,
            db_path=self.db_path,
        )
        self.assertEqual(result["status"], "COMPLETED")
        self.assertTrue(result["dryRun"])
        self.assertEqual(result["videosProcessed"], 2)
        self.assertEqual(result["successfulPosts"], 0)
        self.assertEqual(len(get_recent_activity(db_path=self.db_path)), 2)

    def test_broad_hashtag_grant_blocked_unless_explicit_bulk_policy_enabled(self):
        m_third_party = DummyMedia(
            "850",
            "TP850",
            view_count=140000,
            like_count=9200,
            comment_count=260,
            username="unlisted_third_party_creator",
        )

        # 1. Accidental blanket authorization blocked:
        # reuseAuthorized=True on hashtag target with empty allowlists and default
        # allowAllHashtagCandidatesWhenTargetAuthorized=False MUST NOT authorize third-party candidates.
        ok_blanket, reason_blanket = evaluate_candidate_reuse_authorization(
            media=m_third_party,
            reel_pk="850",
            creator_handle="@unlisted_third_party_creator",
            target_config={"tag": "calisthenics", "reuseAuthorized": True},
            discovery_config={
                "authorizedCreators": [],
                "ownedAccounts": [],
                "authorizedCandidatePks": [],
            },
        )
        self.assertFalse(ok_blanket)
        self.assertIn("fail-closed", reason_blanket)

        # 2. Malformed allowAllHashtagCandidatesWhenTargetAuthorized must fail-closed
        for bad_bulk in ("true", 1, "yes", ["true"], {}):
            ok_bad_bulk, _ = evaluate_candidate_reuse_authorization(
                media=m_third_party,
                reel_pk="850",
                creator_handle="@unlisted_third_party_creator",
                target_config={
                    "tag": "calisthenics",
                    "reuseAuthorized": True,
                    "allowAllHashtagCandidatesWhenTargetAuthorized": bad_bulk,
                },
                discovery_config={},
            )
            self.assertFalse(ok_bad_bulk)

        # 3. Explicitly enabled allowAllHashtagCandidatesWhenTargetAuthorized=True authorizes hashtag candidates
        ok_explicit_bulk, reason_explicit_bulk = evaluate_candidate_reuse_authorization(
            media=m_third_party,
            reel_pk="850",
            creator_handle="@unlisted_third_party_creator",
            target_config={
                "tag": "calisthenics",
                "reuseAuthorized": True,
                "allowAllHashtagCandidatesWhenTargetAuthorized": True,
            },
            discovery_config={
                "allowAllHashtagCandidatesWhenTargetAuthorized": True,
            },
        )
        self.assertTrue(ok_explicit_bulk)
        self.assertIn("TARGET_POLICY_GRANT", reason_explicit_bulk)

        # 4. Legitimate explicitly licensed candidate (authorizedCandidatePks) is authorized even without bulk policy
        ok_licensed, reason_licensed = evaluate_candidate_reuse_authorization(
            media=m_third_party,
            reel_pk="850",
            creator_handle="@unlisted_third_party_creator",
            target_config={"tag": "calisthenics", "reuseAuthorized": False},
            discovery_config={"authorizedCandidatePks": ["850"]},
        )
        self.assertTrue(ok_licensed)
        self.assertIn("EXPLICIT_LICENSE", reason_licensed)

    def test_revoked_authorization_blocks_retry_unless_separate_valid_license_applies_and_propagates_lock_owner(self):
        client = DummyInstaClient()

        # Seed two failed candidates that were previously persisted with reuseAuthorized=True
        for pk, short_code, creator in (
            ("861", "REV861", "@formerly_approved_creator"),
            ("862", "LIC862", "@formerly_approved_creator"),
        ):
            record_activity(
                {
                    "reelPk": pk,
                    "shortCode": short_code,
                    "reelUrl": f"https://www.instagram.com/reel/{short_code}/",
                    "hashtag": "calisthenics",
                    "category": "Strength",
                    "creatorHandle": creator,
                    "viewCount": 95000,
                    "likeCount": 6200,
                    "commentCount": 140,
                    "durationSeconds": 18,
                    "qualityScore": 90.0,
                    "selectionReason": "Previously authorized",
                    "downloaderUsed": "yt-dlp failed",
                    "outcomeStatus": "DOWNLOAD_FAILED",
                    "failureReason": "Transient network error",
                    "captionPreview": "Retry caption #calisthenics",
                    "retryCount": 0,
                    "idempotencyToken": f"idem_{pk}",
                    "reuseAuthorized": True,
                    "authorizationSource": "CREATOR_ALLOWLIST",
                },
                db_path=self.db_path,
            )

        # Case A: Creator was removed from authorizedCreators and bulk policy is False.
        # Even though persisted reuseAuthorized is True and hashtag reuseAuthorized is True,
        # retry MUST NOT fabricate TARGET_POLICY_GRANT and MUST block the retry!
        revoked_creator_bundle = {
            "runState": "DRY_RUN",
            "discoveryConfig": {
                "allowAllHashtagCandidatesWhenTargetAuthorized": False,
                "authorizedCreators": [],
                "ownedAccounts": [],
                "authorizedCandidatePks": [],
            },
            "hashtags": [
                {
                    "tag": "calisthenics",
                    "category": "Strength",
                    "isEnabled": True,
                    "reuseAuthorized": True,
                    "allowAllHashtagCandidatesWhenTargetAuthorized": False,
                }
            ],
        }
        res_revoked = execute_bounded_cycle(
            ig_client=client,
            config_bundle=revoked_creator_bundle,
            force_dry_run=True,
            retry_pk="861",
            idempotency_token="idem_861_r1",
            lock_owner_id="lock_owner_revoked_861",
            db_path=self.db_path,
        )
        self.assertEqual(res_revoked["status"], "RETRY_REJECTED")
        self.assertEqual(res_revoked["lockOwnerId"], "lock_owner_revoked_861")
        persisted_861 = get_activity_by_pk("861", db_path=self.db_path)
        self.assertEqual(persisted_861["outcomeStatus"], "UNAUTHORIZED_BLOCKED")
        self.assertFalse(persisted_861["reuseAuthorized"])
        self.assertEqual(persisted_861["authorizationSource"], "NONE")

        # Case B: Hashtag authorization is revoked (reuseAuthorized=False),
        # but candidate 862 has a separate, still-valid explicit license in authorizedCandidatePks.
        explicit_license_bundle = {
            "runState": "DRY_RUN",
            "discoveryConfig": {
                "allowAllHashtagCandidatesWhenTargetAuthorized": False,
                "authorizedCreators": [],
                "ownedAccounts": [],
                "authorizedCandidatePks": ["862"],
            },
            "hashtags": [
                {
                    "tag": "calisthenics",
                    "category": "Strength",
                    "isEnabled": True,
                    "reuseAuthorized": False,
                    "allowAllHashtagCandidatesWhenTargetAuthorized": False,
                }
            ],
        }
        res_licensed_retry = execute_bounded_cycle(
            ig_client=client,
            config_bundle=explicit_license_bundle,
            force_dry_run=True,
            retry_pk="862",
            idempotency_token="idem_862_r1",
            lock_owner_id="lock_owner_licensed_862",
            db_path=self.db_path,
        )
        self.assertEqual(res_licensed_retry["status"], "RETRY_SUCCEEDED")
        self.assertEqual(res_licensed_retry["lockOwnerId"], "lock_owner_licensed_862")
        persisted_862 = get_activity_by_pk("862", db_path=self.db_path)
        self.assertEqual(persisted_862["outcomeStatus"], "DRY_RUN_VALIDATED")
        self.assertTrue(persisted_862["reuseAuthorized"])
        self.assertEqual(persisted_862["authorizationSource"], "EXPLICIT_LICENSE")

    def test_apps_script_rpc_and_fail_closed_remote_config(self):
        import io
        import json
        from unittest.mock import patch

        client = DummyInstaClient()

        # 1. Fail-closed when require_remote_config=True and config_bundle is None or has remoteConfigError
        res_missing_cfg = execute_bounded_cycle(
            ig_client=client,
            config_bundle=None,
            require_remote_config=True,
            lock_owner_id="lock_owner_cfg_fail_01",
            db_path=self.db_path,
        )
        self.assertEqual(res_missing_cfg["status"], "REMOTE_CONFIG_FAILED")
        self.assertEqual(res_missing_cfg["lockOwnerId"], "lock_owner_cfg_fail_01")
        self.assertEqual(res_missing_cfg["videosProcessed"], 0)
        self.assertEqual(res_missing_cfg["successfulPosts"], 0)
        self.assertEqual(client.top_scrape_calls, 0)

        res_err_cfg = execute_bounded_cycle(
            ig_client=client,
            config_bundle={"remoteConfigError": "Invalid RILOT_ENGINE_API_KEY"},
            require_remote_config=False,
            lock_owner_id="lock_owner_cfg_fail_02",
            db_path=self.db_path,
        )
        self.assertEqual(res_err_cfg["status"], "REMOTE_CONFIG_FAILED")
        self.assertEqual(res_err_cfg["videosProcessed"], 0)

        # 2. Verify ambiguousPks from Google Apps Script config bundle are synced as IN_FLIGHT_AMBIGUOUS
        res_amb_sync = execute_bounded_cycle(
            ig_client=client,
            config_bundle={
                "runState": "PAUSED",
                "ambiguousPks": ["9901122"],
                "postedPks": ["9901123"],
            },
            db_path=self.db_path,
        )
        self.assertEqual(res_amb_sync["status"], "PAUSED_SKIP")
        self.assertEqual(get_upload_state("9901122", db_path=self.db_path), "IN_FLIGHT_AMBIGUOUS")
        self.assertEqual(get_upload_state("9901123", db_path=self.db_path), "PUBLISHED")

        # 3. Verify call_apps_script_rpc, fetch_apps_script_config, and report_cycle_to_apps_script envelope
        captured_requests = []

        class DummyHttpResponse:
            def __init__(self, body_dict: dict):
                self._bytes = json.dumps(body_dict).encode("utf-8")

            def read(self):
                return self._bytes

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc_val, exc_tb):
                return False

        def fake_urlopen(req, timeout=20):
            body = json.loads(req.data.decode("utf-8"))
            captured_requests.append(body)
            if body["action"] == "engine.getConfig":
                return DummyHttpResponse(
                    {
                        "ok": True,
                        "code": "OK",
                        "message": "Config bundle ready.",
                        "data": {"runState": "DRY_RUN", "postedPks": ["111"], "ambiguousPks": ["222"]},
                    }
                )
            if body["action"] == "engine.report":
                return DummyHttpResponse(
                    {
                        "ok": True,
                        "code": "OK",
                        "message": "Reported.",
                        "data": {"accepted": True},
                    }
                )
            return DummyHttpResponse({"ok": False, "code": "UNAUTHORIZED", "message": "Invalid key secret_eng_key"})

        with patch("urllib.request.urlopen", side_effect=fake_urlopen):
            cfg = fetch_apps_script_config("https://script.google.com/macros/s/test/exec", "secret_eng_key")
            self.assertIsNotNone(cfg)
            self.assertEqual(cfg["runState"], "DRY_RUN")
            self.assertEqual(captured_requests[0]["action"], "engine.getConfig")
            self.assertEqual(captured_requests[0]["authToken"], "secret_eng_key")

            rep_ok = report_cycle_to_apps_script(
                "https://script.google.com/macros/s/test/exec",
                "secret_eng_key",
                {"status": "COMPLETED", "lockOwnerId": "lock_owner_test_99"},
            )
            self.assertTrue(rep_ok)
            self.assertEqual(captured_requests[1]["action"], "engine.report")
            self.assertEqual(captured_requests[1]["payload"]["lockOwnerId"], "lock_owner_test_99")

            # Verify secret redaction when an RPC call fails
            with self.assertRaises(RuntimeError) as ctx:
                call_apps_script_rpc(
                    "https://script.google.com/macros/s/test/exec",
                    "secret_eng_key",
                    "engine.unknown",
                    {},
                )
            self.assertNotIn("secret_eng_key", str(ctx.exception))
            self.assertIn("[REDACTED]", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
