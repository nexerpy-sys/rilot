package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ContentSourceType
import com.example.model.DiscoveryConfig
import com.example.model.HashtagTarget
import com.example.model.SelectionStrategy
import com.example.model.ValidationResult
import com.example.ui.components.StatusPillBadge
import com.example.ui.components.ValidationBannerCard
import com.example.ui.theme.CyberMint
import com.example.ui.theme.CyberMintSoft
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.ElectricVioletSoft
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianCardElevated
import com.example.ui.theme.ReelCoral
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverySettingsScreen(
    hashtags: List<HashtagTarget>,
    config: DiscoveryConfig,
    maxPostsPerCycle: Int,
    validation: ValidationResult,
    onUpdateConfig: (DiscoveryConfig) -> Unit,
    onAddHashtag: (String, String, Int, Long) -> Unit,
    onToggleHashtag: (HashtagTarget) -> Unit,
    onDeleteHashtag: (Long) -> Unit
) {
    var showAddHashtagDialog by remember { mutableStateOf(false) }
    var selectedCategoryFilter by remember { mutableStateOf<String?>(null) }

    val categories = remember(hashtags) {
        hashtags.map { it.category }.distinct().sorted()
    }
    val visibleHashtags = remember(hashtags, selectedCategoryFilter) {
        if (selectedCategoryFilter == null) hashtags
        else hashtags.filter { it.category == selectedCategoryFilter }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("discovery_settings_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ValidationBannerCard(validation = validation)
        }

        item {
            HashtagDirectoryCard(
                hashtags = visibleHashtags,
                allCategories = categories,
                selectedCategory = selectedCategoryFilter,
                onSelectCategory = { selectedCategoryFilter = it },
                onOpenAddDialog = { showAddHashtagDialog = true },
                onToggleHashtag = onToggleHashtag,
                onDeleteHashtag = onDeleteHashtag
            )
        }

        item {
            CandidateLimitsAndSourceCard(
                config = config,
                maxPostsPerCycle = maxPostsPerCycle,
                onUpdateConfig = onUpdateConfig
            )
        }

        item {
            QualifyingPoolScoringCard(
                config = config,
                onUpdateConfig = onUpdateConfig
            )
        }

        item {
            DuplicateAndDownloaderGuardsCard(
                config = config,
                onUpdateConfig = onUpdateConfig
            )
        }
    }

    if (showAddHashtagDialog) {
        AddHashtagDialog(
            existingCategories = categories,
            onConfirm = { tag, cat, limit, minViews ->
                onAddHashtag(tag, cat, limit, minViews)
                showAddHashtagDialog = false
            },
            onDismiss = { showAddHashtagDialog = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HashtagDirectoryCard(
    hashtags: List<HashtagTarget>,
    allCategories: List<String>,
    selectedCategory: String?,
    onSelectCategory: (String?) -> Unit,
    onOpenAddDialog: () -> Unit,
    onToggleHashtag: (HashtagTarget) -> Unit,
    onDeleteHashtag: (Long) -> Unit
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
                        imageVector = Icons.Default.Tag,
                        contentDescription = "Hashtags",
                        tint = ReelCoral,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Hashtags & Content Categories",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "${hashtags.count { it.isEnabled }} active of ${hashtags.size} shown",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Button(
                    onClick = onOpenAddDialog,
                    colors = ButtonDefaults.buttonColors(containerColor = ReelCoral),
                    modifier = Modifier.testTag("add_hashtag_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Hashtag", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { onSelectCategory(null) },
                    label = { Text("All Categories") }
                )
                allCategories.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { onSelectCategory(if (selectedCategory == cat) null else cat) },
                        label = { Text(cat) }
                    )
                }
            }

            hashtags.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianCardElevated)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "#${item.tag}",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (item.isEnabled) CyberMint else TextMuted,
                                fontWeight = FontWeight.Bold
                            )
                            StatusPillBadge(
                                text = item.category,
                                color = ElectricViolet,
                                containerColor = ElectricVioletSoft
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Collect up to ${item.candidateLimitPerRun} candidates • Min ${item.minViewsThreshold / 1000}K views",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = item.isEnabled,
                            onCheckedChange = { onToggleHashtag(item) },
                            modifier = Modifier.testTag("toggle_hashtag_${item.tag}")
                        )
                        IconButton(
                            onClick = { onDeleteHashtag(item.id) },
                            modifier = Modifier.testTag("delete_hashtag_${item.tag}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete #${item.tag}",
                                tint = TextMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateLimitsAndSourceCard(
    config: DiscoveryConfig,
    maxPostsPerCycle: Int,
    onUpdateConfig: (DiscoveryConfig) -> Unit
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
                    imageVector = Icons.Default.FilterCenterFocus,
                    contentDescription = "Collection Limits",
                    tint = CyberMint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Collection Limits vs Publishing Limits",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Independent from Max Posts Per Cycle ($maxPostsPerCycle posts/cycle)",
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
                    Text("Candidate Collection Limit / Hashtag", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("${config.candidateCollectionLimitPerTag} medias", style = MaterialTheme.typography.labelMedium, color = CyberMint)
                }
                Slider(
                    value = config.candidateCollectionLimitPerTag.toFloat(),
                    onValueChange = {
                        onUpdateConfig(config.copy(candidateCollectionLimitPerTag = it.roundToInt().coerceIn(5, 80)))
                    },
                    valueRange = 5f..80f,
                    modifier = Modifier.testTag("slider_collection_limit")
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Qualifying Candidate Pool Size", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("${config.qualifyingPoolSize} candidates", style = MaterialTheme.typography.labelMedium, color = ElectricViolet)
                }
                Slider(
                    value = config.qualifyingPoolSize.toFloat(),
                    onValueChange = {
                        onUpdateConfig(config.copy(qualifyingPoolSize = it.roundToInt().coerceIn(2, 30)))
                    },
                    valueRange = 2f..30f,
                    modifier = Modifier.testTag("slider_pool_size")
                )
            }

            Text(
                text = "Content Source Mode",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            ContentSourceType.entries.forEach { source ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (config.contentSource == source) CyberMintSoft else ObsidianCardElevated)
                        .border(
                            1.dp,
                            if (config.contentSource == source) CyberMint else ObsidianBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(source.label, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(source.description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    FilterChip(
                        selected = config.contentSource == source,
                        onClick = { onUpdateConfig(config.copy(contentSource = source)) },
                        label = { Text(if (config.contentSource == source) "Active" else "Select") }
                    )
                }
            }
        }
    }
}

@Composable
private fun QualifyingPoolScoringCard(
    config: DiscoveryConfig,
    onUpdateConfig: (DiscoveryConfig) -> Unit
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
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Qualifying Pool Selection",
                    tint = ElectricViolet,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Qualifying Pool & Diversity Scoring",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Selects from qualifying pool instead of naively picking top-2 highest views",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            SelectionStrategy.entries.forEach { strategy ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (config.selectionStrategy == strategy) ElectricVioletSoft else ObsidianCardElevated)
                        .border(
                            1.dp,
                            if (config.selectionStrategy == strategy) ElectricViolet else ObsidianBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strategy.label, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(strategy.description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    FilterChip(
                        selected = config.selectionStrategy == strategy,
                        onClick = { onUpdateConfig(config.copy(selectionStrategy = strategy)) },
                        label = { Text(if (config.selectionStrategy == strategy) "Active" else "Use") }
                    )
                }
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Min Quality View Threshold", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("${config.minViewCountThreshold / 1000}K views", style = MaterialTheme.typography.labelMedium, color = CyberMint)
                }
                Slider(
                    value = (config.minViewCountThreshold / 1000f).coerceIn(5f, 200f),
                    onValueChange = {
                        onUpdateConfig(config.copy(minViewCountThreshold = (it.roundToInt() * 1000L)))
                    },
                    valueRange = 5f..200f
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Min Like-to-View Engagement Ratio", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("%.1f%%".format(config.minLikeToViewRatioPercent), style = MaterialTheme.typography.labelMedium, color = CyberMint)
                }
                Slider(
                    value = config.minLikeToViewRatioPercent,
                    onValueChange = {
                        onUpdateConfig(config.copy(minLikeToViewRatioPercent = (it * 10).roundToInt() / 10f))
                    },
                    valueRange = 1.0f..12.0f
                )
            }

            WeightSliderRow(
                label = "Recency Weighting",
                value = config.recencyWeightPercent,
                onValueChange = { onUpdateConfig(config.copy(recencyWeightPercent = it)) }
            )
            WeightSliderRow(
                label = "Engagement Velocity Weighting",
                value = config.engagementWeightPercent,
                onValueChange = { onUpdateConfig(config.copy(engagementWeightPercent = it)) }
            )
            WeightSliderRow(
                label = "Content Category Diversity Weighting",
                value = config.diversityWeightPercent,
                onValueChange = { onUpdateConfig(config.copy(diversityWeightPercent = it)) }
            )
        }
    }
}

@Composable
private fun WeightSliderRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text("$value%", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(5, 80)) },
            valueRange = 5f..80f
        )
    }
}

@Composable
private fun DuplicateAndDownloaderGuardsCard(
    config: DiscoveryConfig,
    onUpdateConfig: (DiscoveryConfig) -> Unit
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
                text = "Deduplication & Downloader Pipeline",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )

            ToggleRowItem(
                title = "Exclude Previously Processed Reels (posted.db)",
                subtitle = "Checks SQLite already_posted(media.pk) before downloading",
                checked = config.excludePreviouslyProcessed,
                onCheckedChange = { onUpdateConfig(config.copy(excludePreviouslyProcessed = it)) },
                tag = "switch_exclude_processed"
            )

            ToggleRowItem(
                title = "Avoid Creator Duplicates in Cycle Window",
                subtitle = "Prevents posting multiple Reels from the same @handle in a single cycle",
                checked = config.avoidSameCreatorInWindow,
                onCheckedChange = { onUpdateConfig(config.copy(avoidSameCreatorInWindow = it)) },
                tag = "switch_avoid_creator_dup"
            )

            ToggleRowItem(
                title = "yt-dlp Primary with instagrapi Fallback",
                subtitle = "Matches downloader.py: tries yt-dlp first, falls back to client.clip_download",
                checked = config.preferYtdlpWithInstagrapiFallback,
                onCheckedChange = { onUpdateConfig(config.copy(preferYtdlpWithInstagrapiFallback = it)) },
                tag = "switch_prefer_ytdlp"
            )
        }
    }
}

@Composable
private fun ToggleRowItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ObsidianCardElevated)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun AddHashtagDialog(
    existingCategories: List<String>,
    onConfirm: (String, String, Int, Long) -> Unit,
    onDismiss: () -> Unit
) {
    var tagInput by remember { mutableStateOf("") }
    var categoryInput by remember { mutableStateOf(existingCategories.firstOrNull() ?: "Strength & Conditioning") }
    var limitInput by remember { mutableStateOf("25") }
    var minViewsInput by remember { mutableStateOf("20000") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        title = {
            Text("Add Hashtag Target", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    label = { Text("Hashtag (without #)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_hashtag_input")
                )
                OutlinedTextField(
                    value = categoryInput,
                    onValueChange = { categoryInput = it },
                    label = { Text("Content Category") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_category_input")
                )
                OutlinedTextField(
                    value = limitInput,
                    onValueChange = { limitInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Candidate Collection Limit (5–100)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = minViewsInput,
                    onValueChange = { minViewsInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Minimum View Count Threshold") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val limit = limitInput.toIntOrNull() ?: 25
                    val minViews = minViewsInput.toLongOrNull() ?: 20_000L
                    onConfirm(tagInput, categoryInput, limit, minViews)
                },
                enabled = tagInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ReelCoral),
                modifier = Modifier.testTag("confirm_add_hashtag_button")
            ) {
                Text("Add Hashtag")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
