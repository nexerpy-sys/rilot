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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.CaptionCategory
import com.example.model.CaptionRewriteMode
import com.example.model.CaptionTemplate
import com.example.model.ConfigValidator
import com.example.ui.components.StatusPillBadge
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
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptionLibraryScreen(
    captions: List<CaptionTemplate>,
    rewriteMode: CaptionRewriteMode,
    selectedPreviewCaption: CaptionTemplate?,
    previewHashtag: String,
    onSelectRewriteMode: (CaptionRewriteMode) -> Unit,
    onRollRandomNonRepeating: (CaptionCategory?) -> Unit,
    onSetPreviewHashtag: (String) -> Unit,
    onSaveCaption: (Long, String, String, CaptionCategory, Boolean, String) -> Unit,
    onDeleteCaption: (Long) -> Unit
) {
    var selectedCategory by remember { mutableStateOf<CaptionCategory?>(null) }
    var onlyCopypasta by remember { mutableStateOf(false) }
    var editingCaption by remember { mutableStateOf<CaptionTemplate?>(null) }
    var showEditorDialog by remember { mutableStateOf(false) }

    val filteredCaptions = remember(captions, selectedCategory, onlyCopypasta) {
        captions.filter { item ->
            val matchCat = selectedCategory == null || item.category == selectedCategory
            val matchCopy = !onlyCopypasta || item.isAbsurdCopypasta
            matchCat && matchCopy
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("caption_library_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            CaptionModeSelectorCard(
                rewriteMode = rewriteMode,
                onSelectRewriteMode = onSelectRewriteMode
            )
        }

        item {
            LiveCaptionPreviewCard(
                selectedCaption = selectedPreviewCaption,
                previewHashtag = previewHashtag,
                selectedCategory = selectedCategory,
                onSetPreviewHashtag = onSetPreviewHashtag,
                onRollNext = { onRollRandomNonRepeating(selectedCategory) }
            )
        }

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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Reusable Captions & Viral Copypastas",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "${filteredCaptions.size} templates (${captions.count { it.isAbsurdCopypasta }} absurd copypastas)",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Button(
                            onClick = {
                                editingCaption = null
                                showEditorDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ReelCoral),
                            modifier = Modifier.testTag("new_caption_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "New Caption", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New")
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = onlyCopypasta,
                            onClick = { onlyCopypasta = !onlyCopypasta },
                            label = { Text("🔥 Absurd Copypasta Only") },
                            modifier = Modifier.testTag("filter_copypasta_chip")
                        )
                        FilterChip(
                            selected = selectedCategory == null,
                            onClick = { selectedCategory = null },
                            label = { Text("All Categories") }
                        )
                        CaptionCategory.entries.forEach { cat ->
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                                label = { Text(cat.label) }
                            )
                        }
                    }
                }
            }
        }

        items(filteredCaptions, key = { it.id }) { template ->
            CaptionItemCard(
                template = template,
                onEdit = {
                    editingCaption = template
                    showEditorDialog = true
                },
                onDelete = { onDeleteCaption(template.id) }
            )
        }
    }

    if (showEditorDialog) {
        CaptionEditorDialog(
            initial = editingCaption,
            onConfirm = { id, title, body, category, isCopypasta, tagTemplate ->
                onSaveCaption(id, title, body, category, isCopypasta, tagTemplate)
                showEditorDialog = false
            },
            onDismiss = { showEditorDialog = false }
        )
    }
}

@Composable
private fun CaptionModeSelectorCard(
    rewriteMode: CaptionRewriteMode,
    onSelectRewriteMode: (CaptionRewriteMode) -> Unit
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Caption Engine Mode",
                    tint = ElectricViolet,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Caption Engine Mode (rewriter.py)",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            }

            CaptionRewriteMode.entries.forEach { mode ->
                val isSelected = rewriteMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) ElectricVioletSoft else ObsidianCardElevated)
                        .border(
                            1.dp,
                            if (isSelected) ElectricViolet else ObsidianBorder,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(mode.label, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(mode.subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectRewriteMode(mode) },
                        label = { Text(if (isSelected) "Active" else "Select") }
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveCaptionPreviewCard(
    selectedCaption: CaptionTemplate?,
    previewHashtag: String,
    selectedCategory: CaptionCategory?,
    onSetPreviewHashtag: (String) -> Unit,
    onRollNext: () -> Unit
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
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Caption Preview",
                        tint = CyberMint,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Anti-Repeat Random Selector & Preview",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Excludes recently used captions automatically",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Button(
                    onClick = onRollNext,
                    colors = ButtonDefaults.buttonColors(containerColor = CyberMint),
                    modifier = Modifier.testTag("roll_random_caption_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Casino,
                        contentDescription = "Pick Non-Repeating Caption",
                        tint = ObsidianCard,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Roll Next", color = ObsidianCard, fontWeight = FontWeight.Bold)
                }
            }

            OutlinedTextField(
                value = previewHashtag,
                onValueChange = onSetPreviewHashtag,
                label = { Text("Preview Primary Hashtag Context") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (selectedCaption != null) {
                val formattedPreview = ConfigValidator.buildCaptionPreview(
                    template = selectedCaption,
                    sourceHashtag = previewHashtag,
                    creatorHandle = "@bar_kinetics"
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianCardElevated)
                        .border(1.dp, CyberMint.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                        .testTag("caption_preview_box"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Selected: ${selectedCaption.title}",
                            style = MaterialTheme.typography.labelLarge,
                            color = CyberMint
                        )
                        Text(
                            text = "Used ${selectedCaption.usageCount}x",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                    Text(
                        text = formattedPreview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptionItemCard(
    template: CaptionTemplate,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ObsidianBorder, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StatusPillBadge(
                            text = template.category.label,
                            color = ElectricViolet,
                            containerColor = ElectricVioletSoft
                        )
                        if (template.isAbsurdCopypasta) {
                            StatusPillBadge(
                                text = "ABSURD COPYPASTA",
                                color = ReelCoral,
                                containerColor = ReelCoralSoft
                            )
                        }
                        StatusPillBadge(
                            text = "Used ${template.usageCount}x",
                            color = AmberWarning,
                            containerColor = AmberWarningSoft
                        )
                    }
                }
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Caption", tint = TextSecondary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Caption", tint = TextMuted)
                    }
                }
            }

            Text(
                text = template.body,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )

            Text(
                text = "Hashtag Template: ${template.hashtagTemplate}",
                style = MaterialTheme.typography.labelSmall,
                color = CyberMint
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptionEditorDialog(
    initial: CaptionTemplate?,
    onConfirm: (Long, String, String, CaptionCategory, Boolean, String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(initial) { mutableStateOf(initial?.title ?: "") }
    var body by remember(initial) { mutableStateOf(initial?.body ?: "") }
    var category by remember(initial) { mutableStateOf(initial?.category ?: CaptionCategory.VIRAL_COPYPASTA) }
    var isCopypasta by remember(initial) { mutableStateOf(initial?.isAbsurdCopypasta ?: true) }
    var hashtagTemplate by remember(initial) {
        mutableStateOf(initial?.hashtagTemplate ?: "#reels #viral #gymtok #explorepage")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = ReelCoral)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (initial == null) "Create Caption / Copypasta" else "Edit Caption",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Caption Title") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("caption_title_input")
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Caption Body (supports {hashtag} & {creator})") },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("caption_body_input")
                )
                OutlinedTextField(
                    value = hashtagTemplate,
                    onValueChange = { hashtagTemplate = it },
                    label = { Text("Hashtag Template") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("caption_hashtags_input")
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isCopypasta,
                        onCheckedChange = { isCopypasta = it }
                    )
                    Text(
                        text = "Mark as Absurd Viral Copypasta",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CaptionCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(initial?.id ?: 0L, title, body, category, isCopypasta, hashtagTemplate)
                },
                enabled = title.isNotBlank() && body.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ReelCoral),
                modifier = Modifier.testTag("save_caption_button")
            ) {
                Text("Save Caption")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
