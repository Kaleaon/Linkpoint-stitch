package com.example.linkpoint.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.linkpoint.client.ViewerClient
import com.example.linkpoint.designsystem.DesignLanguage
import com.example.linkpoint.designsystem.LinkpointPanelSurface
import com.example.linkpoint.designsystem.LinkpointStatusChip
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.protocol.ScriptAndEconomyProtocol
import com.example.linkpoint.store.ScriptDialogPrompt
import com.example.linkpoint.store.ScriptPermissionPrompt

private val QUICK_PAY_PRESETS = listOf(10, 50, 100, 500)

/**
 * Compact tappable `L$` balance badge embedded inside `CompactTelemetryPill`.
 * Tapping refreshes the live grid balance (`MoneyBalanceRequest`) or displays sandbox balance status.
 */
@Composable
fun LindenBalanceBadge(
    balance: Int?,
    onRefreshBalance: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalLinkpointDesign.current
    val badgeShape = RoundedCornerShape(
        when (tokens.language) {
            DesignLanguage.LCARS -> 99.dp
            DesignLanguage.METRO -> 0.dp
            DesignLanguage.TERMINAL -> 2.dp
            DesignLanguage.POLISHED -> 10.dp
        }
    )
    val label = if (balance != null) {
        ScriptAndEconomyProtocol.formatLindenAmount(balance)
    } else {
        "L$ —"
    }

    Row(
        modifier = modifier
            .clip(badgeShape)
            .background(tokens.statusOnline.copy(alpha = 0.16f))
            .border(1.dp, tokens.statusOnline.copy(alpha = 0.55f), badgeShape)
            .clickable(onClick = onRefreshBalance)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .testTag("linden_balance_badge"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = Icons.Default.AccountBalanceWallet,
            contentDescription = "Refresh L$ balance",
            tint = tokens.statusOnline,
            modifier = Modifier.size(11.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = tokens.monoFont,
                color = tokens.statusOnline,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            ),
            maxLines = 1
        )
    }
}

/**
 * LSL `ScriptDialog` & `llTextBox` modal.
 * Supports classic up-to-12-button `llDialog` menus as well as `llTextBox` text-input prompts (`!!llTextBox!!`).
 */
@Composable
fun ScriptDialogModal(
    prompt: ScriptDialogPrompt,
    client: ViewerClient
) {
    val tokens = LocalLinkpointDesign.current
    var textDraft by rememberSaveable(prompt.objectName, prompt.channel, prompt.message) {
        mutableStateOf("")
    }

    DisposableEffect(prompt) {
        onDispose { client.setTextInputFocused(false) }
    }

    Dialog(
        onDismissRequest = client::dismissScriptDialog,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
        LinkpointPanelSurface(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .testTag("script_dialog_modal"),
            elevated = true,
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tokens.formatHeader(prompt.objectName),
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = tokens.textPrimary,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val metaParts = buildList {
                        if (prompt.ownerName.isNotBlank()) add("Owner: ${prompt.ownerName}")
                        add("Ch ${prompt.channel}")
                        if (prompt.isTextBox) add("llTextBox")
                    }
                    Text(
                        text = metaParts.joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = tokens.monoFont,
                            color = tokens.accentSecondary,
                            fontSize = 10.sp
                        )
                    )
                }
                LinkpointStatusChip(
                    label = if (prompt.isTextBox) "TEXT INPUT" else "SCRIPT",
                    color = if (prompt.isTextBox) tokens.accentSecondary else tokens.accentPrimary
                )
            }

            Spacer(Modifier.height(10.dp))

            if (prompt.message.isNotBlank()) {
                Text(
                    text = prompt.message,
                    style = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState())
                )
                Spacer(Modifier.height(12.dp))
            }

            if (prompt.isTextBox) {
                val utf8ByteLen = remember(textDraft) { textDraft.toByteArray(Charsets.UTF_8).size }
                OutlinedTextField(
                    value = textDraft,
                    onValueChange = { next ->
                        if (next.toByteArray(Charsets.UTF_8).size <= 250) {
                            textDraft = next
                        }
                    },
                    placeholder = {
                        Text("Enter script response…", color = tokens.textMuted)
                    },
                    supportingText = {
                        Text(
                            text = "$utf8ByteLen / 250 bytes • Sends on channel ${prompt.channel}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = tokens.monoFont,
                                color = tokens.textMuted,
                                fontSize = 10.sp
                            )
                        )
                    },
                    maxLines = 3,
                    shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp)),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = tokens.accentPrimary,
                        unfocusedBorderColor = tokens.borderHairline,
                        focusedTextColor = tokens.textPrimary,
                        unfocusedTextColor = tokens.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { client.setTextInputFocused(it.isFocused) }
                        .testTag("script_textbox_input")
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = client::dismissScriptDialog,
                        modifier = Modifier.testTag("script_dialog_ignore")
                    ) {
                        Text("Ignore", color = tokens.textMuted)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { client.respondScriptDialog(textDraft, prompt.channel) },
                        enabled = textDraft.isNotBlank(),
                        shape = RoundedCornerShape(tokens.buttonRadius),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.accentPrimary,
                            contentColor = tokens.onAccent
                        ),
                        modifier = Modifier.testTag("script_textbox_submit")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(tokens.formatAction("Submit"), fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                val buttonRows = prompt.buttons.ifEmpty { listOf("OK") }.take(12).chunked(3)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    buttonRows.forEach { rowLabels ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowLabels.forEach { label ->
                                OutlinedButton(
                                    onClick = { client.respondScriptDialog(label, prompt.channel) },
                                    shape = RoundedCornerShape(tokens.buttonRadius),
                                    border = BorderStroke(1.dp, tokens.accentPrimary.copy(alpha = 0.65f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = tokens.surfaceElevated.copy(alpha = 0.85f),
                                        contentColor = tokens.accentPrimary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = client::dismissScriptDialog,
                        modifier = Modifier.testTag("script_dialog_ignore")
                    ) {
                        Text("Ignore", color = tokens.textMuted)
                    }
                }
            }
        }
        }
    }
}

/**
 * LSL `ScriptQuestion` permission prompt modal.
 * Displays human-readable `secondlife/lsl-definitions` permission labels, category badges (`AO`,
 * `EXPERIENCE`, `ESTATE`, `LAND`, `MUTE LIST`, `EXTENDED`), and highlights `PERMISSION_DEBIT` in error/warning colors.
 */
@Composable
fun ScriptPermissionModal(
    prompt: ScriptPermissionPrompt,
    client: ViewerClient
) {
    val tokens = LocalLinkpointDesign.current
    val isDebit = prompt.hasDebitPermission
    val hasExperience = prompt.experienceId.mostSignificantBits != 0L || prompt.experienceId.leastSignificantBits != 0L
    val headerAccent = if (isDebit) tokens.statusError else tokens.accentPrimary
    val rowShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))
    val maskHex = remember(prompt.questions) {
        ScriptAndEconomyProtocol.formatPermissionBitmask(prompt.questions)
    }

    Dialog(
        onDismissRequest = client::dismissScriptPermission,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
        LinkpointPanelSurface(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .testTag("script_permission_modal"),
            elevated = true,
            accentStripColor = headerAccent,
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isDebit) Icons.Default.WarningAmber else Icons.Default.Security,
                    contentDescription = null,
                    tint = headerAccent,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tokens.formatHeader(prompt.objectName),
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = tokens.textPrimary,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitleParts = buildList {
                        if (prompt.ownerName.isNotBlank()) {
                            add("Owned by ${prompt.ownerName}")
                        } else {
                            add("Scripted object")
                        }
                        add("Mask $maskHex")
                        if (hasExperience) add("Experience")
                    }
                    Text(
                        text = subtitleParts.joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = tokens.monoFont,
                            color = tokens.textSecondary,
                            fontSize = 10.sp
                        )
                    )
                }
            }

            if (isDebit) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(rowShape)
                        .background(tokens.statusError.copy(alpha = 0.16f))
                        .border(1.5.dp, tokens.statusError, rowShape)
                        .padding(10.dp)
                        .testTag("script_debit_warning_banner"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = tokens.statusError,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "WARNING: Granting Debit L$ lets this script withdraw L$ directly from your account at any time. Only grant to objects and owners you completely trust!",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = tokens.statusError,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("script_perm_list"),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                prompt.permissions.forEach { perm ->
                    val isDebitRow = perm.isDebitWarning
                    val isCautionRow = perm.isCaution
                    val permHex = ScriptAndEconomyProtocol.formatPermissionBitmask(perm.mask)
                    val borderCol = when {
                        isDebitRow -> tokens.statusWarning
                        isCautionRow -> tokens.statusWarning.copy(alpha = 0.65f)
                        else -> tokens.borderHairline
                    }
                    val bgCol = when {
                        isDebitRow -> tokens.statusWarning.copy(alpha = 0.14f)
                        isCautionRow -> tokens.statusWarning.copy(alpha = 0.08f)
                        else -> tokens.surfaceElevated.copy(alpha = 0.78f)
                    }
                    val chipColor = when {
                        isDebitRow -> tokens.statusError
                        isCautionRow -> tokens.statusWarning
                        else -> tokens.accentSecondary
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(rowShape)
                            .background(bgCol)
                            .border(1.dp, borderCol, rowShape)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("script_perm_row_$permHex")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            perm.badgeLabel?.let { badge ->
                                LinkpointStatusChip(
                                    label = badge,
                                    color = chipColor
                                )
                            }
                            Text(
                                text = perm.label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = if (isDebitRow) tokens.statusWarning else tokens.textPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                        Text(
                            text = "${perm.constantName} ($permHex)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = tokens.monoFont,
                                color = tokens.accentSecondary,
                                fontSize = 10.sp
                            )
                        )
                        Text(
                            text = perm.detail,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = tokens.textSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { client.respondScriptPermission(grant = false) },
                    shape = RoundedCornerShape(tokens.buttonRadius),
                    border = BorderStroke(1.dp, tokens.borderHairline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = tokens.surfaceElevated,
                        contentColor = tokens.textPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("script_perm_deny_button")
                ) {
                    Text(tokens.formatAction("Deny"), fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { client.respondScriptPermission(grant = true) },
                    shape = RoundedCornerShape(tokens.buttonRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDebit) tokens.statusError else tokens.accentPrimary,
                        contentColor = if (isDebit) androidx.compose.ui.graphics.Color.White else tokens.onAccent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("script_perm_grant_button")
                ) {
                    Text(
                        text = tokens.formatAction(if (isDebit) "Grant Debit" else "Grant"),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        }
    }
}

/**
 * Reusable `L$` Quick-Pay & Custom Payment dialog with explicit confirmation step.
 * Used by both `ObjectInspectorContent` (`PayObject` = 5008) and `AvatarProfileSheetContent` (`PayGift` = 5001).
 */
@Composable
fun QuickPayModal(
    recipientName: String,
    isObjectPayment: Boolean,
    currentBalance: Int?,
    client: ViewerClient,
    onConfirmPayment: (amount: Int, note: String) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    var amountText by rememberSaveable(recipientName) { mutableStateOf("50") }
    var noteText by rememberSaveable(recipientName) { mutableStateOf("") }
    var confirming by rememberSaveable(recipientName) { mutableStateOf(false) }

    DisposableEffect(recipientName) {
        onDispose { client.setTextInputFocused(false) }
    }

    val parsedAmount = amountText.trim().toIntOrNull() ?: 0
    val exceedsBalance = currentBalance != null && parsedAmount > currentBalance
    val isAmountValid = parsedAmount > 0 && !exceedsBalance
    val cardShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
        LinkpointPanelSurface(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .testTag("quick_pay_modal"),
            elevated = true,
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Payments,
                        contentDescription = null,
                        tint = tokens.statusOnline,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = tokens.formatHeader(if (confirming) "Confirm L$ Payment" else "Pay $recipientName"),
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = tokens.textPrimary,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isObjectPayment) "Pay Object / Tip Jar (5008)" else "Pay Resident Gift (5001)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = tokens.monoFont,
                                color = tokens.accentSecondary,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
                if (currentBalance != null) {
                    LinkpointStatusChip(
                        label = ScriptAndEconomyProtocol.formatLindenAmount(currentBalance),
                        color = tokens.statusOnline
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (!confirming) {
                Text(
                    text = "QUICK PAY PRESETS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = tokens.monoFont,
                        color = tokens.textSecondary,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QUICK_PAY_PRESETS.forEach { preset ->
                        val isSelected = parsedAmount == preset
                        Surface(
                            shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp)),
                            color = if (isSelected) tokens.accentPrimary else tokens.surfaceElevated,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) tokens.accentPrimary else tokens.borderHairline
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { amountText = preset.toString() }
                                .testTag("quick_pay_$preset")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp)
                            ) {
                                Text(
                                    text = "L$ $preset",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = tokens.monoFont,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) tokens.onAccent else tokens.textPrimary
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(8)
                        amountText = digits
                    },
                    label = { Text("Custom Amount (L$)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = cardShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (exceedsBalance) tokens.statusError else tokens.accentPrimary,
                        unfocusedBorderColor = if (exceedsBalance) tokens.statusError else tokens.borderHairline,
                        focusedTextColor = tokens.textPrimary,
                        unfocusedTextColor = tokens.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { client.setTextInputFocused(it.isFocused) }
                        .testTag("quick_pay_custom_amount")
                )

                if (exceedsBalance) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Exceeds your available balance (${ScriptAndEconomyProtocol.formatLindenAmount(currentBalance)})",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = tokens.statusError,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { if (it.length <= 120) noteText = it },
                    label = { Text("Optional Note") },
                    placeholder = { Text("Tip, purchase, or gift message…", color = tokens.textMuted) },
                    singleLine = true,
                    shape = cardShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = tokens.accentPrimary,
                        unfocusedBorderColor = tokens.borderHairline,
                        focusedTextColor = tokens.textPrimary,
                        unfocusedTextColor = tokens.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { client.setTextInputFocused(it.isFocused) }
                        .testTag("quick_pay_note_input")
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("quick_pay_cancel_button")
                    ) {
                        Text("Cancel", color = tokens.textMuted)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            client.setTextInputFocused(false)
                            confirming = true
                        },
                        enabled = isAmountValid,
                        shape = RoundedCornerShape(tokens.buttonRadius),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.accentPrimary,
                            contentColor = tokens.onAccent
                        ),
                        modifier = Modifier.testTag("quick_pay_review_button")
                    ) {
                        Text(
                            text = tokens.formatAction(
                                if (parsedAmount > 0) {
                                    "Review ${ScriptAndEconomyProtocol.formatLindenAmount(parsedAmount)}"
                                } else {
                                    "Review Payment"
                                }
                            ),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                // Step 2: Explicit Confirmation Summary
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(tokens.surfaceElevated.copy(alpha = 0.88f))
                        .border(1.dp, tokens.statusOnline.copy(alpha = 0.6f), cardShape)
                        .padding(12.dp)
                        .testTag("quick_pay_confirm_card"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "PLEASE CONFIRM L$ TRANSFER",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = tokens.monoFont,
                            color = tokens.statusWarning,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = ScriptAndEconomyProtocol.formatLindenAmount(parsedAmount),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = tokens.monoFont,
                            color = tokens.statusOnline,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                    Text(
                        text = "To: $recipientName",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = tokens.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    if (noteText.isNotBlank()) {
                        Text(
                            text = "Note: \"${noteText.trim()}\"",
                            style = MaterialTheme.typography.bodySmall.copy(color = tokens.textSecondary)
                        )
                    }
                    if (currentBalance != null) {
                        Text(
                            text = "Remaining Balance: ${ScriptAndEconomyProtocol.formatLindenAmount((currentBalance - parsedAmount).coerceAtLeast(0))}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = tokens.monoFont,
                                color = tokens.textMuted
                            )
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { confirming = false },
                        shape = RoundedCornerShape(tokens.buttonRadius),
                        border = BorderStroke(1.dp, tokens.borderHairline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = tokens.surfaceElevated,
                            contentColor = tokens.textPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_pay_back_button")
                    ) {
                        Text(tokens.formatAction("Back"), fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            onConfirmPayment(parsedAmount, noteText.trim())
                            onDismiss()
                        },
                        shape = RoundedCornerShape(tokens.buttonRadius),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.statusOnline,
                            contentColor = androidx.compose.ui.graphics.Color(0xFF03140A)
                        ),
                        modifier = Modifier
                            .weight(1.3f)
                            .testTag("quick_pay_confirm_button")
                    ) {
                        Text(
                            text = tokens.formatAction("Confirm ${ScriptAndEconomyProtocol.formatLindenAmount(parsedAmount)}"),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        }
    }
}
