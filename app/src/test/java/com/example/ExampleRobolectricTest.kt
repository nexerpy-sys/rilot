package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.MockSeedData
import com.example.data.RilotDatabase
import com.example.data.RilotRepository
import com.example.model.AutomationRunState
import com.example.model.BackendConnectionState
import com.example.model.DashboardTelemetry
import com.example.model.DataSourceMode
import com.example.model.DiscoveryConfig
import com.example.model.ScheduleConfig
import com.example.network.CaptionsConfigPayloadDto
import com.example.network.CloudflareWorkerApi
import com.example.network.DiscoveryConfigPayloadDto
import com.example.network.SafeRetryRequestDto
import com.example.network.ScheduleConfigPayloadDto
import com.example.network.WorkerActivityRecordDto
import com.example.network.WorkerActivityResponseDto
import com.example.network.WorkerControlRequestDto
import com.example.network.WorkerMutationAckDto
import com.example.network.WorkerStatusDto
import com.example.ui.screens.AboutRilotScreen
import com.example.ui.screens.DashboardScreen
import com.example.config.AppsScriptSecureKeyStore
import com.example.ui.theme.RilotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        AppsScriptSecureKeyStore.enableTestMode()
    }

    @After
    fun tearDown() {
        AppsScriptSecureKeyStore.disableTestMode()
    }

    @Test
    fun `app_name resource is Rilot`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Rilot", appName)
    }

    @Test
    fun `dashboard renders studio header status cards hamburger menu and automation settings rows`() {
        var lastTriggeredCommand: String? = null
        var isMenuOpen by mutableStateOf(false)
        composeTestRule.setContent {
            RilotTheme {
                DashboardScreen(
                    telemetry = DashboardTelemetry(
                        runState = AutomationRunState.PAUSED,
                        connectionState = BackendConnectionState.MOCK_SIMULATED
                    ),
                    scheduleConfig = ScheduleConfig(),
                    dataSourceMode = DataSourceMode.MOCK_PREVIEW,
                    isMenuOpen = isMenuOpen,
                    onToggleMenu = { isMenuOpen = !isMenuOpen },
                    onNavigateToSection = {},
                    onTriggerCommand = { lastTriggeredCommand = it },
                    onRefreshStatus = {},
                    onSimulateConnectionState = {},
                    onOpenGatewayModal = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Rilot").assertExists()
        composeTestRule.onNodeWithText("Kali studio automations").assertExists()
        composeTestRule.onNodeWithTag("phoenix_logo_badge").assertExists()
        composeTestRule.onNodeWithText("Developed by Nexer • Kali studio automations").assertExists()
        composeTestRule.onNodeWithText("Viral content discovery").assertExists()
        composeTestRule.onNodeWithText("Selection engine").assertExists()
        composeTestRule.onNodeWithText("Viral caption library").assertExists()

        // Verify hamburger menu button opens (tilts 90 degrees) and closes (tilts back to 0 degrees)
        composeTestRule.onNodeWithContentDescription("Open navigation menu").assertExists()
        composeTestRule.onNodeWithTag("hamburger_menu_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(true, isMenuOpen)
        composeTestRule.onNodeWithContentDescription("Close navigation menu").assertExists()

        composeTestRule.onNodeWithTag("hamburger_menu_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(false, isMenuOpen)

        composeTestRule.onNodeWithTag("card_automation_status").performClick()
        composeTestRule.waitForIdle()
        assertEquals("RESUME", lastTriggeredCommand)
    }

    @Test
    fun `about rilot screen displays work of the app on top followed by flow architecture and nexer credits`() {
        composeTestRule.setContent {
            RilotTheme {
                AboutRilotScreen(onOpenWorkerContract = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("The Work of Rilot").assertExists()
        composeTestRule.onNodeWithText("The Operational Flow of Rilot").assertExists()
        composeTestRule.onNodeWithText("Kali studio automations • Developed by Nexer").assertExists()
    }

    @Test
    fun `live google apps script mode syncs config status activity and executes safe retries without fake counter increments`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, RilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = context.getSharedPreferences("test_live_worker_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        var lastDiscoveryPayload: DiscoveryConfigPayloadDto? = null
        var lastSchedulePayload: ScheduleConfigPayloadDto? = null
        var lastRetryRequest: SafeRetryRequestDto? = null

        val fakeWorkerApi = object : CloudflareWorkerApi {
            override suspend fun getSystemStatus(): Response<WorkerStatusDto> {
                return Response.success(
                    WorkerStatusDto(
                        runState = "DRY_RUN",
                        authStatus = "OK",
                        lastSuccessfulRunIst = "2026-10-28 12:00:00 IST",
                        nextScheduledRunIst = "2026-10-28 18:00:00 IST",
                        candidatesDiscovered = 42,
                        qualifyingPoolCount = 11,
                        videosProcessed = 4,
                        successfulPosts = 2,
                        duplicateSkips = 9,
                        errorsCount = 1,
                        workflowConclusion = "success (dry-run)"
                    )
                )
            }

            override suspend fun sendControlCommand(request: WorkerControlRequestDto): Response<WorkerMutationAckDto> {
                return Response.success(
                    WorkerMutationAckDto(
                        accepted = true,
                        message = "Dispatched ${request.command} to GitHub Actions",
                        workerTimestampIst = "2026-10-28 12:05:00 IST"
                    )
                )
            }

            override suspend fun updateDiscoveryConfig(payload: DiscoveryConfigPayloadDto): Response<WorkerMutationAckDto> {
                lastDiscoveryPayload = payload
                return Response.success(
                    WorkerMutationAckDto(
                        accepted = true,
                        message = "Discovery synced",
                        workerTimestampIst = "2026-10-28 12:05:00 IST"
                    )
                )
            }

            override suspend fun updateScheduleConfig(payload: ScheduleConfigPayloadDto): Response<WorkerMutationAckDto> {
                lastSchedulePayload = payload
                return Response.success(
                    WorkerMutationAckDto(
                        accepted = true,
                        message = "Schedule synced",
                        workerTimestampIst = "2026-10-28 12:05:00 IST"
                    )
                )
            }

            override suspend fun getCaptions(): Response<CaptionsConfigPayloadDto> {
                return Response.success(
                    CaptionsConfigPayloadDto(
                        captionRewriteMode = "FIXED_LIBRARY_RANDOM",
                        captions = emptyList()
                    )
                )
            }

            override suspend fun updateCaptions(payload: CaptionsConfigPayloadDto): Response<WorkerMutationAckDto> {
                return Response.success(
                    WorkerMutationAckDto(
                        accepted = true,
                        message = "Captions synced",
                        workerTimestampIst = "2026-10-28 12:05:00 IST"
                    )
                )
            }

            override suspend fun getActivity(): Response<WorkerActivityResponseDto> {
                return Response.success(
                    WorkerActivityResponseDto(
                        records = listOf(
                            WorkerActivityRecordDto(
                                reelPk = "3488912049182736455",
                                shortCode = "DB9vLm2pQrT",
                                reelUrl = "https://www.instagram.com/reel/DB9vLm2pQrT/",
                                hashtag = "calisthenics",
                                category = "Strength",
                                creatorHandle = "@bar_brothers_in",
                                viewCount = 95000L,
                                likeCount = 6200L,
                                commentCount = 140L,
                                durationSeconds = 18,
                                qualityScore = 91.2,
                                selectionReason = "Weighted pool selection",
                                downloaderUsed = "yt-dlp failed",
                                outcomeStatus = "DOWNLOAD_FAILED",
                                failureReason = "HTTP 429",
                                captionPreview = "Gravity glitch #calisthenics",
                                timestampIst = "2026-10-28 12:00:00 IST",
                                timestampEpochMs = 1730000000000L,
                                retryCount = 0,
                                idempotencyToken = "idem_3488912049182736455",
                                reuseAuthorized = true
                            )
                        )
                    )
                )
            }

            override suspend fun retryFailedCandidate(
                reelPk: String,
                request: SafeRetryRequestDto
            ): Response<WorkerMutationAckDto> {
                lastRetryRequest = request
                return Response.success(
                    WorkerMutationAckDto(
                        accepted = true,
                        status = "RETRY_DISPATCHED",
                        targetReelPk = reelPk,
                        message = "Safe retry accepted",
                        workerTimestampIst = "2026-10-28 12:06:00 IST"
                    )
                )
            }
        }

        val repo = RilotRepository(
            dao = db.rilotDao(),
            prefs = prefs,
            ioDispatcher = Dispatchers.Unconfined,
            apiProvider = { fakeWorkerApi }
        )
        repo.ensureSeedDataLoaded()
        repo.updateWorkerEndpointUrl(
            url = "https://script.google.com/macros/s/AKfycbx9988776655/exec",
            repoSlug = "nexerpy-sys/rilot",
            clientToken = "live_apps_script_app_key"
        )
        repo.setDataSourceMode(DataSourceMode.GOOGLE_APPS_SCRIPT)
        assertEquals(DataSourceMode.GOOGLE_APPS_SCRIPT, DataSourceMode.fromString("CLOUDFLARE_WORKER"))
        assertEquals(DataSourceMode.GOOGLE_APPS_SCRIPT, DataSourceMode.fromString("GOOGLE_APPS_SCRIPT"))

        // Verify connection test against Google Apps Script Web App
        val connTestRes = repo.testBackendConnection()
        assertTrue(connTestRes.isSuccess)
        assertEquals(true, connTestRes.getOrNull()?.appsScriptReachable)
        assertEquals(true, connTestRes.getOrNull()?.spreadsheetInitialized)

        // 1. Refresh status from live Google Apps Script backend
        val statusRes = repo.refreshStatusFromBackend()
        assertTrue(statusRes.isSuccess)
        assertEquals(BackendConnectionState.CONNECTED_LIVE, repo.telemetry.value.connectionState)
        assertEquals(42, repo.telemetry.value.candidatesDiscoveredTotal)

        // 2. Dispatch START_CYCLE in Live mode: must NOT fake +6 candidatesDiscoveredTotal
        val cmdRes = repo.dispatchEngineCommand("START_CYCLE")
        assertTrue(cmdRes.isSuccess)
        assertEquals(AutomationRunState.RUNNING_CYCLE, repo.telemetry.value.runState)
        assertEquals(42, repo.telemetry.value.candidatesDiscoveredTotal)

        // 3. Sync discovery & schedule configs to Google Apps Script (fail-closed reuseAuthorizedByTag = false)
        repo.saveDiscoveryConfig(DiscoveryConfig(candidateCollectionLimitPerTag = 45, qualifyingPoolSize = 12))
        assertTrue(repo.syncDiscoveryToBackend().isSuccess)
        assertEquals(45, lastDiscoveryPayload?.candidateCollectionLimitPerTag)
        assertTrue(
            "All synced tags must default to fail-closed reuseAuthorized=false",
            lastDiscoveryPayload?.reuseAuthorizedByTag?.values?.all { !it } == true
        )

        repo.saveScheduleConfig(ScheduleConfig(postIntervalHours = 4, dryRunMode = true))
        assertTrue(repo.syncScheduleToBackend().isSuccess)
        assertEquals(4, lastSchedulePayload?.postIntervalHours)

        // 4. Safe retry verifies PK against posted.db and increments idempotencyToken
        val retryRes = repo.retryFailedCandidateSafely("3488912049182736455")
        assertTrue(retryRes.isSuccess)
        assertEquals("idem_3488912049182736455_r1", lastRetryRequest?.idempotencyToken)
        assertTrue(lastRetryRequest?.verifyNotInPostedDb == true)

        // Retrying again after it transitioned to DRY_RUN_VALIDATED must be blocked
        val duplicateRetryRes = repo.retryFailedCandidateSafely("3488912049182736455")
        assertFalse(duplicateRetryRes.isSuccess)

        // 5. Clear credentials / disconnect resets token and returns to Mock Preview mode
        val clearedCfg = repo.clearWorkerEndpointCredentials()
        assertTrue(clearedCfg.isPlaceholderEndpoint)
        assertEquals("", clearedCfg.runtimeClientToken)
        assertEquals(DataSourceMode.MOCK_PREVIEW, repo.dataSourceMode.value)

        db.close()
    }
}
