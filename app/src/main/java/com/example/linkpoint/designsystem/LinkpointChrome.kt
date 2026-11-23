package com.example.linkpoint.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Multi-language surface container adapting geometry, chrome framing, and texture across
 * all 6 `linkpoint-design` layout packs:
 * - POLISHED (spatial glass + top specular hairline)
 * - TERMINAL (CRT scanlines + 1dp phosphor frame)
 * - LCARS (CleverFerret pure #000000 black-field split-frame opposing elbows)
 * - METRO (sharp 0dp flat block + bold top accent bar)
 * - RULE_AND_RAIL (Deco hairline top/bottom rules + stepped rail ticks, no heavy box)
 * - PRESS (Editorial double masthead rule + quiet 3dp border)
 */
@Composable
fun LinkpointPanelSurface(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    accentStripColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val bg = if (elevated) tokens.surfaceElevated else tokens.surfaceFloater
    val accent = accentStripColor ?: tokens.accentPrimary
    val shape = RoundedCornerShape(tokens.cornerRadius)

    when (tokens.language) {
        DesignLanguage.LCARS -> {
            LcarsOpposingElbowsFrame(
                modifier = modifier,
                codeTag = "LCARS-24C",
                primaryColor = accent,
                secondaryColor = tokens.accentSecondary,
                tertiaryColor = tokens.accentTertiary,
                onAccentColor = tokens.onAccent,
                elbowArmWidth = if (elevated) 16.dp else 22.dp,
                topBarHeight = if (elevated) 20.dp else 26.dp,
                bottomBarHeight = if (elevated) 10.dp else 14.dp,
                content = content
            )
        }

        DesignLanguage.TERMINAL -> {
            Box(
                modifier = modifier
                    .clip(shape)
                    .background(bg)
                    .drawBehind {
                        val lineStep = 4.dp.toPx()
                        var y = 0f
                        while (y < size.height) {
                            drawLine(
                                color = accent.copy(alpha = 0.05f),
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1f
                            )
                            y += lineStep
                        }
                    }
                    .border(tokens.borderWidth, tokens.borderHairline, shape)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(contentPadding),
                    content = content
                )
            }
        }

        DesignLanguage.METRO -> {
            Column(
                modifier = modifier
                    .background(bg)
                    .border(1.dp, tokens.borderHairline)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(accent)
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(contentPadding),
                    content = content
                )
            }
        }

        DesignLanguage.RULE_AND_RAIL -> {
            // Deco discipline: 0dp corners, top & bottom hairline rules, left rail tick, stepped corner accents
            Box(
                modifier = modifier
                    .background(bg)
                    .drawBehind {
                        val ruleStroke = 1.dp.toPx()
                        val tickLen = 14.dp.toPx().coerceAtMost(size.width * 0.25f)
                        val railW = 2.dp.toPx()
                        // Top and bottom full hairline rules
                        drawLine(
                            color = tokens.borderHairline,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = ruleStroke
                        )
                        drawLine(
                            color = tokens.borderHairline,
                            start = Offset(0f, size.height - ruleStroke),
                            end = Offset(size.width, size.height - ruleStroke),
                            strokeWidth = ruleStroke
                        )
                        // Left vertical Deco rail accent
                        drawRect(
                            color = accent,
                            topLeft = Offset(0f, 0f),
                            size = Size(railW, size.height)
                        )
                        // Stepped Deco corner gold/accent ticks
                        drawLine(
                            color = accent,
                            start = Offset(0f, 3.dp.toPx()),
                            end = Offset(tickLen, 3.dp.toPx()),
                            strokeWidth = ruleStroke
                        )
                        drawLine(
                            color = accent,
                            start = Offset(size.width - tickLen, size.height - 4.dp.toPx()),
                            end = Offset(size.width, size.height - 4.dp.toPx()),
                            strokeWidth = ruleStroke
                        )
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(contentPadding),
                    content = content
                )
            }
        }

        DesignLanguage.PRESS -> {
            // Editorial Press: quiet 3dp paper/slate surface with classic double masthead rule
            Surface(
                modifier = modifier,
                shape = shape,
                color = bg,
                border = BorderStroke(tokens.borderWidth, tokens.borderHairline)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(tokens.textPrimary.copy(alpha = 0.8f))
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(accent.copy(alpha = 0.7f))
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(contentPadding),
                        content = content
                    )
                }
            }
        }

        DesignLanguage.POLISHED -> {
            Surface(
                modifier = modifier.drawBehind {
                    drawRoundRect(
                        color = tokens.borderSpecular.copy(alpha = 0.35f),
                        topLeft = Offset(1f, 0f),
                        size = Size(size.width - 2f, 2f),
                        cornerRadius = CornerRadius(tokens.cornerRadius.toPx(), tokens.cornerRadius.toPx())
                    )
                },
                shape = shape,
                color = bg,
                border = BorderStroke(tokens.borderWidth, tokens.borderHairline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(contentPadding),
                    content = content
                )
            }
        }
    }
}

@Composable
fun LinkpointActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    enabled: Boolean = true,
    testTag: String? = null
) {
    val tokens = LocalLinkpointDesign.current
    val label = tokens.formatAction(text)
    val shape = RoundedCornerShape(tokens.buttonRadius)
    val mod = modifier
        .heightIn(min = 44.dp)
        .let { if (testTag != null) it.testTag(testTag) else it }

    val letterSpacing = when (tokens.language) {
        DesignLanguage.RULE_AND_RAIL -> 1.8.sp
        DesignLanguage.LCARS -> 1.sp
        else -> 0.2.sp
    }

    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = tokens.accentPrimary,
                contentColor = tokens.onAccent
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            modifier = mod
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = tokens.displayFont,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = letterSpacing
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            border = BorderStroke(tokens.borderWidth, tokens.borderHairline),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (tokens.language == DesignLanguage.LCARS) LcarsBlackField
                else tokens.surfaceElevated.copy(alpha = 0.72f),
                contentColor = tokens.textPrimary
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = mod
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tokens.accentPrimary)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = tokens.displayFont,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = letterSpacing
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun LinkpointStatusChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val tokens = LocalLinkpointDesign.current
    val shape = RoundedCornerShape(
        when (tokens.language) {
            DesignLanguage.LCARS -> 99.dp
            DesignLanguage.METRO, DesignLanguage.RULE_AND_RAIL -> 0.dp
            DesignLanguage.TERMINAL, DesignLanguage.PRESS -> 2.dp
            DesignLanguage.POLISHED -> 10.dp
        }
    )
    val formatted = when (tokens.language) {
        DesignLanguage.LCARS, DesignLanguage.RULE_AND_RAIL -> label.uppercase()
        DesignLanguage.TERMINAL -> "[$label]"
        DesignLanguage.METRO -> label.lowercase()
        DesignLanguage.PRESS, DesignLanguage.POLISHED -> label
    }
    val clickMod = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    val solidPill = tokens.language == DesignLanguage.LCARS
    val bgFill = if (solidPill) color.copy(alpha = 0.94f) else color.copy(alpha = 0.16f)
    val effectiveChipBg = LinkpointContrast.compositeOver(bgFill, tokens.canvasVoid)
    val chipInk = if (solidPill) {
        LinkpointContrast.inkOn(color, listOf(Color.Black, Color.White))
    } else {
        LinkpointContrast.ensureMinContrast(color, effectiveChipBg, 4.5)
    }

    Row(
        modifier = clickMod
            .clip(shape)
            .background(bgFill)
            .border(1.dp, color.copy(alpha = 0.75f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(
                    RoundedCornerShape(
                        if (tokens.language == DesignLanguage.METRO || tokens.language == DesignLanguage.RULE_AND_RAIL) 0.dp
                        else 99.dp
                    )
                )
                .background(chipInk)
        )
        Text(
            text = formatted,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = tokens.monoFont,
                fontWeight = FontWeight.Bold,
                color = chipInk,
                letterSpacing = if (tokens.language == DesignLanguage.RULE_AND_RAIL) 1.2.sp else 0.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Adaptive Linkpoint Floater Header — renders LCARS capsule band, Metro oversized lowercase
 * panorama title, Terminal monospace banner, Rule & Rail wide-tracked Deco hairline header,
 * Press editorial masthead, or Polished glass title bar with close control.
 */
@Composable
fun LinkpointFloaterHeader(
    title: String,
    subtitle: String? = null,
    lcarsCode: String = "47-01",
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (tokens.language == DesignLanguage.LCARS) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(tokens.accentSecondary)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = lcarsCode,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = LinkpointContrast.inkOn(tokens.accentSecondary, listOf(Color.Black, Color.White)),
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = tokens.monoFont
                                )
                            )
                        }
                    }
                    Text(
                        text = tokens.formatHeader(title),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = tokens.displayFont,
                            fontSize = when (tokens.language) {
                                DesignLanguage.METRO -> 24.sp
                                DesignLanguage.RULE_AND_RAIL -> 15.sp
                                else -> 18.sp
                            },
                            fontWeight = if (tokens.language == DesignLanguage.METRO) FontWeight.Light else FontWeight.Bold,
                            color = if (tokens.language == DesignLanguage.LCARS) tokens.accentPrimary else tokens.textPrimary,
                            letterSpacing = when (tokens.language) {
                                DesignLanguage.RULE_AND_RAIL -> 2.8.sp
                                DesignLanguage.LCARS -> 1.4.sp
                                DesignLanguage.PRESS -> 0.5.sp
                                else -> 0.sp
                            }
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = if (tokens.language == DesignLanguage.PRESS) tokens.bodyFont else tokens.monoFont,
                            color = tokens.textSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("close_floater_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close panel",
                    tint = tokens.textSecondary
                )
            }
        }
        if (tokens.language == DesignLanguage.RULE_AND_RAIL || tokens.language == DesignLanguage.PRESS) {
            HorizontalDivider(
                thickness = 1.dp,
                color = if (tokens.language == DesignLanguage.RULE_AND_RAIL) tokens.accentPrimary.copy(alpha = 0.55f)
                else tokens.borderHairline,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

/**
 * Adaptive Live Telemetry Tile across all 6 Design Languages.
 * Uses unboxed pure-black readout styling in LCARS mode and hairline framing in Rule & Rail.
 */
@Composable
fun LinkpointLiveTile(
    title: String,
    metric: String,
    subtitle: String,
    accent: Color,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalLinkpointDesign.current
    val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(14.dp))
    val fill = when (tokens.language) {
        DesignLanguage.METRO -> accent
        DesignLanguage.LCARS -> LcarsBlackField
        DesignLanguage.RULE_AND_RAIL -> tokens.canvasVoid
        DesignLanguage.TERMINAL, DesignLanguage.PRESS -> tokens.surfaceElevated
        DesignLanguage.POLISHED -> tokens.surfaceElevated.copy(alpha = 0.85f)
    }
    val effectiveFill = LinkpointContrast.compositeOver(fill, tokens.canvasVoid)
    val fg = if (tokens.language == DesignLanguage.METRO) {
        LinkpointContrast.inkOn(accent, listOf(Color.White, Color.Black))
    } else {
        LinkpointContrast.ensureMinContrast(tokens.textPrimary, effectiveFill, 4.5)
    }
    val subFg = if (tokens.language == DesignLanguage.METRO) {
        fg.copy(alpha = 0.88f)
    } else {
        LinkpointContrast.ensureMinContrast(tokens.textSecondary, effectiveFill, 4.5)
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(
                width = when (tokens.language) {
                    DesignLanguage.METRO, DesignLanguage.LCARS -> 0.dp
                    else -> 1.dp
                },
                color = when (tokens.language) {
                    DesignLanguage.RULE_AND_RAIL -> accent.copy(alpha = 0.7f)
                    else -> tokens.borderHairline
                },
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (tokens.language == DesignLanguage.METRO) fg
                else LinkpointContrast.ensureMinContrast(accent, effectiveFill, 3.0),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = metric,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = tokens.monoFont,
                    fontWeight = FontWeight.Bold,
                    color = if (tokens.language == DesignLanguage.LCARS) {
                        LinkpointContrast.ensureMinContrast(accent, LcarsBlackField, 4.5)
                    } else fg
                )
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = tokens.formatHeader(title),
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = tokens.displayFont,
                fontWeight = FontWeight.Bold,
                color = fg,
                letterSpacing = if (tokens.language == DesignLanguage.RULE_AND_RAIL) 1.6.sp else 0.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall.copy(color = subFg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
