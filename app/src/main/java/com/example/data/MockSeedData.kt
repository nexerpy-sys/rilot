package com.example.data

import com.example.model.CaptionCategory
import com.example.model.CandidateOutcomeStatus

object MockSeedData {

    fun initialHashtags(): List<HashtagEntity> = listOf(
        HashtagEntity(
            tag = "fitness",
            category = "Strength & Conditioning",
            isEnabled = true,
            candidateLimitPerRun = 30,
            minViewsThreshold = 35_000L,
            priorityWeight = 1.2f
        ),
        HashtagEntity(
            tag = "calisthenics",
            category = "Bodyweight & Skills",
            isEnabled = true,
            candidateLimitPerRun = 25,
            minViewsThreshold = 20_000L,
            priorityWeight = 1.4f
        ),
        HashtagEntity(
            tag = "gymhumor",
            category = "Viral Relatable",
            isEnabled = true,
            candidateLimitPerRun = 30,
            minViewsThreshold = 45_000L,
            priorityWeight = 1.1f
        ),
        HashtagEntity(
            tag = "mobilitytraining",
            category = "Longevity & Recovery",
            isEnabled = true,
            candidateLimitPerRun = 20,
            minViewsThreshold = 15_000L,
            priorityWeight = 1.3f
        ),
        HashtagEntity(
            tag = "highproteinmeals",
            category = "Nutrition & Prep",
            isEnabled = false,
            candidateLimitPerRun = 20,
            minViewsThreshold = 25_000L,
            priorityWeight = 0.9f
        )
    )

    fun initialCaptions(): List<CaptionEntity> = listOf(
        CaptionEntity(
            title = "2006 Honda Civic Pre-Workout Lore (Copypasta)",
            body = "The 2006 Honda Civic 1.8L i-VTEC engine doesn't ask if it's motivated before hitting 6,800 RPM on a Tuesday morning. Meanwhile bro took 2 scoops of sour gummy worm pre-workout just to stare at the pec deck for 19 minutes. Lock in immediately. {hashtag}",
            categoryName = CaptionCategory.VIRAL_COPYPASTA.name,
            isAbsurdCopypasta = true,
            hashtagTemplate = "#gymtok #shitpost #fitnessreels #preworkout #explorepage",
            usageCount = 4,
            lastUsedEpochMs = 1761616800000L,
            isFavorite = true
        ),
        CaptionEntity(
            title = "Microwave 3:00 AM Thermodynamics (Copypasta)",
            body = "Scientists still cannot explain why a 45-second rest between sets feels like 6 seconds, yet a 45-second plank alters the gravitational spacetime continuum of the entire planet. Read that again before skipping mobility. {hashtag}",
            categoryName = CaptionCategory.VIRAL_COPYPASTA.name,
            isAbsurdCopypasta = true,
            hashtagTemplate = "#reelsindia #calisthenics #gymhumor #viralreels #trending",
            usageCount = 2,
            lastUsedEpochMs = 1761588000000L,
            isFavorite = true
        ),
        CaptionEntity(
            title = "Costco Rotisserie Chicken Protocol (Copypasta)",
            body = "My nutritionist asked me for my macro split so I handed her a crumpled receipt for 4 Costco rotisserie chickens and a monster white ultra. 160g protein secured before noon. Zero excuses. {hashtag}",
            categoryName = CaptionCategory.VIRAL_COPYPASTA.name,
            isAbsurdCopypasta = true,
            hashtagTemplate = "#bulking #protein #gymmemes #fitnessmotivation #reels",
            usageCount = 1,
            lastUsedEpochMs = 1761534000000L,
            isFavorite = false
        ),
        CaptionEntity(
            title = "Discipline Over Mood",
            body = "Nobody cares how tired you were when the results show up. Show up for the version of yourself you promised six months ago. {hashtag} credit: {creator}",
            categoryName = CaptionCategory.MOTIVATIONAL.name,
            isAbsurdCopypasta = false,
            hashtagTemplate = "#discipline #consistency #mindset #fitnessjourney #reels",
            usageCount = 6,
            lastUsedEpochMs = 1761631200000L,
            isFavorite = true
        ),
        CaptionEntity(
            title = "Form Check Debate Bait",
            body = "Rate this execution from 1–10. Would you count the last rep or call it a no-rep in competition? Drop your verdict below. {hashtag}",
            categoryName = CaptionCategory.QUESTION_ENGAGEMENT.name,
            isAbsurdCopypasta = false,
            hashtagTemplate = "#formcheck #strength #powerlifting #calisthenics #reelsviral",
            usageCount = 3,
            lastUsedEpochMs = 1761501600000L,
            isFavorite = false
        ),
        CaptionEntity(
            title = "Clean Minimal Aesthetic",
            body = "Quiet work. Loud results. Day by day. {hashtag}",
            categoryName = CaptionCategory.AESTHETIC_MINIMAL.name,
            isAbsurdCopypasta = false,
            hashtagTemplate = "#aesthetic #training #dailygrind #reels",
            usageCount = 0,
            lastUsedEpochMs = null,
            isFavorite = false
        )
    )

    fun initialActivityRecords(): List<ActivityRecordEntity> = listOf(
        ActivityRecordEntity(
            reelPk = "3421984710293847102",
            shortCode = "C8xYz91LmPq",
            reelUrl = "https://www.instagram.com/reel/C8xYz91LmPq/",
            hashtag = "calisthenics",
            category = "Bodyweight & Skills",
            creatorHandle = "@bar_kinetics",
            viewCount = 418_500L,
            likeCount = 29_400L,
            commentCount = 640L,
            durationSeconds = 19,
            qualityScore = 91.4,
            selectionReason = "Selected from 10-reel qualifying pool (Rank #4 by raw views, #1 by composite score: 7.0% like-to-view ratio + 4h recency + category diversity boost).",
            downloaderUsed = "yt-dlp (primary mp4 1080p)",
            outcomeStatusName = CandidateOutcomeStatus.DRY_RUN_VALIDATED.name,
            failureReason = null,
            captionPreview = "The 2006 Honda Civic 1.8L i-VTEC engine doesn't ask if it's motivated...\n.\n.\n#calisthenics #gymtok #shitpost #fitnessreels",
            timestampIst = "2026-10-28 06:00:19 IST",
            timestampEpochMs = 1761611419000L,
            retryCount = 0,
            idempotencyToken = "idem_3421984710293847102"
        ),
        ActivityRecordEntity(
            reelPk = "3421940019283746501",
            shortCode = "C8wQv77KpRn",
            reelUrl = "https://www.instagram.com/reel/C8wQv77KpRn/",
            hashtag = "fitness",
            category = "Strength & Conditioning",
            creatorHandle = "@iron_velocity",
            viewCount = 1_240_000L,
            likeCount = 84_200L,
            commentCount = 1_410L,
            durationSeconds = 24,
            qualityScore = 88.2,
            selectionReason = "Skipped by SQLite posted.db guard: already_posted(pk='3421940019283746501') returned True (originally posted 2026-10-26).",
            downloaderUsed = "none (skipped pre-download)",
            outcomeStatusName = CandidateOutcomeStatus.SKIPPED_DUPLICATE_PK.name,
            failureReason = "Duplicate PK in posted.db — prevented accidental repost.",
            captionPreview = "N/A — Duplicate skipped before rewriter",
            timestampIst = "2026-10-28 06:00:05 IST",
            timestampEpochMs = 1761611405000L,
            retryCount = 0,
            idempotencyToken = "idem_3421940019283746501"
        ),
        ActivityRecordEntity(
            reelPk = "3421912398471029384",
            shortCode = "C8vMn42TrXz",
            reelUrl = "https://www.instagram.com/reel/C8vMn42TrXz/",
            hashtag = "mobilitytraining",
            category = "Longevity & Recovery",
            creatorHandle = "@spine_architect",
            viewCount = 96_300L,
            likeCount = 7_820L,
            commentCount = 312L,
            durationSeconds = 28,
            qualityScore = 89.7,
            selectionReason = "Selected via Qualifying Pool diversity rule: 8.1% engagement velocity in underrepresented 'Longevity & Recovery' category (bypassed 2 higher-view #fitness clips to preserve feed variety).",
            downloaderUsed = "instagrapi clip_download (fallback)",
            outcomeStatusName = CandidateOutcomeStatus.PUBLISHED.name,
            failureReason = null,
            captionPreview = "Scientists still cannot explain why a 45-second rest between sets feels like 6 seconds...\n.\n.\n#mobilitytraining #reelsindia #calisthenics #gymhumor",
            timestampIst = "2026-10-28 00:00:41 IST",
            timestampEpochMs = 1761589841000L,
            retryCount = 0,
            idempotencyToken = "idem_3421912398471029384"
        ),
        ActivityRecordEntity(
            reelPk = "3421889102938471109",
            shortCode = "C8uJk19BnWq",
            reelUrl = "https://www.instagram.com/reel/C8uJk19BnWq/",
            hashtag = "gymhumor",
            category = "Viral Relatable",
            creatorHandle = "@benchpress_banter",
            viewCount = 312_000L,
            likeCount = 19_500L,
            commentCount = 490L,
            durationSeconds = 15,
            qualityScore = 84.5,
            selectionReason = "Qualified in pool (Score 84.5, 6.25% like ratio). Queued for download.",
            downloaderUsed = "yt-dlp + instagrapi fallback",
            outcomeStatusName = CandidateOutcomeStatus.DOWNLOAD_FAILED.name,
            failureReason = "yt-dlp exited with HTTP 429 Too Many Requests; instagrapi clip_download fallback timed out after 15s.",
            captionPreview = "My nutritionist asked me for my macro split so I handed her a crumpled receipt...",
            timestampIst = "2026-10-27 18:01:12 IST",
            timestampEpochMs = 1761568272000L,
            retryCount = 1,
            idempotencyToken = "idem_3421889102938471109"
        ),
        ActivityRecordEntity(
            reelPk = "3421850091827364550",
            shortCode = "C8tHg88LpVz",
            reelUrl = "https://www.instagram.com/reel/C8tHg88LpVz/",
            hashtag = "fitness",
            category = "Strength & Conditioning",
            creatorHandle = "@deadlift_diary",
            viewCount = 205_400L,
            likeCount = 11_900L,
            commentCount = 275L,
            durationSeconds = 22,
            qualityScore = 81.0,
            selectionReason = "Selected from qualifying pool (Score 81.0). Downloaded cleanly via yt-dlp.",
            downloaderUsed = "yt-dlp (primary mp4 1080p)",
            outcomeStatusName = CandidateOutcomeStatus.UPLOAD_FAILED.name,
            failureReason = "uploader.py post_reel failed: instagrapi clip_upload received HTTP 503 upstream transient error. Verified not present in posted.db.",
            captionPreview = "Nobody cares how tired you were when the results show up...\n.\n.\n#fitness #discipline #consistency",
            timestampIst = "2026-10-27 12:02:09 IST",
            timestampEpochMs = 1761546729000L,
            retryCount = 0,
            idempotencyToken = "idem_3421850091827364550"
        ),
        ActivityRecordEntity(
            reelPk = "3421810029384756123",
            shortCode = "C8sDf04QmNa",
            reelUrl = "https://www.instagram.com/reel/C8sDf04QmNa/",
            hashtag = "fitness",
            category = "Strength & Conditioning",
            creatorHandle = "@generic_reposter99",
            viewCount = 890_000L,
            likeCount = 14_200L,
            commentCount = 45L,
            durationSeconds = 48,
            qualityScore = 41.2,
            selectionReason = "Filtered out despite 890K views: Like-to-view ratio (1.59%) is below minimum quality threshold (3.20%). Prevents low-engagement view-botted reposts.",
            downloaderUsed = "none (filtered)",
            outcomeStatusName = CandidateOutcomeStatus.FILTERED_QUALITY.name,
            failureReason = "Engagement ratio 1.59% < required 3.20% threshold.",
            captionPreview = "N/A — Filtered out by quality gate",
            timestampIst = "2026-10-27 12:00:18 IST",
            timestampEpochMs = 1761546618000L,
            retryCount = 0,
            idempotencyToken = "idem_3421810029384756123"
        )
    )
}
