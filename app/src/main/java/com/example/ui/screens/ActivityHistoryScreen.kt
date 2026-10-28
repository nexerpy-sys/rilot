package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ActivityCandidateRecord
import com.example.model.CandidateOutcomeStatus
import com.example.ui.components.StatusPillBadge
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.AmberWarningSoft
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CrimsonErrorSoft
import com.example.ui.theme.CyberMint
import com.example.ui.theme.CyberMintSoft
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.ElectricVioletSoft
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianCardElevated
import com.example.ui.theme.ReelCoral
import com.example.ui.theme.ReelCoralSoft
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

private enum class ActivityFilter(val label: String) {
    ALL("All Candidates"),
    PUBLISHED_AND_DRY_RUN("Published / Dry-Run"),
    FAILED_RETRYABLE("Failed (Retryable)"),
    DUPLICATE_SKIPS("Duplicate Skips")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityHistoryScreen(
    records: List<ActivityCandidateRecord>,
    retryingPk: String?,
    onSafeRetryCandidate: (String) -> Unit
) {
    var activeFilter by remember { mutableStateOf(ActivityFilter.ALL) }
    var expandedPk by remember { mutableStateOf<String?>(records.firstOrNull()?.reelPk) }

    val filteredRecords = remember(records, activeFilter) {
        records.filter { rec ->
            when (activeFilter) {
                ActivityFilter.ALL -> true
                ActivityFilter.PUBLISHED_AND_DRY_RUN ->
                    rec.outcomeStatus == CandidateOutcomeStatus.PUBLISHED ||
                        rec.outcomeStatus == CandidateOutcomeStatus.DRY_RUN_VALIDATED
                ActivityFilter.FAILED_RETRYABLE -> rec.outcomeStatus.isRetryable
                ActivityFilter.DUPLICATE_SKIPS ->
                    rec.outcomeStatus == CandidateOutcomeStatus.SKIPPED_DUPLICATE_PK ||
                        rec.outcomeStatus == CandidateOutcomeStatus.FILTERED_QUALITY
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("activity_history_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Activity Log",
                            tint = CyberMint,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Candidate Discovery & Run History",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Inspect URLs, qualifying pool selection reasons, posted.db duplicate skips & safe retries",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ActivityFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = activeFilter == filter,
                                onClick = { activeFilter = filter },
                                label = { Text(filter.label) },
                                modifier = Modifier.testTag("activity_filter_${filter.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        }

        items(filteredRecords, key = { it.reelPk }) { record ->
            CandidateActivityCard(
                record = record,
                isExpanded = expandedPk == record.reelPk,
                isRetrying = retryingPk == record.reelPk,
                onToggleExpand = {
                    expandedPk = if (expandedPk == record.reelPk) null else record.reelPk
                },
                onSafeRetry = { onSafeRetryCandidate(record.reelPk) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandidateActivityCard(
    record: ActivityCandidateRecord,
    isExpanded: Boolean,
    isRetrying: Boolean,
    onToggleExpand: () -> Unit,
    onSafeRetry: () -> Unit
) {
    val context = LocalContext.current
    val (statusColor, statusBg) = when (record.outcomeStatus) {
        CandidateOutcomeStatus.PUBLISHED -> CyberMint to CyberMintSoft
        CandidateOutcomeStatus.DRY_RUN_VALIDATED -> ElectricViolet to ElectricVioletSoft
        CandidateOutcomeStatus.QUALIFIED_QUEUED -> CyberMint to CyberMintSoft
        CandidateOutcomeStatus.SKIPPED_DUPLICATE_PK -> AmberWarning to AmberWarningSoft
        CandidateOutcomeStatus.FILTERED_QUALITY -> AmberWarning to AmberWarningSoft
        CandidateOutcomeStatus.UNAUTHORIZED_BLOCKED -> AmberWarning to AmberWarningSoft
        CandidateOutcomeStatus.IN_FLIGHT_AMBIGUOUS -> CrimsonError to CrimsonErrorSoft
        CandidateOutcomeStatus.RETRY_DISPATCHED -> ElectricViolet to ElectricVioletSoft
        CandidateOutcomeStatus.DOWNLOAD_FAILED -> CrimsonError to CrimsonErrorSoft
        CandidateOutcomeStatus.UPLOAD_FAILED -> CrimsonError to CrimsonErrorSoft
    }

    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ObsidianBorder, RoundedCornerShape(16.dp))
            .clickable { onToggleExpand() }
            .testTag("candidate_card_${record.reelPk}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "#${record.hashtag}",
                            style = MaterialTheme.typography.titleSmall,
                            color = CyberMint,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = record.creatorHandle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Text(
                            text = "• ${record.durationSeconds}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "PK: ${record.reelPk} • ${record.timestampIst}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse Details" else "Expand Details",
                    tint = TextSecondary
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusPillBadge(
                    text = record.outcomeStatus.label,
                    color = statusColor,
                    containerColor = statusBg
                )
                StatusPillBadge(
                    text = "Pool Score: ${record.qualityScore}",
                    color = ReelCoral,
                    containerColor = ReelCoralSoft
                )
                StatusPillBadge(
                    text = "${formatCompactCount(record.viewCount)} views • ${formatCompactCount(record.likeCount)} likes",
                    color = ElectricViolet,
                    containerColor = ElectricVioletSoft
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianCardElevated)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Candidate URL: ${record.reelUrl}",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberMint
                    )

                    Text(
                        text = "Selection / Filter Reason: ${record.selectionReason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )

                    Text(
                        text = "Downloader Stage: ${record.downloaderUsed}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    if (!record.failureReason.isNullOrBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(CrimsonErrorSoft)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "Failure / Skip Reason: ${record.failureReason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = CrimsonError
                            )
                        }
                    }

                    Text(
                        text = "Caption Preview:\n${record.captionPreview}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(record.reelUrl))
                                    context.startActivity(intent)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Inspect URL",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Reel URL")
                        }

                        if (record.outcomeStatus.isRetryable) {
                            Button(
                                onClick = onSafeRetry,
                                enabled = !isRetrying,
                                colors = ButtonDefaults.buttonColors(containerColor = CyberMint),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("safe_retry_button_${record.reelPk}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Replay,
                                    contentDescription = "Safe Idempotent Retry",
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isRetrying) "Retrying..." else "Safe Retry",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (record.outcomeStatus.isRetryable) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = "Idempotent Guard",
                                tint = CyberMint,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Idempotency guard (${record.idempotencyToken}): verifies PK is absent in posted.db before retry.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatCompactCount(count: Long): String {
    return when {
        count >= 1_000_000L -> "%.2fM".format(count / 1_000_000.0)
        count >= 1_000L -> "%.1fK".format(count / 1_000.0)
        else -> count.toString()
    }
}
