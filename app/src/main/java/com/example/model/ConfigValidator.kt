package com.example.model

import java.time.ZoneId
import kotlin.math.min
import kotlin.math.roundToInt

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

object ConfigValidator {

    fun validateDiscoveryConfig(
        config: DiscoveryConfig,
        hashtags: List<HashtagTarget>,
        maxPostsPerCycle: Int
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val enabledTags = hashtags.filter { it.isEnabled }
        if (enabledTags.isEmpty()) {
            errors.add("At least one active hashtag is required for scraping.")
        }

        if (config.candidateCollectionLimitPerTag < 5 || config.candidateCollectionLimitPerTag > 100) {
            errors.add("Candidate collection limit per hashtag must be between 5 and 100.")
        }

        if (config.qualifyingPoolSize < maxPostsPerCycle) {
            errors.add(
                "Qualifying pool size (${config.qualifyingPoolSize}) must be at least Max Posts Per Cycle ($maxPostsPerCycle) to avoid always picking only the top highest-viewed posts."
            )
        }

        if (config.candidateCollectionLimitPerTag < config.qualifyingPoolSize) {
            errors.add("Candidate collection limit (${config.candidateCollectionLimitPerTag}) must be >= qualifying pool size (${config.qualifyingPoolSize}).")
        }

        if (config.minViewCountThreshold < 0L) {
            errors.add("Minimum view threshold cannot be negative.")
        }

        if (config.minLikeToViewRatioPercent < 0f || config.minLikeToViewRatioPercent > 50f) {
            errors.add("Minimum like-to-view engagement ratio must be between 0% and 50%.")
        }

        val totalWeight = config.recencyWeightPercent + config.engagementWeightPercent + config.diversityWeightPercent
        if (totalWeight <= 0) {
            errors.add("Combined scoring weights (Recency + Engagement + Diversity) must be greater than 0.")
        } else if (totalWeight != 100) {
            warnings.add("Scoring weights sum to $totalWeight% (will be normalized to 100% during candidate pool ranking).")
        }

        if (!config.excludePreviouslyProcessed) {
            warnings.add("Duplicate PK guard (posted.db) is disabled; this risks reposting identical Reels.")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    fun validateScheduleConfig(config: ScheduleConfig): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val validZone = try {
            ZoneId.of(config.timezoneId)
            true
        } catch (_: Exception) {
            false
        }
        if (!validZone || config.timezoneId != "Asia/Kolkata") {
            errors.add("Timezone must be valid and configured to Asia/Kolkata (IST).")
        }

        if (config.postIntervalHours < 1 || config.postIntervalHours > 72) {
            errors.add("Posting interval must be between 1 and 72 hours.")
        } else if (config.postIntervalHours < 4) {
            warnings.add("Instagram safety alert: POST_INTERVAL_HOURS below 4h increases rate-limit/challenge risk.")
        }

        if (config.maxPostsPerCycle < 1 || config.maxPostsPerCycle > 10) {
            errors.add("Max posts per cycle must be between 1 and 10.")
        } else if (config.maxPostsPerCycle > 3) {
            warnings.add("Instagram safety alert: MAX_POSTS_PER_CYCLE above 3 is discouraged by InstaAutomation safety guidelines.")
        }

        if (config.dailyPostingLimit < config.maxPostsPerCycle) {
            errors.add("Daily posting limit (${config.dailyPostingLimit}) cannot be smaller than max posts per cycle (${config.maxPostsPerCycle}).")
        }

        if (config.delayBetweenPostsSeconds < 15) {
            errors.add("Delay between posts must be at least 15 seconds (60s recommended).")
        }

        if (config.postingWindows.none { it.isEnabled }) {
            errors.add("At least one IST posting window must be enabled.")
        }

        if (config.autoPublishEnabled && !config.backendTestingVerified) {
            errors.add("Automatic publishing must remain disabled until backend Apps Script & GitHub Actions testing is verified.")
        }

        if (config.autoPublishEnabled && config.dryRunMode) {
            warnings.add("Dry-Run Mode is active; live clip_upload calls will be simulated even with Auto-Publish enabled.")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    /**
     * Selects a caption from the library while avoiding recently used templates.
     */
    fun selectNextCaption(
        templates: List<CaptionTemplate>,
        recentExcludeCount: Int = 2,
        categoryFilter: CaptionCategory? = null
    ): CaptionTemplate? {
        val filtered = if (categoryFilter != null) {
            templates.filter { it.category == categoryFilter }
        } else {
            templates
        }
        if (filtered.isEmpty()) return null
        if (filtered.size <= recentExcludeCount) {
            return filtered.minByOrNull { it.lastUsedEpochMs ?: 0L }
        }

        val sortedByRecency = filtered.sortedByDescending { it.lastUsedEpochMs ?: 0L }
        val recentlyUsedIds = sortedByRecency
            .filter { it.lastUsedEpochMs != null }
            .take(recentExcludeCount)
            .map { it.id }
            .toSet()

        val eligiblePool = filtered.filter { it.id !in recentlyUsedIds }
        return eligiblePool.minWithOrNull(
            compareBy<CaptionTemplate> { it.usageCount }.thenBy { it.lastUsedEpochMs ?: 0L }
        ) ?: filtered.first()
    }

    /**
     * Builds a complete formatted caption preview with dynamic hashtag injection and template tags.
     */
    fun buildCaptionPreview(
        template: CaptionTemplate,
        sourceHashtag: String,
        creatorHandle: String? = null
    ): String {
        val cleanSourceTag = sourceHashtag.trim().removePrefix("#")
        val normalizedBody = template.body
            .replace("{hashtag}", "#$cleanSourceTag")
            .replace("{creator}", creatorHandle ?: "@creator")
            .trim()

        val templateTags = template.hashtagTemplate
            .split(" ", ",", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith("#")) it else "#$it" }
            .toMutableList()

        val primaryTag = "#$cleanSourceTag"
        if (templateTags.none { it.equals(primaryTag, ignoreCase = true) }) {
            templateTags.add(0, primaryTag)
        }

        return "$normalizedBody\n.\n.\n${templateTags.DistinctIgnoreCase().joinToString(" ")}"
    }

    private fun List<String>.DistinctIgnoreCase(): List<String> {
        val seen = mutableSetOf<String>()
        return filter { seen.add(it.lowercase()) }
    }

    /**
     * Computes composite candidate quality score from engagement, recency, and category diversity
     * so selection draws from a diverse qualifying pool rather than naively taking top-2 view count.
     */
    fun computeCandidateScore(
        viewCount: Long,
        likeCount: Long,
        commentCount: Long,
        ageHours: Int,
        isUnderrepresentedCategory: Boolean,
        config: DiscoveryConfig
    ): Double {
        if (viewCount <= 0L) return 0.0
        val totalWeight = (config.recencyWeightPercent + config.engagementWeightPercent + config.diversityWeightPercent)
            .coerceAtLeast(1)

        val likeRatioPct = (likeCount.toDouble() / viewCount.toDouble()) * 100.0
        val commentRatioPct = (commentCount.toDouble() / viewCount.toDouble()) * 100.0
        val engagementNormalized = min(100.0, (likeRatioPct * 14.0) + (commentRatioPct * 85.0))

        val recencyNormalized = (100.0 - (ageHours * 2.2)).coerceIn(15.0, 100.0)
        val diversityNormalized = if (isUnderrepresentedCategory) 95.0 else 58.0

        val weighted = (
            (engagementNormalized * config.engagementWeightPercent) +
                (recencyNormalized * config.recencyWeightPercent) +
                (diversityNormalized * config.diversityWeightPercent)
            ) / totalWeight.toDouble()

        return ((weighted * 10.0).roundToInt() / 10.0).coerceIn(0.0, 100.0)
    }
}
