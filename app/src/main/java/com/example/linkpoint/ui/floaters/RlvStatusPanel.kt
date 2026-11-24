package com.example.linkpoint.ui.floaters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.linkpoint.client.ViewerClient
import com.example.linkpoint.designsystem.LinkpointActionButton
import com.example.linkpoint.designsystem.LinkpointStatusChip
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.store.ConnectionState
import com.example.linkpoint.store.ViewerState

/**
 * Self-contained RLV / RLVa Status & Behavioral Restriction Inspector panel
 * embedded in `LayoutDesignStudioContent` (and reusable in standalone floaters).
 *
 * Surfaces:
 * - Persisted **Enable RLVa** master switch (`@version` / `@versionnew` / `@getstatus` subsystem).
 * - Interactive **Test RLVa Relay Demo** controls for offline verification in Local Sandbox.
 * - **Active RLVa Restrictions** list grouped by holding object name & UUID with per-object
 *   release and an **Emergency Release** button.
 * - Recent RLVa command & handshake audit log.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RlvStatusPanel(
    state: ViewerState,
    client: ViewerClient,
    modifier: Modifier = Modifier
) {
    val tokens = LocalLinkpointDesign.current
    val rlv = state.rlvSnapshot
    val cardShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
    val isSandbox = state.connection is ConnectionState.LocalSandbox

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("rlva_status_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Master Enable RLVa Switch Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(tokens.surfaceElevated.copy(alpha = 0.82f))
                .border(1.dp, if (rlv.enabled) tokens.accentPrimary.copy(alpha = 0.6f) else tokens.borderHairline, cardShape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (rlv.activeRuleCount > 0) Icons.Default.Lock else Icons.Default.Shield,
                    contentDescription = null,
                    tint = when {
                        !rlv.enabled -> tokens.textMuted
                        rlv.activeRuleCount > 0 -> tokens.statusWarning
                        else -> tokens.accentPrimary
                    },
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        text = "Enable RLVa (Restrained Love)",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = tokens.textPrimary
                        )
                    )
                    Text(
                        text = if (rlv.enabled) {
                            "RLVa 2.4.2 / RLV 2.8.0 • Intercepting llOwnerSay @commands"
                        } else {
                            "Disabled • Ignoring all scripted @commands"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary)
                    )
                }
            }
            Switch(
                checked = state.design.rlvaEnabled,
                onCheckedChange = { client.setRlvaEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = tokens.onAccent,
                    checkedTrackColor = tokens.accentPrimary
                ),
                modifier = Modifier.testTag("rlva_enable_switch")
            )
        }

        // 2. Sandbox RLVa Relay Demo Controls (highlighted in Local Sandbox, also usable anytime offline)
        if (isSandbox || state.connection is ConnectionState.Disconnected) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(tokens.surfaceElevated.copy(alpha = 0.72f))
                    .border(1.dp, tokens.accentTertiary.copy(alpha = 0.55f), cardShape)
                    .padding(10.dp)
                    .testTag("rlva_sandbox_demo_card"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sandbox RLVa Relay Demo",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = tokens.textPrimary
                            )
                        )
                        Text(
                            text = "Simulate OpenCollar / Relay handshakes, chat garbling, sit-lock & @clear",
                            style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary)
                        )
                    }
                    LinkpointActionButton(
                        text = "Test RLVa Demo",
                        icon = Icons.Default.PlayArrow,
                        onClick = { client.triggerSandboxRlvDemo("lock_and_gag") },
                        primary = true,
                        enabled = state.design.rlvaEnabled,
                        testTag = "rlva_demo_trigger_btn"
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LinkpointStatusChip(
                        label = "Handshake (@versionnew)",
                        color = tokens.accentPrimary,
                        onClick = { client.triggerSandboxRlvDemo("handshake") },
                        modifier = Modifier.testTag("rlva_demo_handshake")
                    )
                    LinkpointStatusChip(
                        label = "Redir Gag (@redirchat:324)",
                        color = tokens.accentSecondary,
                        onClick = { client.triggerSandboxRlvDemo("redir_gag") },
                        modifier = Modifier.testTag("rlva_demo_redir")
                    )
                    LinkpointStatusChip(
                        label = "Force Sit Chair (@sit=force)",
                        color = tokens.accentTertiary,
                        onClick = { client.triggerSandboxRlvDemo("force_sit") },
                        modifier = Modifier.testTag("rlva_demo_forcesit")
                    )
                    LinkpointStatusChip(
                        label = "Blind/Anonymize (@shownames)",
                        color = tokens.textSecondary,
                        onClick = { client.triggerSandboxRlvDemo("sensory_hide") },
                        modifier = Modifier.testTag("rlva_demo_sensory")
                    )
                    LinkpointStatusChip(
                        label = "Relay @clear",
                        color = tokens.statusOnline,
                        onClick = { client.triggerSandboxRlvDemo("relay_clear") },
                        modifier = Modifier.testTag("rlva_demo_clear")
                    )
                }
            }
        }

        // 3. Active RLVa Restrictions Header + Emergency Release
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                !rlv.enabled -> tokens.textMuted
                                rlv.activeRuleCount > 0 -> tokens.statusWarning
                                else -> tokens.statusOnline
                            }
                        )
                )
                Text(
                    text = "Active RLVa Restrictions (${rlv.activeRuleCount})",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = tokens.textPrimary
                    )
                )
            }

            if (rlv.activeRuleCount > 0) {
                LinkpointActionButton(
                    text = "Emergency Release",
                    icon = Icons.Default.LockOpen,
                    onClick = client::clearAllRlvRestrictions,
                    primary = false,
                    testTag = "rlva_emergency_release_btn"
                )
            }
        }

        // 4. Per-Object Restriction Inspector Cards
        if (!rlv.enabled) {
            Text(
                text = "RLVa is currently turned off. Toggle 'Enable RLVa' above to allow owned attachments and relays to negotiate restrictions.",
                style = MaterialTheme.typography.bodySmall.copy(color = tokens.textMuted)
            )
        } else if (rlv.controllers.isEmpty()) {
            Text(
                text = "No active RLVa restrictions. Owned scripts using llOwnerSay(\"@...\") will appear here with their UUID and active behavior locks.",
                style = MaterialTheme.typography.bodySmall.copy(color = tokens.textMuted),
                modifier = Modifier.testTag("rlva_empty_notice")
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rlva_controllers_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rlv.controllers.forEach { controller ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(tokens.surfaceElevated.copy(alpha = 0.85f))
                            .border(1.dp, tokens.statusWarning.copy(alpha = 0.55f), cardShape)
                            .padding(10.dp)
                            .testTag("rlva_controller_${controller.objectId.toString().take(8)}"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = controller.objectName,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.textPrimary
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "UUID: ${controller.objectId}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = tokens.monoFont,
                                        color = tokens.textMuted,
                                        fontSize = 10.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            LinkpointStatusChip(
                                label = "Release (${controller.rules.size})",
                                color = tokens.statusWarning,
                                onClick = { client.clearRlvObjectRestrictions(controller.objectId) },
                                modifier = Modifier.testTag("rlva_release_${controller.objectId.toString().take(8)}")
                            )
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            controller.rules.forEach { rule ->
                                LinkpointStatusChip(
                                    label = rule,
                                    color = tokens.accentSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Recent RLVa Command & Handshake Audit Log
        if (rlv.recentLog.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Recent RLVa Handshakes & Events",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textSecondary
                )
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(tokens.canvasVoid.copy(alpha = 0.55f))
                    .border(1.dp, tokens.borderHairline, cardShape)
                    .padding(8.dp)
                    .testTag("rlva_audit_log"),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                rlv.recentLog.takeLast(6).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = tokens.monoFont,
                            color = tokens.textSecondary,
                            fontSize = 10.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
