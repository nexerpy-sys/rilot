package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.LockPerson
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AutomationRunState
import com.example.model.BackendConnectionState
import com.example.model.DashboardTelemetry
import com.example.model.DataSourceMode
import com.example.model.ScheduleConfig
import com.example.ui.components.AnimatedHamburgerMenuButton
import com.example.ui.components.PhoenixLogoBadge
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.AmberWarningSoft
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CrimsonErrorSoft
import com.example.ui.theme.CyberMint
import com.example.ui.theme.DashboardPillBg
import com.example.ui.theme.DashboardPillText
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.StudioEmeraldIconBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.RilotTab

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    telemetry: DashboardTelemetry,
    scheduleConfig: ScheduleConfig,
    dataSourceMode: DataSourceMode,
    isMenuOpen: Boolean = false,
    onToggleMenu: () -> Unit = {},
    onNavigateToSection: (RilotTab) -> Unit,
    onTriggerCommand: (String) -> Unit,
    onRefreshStatus: () -> Unit,
    onSimulateConnectionState: (BackendConnectionState) -> Unit,
    onOpenGatewayModal: () -> Unit
) {
    val isAuthExpired = telemetry.connectionState == BackendConnectionState.AUTH_EXPIRED
    val isOffline = telemetry.connectionState == BackendConnectionState.DISCONNECTED_OFFLINE

    // Allow toggling between clean initial state ("Paused" / "—" / "Not scheduled" matching screenshot 1)
    // and populated telemetry metrics so the user can view both states effortlessly.
    var showPopulatedMetrics by remember { mutableStateOf(false) }

    val automationValueText = when {
        !showPopulatedMetrics && telemetry.runState == AutomationRunState.DRY_RUN -> "Paused"
        telemetry.runState == AutomationRunState.PAUSED -> "Paused"
        telemetry.runState == AutomationRunState.RUNNING_CYCLE -> "Running"
        telemetry.runState == AutomationRunState.DRY_RUN -> "Dry-run"
        else -> "Active"
    }

    val candidatesValueText = if (!showPopulatedMetrics && automationValueText == "Paused") {
        "—"
    } else {
        telemetry.candidatesDiscoveredTotal.toString()
    }

    val nextPostingValueText = if (!showPopulatedMetrics && automationValueText == "Paused") {
        "Not\nscheduled"
    } else if (telemetry.runState == AutomationRunState.PAUSED) {
        "Not\nscheduled"
    } else {
        "12:00 IST"
    }

    val postedTodayValueText = if (!showPopulatedMetrics && automationValueText == "Paused") {
        "—"
    } else {
        telemetry.successfulPostsCount.toString()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("dashboard_screen_list"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Outer rounded studio frame matching Screenshot 1 & Screenshot 2
        Surface(
            color = ObsidianSurface,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ObsidianBorder, RoundedCornerShape(28.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 20.dp)
            ) {
                // Top Studio Header Row: Phoenix Emblem + "Rilot" + "Kali studio automations" + "Dashboard" pill + Hamburger Menu
                StudioHeaderRow(
                    badgeText = "Dashboard",
                    isMenuOpen = isMenuOpen,
                    onToggleMenu = onToggleMenu,
                    onBadgeClick = { showPopulatedMetrics = !showPopulatedMetrics }
                )

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(18.dp))

                // 2x2 Status Grid Cards matching Screenshot 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StudioStatusGridCard(
                        icon = Icons.Outlined.SmartToy,
                        label = "Automation",
                        value = automationValueText,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("card_automation_status"),
                        onClick = {
                            if (automationValueText == "Paused") {
                                showPopulatedMetrics = true
                                onTriggerCommand("RESUME")
                            } else {
                                onTriggerCommand("PAUSE")
                            }
                        }
                    )
                    StudioStatusGridCard(
                        icon = Icons.Outlined.Search,
                        label = "Candidates found",
                        value = candidatesValueText,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("card_candidates_found"),
                        onClick = { showPopulatedMetrics = !showPopulatedMetrics }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StudioStatusGridCard(
                        icon = Icons.Outlined.Event,
                        label = "Next posting",
                        value = nextPostingValueText,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("card_next_posting"),
                        onClick = { onNavigateToSection(RilotTab.SCHEDULING) }
                    )
                    StudioStatusGridCard(
                        icon = Icons.Outlined.History,
                        label = "Posted today",
                        value = postedTodayValueText,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("card_posted_today"),
                        onClick = { onNavigateToSection(RilotTab.ACTIVITY) }
                    )
                }

                Spacer(modifier = Modifier.height(26.dp))

                // Section Title matching Screenshot 1 & 2
                Text(
                    text = "Your automation settings",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Grouped Settings Container matching Screenshot 2
                Surface(
                    color = Color(0xFF0B0C0E),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(22.dp))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AutomationSettingsRow(
                            icon = Icons.Outlined.Search,
                            title = "Viral content discovery",
                            subtitle = "Hashtags, categories, recent posts and popular posts",
                            testTag = "settings_row_discovery",
                            onClick = { onNavigateToSection(RilotTab.DISCOVERY) }
                        )
                        HorizontalDivider(
                            color = ObsidianBorder,
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 18.dp)
                        )
                        AutomationSettingsRow(
                            icon = Icons.Outlined.Tune,
                            title = "Selection engine",
                            subtitle = "Candidate pool, scoring, diversity and duplicate checks",
                            testTag = "settings_row_selection_engine",
                            onClick = { onNavigateToSection(RilotTab.SELECTION_ENGINE) }
                        )
                        HorizontalDivider(
                            color = ObsidianBorder,
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 18.dp)
                        )
                        AutomationSettingsRow(
                            icon = Icons.AutoMirrored.Outlined.Chat,
                            title = "Viral caption library",
                            subtitle = "Save, rotate and manage your fixed captions",
                            testTag = "settings_row_captions",
                            onClick = { onNavigateToSection(RilotTab.CAPTIONS) }
                        )
                        HorizontalDivider(
                            color = ObsidianBorder,
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 18.dp)
                        )
                        AutomationSettingsRow(
                            icon = Icons.Outlined.CalendarToday,
                            title = "Posting schedule",
                            subtitle = "Choose times, daily limits and posting intervals",
                            testTag = "settings_row_schedule",
                            onClick = { onNavigateToSection(RilotTab.SCHEDULING) }
                        )
                        HorizontalDivider(
                            color = ObsidianBorder,
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 18.dp)
                        )
                        AutomationSettingsRow(
                            icon = Icons.Outlined.BrokenImage,
                            title = "Activity and errors",
                            subtitle = "Downloads, publishing history and failure notifications",
                            testTag = "settings_row_activity",
                            onClick = { onNavigateToSection(RilotTab.ACTIVITY) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Engine Controls & Telemetry Drawer Card (Start / Pause / Resume + Offline & Auth Status)
                EngineQuickControlSection(
                    telemetry = telemetry,
                    scheduleConfig = scheduleConfig,
                    dataSourceMode = dataSourceMode,
                    showPopulatedMetrics = showPopulatedMetrics,
                    isAuthExpired = isAuthExpired,
                    isOffline = isOffline,
                    onToggleMetricsPreview = { showPopulatedMetrics = !showPopulatedMetrics },
                    onTriggerCommand = { cmd ->
                        showPopulatedMetrics = true
                        onTriggerCommand(cmd)
                    },
                    onRefreshStatus = onRefreshStatus,
                    onSimulateConnectionState = onSimulateConnectionState,
                    onOpenGatewayModal = onOpenGatewayModal
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Developed by Nexer • Kali studio automations",
                    style = MaterialTheme.typography.labelSmall,
                    color = DashboardPillText,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .testTag("developer_credit_label")
                )
            }
        }
    }
}

@Composable
fun StudioHeaderRow(
    badgeText: String,
    isMenuOpen: Boolean = false,
    onToggleMenu: () -> Unit = {},
    onBadgeClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Emerald-teal rounded icon with geometric Phoenix emblem
            PhoenixLogoBadge(
                boxSize = 54.dp,
                cornerRadius = 15.dp,
                canvasSize = 34.dp
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = "Rilot",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Kali studio automations",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = DashboardPillBg,
                shape = RoundedCornerShape(50),
                modifier = Modifier.clickable { onBadgeClick() }
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = DashboardPillText,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            AnimatedHamburgerMenuButton(
                isOpen = isMenuOpen,
                onToggle = onToggleMenu
            )
        }
    }
}

@Composable
private fun StudioStatusGridCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .heightIn(min = 132.dp)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 25.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun AutomationSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 18.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = TextPrimary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                ),
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "Open $title",
            tint = TextSecondary,
            modifier = Modifier.size(22.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineQuickControlSection(
    telemetry: DashboardTelemetry,
    scheduleConfig: ScheduleConfig,
    dataSourceMode: DataSourceMode,
    showPopulatedMetrics: Boolean,
    isAuthExpired: Boolean,
    isOffline: Boolean,
    onToggleMetricsPreview: () -> Unit,
    onTriggerCommand: (String) -> Unit,
    onRefreshStatus: () -> Unit,
    onSimulateConnectionState: (BackendConnectionState) -> Unit,
    onOpenGatewayModal: () -> Unit
) {
    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Engine Controls & Backend Telemetry",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Last run: ${telemetry.lastSuccessfulRunIst} • Processed: ${telemetry.videosProcessedCount} • Errors: ${telemetry.errorsCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            if (isOffline || isAuthExpired) {
                val alertColor = if (isAuthExpired) CrimsonError else AmberWarning
                val alertBg = if (isAuthExpired) CrimsonErrorSoft else AmberWarningSoft
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(alertBg)
                        .border(1.dp, alertColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                        .testTag("diagnostic_alert_card"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isAuthExpired) Icons.Default.LockPerson else Icons.Default.CloudOff,
                        contentDescription = telemetry.connectionState.title,
                        tint = alertColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = telemetry.connectionState.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = if (isAuthExpired) {
                                "session.json / 2FA expired. Renew on backend before starting."
                            } else {
                                "Backend unreachable — showing last known status cached at ${telemetry.lastCachedSyncIst}."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onTriggerCommand("START_CYCLE") },
                    enabled = !isAuthExpired,
                    colors = ButtonDefaults.buttonColors(containerColor = StudioEmeraldIconBg),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("start_cycle_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Start Cycle",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Start", maxLines = 1)
                }

                OutlinedButton(
                    onClick = { onTriggerCommand("PAUSE") },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("pause_automation_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = "Pause",
                        tint = AmberWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pause", color = AmberWarning, maxLines = 1)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onTriggerCommand("RESUME") },
                    enabled = !isAuthExpired,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("resume_automation_button")
                ) {
                    Text("Resume", color = CyberMint, maxLines = 1)
                }

                OutlinedButton(
                    onClick = onRefreshStatus,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("refresh_status_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Sync Status",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sync Status", color = TextPrimary, maxLines = 1)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = showPopulatedMetrics,
                    onClick = onToggleMetricsPreview,
                    label = { Text(if (showPopulatedMetrics) "Mock Telemetry: Active" else "Preview Mock Telemetry") },
                    modifier = Modifier.testTag("toggle_mock_metrics_chip")
                )
                FilterChip(
                    selected = telemetry.connectionState == BackendConnectionState.DISCONNECTED_OFFLINE,
                    onClick = {
                        val next = if (telemetry.connectionState == BackendConnectionState.DISCONNECTED_OFFLINE) {
                            BackendConnectionState.MOCK_SIMULATED
                        } else {
                            BackendConnectionState.DISCONNECTED_OFFLINE
                        }
                        onSimulateConnectionState(next)
                    },
                    label = { Text("Simulate Offline") },
                    modifier = Modifier.testTag("sim_state_offline")
                )
                FilterChip(
                    selected = telemetry.connectionState == BackendConnectionState.AUTH_EXPIRED,
                    onClick = {
                        val next = if (telemetry.connectionState == BackendConnectionState.AUTH_EXPIRED) {
                            BackendConnectionState.MOCK_SIMULATED
                        } else {
                            BackendConnectionState.AUTH_EXPIRED
                        }
                        onSimulateConnectionState(next)
                    },
                    label = { Text("Simulate Expired Auth") },
                    modifier = Modifier.testTag("sim_state_auth_expired")
                )
                FilterChip(
                    selected = dataSourceMode.isLiveBackend,
                    onClick = onOpenGatewayModal,
                    label = { Text("Apps Script API Contract") },
                    modifier = Modifier.testTag("open_apps_script_contract_chip")
                )
            }

            Text(
                text = "Mode: ${dataSourceMode.badgeText} • Dry-Run: ${if (scheduleConfig.dryRunMode) "ON" else "OFF"} • Auto-Publish: ${if (scheduleConfig.autoPublishEnabled) "ON" else "Disabled (Safe Default)"}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
    }
}
