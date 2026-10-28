package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.StudioSubscreenHeader
import com.example.ui.components.StylishHamburgerMenuOverlay
import com.example.ui.components.ToastFeedbackBanner
import com.example.ui.components.WorkerContractDialog
import com.example.ui.screens.AboutRilotScreen
import com.example.ui.screens.ActivityHistoryScreen
import com.example.ui.screens.CaptionLibraryScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DiscoverySettingsScreen
import com.example.ui.screens.SchedulingScreen
import com.example.ui.theme.CyberMint
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.RilotTheme
import com.example.viewmodel.RilotTab
import com.example.viewmodel.RilotUiState
import com.example.viewmodel.RilotViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RilotTheme {
                val context = LocalContext.current
                val viewModel: RilotViewModel = viewModel(
                    factory = RilotViewModel.provideFactory(context)
                )
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                RilotControlPanelApp(
                    uiState = uiState,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
fun RilotControlPanelApp(
    uiState: RilotUiState,
    viewModel: RilotViewModel
) {
    var showWorkerContractDialog by remember { mutableStateOf(false) }
    var isHamburgerMenuOpen by remember { mutableStateOf(false) }

    if (isHamburgerMenuOpen) {
        BackHandler {
            isHamburgerMenuOpen = false
        }
    } else if (uiState.currentTab != RilotTab.DASHBOARD) {
        BackHandler {
            viewModel.selectTab(RilotTab.DASHBOARD)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg),
        containerColor = ObsidianBg,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianBg)
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianBg)
            ) {
                if (uiState.currentTab != RilotTab.DASHBOARD) {
                    StudioSubscreenHeader(
                        title = uiState.currentTab.label,
                        subtitle = uiState.currentTab.subtitle,
                        badgeText = uiState.dataSourceMode.badgeText,
                        isMenuOpen = isHamburgerMenuOpen,
                        onToggleMenu = { isHamburgerMenuOpen = !isHamburgerMenuOpen },
                        onBack = { viewModel.selectTab(RilotTab.DASHBOARD) }
                    )
                }

                ToastFeedbackBanner(
                    statusBannerMessage = uiState.statusBannerMessage,
                    isErrorBanner = uiState.isErrorBanner,
                    onDismissBanner = { viewModel.dismissBanner() }
                )

                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = CyberMint)
                    }
                } else {
                    Crossfade(
                        targetState = uiState.currentTab,
                        label = "rilot_tab_transition",
                        modifier = Modifier.weight(1f)
                    ) { tab ->
                        when (tab) {
                            RilotTab.DASHBOARD -> DashboardScreen(
                                telemetry = uiState.telemetry,
                                scheduleConfig = uiState.scheduleConfig,
                                dataSourceMode = uiState.dataSourceMode,
                                isMenuOpen = isHamburgerMenuOpen,
                                onToggleMenu = { isHamburgerMenuOpen = !isHamburgerMenuOpen },
                                onNavigateToSection = {
                                    isHamburgerMenuOpen = false
                                    viewModel.selectTab(it)
                                },
                                onTriggerCommand = { viewModel.triggerEngineCommand(it) },
                                onRefreshStatus = { viewModel.refreshBackendStatus() },
                                onSimulateConnectionState = { viewModel.simulateConnectionScenario(it) },
                                onOpenGatewayModal = { showWorkerContractDialog = true }
                            )

                            RilotTab.DISCOVERY, RilotTab.SELECTION_ENGINE -> DiscoverySettingsScreen(
                                hashtags = uiState.hashtags,
                                config = uiState.discoveryConfig,
                                maxPostsPerCycle = uiState.scheduleConfig.maxPostsPerCycle,
                                validation = uiState.discoveryValidation,
                                onUpdateConfig = { viewModel.updateDiscoveryConfig(it) },
                                onAddHashtag = { tag, cat, limit, minViews ->
                                    viewModel.addHashtag(tag, cat, limit, minViews)
                                },
                                onToggleHashtag = { viewModel.toggleHashtag(it) },
                                onDeleteHashtag = { viewModel.deleteHashtag(it) }
                            )

                            RilotTab.CAPTIONS -> CaptionLibraryScreen(
                                captions = uiState.captions,
                                rewriteMode = uiState.captionRewriteMode,
                                selectedPreviewCaption = uiState.selectedPreviewCaption,
                                previewHashtag = uiState.previewSourceHashtag,
                                onSelectRewriteMode = { viewModel.updateCaptionRewriteMode(it) },
                                onRollRandomNonRepeating = { viewModel.rollNextNonRepeatingCaption(it) },
                                onSetPreviewHashtag = { viewModel.setPreviewHashtag(it) },
                                onSaveCaption = { id, title, body, category, isCopypasta, tagTemplate ->
                                    viewModel.saveCaptionTemplate(
                                        id = id,
                                        title = title,
                                        body = body,
                                        category = category,
                                        isAbsurdCopypasta = isCopypasta,
                                        hashtagTemplate = tagTemplate
                                    )
                                },
                                onDeleteCaption = { viewModel.deleteCaptionTemplate(it) }
                            )

                            RilotTab.SCHEDULING -> SchedulingScreen(
                                config = uiState.scheduleConfig,
                                validation = uiState.scheduleValidation,
                                onUpdateConfig = { viewModel.updateScheduleConfig(it) }
                            )

                            RilotTab.ACTIVITY -> ActivityHistoryScreen(
                                records = uiState.activityRecords,
                                retryingPk = uiState.retryingPk,
                                onSafeRetryCandidate = { viewModel.retryCandidateSafely(it) }
                            )

                            RilotTab.ABOUT_RILOT -> AboutRilotScreen(
                                onOpenWorkerContract = { showWorkerContractDialog = true }
                            )
                        }
                    }
                }
            }

            StylishHamburgerMenuOverlay(
                isOpen = isHamburgerMenuOpen,
                currentTab = uiState.currentTab,
                dataSourceMode = uiState.dataSourceMode,
                onSelectTab = { viewModel.selectTab(it) },
                onDismiss = { isHamburgerMenuOpen = false }
            )
        }
    }

    if (showWorkerContractDialog) {
        WorkerContractDialog(
            dataSourceMode = uiState.dataSourceMode,
            workerConfig = uiState.workerEndpointConfig,
            connectionTestResult = uiState.connectionTestResult,
            onSelectMode = { viewModel.setDataSourceMode(it) },
            onSaveEndpoint = { url, repo, token -> viewModel.updateWorkerEndpoint(url, repo, token) },
            onTestConnection = { url, repo, token -> viewModel.testBackendConnection(url, repo, token) },
            onClearCredentials = { viewModel.clearBackendCredentials() },
            onDismiss = { showWorkerContractDialog = false }
        )
    }
}
