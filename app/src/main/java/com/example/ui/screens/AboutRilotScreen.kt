package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.Code
import com.example.ui.components.PhoenixLogoBadge
import com.example.ui.theme.DashboardPillBg
import com.example.ui.theme.DashboardPillText
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianGroupedSurface
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.StudioEmeraldIconBg
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun AboutRilotScreen(
    onOpenWorkerContract: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("about_rilot_screen_list"),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
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
                        .padding(horizontal = 18.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Top Hero Summary Banner
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            PhoenixLogoBadge(
                                boxSize = 52.dp,
                                cornerRadius = 15.dp,
                                canvasSize = 33.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Rilot Automation Studio",
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontSize = 21.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "Kali studio automations • Developed by Nexer",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = DashboardPillText
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = DashboardPillBg,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = "By Nexer",
                                style = MaterialTheme.typography.labelSmall,
                                color = DashboardPillText,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

                    // SECTION 1 (ON TOP): The Work of the App
                    AboutBulletSectionCard(
                        badgeNumber = "01",
                        sectionTag = "CORE MISSION",
                        title = "The Work of Rilot",
                        subtitle = "What the application governs and accomplishes for your Instagram studio",
                        icon = Icons.Outlined.AutoGraph,
                        bullets = listOf(
                            "Remote Command & Telemetry Console: Serves as the dedicated native Android control panel for monitoring, starting, pausing, and auditing your automated Instagram Reels pipeline without running heavy scraping jobs on your phone.",
                            "Intelligent Content Curation: Governs hashtag targeting, category organization, and quality thresholds so the backend discovers high-performing Reels across both popular and recent feeds.",
                            "Qualifying Pool Selection (Beyond Raw Views): Replaces naive top-2 view-count picking with a multi-signal qualifying pool weighted by engagement velocity, upload recency, and content category diversity.",
                            "Viral Caption & Copypasta Management: Maintains a categorized library of reusable hooks, absurd viral copypastas, and hashtag templates with automated anti-repeat rotation and optional Claude AI rewriting.",
                            "Asia/Kolkata (IST) Schedule Governance: Enforces safe posting intervals, daily caps, IST peak-hour windows, and a default Dry-Run lock so automatic publishing never runs untested.",
                            "Idempotent Failure Recovery: Tracks every candidate URL, selection reason, duplicate skip, and download/upload error, allowing safe job retries without risking duplicate posts."
                        )
                    )

                    HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

                    // SECTION 2: The Flow of the App
                    AboutBulletSectionCard(
                        badgeNumber = "02",
                        sectionTag = "EXECUTION LIFECYCLE",
                        title = "The Operational Flow of Rilot",
                        subtitle = "End-to-end execution sequence from configuration to published Reel",
                        icon = Icons.Outlined.AccountTree,
                        bullets = listOf(
                            "Step 1 — Policy & Schedule Configuration: You configure target hashtags, qualifying pool weights, caption templates, and Asia/Kolkata (IST) posting windows inside Rilot. Settings are persisted locally in Room and synced to the Google Apps Script Web App + Google Sheets backend.",
                            "Step 2 — Scheduled Workflow Trigger: GitHub Actions triggers the Python InstaAutomation engine on schedule (or on-demand when you tap Start Cycle in Rilot) and fetches the active configuration from Google Apps Script.",
                            "Step 3 — Discovery & Qualifying Pool Ranking: scraper.py queries Instagram hashtags, filters out low-engagement clips, and scores qualifying candidates across recency, like/comment ratio, and category diversity.",
                            "Step 4 — SQLite Deduplication Guard: Before downloading any video, db.py verifies the Reel primary key (PK) against posted.db and creator window history to guarantee zero accidental reposts.",
                            "Step 5 — Resilient Media Acquisition: downloader.py downloads the selected Reel via yt-dlp as the primary engine and automatically falls back to instagrapi clip_download if rate-limited.",
                            "Step 6 — Caption Assembly & Publishing Gate: rewriter.py applies either a non-repeating fixed/copypasta template or Claude Sonnet rewrite, appends hashtag templates, and—only when Dry-Run is off and Auto-Publish is verified—uploads via uploader.py and cleans up local files."
                        )
                    )

                    HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

                    // SECTION 3: The Architecture & Structure of the App
                    AboutBulletSectionCard(
                        badgeNumber = "03",
                        sectionTag = "SYSTEM DESIGN",
                        title = "Architecture & Structure of the App",
                        subtitle = "Clean MVVM Android client decoupled from the cloud execution engine",
                        icon = Icons.Outlined.Layers,
                        bullets = listOf(
                            "Presentation Layer (Jetpack Compose + Material 3): Modular dark-studio screens (Dashboard, Discovery, Selection Engine, Caption Library, Posting Schedule, Activity & Errors) driven by unidirectional StateFlow in RilotViewModel.",
                            "Domain & Validation Layer (ConfigValidator + Typed Models): Pure Kotlin validation enforcing independent collection vs. publishing limits, Asia/Kolkata timezone rules, Instagram rate-limit safety warnings, and anti-repeat caption selection.",
                            "Offline-First Persistence Layer (Room + SharedPreferences): Local SQLite tables (hashtag_targets, caption_templates, activity_candidates) cache all configurations and last known telemetry so Rilot remains fully functional when the backend is unreachable.",
                            "Backend Gateway Layer (Google Apps Script Web App + Google Sheets): Dedicated JSON RPC client and Android Keystore secret store isolate all network calls behind your authenticated Google Apps Script Web App (/exec).",
                            "Backend Execution Engine (GitHub Actions + Python InstaAutomation): Runs auth.py, scraper.py, downloader.py, rewriter.py, uploader.py, and db.py in isolated cloud runners on nexerpy-sys/rilot (main)."
                        )
                    )

                    HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

                    // SECTION 4: Security & Credential Boundary
                    AboutBulletSectionCard(
                        badgeNumber = "04",
                        sectionTag = "SECURITY BOUNDARY",
                        title = "Zero-Credential APK Guarantee",
                        subtitle = "Strict separation between mobile client controls and privileged secrets",
                        icon = Icons.Outlined.VerifiedUser,
                        bullets = listOf(
                            "No Secrets in the APK: Instagram passwords, session.json cookies, GitHub personal access tokens, Anthropic API keys, and backend admin secrets are never embedded in the Android application.",
                            "Honest Backend State Reporting: Rilot clearly distinguishes between Mock Preview data, Live Connected telemetry, Offline Cached fallback, and Expired Session/2FA states.",
                            "Safe Retry Idempotency: Retrying a failed download or upload attaches an idempotency token and requires posted.db verification before re-attempting execution."
                        )
                    )

                    HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

                    // SECTION 5: Developer & Studio Credits
                    AboutBulletSectionCard(
                        badgeNumber = "05",
                        sectionTag = "DEVELOPER CREDITS",
                        title = "Crafted by Nexer",
                        subtitle = "Official creator & systems architect of Kali studio automations",
                        icon = Icons.Outlined.Code,
                        bullets = listOf(
                            "Lead Developer: Nexer — Creator and architect of the Rilot Android control plane, Google Apps Script + Sheets gateway, and Kali studio automations pipeline.",
                            "Studio Identity: Kali studio automations — Engineered with the Phoenix emblem for resilient, high-precision Instagram Reels discovery and publishing."
                        )
                    )

                    OutlinedButton(
                        onClick = onOpenWorkerContract,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("about_inspect_worker_contract_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = "Inspect Google Apps Script API Contract",
                            tint = DashboardPillText,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Inspect Google Apps Script & API Contract",
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutBulletSectionCard(
    badgeNumber: String,
    sectionTag: String,
    title: String,
    subtitle: String,
    icon: ImageVector,
    bullets: List<String>
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(StudioEmeraldIconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "$badgeNumber • $sectionTag",
                        style = MaterialTheme.typography.labelSmall,
                        color = DashboardPillText,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextPrimary
                    )
                }
            }
        }

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Surface(
            color = ObsidianGroupedSurface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ObsidianBorder, RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                bullets.forEachIndexed { index, itemText ->
                    val parts = itemText.split(":", limit = 2)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 7.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(DashboardPillText)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            if (parts.size == 2) {
                                Text(
                                    text = parts[0].trim(),
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = parts[1].trim(),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        lineHeight = 20.sp
                                    ),
                                    color = TextSecondary
                                )
                            } else {
                                Text(
                                    text = itemText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    if (index < bullets.lastIndex) {
                        HorizontalDivider(
                            color = ObsidianCard,
                            thickness = 1.dp
                        )
                    }
                }
            }
        }
    }
}
