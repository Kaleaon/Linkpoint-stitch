package com.example.linkpoint.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Multi-language surface container adapting geometry, chrome framing, and texture
 * across Polished (spatial glass), Terminal (scanlines + 1dp phosphor border),
 * Sweep Console / LCARS (L-shaped curved elbow spine), and Metro (sharp 0dp flat block).
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
            // Authentic LCARS curved sweeping elbow frame wrapping left spine + top header rail
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 20.dp, topEnd = 8.dp, bottomEnd = 8.dp))
                    .background(bg)
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val spineW = 16.dp.toPx()
                    val topH = 8.dp.toPx()
                    val innerR = 14.dp.toPx()
                    // Sweeping L-elbow path
                    val path = Path().apply {
                        moveTo(0f, size.height)
                        lineTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width, topH)
                        lineTo(spineW + innerR, topH)
                        quadraticTo(spineW, topH, spineW, topH + innerR)
                        lineTo(spineW, size.height)
                        close()
                    }
                    drawPath(path, color = accent)
                    // Secondary LCARS lilac lower spine segment
                    drawRect(
                        color = tokens.accentSecondary,
                        topLeft = Offset(0f, size.height * 0.56f),
                        size = Size(spineW, size.height * 0.28f)
                    )
                    // Tertiary periwinkle bottom cap
                    drawRect(
                        color = tokens.accentTertiary,
                        topLeft = Offset(0f, size.height * 0.87f),
                        size = Size(spineW, size.height * 0.13f)
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, top = 14.dp, end = 12.dp, bottom = 12.dp),
                    content = content
                )
            }
        }

        DesignLanguage.TERMINAL -> {
            // Crisp phosphor-bordered console with subtle CRT scanlines
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
            // Pure sharp 0dp flat geometric blade with crisp top accent bar
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

        DesignLanguage.POLISHED -> {
            // Refined linkpoint-design translucent glass with top-edge specular hairline
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
        .heightIn(min = 48.dp)
        .let { if (testTag != null) it.testTag(testTag) else it }

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
                    letterSpacing = if (tokens.language == DesignLanguage.LCARS) 1.sp else 0.2.sp
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
                containerColor = tokens.surfaceElevated.copy(alpha = 0.72f),
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
                    fontWeight = FontWeight.SemiBold
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
            DesignLanguage.METRO -> 0.dp
            DesignLanguage.TERMINAL -> 2.dp
            DesignLanguage.POLISHED -> 10.dp
        }
    )
    val formatted = when (tokens.language) {
        DesignLanguage.LCARS -> label.uppercase()
        DesignLanguage.TERMINAL -> "[$label]"
        DesignLanguage.METRO -> label.lowercase()
        DesignLanguage.POLISHED -> label
    }
    val clickMod = if (onClick != null) modifier.clickable(onClick = onClick) else modifier

    Row(
        modifier = clickMod
            .clip(shape)
            .background(color.copy(alpha = if (tokens.language == DesignLanguage.LCARS) 0.92f else 0.16f))
            .border(1.dp, color.copy(alpha = 0.75f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(if (tokens.language == DesignLanguage.METRO) 0.dp else 99.dp))
                .background(if (tokens.language == DesignLanguage.LCARS) Color.Black else color)
        )
        Text(
            text = formatted,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = tokens.monoFont,
                fontWeight = FontWeight.Bold,
                color = if (tokens.language == DesignLanguage.LCARS) Color.Black else color
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Adaptive Linkpoint Floater Header — renders LCARS capsule band, Metro oversized lowercase
 * panorama title, Terminal monospace banner, or Polished glass title bar with close control.
 */
@Composable
fun LinkpointFloaterHeader(
    title: String,
    subtitle: String? = null,
    lcarsCode: String = "47-01",
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
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
                                color = Color.Black,
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
                        fontSize = if (tokens.language == DesignLanguage.METRO) 24.sp else 18.sp,
                        fontWeight = if (tokens.language == DesignLanguage.METRO) FontWeight.Light else FontWeight.Bold,
                        color = if (tokens.language == DesignLanguage.LCARS) tokens.accentPrimary else tokens.textPrimary,
                        letterSpacing = if (tokens.language == DesignLanguage.LCARS) 1.4.sp else 0.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = tokens.monoFont,
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
}

/**
 * Metro-style Live Telemetry Tile (adapts cleanly to all 4 Design Languages when used in dashboards/docks).
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
        DesignLanguage.LCARS -> accent.copy(alpha = 0.20f)
        DesignLanguage.TERMINAL -> tokens.surfaceElevated
        DesignLanguage.POLISHED -> tokens.surfaceElevated.copy(alpha = 0.85f)
    }
    val fg = if (tokens.language == DesignLanguage.METRO) Color.White else tokens.textPrimary
    val subFg = if (tokens.language == DesignLanguage.METRO) Color.White.copy(alpha = 0.85f) else tokens.textSecondary

    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(
                width = if (tokens.language == DesignLanguage.METRO) 0.dp else 1.dp,
                color = if (tokens.language == DesignLanguage.LCARS) accent else tokens.borderHairline,
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
                tint = if (tokens.language == DesignLanguage.METRO) Color.White else accent,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = metric,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = tokens.monoFont,
                    fontWeight = FontWeight.Bold,
                    color = fg
                )
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = tokens.formatHeader(title),
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = tokens.displayFont,
                fontWeight = FontWeight.Bold,
                color = fg
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
