package com.example.linkpoint.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.linkpoint.cache.GridConfigEntity
import com.example.linkpoint.cache.SavedAccountEntity
import com.example.linkpoint.client.ViewerClient
import com.example.linkpoint.designsystem.DesignLanguage
import com.example.linkpoint.designsystem.LinkpointActionButton
import com.example.linkpoint.designsystem.LinkpointLiveTile
import com.example.linkpoint.designsystem.LinkpointPanelSurface
import com.example.linkpoint.designsystem.LinkpointStatusChip
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.protocol.NetworkDiagnosticReport
import com.example.linkpoint.store.ConnectionState
import com.example.linkpoint.store.ViewerState

@Composable
fun GatewayScreen(
    state: ViewerState,
    client: ViewerClient
) {
    val tokens = LocalLinkpointDesign.current
    var firstName by rememberSaveable { mutableStateOf("") }
    var lastName by rememberSaveable { mutableStateOf("Resident") }
    var password by rememberSaveable { mutableStateOf("") }
    var startLocation by rememberSaveable { mutableStateOf("last") }
    var rememberLogin by rememberSaveable { mutableStateOf(true) }
    var activeSavedAccount by remember { mutableStateOf<SavedAccountEntity?>(null) }
    var showAddGridDialog by remember { mutableStateOf(false) }

    val selectedGrid = state.grids.firstOrNull { it.gridId == state.selectedGridId }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.canvasVoid)
    ) {
        // Atmospheric generated hero backdrop
        Image(
            painter = painterResource(id = R.drawable.img_gateway_hero_1795454046916),
            contentDescription = stringResource(R.string.cd_gateway_hero),
            contentScale = ContentScale.Crop,
            alpha = if (tokens.isLight) 0.16f else 0.34f,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            tokens.canvasVoid.copy(alpha = 0.55f),
                            tokens.canvasVoid.copy(alpha = 0.86f),
                            tokens.canvasVoid.copy(alpha = 0.96f)
                        )
                    )
                )
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            val isWide = maxWidth >= 720.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 980.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header & Instant Design Language Switcher
                    GatewayBrandHeader(
                        designLanguage = state.design.designLanguage,
                        onSelectLanguage = client::setDesignLanguage
                    )

                    // Responsive 2-column on tablets/foldables, stacked on phones
                    if (isWide) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(
                                modifier = Modifier.weight(1.1f),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                LoginCardSection(
                                    state = state,
                                    selectedGrid = selectedGrid,
                                    firstName = firstName,
                                    onFirstName = { firstName = it; activeSavedAccount = null },
                                    lastName = lastName,
                                    onLastName = { lastName = it; activeSavedAccount = null },
                                    password = password,
                                    onPassword = { password = it },
                                    startLocation = startLocation,
                                    onStartLocation = { startLocation = it },
                                    rememberLogin = rememberLogin,
                                    onRememberLogin = { rememberLogin = it },
                                    savedAccount = activeSavedAccount,
                                    onConnect = {
                                        client.connectToGrid(
                                            firstName, lastName, password, startLocation, rememberLogin, activeSavedAccount
                                        )
                                    },
                                    onRetry = client::retryLastConnection,
                                    onCancelConnect = client::cancelConnectionAttempt,
                                    onRunDiagnostics = client::runNetworkDiagnostics,
                                    onEnterSandbox = client::enterLocalSandbox
                                )
                                if (state.isRunningDiagnostics || state.diagnosticReport != null) {
                                    NetworkDiagnosticsCard(
                                        isRunning = state.isRunningDiagnostics,
                                        report = state.diagnosticReport,
                                        onRerun = client::runNetworkDiagnostics,
                                        onClose = client::clearDiagnosticReport
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(0.9f),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                GridDirectorySection(
                                    state = state,
                                    onSelectGrid = { gridId ->
                                        if (activeSavedAccount?.gridId != gridId) activeSavedAccount = null
                                        client.selectGrid(gridId)
                                    },
                                    onProbeAll = client::probeAllGrids,
                                    onRunDiagnostics = client::runNetworkDiagnostics,
                                    onOpenAddGrid = { showAddGridDialog = true },
                                    onDeleteCustom = client::deleteCustomGrid
                                )
                                SavedAccountsSection(
                                    accounts = state.savedAccounts,
                                    grids = state.grids,
                                    onSelectAccount = { acc ->
                                        firstName = acc.firstName
                                        lastName = acc.lastName
                                        startLocation = acc.startLocation
                                        password = ""
                                        activeSavedAccount = acc
                                        client.selectGrid(acc.gridId)
                                    },
                                    onDeleteAccount = client::deleteSavedAccount
                                )
                            }
                        }
                    } else {
                        LoginCardSection(
                            state = state,
                            selectedGrid = selectedGrid,
                            firstName = firstName,
                            onFirstName = { firstName = it; activeSavedAccount = null },
                            lastName = lastName,
                            onLastName = { lastName = it; activeSavedAccount = null },
                            password = password,
                            onPassword = { password = it },
                            startLocation = startLocation,
                            onStartLocation = { startLocation = it },
                            rememberLogin = rememberLogin,
                            onRememberLogin = { rememberLogin = it },
                            savedAccount = activeSavedAccount,
                            onConnect = {
                                client.connectToGrid(
                                    firstName, lastName, password, startLocation, rememberLogin, activeSavedAccount
                                )
                            },
                            onRetry = client::retryLastConnection,
                            onCancelConnect = client::cancelConnectionAttempt,
                            onRunDiagnostics = client::runNetworkDiagnostics,
                            onEnterSandbox = client::enterLocalSandbox
                        )
                        if (state.isRunningDiagnostics || state.diagnosticReport != null) {
                            NetworkDiagnosticsCard(
                                isRunning = state.isRunningDiagnostics,
                                report = state.diagnosticReport,
                                onRerun = client::runNetworkDiagnostics,
                                onClose = client::clearDiagnosticReport
                            )
                        }
                        GridDirectorySection(
                            state = state,
                            onSelectGrid = { gridId ->
                                if (activeSavedAccount?.gridId != gridId) activeSavedAccount = null
                                client.selectGrid(gridId)
                            },
                            onProbeAll = client::probeAllGrids,
                            onRunDiagnostics = client::runNetworkDiagnostics,
                            onOpenAddGrid = { showAddGridDialog = true },
                            onDeleteCustom = client::deleteCustomGrid
                        )
                        SavedAccountsSection(
                            accounts = state.savedAccounts,
                            grids = state.grids,
                            onSelectAccount = { acc ->
                                firstName = acc.firstName
                                lastName = acc.lastName
                                startLocation = acc.startLocation
                                password = ""
                                activeSavedAccount = acc
                                client.selectGrid(acc.gridId)
                            },
                            onDeleteAccount = client::deleteSavedAccount
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    if (showAddGridDialog) {
        AddCustomGridDialog(
            onDismiss = { showAddGridDialog = false },
            onAdd = { name, uri, desc ->
                client.addCustomGrid(name, uri, desc)
                showAddGridDialog = false
            }
        )
    }

    val mfaState = state.connection as? ConnectionState.MfaRequired
    if (mfaState != null) {
        MfaChallengeDialog(
            gridName = mfaState.gridName,
            message = mfaState.message,
            onDismiss = client::cancelMfaChallenge,
            onSubmit = client::submitMfaCode
        )
    }
}

@Composable
private fun GatewayBrandHeader(
    designLanguage: DesignLanguage,
    onSelectLanguage: (DesignLanguage) -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    LinkpointPanelSurface(contentPadding = PaddingValues(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    painter = painterResource(id = R.drawable.img_linkpoint_icon_1795454046670),
                    contentDescription = "Linkpoint Emblem",
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp)))
                        .border(1.dp, tokens.accentPrimary.copy(alpha = 0.6f), RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp)))
                )
                Column {
                    Text(
                        text = tokens.formatHeader("Linkpoint"),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            color = if (tokens.language == DesignLanguage.LCARS) tokens.accentPrimary else tokens.textPrimary
                        )
                    )
                    Text(
                        text = stringResource(R.string.gateway_subtitle),
                        style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary)
                    )
                }
            }
            LinkpointStatusChip(
                label = "GLES 3.0 • LLUDP",
                color = tokens.accentPrimary
            )
        }

        Spacer(Modifier.height(12.dp))

        // Instant Design Language Selector directly on Gateway
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Palette,
                contentDescription = null,
                tint = tokens.accentSecondary,
                modifier = Modifier.size(16.dp)
            )
            DesignLanguage.entries.forEach { lang ->
                val selected = lang == designLanguage
                LinkpointStatusChip(
                    label = lang.displayName,
                    color = if (selected) tokens.accentPrimary else tokens.textMuted,
                    onClick = { onSelectLanguage(lang) },
                    modifier = Modifier.testTag("gateway_theme_${lang.name.lowercase()}")
                )
            }
        }
    }
}

@Composable
private fun LoginCardSection(
    state: ViewerState,
    selectedGrid: GridConfigEntity?,
    firstName: String,
    onFirstName: (String) -> Unit,
    lastName: String,
    onLastName: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    startLocation: String,
    onStartLocation: (String) -> Unit,
    rememberLogin: Boolean,
    onRememberLogin: (Boolean) -> Unit,
    savedAccount: SavedAccountEntity?,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onCancelConnect: () -> Unit,
    onRunDiagnostics: () -> Unit,
    onEnterSandbox: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val context = LocalContext.current
    val isConnecting = state.connection is ConnectionState.Connecting
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = tokens.accentPrimary,
        unfocusedBorderColor = tokens.borderHairline,
        focusedTextColor = tokens.textPrimary,
        unfocusedTextColor = tokens.textPrimary,
        focusedLabelColor = tokens.accentPrimary,
        unfocusedLabelColor = tokens.textSecondary,
        cursorColor = tokens.accentPrimary
    )
    val fieldShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))

    LinkpointPanelSurface(contentPadding = PaddingValues(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tokens.formatHeader("Grid Authentication"),
                style = MaterialTheme.typography.titleLarge.copy(color = tokens.textPrimary)
            )
            if (selectedGrid != null) {
                LinkpointStatusChip(
                    label = if (selectedGrid.isHttps) "HTTPS" else "HTTP OpenSim",
                    color = if (selectedGrid.isHttps) tokens.statusOnline else tokens.statusWarning
                )
            }
        }

        if (selectedGrid != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${selectedGrid.name} • ${selectedGrid.loginUri}",
                style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = firstName,
                onValueChange = onFirstName,
                label = { Text("First Name / Username") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = tokens.accentPrimary) },
                shape = fieldShape,
                colors = textFieldColors,
                modifier = Modifier
                    .weight(1f)
                    .testTag("first_name_input")
            )
            OutlinedTextField(
                value = lastName,
                onValueChange = onLastName,
                label = { Text("Last Name") },
                singleLine = true,
                shape = fieldShape,
                colors = textFieldColors,
                modifier = Modifier
                    .weight(0.85f)
                    .testTag("last_name_input")
            )
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = {
                Text(if (savedAccount?.rememberedDigest != null) "Password (saved digest ready)" else "Grid Password")
            },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = tokens.accentPrimary) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(
                onGo = { if (!isConnecting) onConnect() }
            ),
            shape = fieldShape,
            colors = textFieldColors,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("password_input")
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = startLocation,
            onValueChange = onStartLocation,
            label = { Text("Start Location (last, home, or Region/128/128/25)") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Explore, contentDescription = null, tint = tokens.accentSecondary) },
            shape = fieldShape,
            colors = textFieldColors,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("start_location_input")
        )

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = rememberLogin,
                onCheckedChange = onRememberLogin,
                colors = CheckboxDefaults.colors(checkedColor = tokens.accentPrimary, checkmarkColor = tokens.onAccent)
            )
            Text(
                text = "Save account profile locally (stores protocol MD5 digest only)",
                style = MaterialTheme.typography.bodySmall.copy(color = tokens.textSecondary)
            )
        }

        AnimatedVisibility(visible = state.connection is ConnectionState.Connecting) {
            val conn = state.connection as? ConnectionState.Connecting
            if (conn != null) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = conn.stage,
                            style = MaterialTheme.typography.labelMedium.copy(color = tokens.accentPrimary),
                            modifier = Modifier.weight(1f)
                        )
                        LinkpointStatusChip(
                            label = "Try ${conn.attempt}/${conn.maxAttempts}",
                            color = tokens.accentSecondary
                        )
                        IconButton(
                            onClick = onCancelConnect,
                            modifier = Modifier.testTag("cancel_connection_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel connection", tint = tokens.statusError)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { conn.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = tokens.accentPrimary,
                        trackColor = tokens.surfaceElevated
                    )
                }
            }
        }

        AnimatedVisibility(visible = state.connection is ConnectionState.Error) {
            val err = state.connection as? ConnectionState.Error
            var showDiagnostics by remember(err) { mutableStateOf(true) }
            var copiedTrace by remember(err) { mutableStateOf(false) }
            if (err != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.statusError.copy(alpha = 0.14f))
                        .border(1.dp, tokens.statusError.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = tokens.statusError)
                            LinkpointStatusChip(label = err.category.label, color = tokens.statusError)
                        }
                        if (err.attemptsUsed > 1) {
                            Text(
                                text = "${err.attemptsUsed} attempts",
                                style = MaterialTheme.typography.labelSmall.copy(color = tokens.textMuted)
                            )
                        }
                    }
                    Text(
                        text = err.message,
                        style = MaterialTheme.typography.bodySmall.copy(color = tokens.textPrimary)
                    )
                    if (err.recoveryHint.isNotBlank()) {
                        Text(
                            text = err.recoveryHint,
                            style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (err.canRetry) {
                            LinkpointActionButton(
                                text = "Retry Login",
                                icon = Icons.Default.Refresh,
                                onClick = onRetry,
                                primary = true,
                                testTag = "retry_connection_button",
                                modifier = Modifier.weight(1f)
                            )
                        }
                        LinkpointActionButton(
                            text = if (state.isRunningDiagnostics) "Testing..." else "Diagnose",
                            icon = Icons.Default.NetworkCheck,
                            onClick = onRunDiagnostics,
                            enabled = !state.isRunningDiagnostics,
                            primary = false,
                            testTag = "diagnose_network_button",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (err.diagnostics.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (showDiagnostics) "Hide Trace (${err.diagnostics.size}) ▲" else "Show Trace (${err.diagnostics.size}) ▼",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = tokens.accentPrimary,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier
                                    .clickable { showDiagnostics = !showDiagnostics }
                                    .testTag("toggle_connection_diagnostics")
                            )
                            LinkpointStatusChip(
                                label = if (copiedTrace) "Copied" else "Copy Trace",
                                color = tokens.accentSecondary,
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val traceText = buildString {
                                        appendLine("Linkpoint Login Error [${err.category.label}]")
                                        appendLine(err.message)
                                        err.diagnostics.forEach { appendLine(it) }
                                    }
                                    cm?.setPrimaryClip(ClipData.newPlainText("Linkpoint Diagnostics", traceText))
                                    copiedTrace = true
                                },
                                modifier = Modifier.testTag("copy_error_trace_button")
                            )
                        }
                        if (showDiagnostics) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(tokens.canvasVoid.copy(alpha = 0.78f))
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                err.diagnostics.forEach { line ->
                                    Text(
                                        text = "• $line",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = tokens.monoFont,
                                            color = tokens.textSecondary
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        LinkpointActionButton(
            text = stringResource(R.string.gateway_login_button),
            icon = Icons.AutoMirrored.Filled.Login,
            onClick = onConnect,
            enabled = !isConnecting,
            primary = true,
            testTag = "connect_grid_button",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        // Explicitly labeled Local 3D & HUD Sandbox Preview button
        LinkpointActionButton(
            text = stringResource(R.string.gateway_sandbox_button),
            icon = Icons.Default.ViewInAr,
            onClick = onEnterSandbox,
            enabled = !isConnecting,
            primary = false,
            testTag = "enter_sandbox_button",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Local Preview tests the OpenGL ES 3.0 engine, Avatar Attachment HUDs (slots 32/34/36), and all 4 Design Languages without connecting to a grid.",
            style = MaterialTheme.typography.labelSmall.copy(color = tokens.textMuted)
        )
    }
}

@Composable
private fun NetworkDiagnosticsCard(
    isRunning: Boolean,
    report: NetworkDiagnosticReport?,
    onRerun: () -> Unit,
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val context = LocalContext.current
    var copied by remember(report) { mutableStateOf(false) }

    LinkpointPanelSurface(
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier.testTag("network_diagnostics_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = tokens.accentPrimary)
                Text(
                    text = tokens.formatHeader("Network Diagnostics"),
                    style = MaterialTheme.typography.titleMedium.copy(color = tokens.textPrimary)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onRerun,
                    enabled = !isRunning,
                    modifier = Modifier.testTag("rerun_diagnostics_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Re-run diagnostics", tint = tokens.accentPrimary)
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("close_diagnostics_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close diagnostics", tint = tokens.textSecondary)
                }
            }
        }

        if (isRunning) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Running live 5-stage probe: Network → DNS → TCP → TLS → HTTP/1.1...",
                style = MaterialTheme.typography.labelSmall.copy(color = tokens.accentSecondary)
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = tokens.accentPrimary,
                trackColor = tokens.surfaceElevated
            )
        } else if (report != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${report.gridName} • ${report.targetUri}",
                style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))

            val verdictColor = if (report.overallHealthy) tokens.statusOnline else tokens.statusError
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(verdictColor.copy(alpha = 0.14f))
                    .border(1.dp, verdictColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = report.summary,
                    style = MaterialTheme.typography.bodySmall.copy(color = tokens.textPrimary)
                )
            }

            Spacer(Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                report.steps.forEach { step ->
                    val stepColor = if (step.passed) tokens.statusOnline else tokens.statusError
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.canvasVoid.copy(alpha = 0.65f))
                            .border(1.dp, stepColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (step.passed) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            contentDescription = if (step.passed) "Passed" else "Failed",
                            tint = stepColor,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(18.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = step.name,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = tokens.textPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                if (step.latencyMs >= 0) {
                                    Text(
                                        text = "${step.latencyMs} ms",
                                        style = MaterialTheme.typography.labelSmall.copy(color = stepColor)
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = step.detail,
                                style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                LinkpointActionButton(
                    text = if (copied) "Copied Report" else "Copy Diagnostic Report",
                    icon = Icons.Default.ContentCopy,
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Linkpoint Network Report", report.toPlainText()))
                        copied = true
                    },
                    primary = false,
                    testTag = "copy_diagnostic_report_button"
                )
            }
        }
    }
}

@Composable
private fun GridDirectorySection(
    state: ViewerState,
    onSelectGrid: (String) -> Unit,
    onProbeAll: () -> Unit,
    onRunDiagnostics: () -> Unit,
    onOpenAddGrid: () -> Unit,
    onDeleteCustom: (String) -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    LinkpointPanelSurface(contentPadding = PaddingValues(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tokens.formatHeader("Grid Directory & Status"),
                style = MaterialTheme.typography.titleMedium.copy(color = tokens.textPrimary),
                modifier = Modifier.weight(1f)
            )
            Row {
                IconButton(
                    onClick = onRunDiagnostics,
                    enabled = !state.isRunningDiagnostics,
                    modifier = Modifier.testTag("directory_diagnose_button")
                ) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = "Run network diagnostics", tint = tokens.accentSecondary)
                }
                IconButton(onClick = onProbeAll, modifier = Modifier.testTag("probe_grids_button")) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh grid status", tint = tokens.accentPrimary)
                }
                IconButton(onClick = onOpenAddGrid, modifier = Modifier.testTag("add_custom_grid_button")) {
                    Icon(Icons.Default.Add, contentDescription = "Add custom grid URI", tint = tokens.accentSecondary)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Metro uses a 2-column live tile grid; other themes use crisp rows
        if (tokens.language == DesignLanguage.METRO) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.grids.chunked(2).forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { grid ->
                            val status = state.gridStatusMap[grid.gridId]
                            val selected = grid.gridId == state.selectedGridId
                            val metric = when {
                                status?.isChecking == true -> "..."
                                status?.isReachable == true -> "${status.latencyMs}ms"
                                status?.isReachable == false -> "OFF"
                                else -> "IDLE"
                            }
                            LinkpointLiveTile(
                                title = grid.name,
                                metric = metric,
                                subtitle = grid.gridType,
                                accent = if (selected) tokens.accentPrimary else tokens.surfaceElevated,
                                icon = Icons.Default.Public,
                                onClick = { onSelectGrid(grid.gridId) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.grids.forEach { grid ->
                    val selected = grid.gridId == state.selectedGridId
                    val status = state.gridStatusMap[grid.gridId]
                    val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
                    val statusColor = when {
                        status?.isChecking == true -> tokens.accentSecondary
                        status?.isReachable == true -> tokens.statusOnline
                        status?.isReachable == false -> tokens.statusWarning
                        else -> tokens.textMuted
                    }
                    val statusText = when {
                        status?.isChecking == true -> "Probing..."
                        status?.isReachable == true -> "${status.latencyMs} ms"
                        status?.isReachable == false -> "No probe"
                        else -> "Ready"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(
                                if (selected) tokens.accentPrimary.copy(alpha = 0.16f)
                                else tokens.surfaceElevated.copy(alpha = 0.65f)
                            )
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) tokens.accentPrimary else tokens.borderHairline,
                                shape = shape
                            )
                            .clickable { onSelectGrid(grid.gridId) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = grid.name,
                                style = MaterialTheme.typography.titleMedium.copy(color = tokens.textPrimary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = grid.loginUri,
                                style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        LinkpointStatusChip(label = statusText, color = statusColor)
                        if (!grid.isBuiltIn) {
                            IconButton(onClick = { onDeleteCustom(grid.gridId) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove custom grid", tint = tokens.statusError)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedAccountsSection(
    accounts: List<SavedAccountEntity>,
    grids: List<GridConfigEntity>,
    onSelectAccount: (SavedAccountEntity) -> Unit,
    onDeleteAccount: (Long) -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    LinkpointPanelSurface(contentPadding = PaddingValues(14.dp)) {
        Text(
            text = tokens.formatHeader("Saved Grid Profiles"),
            style = MaterialTheme.typography.titleMedium.copy(color = tokens.textPrimary)
        )
        Spacer(Modifier.height(8.dp))
        if (accounts.isEmpty()) {
            Text(
                text = "No saved profiles yet. Enable \"Save account profile\" when logging in to quick-fill future grid sessions.",
                style = MaterialTheme.typography.bodySmall.copy(color = tokens.textMuted)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts.forEach { acc ->
                    val gridName = grids.firstOrNull { it.gridId == acc.gridId }?.name ?: acc.gridId
                    val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(tokens.surfaceElevated.copy(alpha = 0.7f))
                            .border(1.dp, tokens.borderHairline, shape)
                            .clickable { onSelectAccount(acc) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${acc.firstName} ${acc.lastName}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = tokens.textPrimary
                                )
                            )
                            Text(
                                text = "$gridName • Start: ${acc.startLocation}",
                                style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary)
                            )
                        }
                        IconButton(onClick = { onDeleteAccount(acc.id) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete saved profile", tint = tokens.statusError)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddCustomGridDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    var name by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf("http://") }
    var desc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tokens.surfaceFloater,
        title = {
            Text(
                text = tokens.formatHeader("Add OpenSim / Hypergrid"),
                color = tokens.textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Grid Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = uri,
                    onValueChange = { uri = it },
                    label = { Text("Login URI (https:// or http://)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Notes (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, uri, desc) }) {
                Text("Add Grid", color = tokens.accentPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = tokens.textSecondary)
            }
        }
    )
}

@Composable
private fun MfaChallengeDialog(
    gridName: String,
    message: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tokens.surfaceFloater,
        title = {
            Text(
                text = tokens.formatHeader("2FA Verification • $gridName"),
                color = tokens.textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall.copy(color = tokens.textSecondary)
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(8) },
                    label = { Text("6-Digit Authenticator Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mfa_code_input")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(code) },
                enabled = code.length >= 6,
                modifier = Modifier.testTag("mfa_submit_button")
            ) {
                Text("Verify & Connect", color = tokens.accentPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = tokens.textSecondary)
            }
        }
    )
}
