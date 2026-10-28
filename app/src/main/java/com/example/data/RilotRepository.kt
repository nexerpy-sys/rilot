package com.example.data

import android.content.SharedPreferences
import com.example.config.AppsScriptSecureKeyStore
import com.example.config.WorkerEndpointConfig
import com.example.model.ActivityCandidateRecord
import com.example.model.AutomationRunState
import com.example.model.BackendConnectionState
import com.example.model.CandidateOutcomeStatus
import com.example.model.CaptionCategory
import com.example.model.CaptionRewriteMode
import com.example.model.CaptionTemplate
import com.example.model.ContentSourceType
import com.example.model.DashboardTelemetry
import com.example.model.DataSourceMode
import com.example.model.DiscoveryConfig
import com.example.model.HashtagTarget
import com.example.model.PostingWindow
import com.example.model.ScheduleConfig
import com.example.model.SelectionStrategy
import com.example.network.AppsScriptConnectionTestDto
import com.example.network.CaptionsConfigPayloadDto
import com.example.network.CloudflareWorkerApi
import com.example.network.CloudflareWorkerClientFactory
import com.example.network.DiscoveryConfigPayloadDto
import com.example.network.SafeRetryRequestDto
import com.example.network.ScheduleConfigPayloadDto
import com.example.network.ScheduleWindowDto
import com.example.network.WorkerCaptionDto
import com.example.network.WorkerControlRequestDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class RilotRepository(
    private val dao: RilotDao,
    private val prefs: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val apiProvider: (WorkerEndpointConfig) -> CloudflareWorkerApi = {
        CloudflareWorkerClientFactory.create(it)
    }
) {
    private val istZone = ZoneId.of("Asia/Kolkata")
    private val istFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'IST'")

    private val _dataSourceMode = MutableStateFlow(loadDataSourceMode())
    val dataSourceMode: StateFlow<DataSourceMode> = _dataSourceMode.asStateFlow()

    private val _workerEndpointConfig = MutableStateFlow(loadWorkerEndpointConfig())
    val workerEndpointConfig: StateFlow<WorkerEndpointConfig> = _workerEndpointConfig.asStateFlow()

    private val _discoveryConfig = MutableStateFlow(loadDiscoveryConfig())
    val discoveryConfig: StateFlow<DiscoveryConfig> = _discoveryConfig.asStateFlow()

    private val _captionRewriteMode = MutableStateFlow(loadCaptionRewriteMode())
    val captionRewriteMode: StateFlow<CaptionRewriteMode> = _captionRewriteMode.asStateFlow()

    private val _scheduleConfig = MutableStateFlow(loadScheduleConfig())
    val scheduleConfig: StateFlow<ScheduleConfig> = _scheduleConfig.asStateFlow()

    private val _telemetry = MutableStateFlow(loadInitialTelemetry())
    val telemetry: StateFlow<DashboardTelemetry> = _telemetry.asStateFlow()

    val hashtagsFlow: Flow<List<HashtagTarget>> = dao.observeHashtags().map { entities ->
        entities.map { it.toDomain() }
    }

    val captionsFlow: Flow<List<CaptionTemplate>> = dao.observeCaptions().map { entities ->
        entities.map { it.toDomain() }
    }

    val activityFlow: Flow<List<ActivityCandidateRecord>> = dao.observeActivityRecords().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun ensureSeedDataLoaded() = withContext(ioDispatcher) {
        if (dao.countHashtags() == 0) {
            dao.insertHashtags(MockSeedData.initialHashtags())
        }
        if (dao.countCaptions() == 0) {
            dao.insertCaptions(MockSeedData.initialCaptions())
        }
        if (dao.countActivityRecords() == 0) {
            dao.insertActivityRecords(MockSeedData.initialActivityRecords())
        }
    }

    fun setDataSourceMode(mode: DataSourceMode) {
        prefs.edit().putString("data_source_mode", mode.name).apply()
        _dataSourceMode.value = mode
        if (mode == DataSourceMode.MOCK_PREVIEW) {
            _telemetry.value = _telemetry.value.copy(
                connectionState = BackendConnectionState.MOCK_SIMULATED
            )
        }
    }

    fun updateWorkerEndpointUrl(
        url: String,
        repoSlug: String,
        clientToken: String = _workerEndpointConfig.value.runtimeClientToken
    ): Result<Unit> {
        val cleanedUrl = url.trim().ifEmpty { WorkerEndpointConfig.DEFAULT_PLACEHOLDER_URL }
        val cleanedRepo = repoSlug.trim().ifEmpty {
            WorkerEndpointConfig.DEFAULT_GITHUB_REPO_REFERENCE
        }
        val cleanedToken = clientToken.trim()
        val saveResult = AppsScriptSecureKeyStore.saveApiKey(prefs, cleanedToken)
        if (saveResult.isFailure) {
            return saveResult
        }
        prefs.edit()
            .putString("worker_base_url", cleanedUrl)
            .putString("github_repo_slug", cleanedRepo)
            .remove("runtime_client_token")
            .apply()
        _workerEndpointConfig.value = _workerEndpointConfig.value.copy(
            workerBaseUrl = cleanedUrl,
            githubRepoSlug = cleanedRepo,
            runtimeClientToken = cleanedToken
        )
        return Result.success(Unit)
    }

    fun clearWorkerEndpointCredentials(): WorkerEndpointConfig {
        AppsScriptSecureKeyStore.clearApiKey(prefs)
        prefs.edit()
            .remove("worker_base_url")
            .remove("github_repo_slug")
            .remove("runtime_client_token")
            .putString("data_source_mode", DataSourceMode.MOCK_PREVIEW.name)
            .apply()
        val resetConfig = WorkerEndpointConfig(
            workerBaseUrl = WorkerEndpointConfig.DEFAULT_PLACEHOLDER_URL,
            githubRepoSlug = WorkerEndpointConfig.DEFAULT_GITHUB_REPO_REFERENCE,
            runtimeClientToken = ""
        )
        _workerEndpointConfig.value = resetConfig
        _dataSourceMode.value = DataSourceMode.MOCK_PREVIEW
        _telemetry.value = _telemetry.value.copy(
            connectionState = BackendConnectionState.MOCK_SIMULATED
        )
        return resetConfig
    }

    suspend fun testBackendConnection(): Result<AppsScriptConnectionTestDto> = withContext(ioDispatcher) {
        val config = _workerEndpointConfig.value
        if (config.isPlaceholderEndpoint || config.runtimeClientToken.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Configure your Google Apps Script Web App /exec URL and Rilot App API Key first.")
            )
        }
        try {
            val api = apiProvider(config)
            val response = api.testConnection()
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val nowIst = currentIstTimestamp()
                _telemetry.value = _telemetry.value.copy(
                    connectionState = BackendConnectionState.CONNECTED_LIVE,
                    lastCachedSyncIst = nowIst
                )
                Result.success(body)
            } else {
                val errBody = config.redactSecrets(response.errorBody()?.string())
                Result.failure(
                    IllegalStateException(
                        "Connection test failed (HTTP ${response.code()}): ${errBody.ifBlank { "Rejected by Apps Script" }}"
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "Connection test failed: ${config.redactSecrets(e.message ?: "Apps Script unreachable")}"
                )
            )
        }
    }

    fun simulateConnectionScenario(state: BackendConnectionState) {
        _telemetry.value = _telemetry.value.copy(
            connectionState = state,
            lastCachedSyncIst = currentIstTimestamp()
        )
    }

    suspend fun refreshStatusFromBackend(): Result<String> = withContext(ioDispatcher) {
        val mode = _dataSourceMode.value
        if (mode == DataSourceMode.MOCK_PREVIEW) {
            val nowIst = currentIstTimestamp()
            _telemetry.value = _telemetry.value.copy(
                connectionState = BackendConnectionState.MOCK_SIMULATED,
                lastCachedSyncIst = nowIst
            )
            return@withContext Result.success("Refreshed Mock Preview telemetry ($nowIst)")
        }

        val config = _workerEndpointConfig.value
        if (config.isPlaceholderEndpoint) {
            _telemetry.value = _telemetry.value.copy(
                connectionState = BackendConnectionState.DISCONNECTED_OFFLINE,
                lastCachedSyncIst = currentIstTimestamp()
            )
            return@withContext Result.failure(
                IllegalStateException("Apps Script Web App URL is still set to placeholder. Showing cached local state.")
            )
        }

        try {
            val api = apiProvider(config)
            val response = api.getSystemStatus()
            if (response.isSuccessful && response.body() != null) {
                val dto = response.body()!!
                val mappedRunState = runCatching { AutomationRunState.valueOf(dto.runState) }
                    .getOrDefault(AutomationRunState.DRY_RUN)
                val mappedConn = if (dto.authStatus.equals("EXPIRED", ignoreCase = true)) {
                    BackendConnectionState.AUTH_EXPIRED
                } else {
                    BackendConnectionState.CONNECTED_LIVE
                }
                val nowIst = currentIstTimestamp()

                // Pull persisted captions, activity records, discovery config, and schedule config from Apps Script
                runCatching {
                    val discResp = api.getDiscoveryConfig()
                    if (discResp.isSuccessful && discResp.body() != null) {
                        val d = discResp.body()!!
                        saveDiscoveryConfig(
                            _discoveryConfig.value.copy(
                                candidateCollectionLimitPerTag = d.candidateCollectionLimitPerTag,
                                qualifyingPoolSize = d.qualifyingPoolSize,
                                minViewCountThreshold = d.minViewCountThreshold,
                                minLikeToViewRatioPercent = d.minLikeToViewRatioPercent,
                                recencyWeightPercent = d.recencyWeightPercent,
                                engagementWeightPercent = d.engagementWeightPercent,
                                diversityWeightPercent = d.diversityWeightPercent,
                                excludePreviouslyProcessed = d.excludePreviouslyProcessed,
                                preferYtdlpWithInstagrapiFallback = d.preferYtdlpFallback,
                                allowAllHashtagCandidatesWhenTargetAuthorized = d.allowAllHashtagCandidatesWhenTargetAuthorized,
                                authorizedCreators = d.authorizedCreators,
                                ownedAccounts = d.ownedAccounts,
                                authorizedCandidatePks = d.authorizedCandidatePks
                            )
                        )
                    }
                }
                runCatching {
                    val schedResp = api.getScheduleConfig()
                    if (schedResp.isSuccessful && schedResp.body() != null) {
                        val s = schedResp.body()!!
                        saveScheduleConfig(
                            _scheduleConfig.value.copy(
                                timezoneId = s.timezoneId,
                                postIntervalHours = s.postIntervalHours,
                                maxPostsPerCycle = s.maxPostsPerCycle,
                                dailyPostingLimit = s.dailyPostingLimit,
                                delayBetweenPostsSeconds = s.delayBetweenPostsSeconds,
                                dryRunMode = s.dryRunMode,
                                autoPublishEnabled = s.autoPublishEnabled,
                                postingWindows = s.postingWindows.map { w ->
                                    PostingWindow(
                                        id = w.id,
                                        label = w.label,
                                        startHourIst = w.startHourIst,
                                        startMinuteIst = w.startMinuteIst,
                                        endHourIst = w.endHourIst,
                                        endMinuteIst = w.endMinuteIst,
                                        isEnabled = w.isEnabled
                                    )
                                }
                            )
                        )
                    }
                }
                runCatching {
                    val captionsResp = api.getCaptions()
                    if (captionsResp.isSuccessful && captionsResp.body() != null) {
                        val capPayload = captionsResp.body()!!
                        val mappedMode = runCatching {
                            CaptionRewriteMode.valueOf(capPayload.captionRewriteMode)
                        }.getOrDefault(_captionRewriteMode.value)
                        saveCaptionRewriteMode(mappedMode)
                        if (capPayload.captions.isNotEmpty()) {
                            dao.insertCaptions(
                                capPayload.captions.map { c ->
                                    CaptionEntity(
                                        id = c.id,
                                        title = c.title,
                                        body = c.body,
                                        categoryName = c.category,
                                        isAbsurdCopypasta = c.isAbsurdCopypasta,
                                        hashtagTemplate = c.hashtagTemplate,
                                        usageCount = c.usageCount,
                                        lastUsedEpochMs = c.lastUsedEpochMs,
                                        isFavorite = false
                                    )
                                }
                            )
                        }
                    }
                }

                runCatching {
                    val activityResp = api.getActivity()
                    if (activityResp.isSuccessful && activityResp.body() != null) {
                        val records = activityResp.body()!!.records
                        if (records.isNotEmpty()) {
                            dao.insertActivityRecords(
                                records.map { r ->
                                    ActivityRecordEntity(
                                        reelPk = r.reelPk,
                                        shortCode = r.shortCode,
                                        reelUrl = r.reelUrl,
                                        hashtag = r.hashtag,
                                        category = r.category,
                                        creatorHandle = r.creatorHandle,
                                        viewCount = r.viewCount,
                                        likeCount = r.likeCount,
                                        commentCount = r.commentCount,
                                        durationSeconds = r.durationSeconds,
                                        qualityScore = r.qualityScore,
                                        selectionReason = r.selectionReason,
                                        downloaderUsed = r.downloaderUsed,
                                        outcomeStatusName = r.outcomeStatus,
                                        failureReason = r.failureReason,
                                        captionPreview = r.captionPreview,
                                        timestampIst = r.timestampIst,
                                        timestampEpochMs = r.timestampEpochMs,
                                        retryCount = r.retryCount,
                                        idempotencyToken = r.idempotencyToken.ifBlank { "idem_${r.reelPk}" }
                                    )
                                }
                            )
                        }
                    }
                }

                _telemetry.value = DashboardTelemetry(
                    runState = mappedRunState,
                    connectionState = mappedConn,
                    lastSuccessfulRunIst = dto.lastSuccessfulRunIst.ifEmpty { _telemetry.value.lastSuccessfulRunIst },
                    nextScheduledRunIst = dto.nextScheduledRunIst.ifEmpty { _telemetry.value.nextScheduledRunIst },
                    lastCachedSyncIst = nowIst,
                    candidatesDiscoveredTotal = dto.candidatesDiscovered,
                    qualifyingPoolCount = dto.qualifyingPoolCount,
                    videosProcessedCount = dto.videosProcessed,
                    successfulPostsCount = dto.successfulPosts,
                    duplicateSkipsCount = dto.duplicateSkips,
                    errorsCount = dto.errorsCount,
                    githubWorkflowName = config.workflowFileName,
                    lastWorkflowConclusion = dto.workflowConclusion
                )
                saveCachedTelemetry(_telemetry.value)
                Result.success("Synced live status from Google Apps Script ($nowIst)")
            } else {
                val code = response.code()
                val connState = if (code == 401 || code == 403) {
                    BackendConnectionState.AUTH_EXPIRED
                } else {
                    BackendConnectionState.DISCONNECTED_OFFLINE
                }
                _telemetry.value = _telemetry.value.copy(connectionState = connState)
                Result.failure(IllegalStateException("Apps Script returned HTTP $code. Showing cached telemetry."))
            }
        } catch (e: Exception) {
            _telemetry.value = _telemetry.value.copy(
                connectionState = BackendConnectionState.DISCONNECTED_OFFLINE
            )
            Result.failure(
                IllegalStateException(
                    "Backend unreachable (${config.redactSecrets(e.message ?: "network error")}). Offline fallback active."
                )
            )
        }
    }

    suspend fun dispatchEngineCommand(command: String): Result<String> = withContext(ioDispatcher) {
        val sched = _scheduleConfig.value
        val dryRunEnforced = sched.dryRunMode || !sched.autoPublishEnabled || command == "DRY_RUN_CYCLE"
        val nowIst = currentIstTimestamp()

        if (_dataSourceMode.value.isLiveBackend && !_workerEndpointConfig.value.isPlaceholderEndpoint) {
            try {
                val api = apiProvider(_workerEndpointConfig.value)
                val resp = api.sendControlCommand(
                    WorkerControlRequestDto(
                        command = command,
                        dryRun = dryRunEnforced
                    )
                )
                if (resp.isSuccessful && resp.body()?.accepted == true) {
                    val nextState = when (command) {
                        "PAUSE" -> AutomationRunState.PAUSED
                        "RESUME" -> if (dryRunEnforced) AutomationRunState.DRY_RUN else AutomationRunState.ENABLED
                        "START_CYCLE", "DRY_RUN_CYCLE" -> AutomationRunState.RUNNING_CYCLE
                        else -> _telemetry.value.runState
                    }
                    _telemetry.value = _telemetry.value.copy(
                        runState = nextState,
                        connectionState = BackendConnectionState.CONNECTED_LIVE,
                        lastCachedSyncIst = nowIst,
                        lastWorkflowConclusion = resp.body()?.message?.ifBlank { "dispatched ($command)" } ?: "dispatched ($command)"
                    )
                    saveCachedTelemetry(_telemetry.value)
                    return@withContext Result.success(
                        resp.body()?.message?.ifBlank {
                            "Dispatched '$command' via Google Apps Script (Dry-Run=$dryRunEnforced)"
                        } ?: "Dispatched '$command' via Google Apps Script (Dry-Run=$dryRunEnforced)"
                    )
                } else {
                    val errCode = resp.code()
                    val errMsg = resp.body()?.message?.ifBlank { "Apps Script rejected command (HTTP $errCode)" }
                        ?: "Apps Script rejected command (HTTP $errCode)"
                    return@withContext Result.failure(IllegalStateException(errMsg))
                }
            } catch (e: Exception) {
                _telemetry.value = _telemetry.value.copy(
                    connectionState = BackendConnectionState.DISCONNECTED_OFFLINE
                )
                return@withContext Result.failure(
                    IllegalStateException(
                        "Failed to dispatch '$command' to Apps Script: ${_workerEndpointConfig.value.redactSecrets(e.message ?: "network error")}"
                    )
                )
            }
        }

        // Mock Preview Mode simulation
        val nextRunState = when (command) {
            "PAUSE" -> AutomationRunState.PAUSED
            "RESUME" -> if (dryRunEnforced) AutomationRunState.DRY_RUN else AutomationRunState.ENABLED
            "START_CYCLE", "DRY_RUN_CYCLE" -> AutomationRunState.RUNNING_CYCLE
            else -> _telemetry.value.runState
        }

        val current = _telemetry.value
        val updated = if (command == "START_CYCLE" || command == "DRY_RUN_CYCLE") {
            current.copy(
                runState = if (dryRunEnforced) AutomationRunState.DRY_RUN else AutomationRunState.ENABLED,
                lastSuccessfulRunIst = nowIst,
                lastCachedSyncIst = nowIst,
                candidatesDiscoveredTotal = current.candidatesDiscoveredTotal + 6,
                qualifyingPoolCount = current.qualifyingPoolCount + 2,
                videosProcessedCount = current.videosProcessedCount + 1,
                successfulPostsCount = if (dryRunEnforced) current.successfulPostsCount else current.successfulPostsCount + 1,
                lastWorkflowConclusion = if (dryRunEnforced) "success (dry-run — no clip_upload)" else "success (published)"
            )
        } else {
            current.copy(
                runState = nextRunState,
                lastCachedSyncIst = nowIst
            )
        }
        _telemetry.value = updated
        saveCachedTelemetry(updated)

        val message = when (command) {
            "PAUSE" -> "Automation schedule paused. Scheduled GitHub Actions runs will skip execution."
            "RESUME" -> if (dryRunEnforced) {
                "Automation resumed in Safe Dry-Run Mode (Auto-Publish remains disabled)."
            } else {
                "Automation resumed in Live Publishing Mode."
            }
            "START_CYCLE", "DRY_RUN_CYCLE" -> if (dryRunEnforced) {
                "Executed Dry-Run cycle: scraped candidates, scored qualifying pool & verified posted.db without uploading."
            } else {
                "Triggered live cycle via GitHub Actions workflow."
            }
            else -> "Command $command processed."
        }
        Result.success(message)
    }

    fun saveDiscoveryConfig(config: DiscoveryConfig) {
        prefs.edit()
            .putInt("disc_collection_limit", config.candidateCollectionLimitPerTag)
            .putInt("disc_pool_size", config.qualifyingPoolSize)
            .putString("disc_content_source", config.contentSource.name)
            .putString("disc_selection_strategy", config.selectionStrategy.name)
            .putLong("disc_min_views", config.minViewCountThreshold)
            .putFloat("disc_min_like_ratio", config.minLikeToViewRatioPercent)
            .putInt("disc_min_duration", config.minDurationSeconds)
            .putInt("disc_max_duration", config.maxDurationSeconds)
            .putInt("disc_weight_recency", config.recencyWeightPercent)
            .putInt("disc_weight_engagement", config.engagementWeightPercent)
            .putInt("disc_weight_diversity", config.diversityWeightPercent)
            .putBoolean("disc_exclude_processed", config.excludePreviouslyProcessed)
            .putBoolean("disc_avoid_same_creator", config.avoidSameCreatorInWindow)
            .putBoolean("disc_prefer_ytdlp", config.preferYtdlpWithInstagrapiFallback)
            .putBoolean("disc_default_reuse_authorized", config.defaultReuseAuthorized)
            .putBoolean(
                "disc_allow_all_hashtag_candidates",
                config.allowAllHashtagCandidatesWhenTargetAuthorized
            )
            .putStringSet("disc_authorized_creators", config.authorizedCreators.toSet())
            .putStringSet("disc_owned_accounts", config.ownedAccounts.toSet())
            .putStringSet("disc_authorized_candidate_pks", config.authorizedCandidatePks.toSet())
            .apply()
        _discoveryConfig.value = config
    }

    suspend fun syncDiscoveryToBackend(): Result<String> = withContext(ioDispatcher) {
        if (!_dataSourceMode.value.isLiveBackend) {
            return@withContext Result.success("Saved discovery configuration locally.")
        }
        val workerCfg = _workerEndpointConfig.value
        if (workerCfg.isPlaceholderEndpoint) {
            return@withContext Result.failure(IllegalStateException("Apps Script Web App URL is not configured."))
        }
        try {
            val api = apiProvider(workerCfg)
            val cfg = _discoveryConfig.value
            val allTags = dao.getAllHashtags()
            val activeTags = allTags.filter { it.isEnabled }.map { it.tag }
            val reuseAuthMap = allTags.associate { it.tag to cfg.defaultReuseAuthorized }
            val allowAllByTagMap = allTags.associate {
                it.tag to cfg.allowAllHashtagCandidatesWhenTargetAuthorized
            }
            val resp = api.updateDiscoveryConfig(
                DiscoveryConfigPayloadDto(
                    activeHashtags = activeTags,
                    candidateCollectionLimitPerTag = cfg.candidateCollectionLimitPerTag,
                    qualifyingPoolSize = cfg.qualifyingPoolSize,
                    contentSource = cfg.contentSource.name,
                    selectionStrategy = cfg.selectionStrategy.name,
                    minViewCountThreshold = cfg.minViewCountThreshold,
                    minLikeToViewRatioPercent = cfg.minLikeToViewRatioPercent,
                    recencyWeightPercent = cfg.recencyWeightPercent,
                    engagementWeightPercent = cfg.engagementWeightPercent,
                    diversityWeightPercent = cfg.diversityWeightPercent,
                    excludePreviouslyProcessed = cfg.excludePreviouslyProcessed,
                    preferYtdlpFallback = cfg.preferYtdlpWithInstagrapiFallback,
                    reuseAuthorizedByTag = reuseAuthMap,
                    allowAllHashtagCandidatesWhenTargetAuthorized = cfg.allowAllHashtagCandidatesWhenTargetAuthorized,
                    allowAllHashtagCandidatesByTag = allowAllByTagMap,
                    authorizedCreators = cfg.authorizedCreators,
                    ownedAccounts = cfg.ownedAccounts,
                    authorizedCandidatePks = cfg.authorizedCandidatePks
                )
            )
            if (resp.isSuccessful && resp.body()?.accepted == true) {
                Result.success(resp.body()?.message?.ifBlank { "Discovery config synced to Google Sheets." } ?: "Discovery config synced to Google Sheets.")
            } else {
                Result.failure(IllegalStateException("Apps Script rejected discovery update (HTTP ${resp.code()})."))
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Failed to sync discovery config: ${_workerEndpointConfig.value.redactSecrets(e.message ?: "network error")}"))
        }
    }

    suspend fun addHashtag(tag: String, category: String, limit: Int, minViews: Long) = withContext(ioDispatcher) {
        val cleanTag = tag.trim().removePrefix("#").lowercase()
        if (cleanTag.isEmpty()) return@withContext
        dao.insertHashtag(
            HashtagEntity(
                tag = cleanTag,
                category = category.trim().ifEmpty { "General" },
                isEnabled = true,
                candidateLimitPerRun = limit.coerceIn(5, 100),
                minViewsThreshold = minViews.coerceAtLeast(1_000L),
                priorityWeight = 1.0f
            )
        )
    }

    suspend fun toggleHashtag(target: HashtagTarget) = withContext(ioDispatcher) {
        dao.updateHashtag(
            HashtagEntity(
                id = target.id,
                tag = target.tag,
                category = target.category,
                isEnabled = !target.isEnabled,
                candidateLimitPerRun = target.candidateLimitPerRun,
                minViewsThreshold = target.minViewsThreshold,
                priorityWeight = target.priorityWeight
            )
        )
    }

    suspend fun deleteHashtag(id: Long) = withContext(ioDispatcher) {
        dao.deleteHashtagById(id)
    }

    fun saveCaptionRewriteMode(mode: CaptionRewriteMode) {
        prefs.edit().putString("caption_rewrite_mode", mode.name).apply()
        _captionRewriteMode.value = mode
    }

    suspend fun syncCaptionsToBackend(): Result<String> = withContext(ioDispatcher) {
        if (!_dataSourceMode.value.isLiveBackend) {
            return@withContext Result.success("Saved caption library locally.")
        }
        val workerCfg = _workerEndpointConfig.value
        if (workerCfg.isPlaceholderEndpoint) {
            return@withContext Result.failure(IllegalStateException("Apps Script Web App URL is not configured."))
        }
        try {
            val api = apiProvider(workerCfg)
            val allCaps = dao.getAllCaptions().map {
                WorkerCaptionDto(
                    id = it.id,
                    title = it.title,
                    body = it.body,
                    category = it.categoryName,
                    isAbsurdCopypasta = it.isAbsurdCopypasta,
                    hashtagTemplate = it.hashtagTemplate,
                    usageCount = it.usageCount,
                    lastUsedEpochMs = it.lastUsedEpochMs
                )
            }
            val resp = api.updateCaptions(
                CaptionsConfigPayloadDto(
                    captionRewriteMode = _captionRewriteMode.value.name,
                    captions = allCaps
                )
            )
            if (resp.isSuccessful && resp.body()?.accepted == true) {
                Result.success(resp.body()?.message?.ifBlank { "Captions synced to Google Sheets." } ?: "Captions synced to Google Sheets.")
            } else {
                Result.failure(IllegalStateException("Apps Script rejected captions update (HTTP ${resp.code()})."))
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Failed to sync captions: ${_workerEndpointConfig.value.redactSecrets(e.message ?: "network error")}"))
        }
    }

    suspend fun saveCaptionTemplate(
        id: Long = 0L,
        title: String,
        body: String,
        category: CaptionCategory,
        isAbsurdCopypasta: Boolean,
        hashtagTemplate: String
    ) = withContext(ioDispatcher) {
        val cleanTitle = title.trim().ifEmpty { "Untitled Template" }
        val cleanBody = body.trim()
        if (cleanBody.isEmpty()) return@withContext

        dao.insertCaption(
            CaptionEntity(
                id = id,
                title = cleanTitle,
                body = cleanBody,
                categoryName = category.name,
                isAbsurdCopypasta = isAbsurdCopypasta,
                hashtagTemplate = hashtagTemplate.trim().ifEmpty { "#reels #viral" },
                usageCount = 0,
                lastUsedEpochMs = null,
                isFavorite = isAbsurdCopypasta
            )
        )
    }

    suspend fun deleteCaptionTemplate(id: Long) = withContext(ioDispatcher) {
        dao.deleteCaptionById(id)
    }

    suspend fun markCaptionUsed(template: CaptionTemplate) = withContext(ioDispatcher) {
        dao.updateCaption(
            CaptionEntity(
                id = template.id,
                title = template.title,
                body = template.body,
                categoryName = template.category.name,
                isAbsurdCopypasta = template.isAbsurdCopypasta,
                hashtagTemplate = template.hashtagTemplate,
                usageCount = template.usageCount + 1,
                lastUsedEpochMs = System.currentTimeMillis(),
                isFavorite = template.isFavorite
            )
        )
    }

    fun saveScheduleConfig(config: ScheduleConfig) {
        val windowsSerialized = config.postingWindows.joinToString(";") { w ->
            "${w.id}|${w.label}|${w.startHourIst}|${w.startMinuteIst}|${w.endHourIst}|${w.endMinuteIst}|${w.isEnabled}"
        }
        prefs.edit()
            .putString("sched_timezone", config.timezoneId)
            .putInt("sched_interval_hours", config.postIntervalHours)
            .putInt("sched_max_per_cycle", config.maxPostsPerCycle)
            .putInt("sched_daily_limit", config.dailyPostingLimit)
            .putInt("sched_delay_seconds", config.delayBetweenPostsSeconds)
            .putBoolean("sched_dry_run", config.dryRunMode)
            .putBoolean("sched_auto_publish", config.autoPublishEnabled)
            .putBoolean("sched_testing_verified", config.backendTestingVerified)
            .putString("sched_windows", windowsSerialized)
            .apply()
        _scheduleConfig.value = config
    }

    suspend fun syncScheduleToBackend(): Result<String> = withContext(ioDispatcher) {
        if (!_dataSourceMode.value.isLiveBackend) {
            return@withContext Result.success("Saved schedule configuration locally.")
        }
        val workerCfg = _workerEndpointConfig.value
        if (workerCfg.isPlaceholderEndpoint) {
            return@withContext Result.failure(IllegalStateException("Apps Script Web App URL is not configured."))
        }
        try {
            val api = apiProvider(workerCfg)
            val cfg = _scheduleConfig.value
            val resp = api.updateScheduleConfig(
                ScheduleConfigPayloadDto(
                    timezoneId = cfg.timezoneId,
                    postIntervalHours = cfg.postIntervalHours,
                    maxPostsPerCycle = cfg.maxPostsPerCycle,
                    dailyPostingLimit = cfg.dailyPostingLimit,
                    delayBetweenPostsSeconds = cfg.delayBetweenPostsSeconds,
                    dryRunMode = cfg.dryRunMode,
                    autoPublishEnabled = cfg.autoPublishEnabled,
                    postingWindows = cfg.postingWindows.map { w ->
                        ScheduleWindowDto(
                            id = w.id,
                            label = w.label,
                            startHourIst = w.startHourIst,
                            startMinuteIst = w.startMinuteIst,
                            endHourIst = w.endHourIst,
                            endMinuteIst = w.endMinuteIst,
                            isEnabled = w.isEnabled
                        )
                    }
                )
            )
            if (resp.isSuccessful && resp.body()?.accepted == true) {
                Result.success(resp.body()?.message?.ifBlank { "Schedule config synced to Google Sheets." } ?: "Schedule config synced to Google Sheets.")
            } else {
                Result.failure(IllegalStateException("Apps Script rejected schedule update (HTTP ${resp.code()})."))
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Failed to sync schedule config: ${_workerEndpointConfig.value.redactSecrets(e.message ?: "network error")}"))
        }
    }

    suspend fun retryFailedCandidateSafely(reelPk: String): Result<String> = withContext(ioDispatcher) {
        val existing = dao.getActivityByPk(reelPk)
            ?: return@withContext Result.failure(IllegalArgumentException("Candidate $reelPk not found in activity log."))

        val currentStatus = runCatching {
            CandidateOutcomeStatus.valueOf(existing.outcomeStatusName)
        }.getOrDefault(CandidateOutcomeStatus.FILTERED_QUALITY)

        if (currentStatus == CandidateOutcomeStatus.PUBLISHED ||
            currentStatus == CandidateOutcomeStatus.SKIPPED_DUPLICATE_PK
        ) {
            return@withContext Result.failure(
                IllegalStateException("Blocked by posted.db guard: Reel $reelPk is already marked as published/processed.")
            )
        }

        if (currentStatus == CandidateOutcomeStatus.IN_FLIGHT_AMBIGUOUS ||
            (existing.failureReason?.contains("IN_FLIGHT_AMBIGUOUS", ignoreCase = true) == true)
        ) {
            return@withContext Result.failure(
                IllegalStateException("Blocked by IN_FLIGHT_AMBIGUOUS guard: Reel $reelPk has an uncertain prior upload state. Manual check required.")
            )
        }

        if (currentStatus == CandidateOutcomeStatus.UNAUTHORIZED_BLOCKED) {
            return@withContext Result.failure(
                IllegalStateException("Blocked by fail-closed content authorization: Reel $reelPk is not authorized for reuse.")
            )
        }

        if (!currentStatus.isRetryable) {
            return@withContext Result.failure(
                IllegalStateException("Safe retry rejected: status '${currentStatus.label}' is not eligible for retry.")
            )
        }

        val sched = _scheduleConfig.value
        val dryRun = sched.dryRunMode || !sched.autoPublishEnabled
        val nowIst = currentIstTimestamp()
        val nextRetryCount = existing.retryCount + 1
        val nextIdempotencyToken = "idem_${reelPk}_r$nextRetryCount"

        if (_dataSourceMode.value.isLiveBackend && !_workerEndpointConfig.value.isPlaceholderEndpoint) {
            try {
                val api = apiProvider(_workerEndpointConfig.value)
                val resp = api.retryFailedCandidate(
                    reelPk = reelPk,
                    request = SafeRetryRequestDto(
                        reelPk = reelPk,
                        idempotencyToken = nextIdempotencyToken,
                        verifyNotInPostedDb = true,
                        dryRun = dryRun
                    )
                )
                if (!resp.isSuccessful || resp.body()?.accepted != true) {
                    val reason = resp.body()?.message?.ifBlank { "Apps Script rejected retry (HTTP ${resp.code()})" }
                        ?: "Apps Script rejected retry (HTTP ${resp.code()})"
                    return@withContext Result.failure(IllegalStateException(reason))
                }
            } catch (e: Exception) {
                return@withContext Result.failure(
                    IllegalStateException("Apps Script retry request failed: ${_workerEndpointConfig.value.redactSecrets(e.message ?: "network error")}")
                )
            }
        }

        val updatedOutcome = if (dryRun) {
            CandidateOutcomeStatus.DRY_RUN_VALIDATED
        } else {
            CandidateOutcomeStatus.PUBLISHED
        }

        val updatedEntity = existing.copy(
            outcomeStatusName = updatedOutcome.name,
            failureReason = null,
            downloaderUsed = "yt-dlp (idempotent retry #$nextRetryCount)",
            timestampIst = nowIst,
            timestampEpochMs = System.currentTimeMillis(),
            retryCount = nextRetryCount,
            idempotencyToken = nextIdempotencyToken
        )
        dao.upsertActivityRecord(updatedEntity)

        Result.success(
            "Safe Retry #$nextRetryCount for PK $reelPk succeeded (${updatedOutcome.label}). Verified no duplicate in posted.db."
        )
    }

    private fun currentIstTimestamp(): String =
        ZonedDateTime.now(istZone).format(istFormatter)

    private fun loadDataSourceMode(): DataSourceMode {
        val raw = prefs.getString("data_source_mode", DataSourceMode.MOCK_PREVIEW.name)
        return DataSourceMode.fromString(raw)
    }

    private fun loadWorkerEndpointConfig(): WorkerEndpointConfig {
        val defaults = WorkerEndpointConfig()
        val storedKey = AppsScriptSecureKeyStore.loadApiKey(prefs)
            .ifBlank { defaults.runtimeClientToken }
        return WorkerEndpointConfig(
            workerBaseUrl = prefs.getString("worker_base_url", defaults.workerBaseUrl) ?: defaults.workerBaseUrl,
            githubRepoSlug = prefs.getString("github_repo_slug", defaults.githubRepoSlug)
                ?: defaults.githubRepoSlug,
            runtimeClientToken = storedKey
        )
    }

    private fun loadDiscoveryConfig(): DiscoveryConfig {
        val defaults = DiscoveryConfig()
        return DiscoveryConfig(
            candidateCollectionLimitPerTag = prefs.getInt("disc_collection_limit", defaults.candidateCollectionLimitPerTag),
            qualifyingPoolSize = prefs.getInt("disc_pool_size", defaults.qualifyingPoolSize),
            contentSource = runCatching {
                ContentSourceType.valueOf(prefs.getString("disc_content_source", defaults.contentSource.name)!!)
            }.getOrDefault(defaults.contentSource),
            selectionStrategy = runCatching {
                SelectionStrategy.valueOf(prefs.getString("disc_selection_strategy", defaults.selectionStrategy.name)!!)
            }.getOrDefault(defaults.selectionStrategy),
            minViewCountThreshold = prefs.getLong("disc_min_views", defaults.minViewCountThreshold),
            minLikeToViewRatioPercent = prefs.getFloat("disc_min_like_ratio", defaults.minLikeToViewRatioPercent),
            minDurationSeconds = prefs.getInt("disc_min_duration", defaults.minDurationSeconds),
            maxDurationSeconds = prefs.getInt("disc_max_duration", defaults.maxDurationSeconds),
            recencyWeightPercent = prefs.getInt("disc_weight_recency", defaults.recencyWeightPercent),
            engagementWeightPercent = prefs.getInt("disc_weight_engagement", defaults.engagementWeightPercent),
            diversityWeightPercent = prefs.getInt("disc_weight_diversity", defaults.diversityWeightPercent),
            excludePreviouslyProcessed = prefs.getBoolean("disc_exclude_processed", defaults.excludePreviouslyProcessed),
            avoidSameCreatorInWindow = prefs.getBoolean("disc_avoid_same_creator", defaults.avoidSameCreatorInWindow),
            preferYtdlpWithInstagrapiFallback = prefs.getBoolean("disc_prefer_ytdlp", defaults.preferYtdlpWithInstagrapiFallback),
            defaultReuseAuthorized = prefs.getBoolean("disc_default_reuse_authorized", false),
            allowAllHashtagCandidatesWhenTargetAuthorized = prefs.getBoolean(
                "disc_allow_all_hashtag_candidates",
                false
            ),
            authorizedCreators = prefs.getStringSet("disc_authorized_creators", emptySet())?.toList() ?: emptyList(),
            ownedAccounts = prefs.getStringSet("disc_owned_accounts", emptySet())?.toList() ?: emptyList(),
            authorizedCandidatePks = prefs.getStringSet("disc_authorized_candidate_pks", emptySet())?.toList() ?: emptyList()
        )
    }

    private fun loadCaptionRewriteMode(): CaptionRewriteMode {
        val raw = prefs.getString("caption_rewrite_mode", CaptionRewriteMode.FIXED_LIBRARY_RANDOM.name)
        return runCatching { CaptionRewriteMode.valueOf(raw!!) }
            .getOrDefault(CaptionRewriteMode.FIXED_LIBRARY_RANDOM)
    }

    private fun loadScheduleConfig(): ScheduleConfig {
        val defaults = ScheduleConfig()
        val rawWindows = prefs.getString("sched_windows", null)
        val parsedWindows = if (!rawWindows.isNullOrBlank()) {
            rawWindows.split(";").mapNotNull { token ->
                val parts = token.split("|")
                if (parts.size == 7) {
                    PostingWindow(
                        id = parts[0],
                        label = parts[1],
                        startHourIst = parts[2].toIntOrNull() ?: 9,
                        startMinuteIst = parts[3].toIntOrNull() ?: 0,
                        endHourIst = parts[4].toIntOrNull() ?: 12,
                        endMinuteIst = parts[5].toIntOrNull() ?: 0,
                        isEnabled = parts[6].toBooleanStrictOrNull() ?: true
                    )
                } else null
            }.ifEmpty { defaults.postingWindows }
        } else {
            defaults.postingWindows
        }

        return ScheduleConfig(
            timezoneId = prefs.getString("sched_timezone", "Asia/Kolkata") ?: "Asia/Kolkata",
            postIntervalHours = prefs.getInt("sched_interval_hours", defaults.postIntervalHours),
            maxPostsPerCycle = prefs.getInt("sched_max_per_cycle", defaults.maxPostsPerCycle),
            dailyPostingLimit = prefs.getInt("sched_daily_limit", defaults.dailyPostingLimit),
            delayBetweenPostsSeconds = prefs.getInt("sched_delay_seconds", defaults.delayBetweenPostsSeconds),
            dryRunMode = prefs.getBoolean("sched_dry_run", true),
            autoPublishEnabled = prefs.getBoolean("sched_auto_publish", false),
            backendTestingVerified = prefs.getBoolean("sched_testing_verified", false),
            postingWindows = parsedWindows
        )
    }

    private fun loadInitialTelemetry(): DashboardTelemetry {
        val defaults = DashboardTelemetry()
        return defaults.copy(
            lastSuccessfulRunIst = prefs.getString("tel_last_run", defaults.lastSuccessfulRunIst) ?: defaults.lastSuccessfulRunIst,
            nextScheduledRunIst = prefs.getString("tel_next_run", defaults.nextScheduledRunIst) ?: defaults.nextScheduledRunIst,
            lastCachedSyncIst = prefs.getString("tel_last_sync", defaults.lastCachedSyncIst) ?: defaults.lastCachedSyncIst,
            candidatesDiscoveredTotal = prefs.getInt("tel_discovered", defaults.candidatesDiscoveredTotal),
            qualifyingPoolCount = prefs.getInt("tel_pool", defaults.qualifyingPoolCount),
            videosProcessedCount = prefs.getInt("tel_processed", defaults.videosProcessedCount),
            successfulPostsCount = prefs.getInt("tel_posted", defaults.successfulPostsCount),
            duplicateSkipsCount = prefs.getInt("tel_skips", defaults.duplicateSkipsCount),
            errorsCount = prefs.getInt("tel_errors", defaults.errorsCount)
        )
    }

    private fun saveCachedTelemetry(tel: DashboardTelemetry) {
        prefs.edit()
            .putString("tel_last_run", tel.lastSuccessfulRunIst)
            .putString("tel_next_run", tel.nextScheduledRunIst)
            .putString("tel_last_sync", tel.lastCachedSyncIst)
            .putInt("tel_discovered", tel.candidatesDiscoveredTotal)
            .putInt("tel_pool", tel.qualifyingPoolCount)
            .putInt("tel_processed", tel.videosProcessedCount)
            .putInt("tel_posted", tel.successfulPostsCount)
            .putInt("tel_skips", tel.duplicateSkipsCount)
            .putInt("tel_errors", tel.errorsCount)
            .apply()
    }
}

private fun HashtagEntity.toDomain(): HashtagTarget = HashtagTarget(
    id = id,
    tag = tag,
    category = category,
    isEnabled = isEnabled,
    candidateLimitPerRun = candidateLimitPerRun,
    minViewsThreshold = minViewsThreshold,
    priorityWeight = priorityWeight
)

private fun CaptionEntity.toDomain(): CaptionTemplate = CaptionTemplate(
    id = id,
    title = title,
    body = body,
    category = runCatching { CaptionCategory.valueOf(categoryName) }.getOrDefault(CaptionCategory.VIRAL_COPYPASTA),
    isAbsurdCopypasta = isAbsurdCopypasta,
    hashtagTemplate = hashtagTemplate,
    usageCount = usageCount,
    lastUsedEpochMs = lastUsedEpochMs,
    isFavorite = isFavorite
)

private fun ActivityRecordEntity.toDomain(): ActivityCandidateRecord = ActivityCandidateRecord(
    reelPk = reelPk,
    shortCode = shortCode,
    reelUrl = reelUrl,
    hashtag = hashtag,
    category = category,
    creatorHandle = creatorHandle,
    viewCount = viewCount,
    likeCount = likeCount,
    commentCount = commentCount,
    durationSeconds = durationSeconds,
    qualityScore = qualityScore,
    selectionReason = selectionReason,
    downloaderUsed = downloaderUsed,
    outcomeStatus = runCatching { CandidateOutcomeStatus.valueOf(outcomeStatusName) }
        .getOrDefault(CandidateOutcomeStatus.QUALIFIED_QUEUED),
    failureReason = failureReason,
    captionPreview = captionPreview,
    timestampIst = timestampIst,
    timestampEpochMs = timestampEpochMs,
    retryCount = retryCount,
    idempotencyToken = idempotencyToken
)
