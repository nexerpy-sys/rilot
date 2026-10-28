package com.example.model

enum class DataSourceMode(val label: String, val badgeText: String) {
    MOCK_PREVIEW(
        label = "Mock Preview (Local Seed & Contract)",
        badgeText = "MOCK PREVIEW DATA"
    ),
    GOOGLE_APPS_SCRIPT(
        label = "Google Apps Script + Sheets (Live API)",
        badgeText = "LIVE APPS SCRIPT API"
    ),
    @Deprecated("Migrated to GOOGLE_APPS_SCRIPT", ReplaceWith("GOOGLE_APPS_SCRIPT"))
    CLOUDFLARE_WORKER(
        label = "Google Apps Script + Sheets (Live API)",
        badgeText = "LIVE APPS SCRIPT API"
    );

    val isLiveBackend: Boolean
        get() = this == GOOGLE_APPS_SCRIPT || this == CLOUDFLARE_WORKER

    companion object {
        fun fromString(value: String?): DataSourceMode {
            if (value.isNullOrBlank()) return MOCK_PREVIEW
            return when (value.trim().uppercase()) {
                "GOOGLE_APPS_SCRIPT" -> GOOGLE_APPS_SCRIPT
                "CLOUDFLARE_WORKER" -> GOOGLE_APPS_SCRIPT
                else -> runCatching { valueOf(value.trim()) }.getOrDefault(MOCK_PREVIEW)
            }
        }
    }
}

enum class BackendConnectionState(val title: String, val subtitle: String) {
    MOCK_SIMULATED(
        title = "Mock Preview Active",
        subtitle = "Using local realistic InstaAutomation dataset. No live Instagram requests."
    ),
    CONNECTED_LIVE(
        title = "Google Apps Script Connected",
        subtitle = "Authenticated with Apps Script Web App, Google Sheets & GitHub Actions state."
    ),
    DISCONNECTED_OFFLINE(
        title = "Backend Unreachable (Offline Fallback)",
        subtitle = "Displaying last known cached telemetry from local storage."
    ),
    AUTH_EXPIRED(
        title = "Authorization / Session Expired",
        subtitle = "instagrapi session.json or Apps Script API key expired (2FA / re-auth required on backend)."
    )
}

enum class AutomationRunState(val label: String) {
    ENABLED("Active & Scheduled"),
    PAUSED("Paused"),
    RUNNING_CYCLE("Running GitHub Action Cycle"),
    DRY_RUN("Dry-Run Only (No Upload)")
}

enum class ContentSourceType(val label: String, val description: String) {
    RECENT_AND_TOP(
        label = "Popular + Recent Feed",
        description = "Blends hashtag_medias_top with recent clips where accessible"
    ),
    TOP_TRENDING_ONLY(
        label = "Popular / Top Reels Only",
        description = "Uses Instagram trending selection (hashtag_medias_top)"
    ),
    RECENT_ONLY(
        label = "Recent Chronological",
        description = "Prioritizes fresh uploads with early velocity signals"
    )
}

enum class SelectionStrategy(val label: String, val description: String) {
    QUALIFYING_POOL_WEIGHTED(
        label = "Qualifying Pool (Weighted Score)",
        description = "Samples from all qualifying candidates weighted by engagement, recency & diversity instead of always picking top-2 views"
    ),
    DIVERSE_CATEGORY_ROTATION(
        label = "Category Diversity Round-Robin",
        description = "Ensures consecutive posts rotate across distinct content categories"
    ),
    ENGAGEMENT_VELOCITY(
        label = "Engagement-to-View Velocity",
        description = "Ranks qualifying pool by like/comment ratio and freshness"
    )
}

data class HashtagTarget(
    val id: Long = 0L,
    val tag: String,
    val category: String,
    val isEnabled: Boolean = true,
    val candidateLimitPerRun: Int = 25,
    val minViewsThreshold: Long = 20_000L,
    val priorityWeight: Float = 1.0f
)

data class DiscoveryConfig(
    val candidateCollectionLimitPerTag: Int = 30,
    val qualifyingPoolSize: Int = 10,
    val contentSource: ContentSourceType = ContentSourceType.RECENT_AND_TOP,
    val selectionStrategy: SelectionStrategy = SelectionStrategy.QUALIFYING_POOL_WEIGHTED,
    val minViewCountThreshold: Long = 25_000L,
    val minLikeToViewRatioPercent: Float = 3.2f,
    val minDurationSeconds: Int = 6,
    val maxDurationSeconds: Int = 60,
    val recencyWeightPercent: Int = 35,
    val engagementWeightPercent: Int = 40,
    val diversityWeightPercent: Int = 25,
    val excludePreviouslyProcessed: Boolean = true,
    val avoidSameCreatorInWindow: Boolean = true,
    val preferYtdlpWithInstagrapiFallback: Boolean = true,
    val defaultReuseAuthorized: Boolean = false,
    val allowAllHashtagCandidatesWhenTargetAuthorized: Boolean = false,
    val authorizedCreators: List<String> = emptyList(),
    val ownedAccounts: List<String> = emptyList(),
    val authorizedCandidatePks: List<String> = emptyList()
)

enum class CaptionCategory(val label: String) {
    VIRAL_COPYPASTA("Absurd Viral Copypasta"),
    MOTIVATIONAL("Motivational / Grind"),
    FITNESS_TECHNIQUE("Fitness & Performance"),
    AESTHETIC_MINIMAL("Minimal & Clean Hook"),
    QUESTION_ENGAGEMENT("Comment-Bait Question")
}

enum class CaptionRewriteMode(val label: String, val subtitle: String) {
    FIXED_LIBRARY_RANDOM(
        label = "Fixed Library (Anti-Repeat Random)",
        subtitle = "Selects from curated & copypasta library while avoiding recent repeats"
    ),
    AI_CLAUDE_SONNET_OPTIONAL(
        label = "Optional AI Rewriter (Claude Sonnet 4.6)",
        subtitle = "Backend rewriter.py rewrites original caption via Anthropic API (falls back to library if disabled)"
    ),
    HYBRID_TEMPLATE_PLUS_AI(
        label = "Hybrid (Copypasta Body + Dynamic Hashtags)",
        subtitle = "Combines fixed viral copypasta with category hashtag templates"
    )
}

data class CaptionTemplate(
    val id: Long = 0L,
    val title: String,
    val body: String,
    val category: CaptionCategory,
    val isAbsurdCopypasta: Boolean = false,
    val hashtagTemplate: String = "#reels #viral #trending",
    val usageCount: Int = 0,
    val lastUsedEpochMs: Long? = null,
    val isFavorite: Boolean = false
)

data class PostingWindow(
    val id: String,
    val label: String,
    val startHourIst: Int,
    val startMinuteIst: Int,
    val endHourIst: Int,
    val endMinuteIst: Int,
    val isEnabled: Boolean = true
) {
    fun formattedRange(): String =
        "%02d:%02d – %02d:%02d IST".format(startHourIst, startMinuteIst, endHourIst, endMinuteIst)
}

data class ScheduleConfig(
    val timezoneId: String = "Asia/Kolkata",
    val postIntervalHours: Int = 6,
    val maxPostsPerCycle: Int = 2,
    val dailyPostingLimit: Int = 6,
    val delayBetweenPostsSeconds: Int = 60,
    val dryRunMode: Boolean = true,
    val autoPublishEnabled: Boolean = false,
    val backendTestingVerified: Boolean = false,
    val postingWindows: List<PostingWindow> = defaultPostingWindows()
) {
    companion object {
        fun defaultPostingWindows(): List<PostingWindow> = listOf(
            PostingWindow("win_morning", "Morning Commute Peak", 8, 30, 11, 0, true),
            PostingWindow("win_evening", "Prime Evening Engagement", 18, 0, 21, 30, true),
            PostingWindow("win_latenight", "Late Night Scroll Window", 22, 15, 23, 45, false)
        )
    }
}

enum class CandidateOutcomeStatus(val label: String, val isRetryable: Boolean) {
    PUBLISHED("Published Reel", false),
    DRY_RUN_VALIDATED("Dry-Run Validated (No Upload)", false),
    QUALIFIED_QUEUED("Qualified in Pool", false),
    SKIPPED_DUPLICATE_PK("Skipped Duplicate (posted.db)", false),
    FILTERED_QUALITY("Filtered (Below Quality Threshold)", false),
    UNAUTHORIZED_BLOCKED("Blocked (Reuse Unauthorized)", false),
    IN_FLIGHT_AMBIGUOUS("Blocked (Ambiguous In-Flight State)", false),
    RETRY_DISPATCHED("Retry Dispatched to CI", false),
    DOWNLOAD_FAILED("Download Failed (yt-dlp + instagrapi)", true),
    UPLOAD_FAILED("Upload Failed (clip_upload error)", true)
}

data class ActivityCandidateRecord(
    val reelPk: String,
    val shortCode: String,
    val reelUrl: String,
    val hashtag: String,
    val category: String,
    val creatorHandle: String,
    val viewCount: Long,
    val likeCount: Long,
    val commentCount: Long,
    val durationSeconds: Int,
    val qualityScore: Double,
    val selectionReason: String,
    val downloaderUsed: String,
    val outcomeStatus: CandidateOutcomeStatus,
    val failureReason: String? = null,
    val captionPreview: String,
    val timestampIst: String,
    val timestampEpochMs: Long,
    val retryCount: Int = 0,
    val idempotencyToken: String = "idem_$reelPk",
    val reuseAuthorized: Boolean = false
)

data class DashboardTelemetry(
    val runState: AutomationRunState = AutomationRunState.DRY_RUN,
    val connectionState: BackendConnectionState = BackendConnectionState.MOCK_SIMULATED,
    val lastSuccessfulRunIst: String = "2026-10-28 06:00:19 IST",
    val nextScheduledRunIst: String = "2026-10-28 12:00:00 IST",
    val lastCachedSyncIst: String = "2026-10-28 11:02:45 IST",
    val candidatesDiscoveredTotal: Int = 148,
    val qualifyingPoolCount: Int = 34,
    val videosProcessedCount: Int = 18,
    val successfulPostsCount: Int = 12,
    val duplicateSkipsCount: Int = 27,
    val errorsCount: Int = 2,
    val githubWorkflowName: String = "insta-automation-cycle.yml",
    val lastWorkflowConclusion: String = "success (dry-run)"
)
