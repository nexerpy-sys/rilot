package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.config.WorkerEndpointConfig
import com.example.data.RilotDatabase
import com.example.data.RilotRepository
import com.example.model.ActivityCandidateRecord
import com.example.model.BackendConnectionState
import com.example.model.CaptionCategory
import com.example.model.CaptionRewriteMode
import com.example.model.CaptionTemplate
import com.example.model.ConfigValidator
import com.example.model.DashboardTelemetry
import com.example.model.DataSourceMode
import com.example.model.DiscoveryConfig
import com.example.model.HashtagTarget
import com.example.model.ScheduleConfig
import com.example.model.ValidationResult
import com.example.network.AppsScriptConnectionTestDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class RilotTab(val route: String, val label: String, val subtitle: String) {
    DASHBOARD("dashboard", "Dashboard", "Kali studio automations"),
    DISCOVERY("discovery", "Viral content discovery", "Hashtags, categories, recent posts and popular posts"),
    SELECTION_ENGINE("selection_engine", "Selection engine", "Candidate pool, scoring, diversity and duplicate checks"),
    CAPTIONS("captions", "Viral caption library", "Save, rotate and manage your fixed captions"),
    SCHEDULING("scheduling", "Posting schedule", "Choose times, daily limits and posting intervals"),
    ACTIVITY("activity", "Activity and errors", "Downloads, publishing history and failure notifications"),
    ABOUT_RILOT("about_rilot", "About Rilot", "The work, operational flow, and architecture of Rilot")
}

data class RilotUiState(
    val isLoading: Boolean = true,
    val currentTab: RilotTab = RilotTab.DASHBOARD,
    val dataSourceMode: DataSourceMode = DataSourceMode.MOCK_PREVIEW,
    val workerEndpointConfig: WorkerEndpointConfig = WorkerEndpointConfig(),
    val telemetry: DashboardTelemetry = DashboardTelemetry(),
    val hashtags: List<HashtagTarget> = emptyList(),
    val discoveryConfig: DiscoveryConfig = DiscoveryConfig(),
    val discoveryValidation: ValidationResult = ValidationResult(true),
    val captions: List<CaptionTemplate> = emptyList(),
    val captionRewriteMode: CaptionRewriteMode = CaptionRewriteMode.FIXED_LIBRARY_RANDOM,
    val selectedPreviewCaption: CaptionTemplate? = null,
    val previewSourceHashtag: String = "calisthenics",
    val scheduleConfig: ScheduleConfig = ScheduleConfig(),
    val scheduleValidation: ValidationResult = ValidationResult(true),
    val activityRecords: List<ActivityCandidateRecord> = emptyList(),
    val retryingPk: String? = null,
    val statusBannerMessage: String? = null,
    val isErrorBanner: Boolean = false,
    val connectionTestResult: AppsScriptConnectionTestDto? = null
)

class RilotViewModel(
    private val repository: RilotRepository
) : ViewModel() {

    private val currentTabFlow = MutableStateFlow(RilotTab.DASHBOARD)
    private val isLoadingFlow = MutableStateFlow(true)
    private val selectedPreviewCaptionFlow = MutableStateFlow<CaptionTemplate?>(null)
    private val previewHashtagFlow = MutableStateFlow("calisthenics")
    private val retryingPkFlow = MutableStateFlow<String?>(null)
    private val bannerFlow = MutableStateFlow<Pair<String?, Boolean>>(null to false)
    private val connectionTestFlow = MutableStateFlow<AppsScriptConnectionTestDto?>(null)

    private val baseDataFlow = combine(
        repository.dataSourceMode,
        repository.workerEndpointConfig,
        repository.telemetry,
        repository.hashtagsFlow,
        repository.discoveryConfig
    ) { mode, workerCfg, tel, tags, discCfg ->
        BaseDataBundle(mode, workerCfg, tel, tags, discCfg)
    }

    private val contentDataFlow = combine(
        repository.captionsFlow,
        repository.captionRewriteMode,
        repository.scheduleConfig,
        repository.activityFlow
    ) { caps, capMode, schedCfg, activity ->
        ContentDataBundle(caps, capMode, schedCfg, activity)
    }

    private val transientUiFlow = combine(
        currentTabFlow,
        isLoadingFlow,
        selectedPreviewCaptionFlow,
        previewHashtagFlow,
        combine(retryingPkFlow, bannerFlow, connectionTestFlow) { retryPk, banner, connTest ->
            Triple(retryPk, banner, connTest)
        }
    ) { tab, loading, previewCap, previewTag, retryBannerAndTest ->
        TransientUiBundle(
            tab = tab,
            isLoading = loading,
            previewCap = previewCap,
            previewTag = previewTag,
            retryingPk = retryBannerAndTest.first,
            bannerMessage = retryBannerAndTest.second.first,
            isErrorBanner = retryBannerAndTest.second.second,
            connectionTestResult = retryBannerAndTest.third
        )
    }

    val uiState: StateFlow<RilotUiState> = combine(
        baseDataFlow,
        contentDataFlow,
        transientUiFlow
    ) { base, content, ui ->
        val discVal = ConfigValidator.validateDiscoveryConfig(
            config = base.discoveryConfig,
            hashtags = base.hashtags,
            maxPostsPerCycle = content.scheduleConfig.maxPostsPerCycle
        )
        val schedVal = ConfigValidator.validateScheduleConfig(content.scheduleConfig)
        val effectivePreviewCap = ui.previewCap
            ?: ConfigValidator.selectNextCaption(content.captions)

        RilotUiState(
            isLoading = ui.isLoading,
            currentTab = ui.tab,
            dataSourceMode = base.mode,
            workerEndpointConfig = base.workerConfig,
            telemetry = base.telemetry,
            hashtags = base.hashtags,
            discoveryConfig = base.discoveryConfig,
            discoveryValidation = discVal,
            captions = content.captions,
            captionRewriteMode = content.captionRewriteMode,
            selectedPreviewCaption = effectivePreviewCap,
            previewSourceHashtag = ui.previewTag,
            scheduleConfig = content.scheduleConfig,
            scheduleValidation = schedVal,
            activityRecords = content.activityRecords,
            retryingPk = ui.retryingPk,
            statusBannerMessage = ui.bannerMessage,
            isErrorBanner = ui.isErrorBanner,
            connectionTestResult = ui.connectionTestResult
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RilotUiState()
    )

    init {
        viewModelScope.launch {
            repository.ensureSeedDataLoaded()
            isLoadingFlow.value = false
        }
    }

    fun selectTab(tab: RilotTab) {
        currentTabFlow.value = tab
    }

    fun dismissBanner() {
        bannerFlow.value = null to false
    }

    fun setDataSourceMode(mode: DataSourceMode) {
        repository.setDataSourceMode(mode)
        if (mode.isLiveBackend) {
            refreshBackendStatus()
        } else {
            bannerFlow.value = "Switched to Mock Preview Mode (local InstaAutomation dataset)." to false
        }
    }

    fun updateWorkerEndpoint(
        url: String,
        repoSlug: String,
        clientToken: String = uiState.value.workerEndpointConfig.runtimeClientToken
    ): Result<Unit> {
        val res = repository.updateWorkerEndpointUrl(url, repoSlug, clientToken)
        res.fold(
            onSuccess = { bannerFlow.value = "Updated Google Apps Script Web App configuration." to false },
            onFailure = { err ->
                bannerFlow.value = (err.message ?: "Failed to securely save API key in Android Keystore") to true
            }
        )
        return res
    }

    fun clearBackendCredentials() {
        repository.clearWorkerEndpointCredentials()
        connectionTestFlow.value = null
        bannerFlow.value = "Cleared Google Apps Script API key and disconnected." to false
    }

    fun testBackendConnection(
        url: String = uiState.value.workerEndpointConfig.workerBaseUrl,
        repoSlug: String = uiState.value.workerEndpointConfig.githubRepoSlug,
        clientToken: String = uiState.value.workerEndpointConfig.runtimeClientToken
    ): Result<Unit> {
        val saveRes = repository.updateWorkerEndpointUrl(url, repoSlug, clientToken)
        if (saveRes.isFailure) {
            connectionTestFlow.value = null
            bannerFlow.value = (saveRes.exceptionOrNull()?.message ?: "Failed to securely save API key in Android Keystore") to true
            return saveRes
        }
        viewModelScope.launch {
            val res = repository.testBackendConnection()
            res.fold(
                onSuccess = { dto ->
                    connectionTestFlow.value = dto
                    bannerFlow.value = "Apps Script Connected • Sheets Ready • GitHub Target: ${dto.githubDispatchTarget.ifBlank { "Verified" }}" to false
                },
                onFailure = { err ->
                    connectionTestFlow.value = null
                    bannerFlow.value = (err.message ?: "Apps Script connection test failed") to true
                }
            )
        }
        return Result.success(Unit)
    }

    fun simulateConnectionScenario(state: BackendConnectionState) {
        repository.simulateConnectionScenario(state)
        bannerFlow.value = "Connection state updated: ${state.title}" to (state == BackendConnectionState.AUTH_EXPIRED || state == BackendConnectionState.DISCONNECTED_OFFLINE)
    }

    fun refreshBackendStatus() {
        viewModelScope.launch {
            val result = repository.refreshStatusFromBackend()
            result.fold(
                onSuccess = { msg -> bannerFlow.value = msg to false },
                onFailure = { err -> bannerFlow.value = (err.message ?: "Backend unreachable") to true }
            )
        }
    }

    fun triggerEngineCommand(command: String) {
        viewModelScope.launch {
            val result = repository.dispatchEngineCommand(command)
            result.fold(
                onSuccess = { msg -> bannerFlow.value = msg to false },
                onFailure = { err -> bannerFlow.value = (err.message ?: "Command failed") to true }
            )
        }
    }

    fun updateDiscoveryConfig(updated: DiscoveryConfig) {
        repository.saveDiscoveryConfig(updated)
        if (uiState.value.dataSourceMode.isLiveBackend) {
            viewModelScope.launch {
                val res = repository.syncDiscoveryToBackend()
                res.fold(
                    onSuccess = { msg -> bannerFlow.value = msg to false },
                    onFailure = { err -> bannerFlow.value = (err.message ?: "Discovery sync failed") to true }
                )
            }
        }
    }

    fun addHashtag(tag: String, category: String, limit: Int, minViews: Long) {
        viewModelScope.launch {
            repository.addHashtag(tag, category, limit, minViews)
            val clean = tag.trim().removePrefix("#").lowercase()
            if (uiState.value.dataSourceMode.isLiveBackend) {
                val res = repository.syncDiscoveryToBackend()
                res.fold(
                    onSuccess = { msg -> bannerFlow.value = "Added #$clean ($msg)" to false },
                    onFailure = { err -> bannerFlow.value = (err.message ?: "Hashtag sync failed") to true }
                )
            } else {
                bannerFlow.value = "Added #$clean to $category." to false
            }
        }
    }

    fun toggleHashtag(target: HashtagTarget) {
        viewModelScope.launch {
            repository.toggleHashtag(target)
            if (uiState.value.dataSourceMode.isLiveBackend) {
                val res = repository.syncDiscoveryToBackend()
                res.onFailure { err ->
                    bannerFlow.value = (err.message ?: "Hashtag sync failed") to true
                }
            }
        }
    }

    fun deleteHashtag(id: Long) {
        viewModelScope.launch {
            repository.deleteHashtag(id)
            if (uiState.value.dataSourceMode.isLiveBackend) {
                val res = repository.syncDiscoveryToBackend()
                res.onFailure { err ->
                    bannerFlow.value = (err.message ?: "Hashtag sync failed") to true
                }
            }
        }
    }

    fun updateCaptionRewriteMode(mode: CaptionRewriteMode) {
        repository.saveCaptionRewriteMode(mode)
        if (uiState.value.dataSourceMode.isLiveBackend) {
            viewModelScope.launch {
                val res = repository.syncCaptionsToBackend()
                res.fold(
                    onSuccess = { msg -> bannerFlow.value = msg to false },
                    onFailure = { err -> bannerFlow.value = (err.message ?: "Caption mode sync failed") to true }
                )
            }
        } else {
            bannerFlow.value = "Caption mode set to: ${mode.label}" to false
        }
    }

    fun saveCaptionTemplate(
        id: Long = 0L,
        title: String,
        body: String,
        category: CaptionCategory,
        isAbsurdCopypasta: Boolean,
        hashtagTemplate: String
    ) {
        viewModelScope.launch {
            repository.saveCaptionTemplate(id, title, body, category, isAbsurdCopypasta, hashtagTemplate)
            if (uiState.value.dataSourceMode.isLiveBackend) {
                val res = repository.syncCaptionsToBackend()
                res.fold(
                    onSuccess = { msg -> bannerFlow.value = msg to false },
                    onFailure = { err -> bannerFlow.value = (err.message ?: "Caption sync failed") to true }
                )
            } else {
                val msg = if (id == 0L) "Added caption template '$title'." else "Updated caption '$title'."
                bannerFlow.value = msg to false
            }
        }
    }

    fun deleteCaptionTemplate(id: Long) {
        viewModelScope.launch {
            repository.deleteCaptionTemplate(id)
            if (uiState.value.dataSourceMode.isLiveBackend) {
                val res = repository.syncCaptionsToBackend()
                res.onFailure { err ->
                    bannerFlow.value = (err.message ?: "Caption deletion sync failed") to true
                }
            }
        }
    }

    fun rollNextNonRepeatingCaption(categoryFilter: CaptionCategory? = null) {
        val currentList = uiState.value.captions
        val chosen = ConfigValidator.selectNextCaption(
            templates = currentList,
            recentExcludeCount = 2,
            categoryFilter = categoryFilter
        )
        selectedPreviewCaptionFlow.value = chosen
        if (chosen != null) {
            viewModelScope.launch {
                repository.markCaptionUsed(chosen)
            }
        }
    }

    fun setPreviewHashtag(tag: String) {
        previewHashtagFlow.value = tag.trim().removePrefix("#").ifEmpty { "fitness" }
    }

    fun updateScheduleConfig(updated: ScheduleConfig) {
        val safeUpdated = if (updated.autoPublishEnabled && !updated.backendTestingVerified) {
            bannerFlow.value = "Automatic publishing cannot be enabled until backend testing is marked verified." to true
            updated.copy(autoPublishEnabled = false)
        } else {
            updated
        }
        repository.saveScheduleConfig(safeUpdated)
        if (uiState.value.dataSourceMode.isLiveBackend) {
            viewModelScope.launch {
                val res = repository.syncScheduleToBackend()
                res.fold(
                    onSuccess = { msg -> bannerFlow.value = msg to false },
                    onFailure = { err -> bannerFlow.value = (err.message ?: "Schedule sync failed") to true }
                )
            }
        }
    }

    fun retryCandidateSafely(reelPk: String) {
        viewModelScope.launch {
            retryingPkFlow.value = reelPk
            val result = repository.retryFailedCandidateSafely(reelPk)
            retryingPkFlow.value = null
            result.fold(
                onSuccess = { msg -> bannerFlow.value = msg to false },
                onFailure = { err -> bannerFlow.value = (err.message ?: "Safe retry rejected") to true }
            )
        }
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val appContext = context.applicationContext
                    val db = RilotDatabase.getInstance(appContext)
                    val prefs = appContext.getSharedPreferences("rilot_prefs", Context.MODE_PRIVATE)
                    val repo = RilotRepository(db.rilotDao(), prefs)
                    return RilotViewModel(repo) as T
                }
            }
    }
}

private data class BaseDataBundle(
    val mode: DataSourceMode,
    val workerConfig: WorkerEndpointConfig,
    val telemetry: DashboardTelemetry,
    val hashtags: List<HashtagTarget>,
    val discoveryConfig: DiscoveryConfig
)

private data class ContentDataBundle(
    val captions: List<CaptionTemplate>,
    val captionRewriteMode: CaptionRewriteMode,
    val scheduleConfig: ScheduleConfig,
    val activityRecords: List<ActivityCandidateRecord>
)

private data class TransientUiBundle(
    val tab: RilotTab,
    val isLoading: Boolean,
    val previewCap: CaptionTemplate?,
    val previewTag: String,
    val retryingPk: String?,
    val bannerMessage: String?,
    val isErrorBanner: Boolean,
    val connectionTestResult: AppsScriptConnectionTestDto?
)
