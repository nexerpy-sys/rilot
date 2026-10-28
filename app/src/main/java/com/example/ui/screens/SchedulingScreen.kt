package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.PostingWindow
import com.example.model.ScheduleConfig
import com.example.model.ValidationResult
import com.example.ui.components.StatusPillBadge
import com.example.ui.components.ValidationBannerCard
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.AmberWarningSoft
import com.example.ui.theme.CyberMint
import com.example.ui.theme.CyberMintSoft
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.ElectricVioletSoft
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianCardElevated
import com.example.ui.theme.ReelCoral
import com.example.ui.theme.ReelCoralSoft
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun SchedulingScreen(
    config: ScheduleConfig,
    validation: ValidationResult,
    onUpdateConfig: (ScheduleConfig) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("scheduling_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ValidationBannerCard(validation = validation)
        }

        item {
            TimezoneAndSafetyGateCard(
                config = config,
                onUpdateConfig = onUpdateConfig
            )
        }

        item {
            CycleCadenceCard(
                config = config,
                onUpdateConfig = onUpdateConfig
            )
        }

        item {
            PostingWindowsCard(
                windows = config.postingWindows,
                onToggleWindow = { toggled ->
                    val updatedList = config.postingWindows.map { win ->
                        if (win.id == toggled.id) win.copy(isEnabled = !win.isEnabled) else win
                    }
                    onUpdateConfig(config.copy(postingWindows = updatedList))
                }
            )
        }
    }
}

@Composable
private fun TimezoneAndSafetyGateCard(
    config: ScheduleConfig,
    onUpdateConfig: (ScheduleConfig) -> Unit
) {
    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ObsidianBorder, RoundedCornerShape(18.dp))
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = "Timezone",
                        tint = CyberMint,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Timezone & Publishing Safety Gate",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Locked to ${config.timezoneId} (UTC+05:30 IST)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                StatusPillBadge(
                    text = config.timezoneId,
                    color = CyberMint,
                    containerColor = CyberMintSoft
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianCardElevated)
                    .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = "Dry-Run Mode",
                        tint = ElectricViolet,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Dry-Run Mode",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "Scrapes, scores pool, downloads via yt-dlp & rewrites caption, but skips uploader.py clip_upload",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Switch(
                    checked = config.dryRunMode,
                    onCheckedChange = { onUpdateConfig(config.copy(dryRunMode = it)) },
                    modifier = Modifier.testTag("switch_dry_run_mode")
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (config.autoPublishEnabled) AmberWarningSoft else ObsidianCardElevated)
                    .border(
                        1.dp,
                        if (config.autoPublishEnabled) AmberWarning else ObsidianBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        Icon(
                            imageVector = if (config.autoPublishEnabled) Icons.Default.VerifiedUser else Icons.Default.Lock,
                            contentDescription = "Automatic Publishing",
                            tint = if (config.autoPublishEnabled) AmberWarning else CyberMint,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Automatic Publishing (Default: Disabled)",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = "Remains disabled by default until Google Apps Script + GitHub Actions testing is verified.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                    Switch(
                        checked = config.autoPublishEnabled,
                        enabled = config.backendTestingVerified,
                        onCheckedChange = { enabled ->
                            onUpdateConfig(
                                config.copy(
                                    autoPublishEnabled = enabled,
                                    dryRunMode = if (enabled) false else config.dryRunMode
                                )
                            )
                        },
                        modifier = Modifier.testTag("switch_auto_publish")
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = config.backendTestingVerified,
                        onCheckedChange = { verified ->
                            onUpdateConfig(
                                config.copy(
                                    backendTestingVerified = verified,
                                    autoPublishEnabled = if (!verified) false else config.autoPublishEnabled
                                )
                            )
                        },
                        modifier = Modifier.testTag("checkbox_backend_verified")
                    )
                    Text(
                        text = "Confirm backend Worker & dry-run pipeline testing is complete (unlocks Auto-Publish toggle)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun CycleCadenceCard(
    config: ScheduleConfig,
    onUpdateConfig: (ScheduleConfig) -> Unit
) {
    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ObsidianBorder, RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = "Cycle Cadence",
                    tint = ReelCoral,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Cycle Interval & Rate-Limit Caps",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Aligned with main.py POST_INTERVAL_HOURS & MAX_POSTS_PER_CYCLE",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Cycle Interval (POST_INTERVAL_HOURS)", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("Every ${config.postIntervalHours}h", style = MaterialTheme.typography.labelMedium, color = CyberMint)
                }
                Slider(
                    value = config.postIntervalHours.toFloat(),
                    onValueChange = {
                        onUpdateConfig(config.copy(postIntervalHours = it.roundToInt().coerceIn(2, 24)))
                    },
                    valueRange = 2f..24f,
                    modifier = Modifier.testTag("slider_post_interval")
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Max Posts Per Cycle (MAX_POSTS_PER_CYCLE)", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("${config.maxPostsPerCycle} reels/cycle", style = MaterialTheme.typography.labelMedium, color = ReelCoral)
                }
                Slider(
                    value = config.maxPostsPerCycle.toFloat(),
                    onValueChange = {
                        val nextMax = it.roundToInt().coerceIn(1, 6)
                        onUpdateConfig(
                            config.copy(
                                maxPostsPerCycle = nextMax,
                                dailyPostingLimit = config.dailyPostingLimit.coerceAtLeast(nextMax)
                            )
                        )
                    },
                    valueRange = 1f..6f,
                    modifier = Modifier.testTag("slider_max_posts_cycle")
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Daily Publishing Cap (24h Window)", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("${config.dailyPostingLimit} reels/day", style = MaterialTheme.typography.labelMedium, color = ElectricViolet)
                }
                Slider(
                    value = config.dailyPostingLimit.toFloat(),
                    onValueChange = {
                        onUpdateConfig(config.copy(dailyPostingLimit = it.roundToInt().coerceIn(1, 15)))
                    },
                    valueRange = 1f..15f,
                    modifier = Modifier.testTag("slider_daily_limit")
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Delay Between Posts (DELAY_BETWEEN_POSTS)", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("${config.delayBetweenPostsSeconds}s", style = MaterialTheme.typography.labelMedium, color = CyberMint)
                }
                Slider(
                    value = config.delayBetweenPostsSeconds.toFloat(),
                    onValueChange = {
                        onUpdateConfig(config.copy(delayBetweenPostsSeconds = (it.roundToInt() / 15) * 15))
                    },
                    valueRange = 15f..180f
                )
            }
        }
    }
}

@Composable
private fun PostingWindowsCard(
    windows: List<PostingWindow>,
    onToggleWindow: (PostingWindow) -> Unit
) {
    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ObsidianBorder, RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Active Posting Windows (Asia/Kolkata IST)",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                text = "GitHub Actions cycles only publish inside enabled IST windows:",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            windows.forEach { win ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianCardElevated)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = win.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = win.formattedRange(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (win.isEnabled) CyberMint else TextSecondary
                        )
                    }
                    StatusPillBadge(
                        text = if (win.isEnabled) "ACTIVE" else "MUTED",
                        color = if (win.isEnabled) CyberMint else ReelCoral,
                        containerColor = if (win.isEnabled) CyberMintSoft else ReelCoralSoft
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = win.isEnabled,
                        onCheckedChange = { onToggleWindow(win) },
                        modifier = Modifier.testTag("toggle_window_${win.id}")
                    )
                }
            }
        }
    }
}
