package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.config.WorkerEndpointConfig
import com.example.model.DataSourceMode
import com.example.model.ValidationResult
import com.example.network.AppsScriptConnectionTestDto
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.AmberWarningSoft
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CrimsonErrorSoft
import com.example.ui.theme.CyberMint
import com.example.ui.theme.DashboardPillBg
import com.example.ui.theme.DashboardPillText
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianCardElevated
import com.example.ui.theme.ObsidianGroupedSurface
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.StudioEmeraldIconBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.RilotTab

/**
 * Stylish hamburger button whose custom 3-bar icon fluidly tilts 90° when the menu opens
 * and tilts back to 0° when the menu closes.
 */
@Composable
fun AnimatedHamburgerMenuButton(
    isOpen: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotationDeg by animateFloatAsState(
        targetValue = if (isOpen) 90f else 0f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "hamburger_90_tilt_rotation"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isOpen) DashboardPillText else ObsidianBorder,
        label = "hamburger_border_color"
    )

    val containerColor by animateColorAsState(
        targetValue = if (isOpen) DashboardPillBg else ObsidianCard,
        label = "hamburger_container_color"
    )

    val lineColor by animateColorAsState(
        targetValue = if (isOpen) DashboardPillText else TextPrimary,
        label = "hamburger_line_color"
    )

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .size(48.dp)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { onToggle() }
            .semantics {
                contentDescription = if (isOpen) "Close navigation menu" else "Open navigation menu"
            }
            .testTag("hamburger_menu_button")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = rotationDeg
                }
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                val strokeWidth = 2.2.dp.toPx()
                val h = size.height
                val w = size.width
                // Top bar
                drawLine(
                    color = lineColor,
                    start = Offset(w * 0.10f, h * 0.24f),
                    end = Offset(w * 0.90f, h * 0.24f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                // Middle bar
                drawLine(
                    color = lineColor,
                    start = Offset(w * 0.18f, h * 0.50f),
                    end = Offset(w * 0.82f, h * 0.50f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                // Bottom bar
                drawLine(
                    color = lineColor,
                    start = Offset(w * 0.10f, h * 0.76f),
                    end = Offset(w * 0.90f, h * 0.76f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Kali-Hunter inspired emerald-green Phoenix emblem rendered on a pitch-black obsidian badge.
 */
@Composable
fun PhoenixLogoBadge(
    modifier: Modifier = Modifier,
    boxSize: Dp = 54.dp,
    cornerRadius: Dp = 15.dp,
    canvasSize: Dp = 34.dp
) {
    Box(
        modifier = modifier
            .size(boxSize)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0D1412),
                        Color(0xFF060809),
                        Color(0xFF030405)
                    )
                )
            )
            .border(1.2.dp, CyberMint.copy(alpha = 0.55f), RoundedCornerShape(cornerRadius))
            .semantics { contentDescription = "Rilot Phoenix Logo" }
            .testTag("phoenix_logo_badge"),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(canvasSize * 1.45f)
        )
    }
}

@Composable
fun StylishHamburgerMenuOverlay(
    isOpen: Boolean,
    currentTab: RilotTab,
    dataSourceMode: DataSourceMode,
    onSelectTab: (RilotTab) -> Unit,
    onDismiss: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)),
            exit = fadeOut(spring(stiffness = Spring.StiffnessMedium))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.76f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
                    .testTag("hamburger_menu_scrim")
            )
        }

        AnimatedVisibility(
            visible = isOpen,
            enter = slideInVertically(
                initialOffsetY = { -it / 3 },
                animationSpec = spring(
                    dampingRatio = 0.78f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(),
            exit = slideOutVertically(
                targetOffsetY = { -it / 3 },
                animationSpec = spring(
                    dampingRatio = 0.85f,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Surface(
                color = ObsidianSurface,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ObsidianBorder, RoundedCornerShape(26.dp))
                    .testTag("hamburger_menu_sheet")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 18.dp)
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
                            PhoenixLogoBadge(
                                boxSize = 42.dp,
                                cornerRadius = 12.dp,
                                canvasSize = 27.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Rilot Menu",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${dataSourceMode.badgeText} • By Nexer",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DashboardPillText
                                )
                            }
                        }
                        AnimatedHamburgerMenuButton(
                            isOpen = isOpen,
                            onToggle = onDismiss
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(14.dp))

                    val isAboutActive = currentTab == RilotTab.ABOUT_RILOT
                    Surface(
                        color = ObsidianGroupedSurface,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                if (isAboutActive) DashboardPillText else ObsidianBorder,
                                RoundedCornerShape(20.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isAboutActive) DashboardPillBg.copy(alpha = 0.55f) else Color.Transparent
                                )
                                .clickable {
                                    onSelectTab(RilotTab.ABOUT_RILOT)
                                    onDismiss()
                                }
                                .padding(horizontal = 18.dp, vertical = 18.dp)
                                .testTag("menu_item_about_rilot"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PhoenixLogoBadge(
                                boxSize = 44.dp,
                                cornerRadius = 13.dp,
                                canvasSize = 28.dp
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "About Rilot",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (isAboutActive) DashboardPillText else TextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "The work of the app, operational flow, architecture & developer credits (Nexer)",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp
                                    ),
                                    color = TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = "Open About Rilot",
                                tint = TextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Developed by Nexer • Kali studio automations",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

@Composable
fun StudioSubscreenHeader(
    title: String,
    subtitle: String,
    badgeText: String,
    isMenuOpen: Boolean,
    onToggleMenu: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp)
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
                Surface(
                    color = ObsidianCard,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .size(48.dp)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(14.dp))
                        .clickable { onBack() }
                        .testTag("back_to_dashboard_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Dashboard",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextPrimary
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1
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
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = DashboardPillText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                AnimatedHamburgerMenuButton(
                    isOpen = isMenuOpen,
                    onToggle = onToggleMenu
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)
    }
}

@Composable
fun ToastFeedbackBanner(
    statusBannerMessage: String?,
    isErrorBanner: Boolean,
    onDismissBanner: () -> Unit
) {
    if (statusBannerMessage.isNullOrBlank()) return

    val toastBg = if (isErrorBanner) CrimsonErrorSoft else DashboardPillBg
    val toastBorder = if (isErrorBanner) CrimsonError else StudioEmeraldIconBg
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(toastBg)
            .border(1.dp, toastBorder.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("feedback_toast_banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = statusBannerMessage,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onDismissBanner,
            modifier = Modifier.size(26.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss notification",
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun ValidationBannerCard(
    validation: ValidationResult,
    modifier: Modifier = Modifier
) {
    if (validation.errors.isEmpty() && validation.warnings.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        validation.errors.forEach { err ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CrimsonErrorSoft)
                    .border(1.dp, CrimsonError.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Configuration Error",
                    tint = CrimsonError,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
            }
        }
        validation.warnings.forEach { warn ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AmberWarningSoft)
                    .border(1.dp, AmberWarning.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = "Safety Warning",
                    tint = AmberWarning,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = warn,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
fun StatusPillBadge(
    text: String,
    color: Color,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun WorkerContractDialog(
    dataSourceMode: DataSourceMode,
    workerConfig: WorkerEndpointConfig,
    connectionTestResult: AppsScriptConnectionTestDto? = null,
    onSelectMode: (DataSourceMode) -> Unit,
    onSaveEndpoint: (String, String, String) -> Result<Unit>,
    onTestConnection: (String, String, String) -> Result<Unit> = { _, _, _ -> Result.success(Unit) },
    onClearCredentials: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var urlInput by remember(workerConfig.workerBaseUrl) { mutableStateOf(workerConfig.workerBaseUrl) }
    var repoInput by remember(workerConfig.githubRepoSlug) { mutableStateOf(workerConfig.githubRepoSlug) }
    var tokenInput by remember(workerConfig.runtimeClientToken) { mutableStateOf(workerConfig.runtimeClientToken) }
    var revealApiKey by remember { mutableStateOf(false) }
    var keystoreErrorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Google Apps Script Web App Gateway",
                    tint = CyberMint,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Google Apps Script & API Contract",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Rilot never embeds Instagram passwords, session.json cookies, GitHub PATs, or backend secrets in the APK. Your Rilot App API Key is encrypted via Android Keystore on-device, and all privileged actions run in GitHub Actions via your Google Apps Script Web App + Google Sheets backend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Text(
                    text = "Data Source Mode",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = dataSourceMode == DataSourceMode.MOCK_PREVIEW,
                        onClick = { onSelectMode(DataSourceMode.MOCK_PREVIEW) },
                        label = { Text("Mock Preview") },
                        modifier = Modifier.testTag("mode_chip_mock")
                    )
                    FilterChip(
                        selected = dataSourceMode.isLiveBackend,
                        onClick = { onSelectMode(DataSourceMode.GOOGLE_APPS_SCRIPT) },
                        label = { Text("Live Apps Script API") },
                        modifier = Modifier.testTag("mode_chip_live")
                    )
                }

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        keystoreErrorMessage = null
                    },
                    label = { Text("Apps Script Web App URL (/exec)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("worker_url_input")
                )

                OutlinedTextField(
                    value = repoInput,
                    onValueChange = {
                        repoInput = it
                        keystoreErrorMessage = null
                    },
                    label = { Text("GitHub Repo Reference (Display Only — Locked in Apps Script)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("github_repo_input")
                )

                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = {
                        tokenInput = it
                        keystoreErrorMessage = null
                    },
                    label = { Text("Rilot App API Key (RILOT_APP_API_KEY)") },
                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Text(
                            text = if (revealApiKey) "Hide" else "Show",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberMint,
                            modifier = Modifier
                                .clickable { revealApiKey = !revealApiKey }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("worker_token_input")
                )

                if (keystoreErrorMessage != null) {
                    Surface(
                        color = CrimsonError.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CrimsonError.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("keystore_error_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = "Keystore Error",
                                tint = CrimsonError,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = keystoreErrorMessage ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = CrimsonError
                            )
                        }
                    }
                }

                Text(
                    text = "Saved Key Preview (Android Keystore): ${workerConfig.maskedTokenPreview}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            keystoreErrorMessage = null
                            val testRes = onTestConnection(urlInput, repoInput, tokenInput)
                            if (testRes.isFailure) {
                                keystoreErrorMessage = testRes.exceptionOrNull()?.message
                                    ?: "Failed to securely save API key in Android Keystore."
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("test_connection_button")
                    ) {
                        Text("Test Connection", color = CyberMint)
                    }
                    OutlinedButton(
                        onClick = {
                            keystoreErrorMessage = null
                            tokenInput = ""
                            urlInput = WorkerEndpointConfig.DEFAULT_PLACEHOLDER_URL
                            onClearCredentials()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("clear_worker_key_button")
                    ) {
                        Text("Clear Key / Disconnect", color = CrimsonError)
                    }
                }

                if (connectionTestResult != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianCardElevated)
                            .border(1.dp, CyberMint.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .testTag("connection_test_result_card"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Apps Script Connection Diagnostics",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberMint,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "• Apps Script Reachable: ${if (connectionTestResult.appsScriptReachable) "YES" else "NO"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "• Spreadsheet Initialized: ${if (connectionTestResult.spreadsheetInitialized) "YES (${connectionTestResult.sheetTabsReady.size} tabs)" else "NO"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "• GitHub Dispatch Config Valid: ${if (connectionTestResult.githubDispatchValid) "YES (${connectionTestResult.githubDispatchTarget})" else "NO"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                        Text(
                            text = "• Engine Heartbeat / Last Sync: ${connectionTestResult.engineHeartbeatStatus} (${connectionTestResult.lastEngineSyncIst})",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Text(
                    text = "Defined JSON RPC Contract (POST /exec)",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                WorkerEndpointConfig.ENDPOINT_SPECS.forEach { spec ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ObsidianCardElevated)
                            .border(1.dp, ObsidianBorder, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "${spec.method} ${spec.path}",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberMint
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = spec.purpose,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    keystoreErrorMessage = null
                    val saveResult = onSaveEndpoint(urlInput, repoInput, tokenInput)
                    if (saveResult.isSuccess) {
                        onDismiss()
                    } else {
                        keystoreErrorMessage = saveResult.exceptionOrNull()?.message
                            ?: "Failed to securely save API key in Android Keystore."
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = StudioEmeraldIconBg),
                modifier = Modifier.testTag("save_worker_config_button")
            ) {
                Text("Save Endpoint", color = TextPrimary)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Close", color = TextMuted)
            }
        }
    )
}
