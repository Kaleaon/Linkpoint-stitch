package com.example.linkpoint.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CleverFerret's revised pure `#000000` black-field LCARS split-frame opposing elbows
 * with crisp black gap breaks and unboxed telemetry readouts.
 *
 * Unlike nested translucent card containers, LCARS 24c surfaces sit on an absolute
 * `#000000` void bounded by a top-left sweeping elbow and an opposing bottom-right
 * sweeping elbow separated into colored rail segments.
 */
val LcarsBlackField = Color(0xFF000000)

@Composable
fun LcarsOpposingElbowsFrame(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    codeTag: String = "LCARS-24C",
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    onAccentColor: Color = Color.Black,
    elbowArmWidth: Dp = 24.dp,
    topBarHeight: Dp = 28.dp,
    bottomBarHeight: Dp = 16.dp,
    onClose: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LinkpointTheme.tokens
    val primarySweep = LinkpointContrast.ensureMinContrast(primaryColor, LcarsBlackField, 3.0)
    val secondarySweep = LinkpointContrast.ensureMinContrast(secondaryColor, LcarsBlackField, 3.0)
    val tertiarySweep = LinkpointContrast.ensureMinContrast(tertiaryColor, LcarsBlackField, 3.0)
    val headerInk = LinkpointContrast.inkOn(LcarsBlackField, listOf(primarySweep, Color.White))
    val closeInk = LinkpointContrast.inkOn(tertiarySweep, listOf(onAccentColor, Color.Black, Color.White))

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 24.dp))
            .background(LcarsBlackField)
            .semantics { contentDescription = "LCARS Opposing Elbows Frame" }
    ) {
        // Split-frame opposing elbows vector geometry on pure #000000 field
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val armW = elbowArmWidth.toPx().coerceAtMost(w * 0.18f)
            val topH = topBarHeight.toPx().coerceAtMost(h * 0.28f)
            val botH = bottomBarHeight.toPx().coerceAtMost(h * 0.22f)
            val gap = 4.dp.toPx()
            val outerR = 24.dp.toPx().coerceAtMost(minOf(w, h) * 0.35f)
            val innerR = 12.dp.toPx()

            // 1. Top-Left Sweeping Elbow (left vertical spine + top bar head)
            val topElbowLegBottom = (h - botH - gap * 2f).coerceAtLeast(topH + innerR + 8f)
            val topSweepRight = (w * 0.34f).coerceAtLeast(armW + innerR + 16f)
            val topElbowSegmentBottom = (topH + (topElbowLegBottom - topH) * 0.56f).coerceAtMost(topElbowLegBottom)

            val topLeftPath = buildTopLeftElbowPath(
                outerRadius = outerR,
                innerRadius = innerR,
                armWidth = armW,
                barHeight = topH,
                barRight = topSweepRight,
                legBottom = topElbowSegmentBottom
            )
            drawPath(path = topLeftPath, color = primarySweep)

            // Split lower-left rail pill below black gap
            val lowerRailTop = topElbowSegmentBottom + gap
            if (topElbowLegBottom - lowerRailTop > 10f) {
                drawRoundRect(
                    color = secondarySweep,
                    topLeft = Offset(0f, lowerRailTop),
                    size = Size(armW, topElbowLegBottom - lowerRailTop),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }

            // Bottom-left anchor cap pill (opposing the bottom-right elbow)
            val bottomLeftPillWidth = (w * 0.22f).coerceAtLeast(armW)
            drawRoundRect(
                color = tertiarySweep,
                topLeft = Offset(0f, h - botH),
                size = Size(bottomLeftPillWidth, botH),
                cornerRadius = CornerRadius(botH / 2f, botH / 2f)
            )

            // 2. Opposing Bottom-Right Sweeping Elbow (bottom bar + right upward spine)
            val rightArmW = (armW * 0.72f).coerceAtLeast(12.dp.toPx())
            val rightLegTop = (topH + gap * 2f + (h - topH - botH) * 0.32f).coerceAtMost(h - botH - innerR - 6f)
            val botSweepLeft = (bottomLeftPillWidth + gap).coerceAtMost(w - rightArmW - innerR - 8f)

            if (h > topH + botH + 28.dp.toPx() && w > bottomLeftPillWidth + rightArmW + 24.dp.toPx()) {
                val bottomRightPath = buildBottomRightElbowPath(
                    width = w,
                    height = h,
                    outerRadius = outerR.coerceAtMost(22.dp.toPx()),
                    innerRadius = innerR.coerceAtMost(10.dp.toPx()),
                    armWidth = rightArmW,
                    barHeight = botH,
                    barLeft = botSweepLeft,
                    legTop = rightLegTop
                )
                drawPath(path = bottomRightPath, color = secondarySweep)

                // Split upper-right opposing rail tick above the bottom-right elbow
                val upperRightHeight = (rightLegTop - topH - gap * 3f).coerceAtLeast(0f)
                if (upperRightHeight > 12f) {
                    drawRoundRect(
                        color = primarySweep.copy(alpha = 0.85f),
                        topLeft = Offset(w - rightArmW, topH + gap * 2f),
                        size = Size(rightArmW, upperRightHeight),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                    )
                }
            }
        }

        // Foreground content & header row inside the split-frame black field
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = elbowArmWidth + 10.dp,
                    end = (elbowArmWidth * 0.72f) + 10.dp,
                    top = 2.dp,
                    bottom = bottomBarHeight + 8.dp
                )
        ) {
            // Top bar readout cutout (unboxed title & telemetry pills)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topBarHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Spacer(modifier = Modifier.weight(0.28f))
                if (title != null) {
                    Text(
                        text = title.uppercase(),
                        fontFamily = tokens.displayFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 1.6.sp,
                        color = headerInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .background(LcarsBlackField)
                            .padding(horizontal = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(0.72f))
                Box(
                    modifier = Modifier
                        .height(topBarHeight - 6.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(secondarySweep)
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (subtitle ?: codeTag).uppercase(),
                        fontFamily = tokens.monoFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        letterSpacing = 1.0.sp,
                        color = LinkpointContrast.inkOn(secondarySweep, listOf(Color.Black, Color.White)),
                        maxLines = 1
                    )
                }
                if (onClose != null) {
                    Row(
                        modifier = Modifier
                            .height(topBarHeight - 6.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(tertiarySweep)
                            .clickable(onClick = onClose)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = closeInk,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "END",
                            fontFamily = tokens.displayFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            letterSpacing = 0.8.sp,
                            color = closeInk
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

/**
 * Unboxed LCARS telemetry readout row rendered directly on the `#000000` black field
 * without card borders or translucent boxes.
 */
@Composable
fun LcarsUnboxedReadout(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    val tokens = LinkpointTheme.tokens
    val safeAccent = LinkpointContrast.ensureMinContrast(accentColor, LcarsBlackField, 4.5)
    val safePrimary = LinkpointContrast.ensureMinContrast(tokens.textPrimary, LcarsBlackField, 4.5)
    val safeSecondary = LinkpointContrast.ensureMinContrast(tokens.textSecondary, LcarsBlackField, 4.5)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(safeAccent)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label.uppercase(),
                fontFamily = tokens.displayFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                letterSpacing = 1.4.sp,
                color = safeSecondary
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    fontFamily = tokens.monoFont,
                    fontSize = 10.sp,
                    color = safeSecondary.copy(alpha = 0.88f)
                )
            }
        }
        Text(
            text = value,
            fontFamily = tokens.monoFont,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = 0.8.sp,
            color = safeAccent
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = "▮",
            fontFamily = tokens.monoFont,
            fontSize = 10.sp,
            color = safePrimary
        )
    }
}

private fun buildTopLeftElbowPath(
    outerRadius: Float,
    innerRadius: Float,
    armWidth: Float,
    barHeight: Float,
    barRight: Float,
    legBottom: Float
): Path = Path().apply {
    val safeOuter = outerRadius.coerceAtMost(minOf(barRight, legBottom))
    val safeInner = innerRadius.coerceAtMost(minOf(barRight - armWidth, legBottom - barHeight).coerceAtLeast(0f))

    moveTo(0f, legBottom)
    lineTo(0f, safeOuter)
    arcTo(
        rect = Rect(0f, 0f, safeOuter * 2f, safeOuter * 2f),
        startAngleDegrees = 180f,
        sweepAngleDegrees = 90f,
        forceMoveTo = false
    )
    lineTo(barRight, 0f)
    lineTo(barRight, barHeight)
    lineTo(armWidth + safeInner, barHeight)
    if (safeInner > 0.5f) {
        arcTo(
            rect = Rect(armWidth, barHeight, armWidth + safeInner * 2f, barHeight + safeInner * 2f),
            startAngleDegrees = 270f,
            sweepAngleDegrees = -90f,
            forceMoveTo = false
        )
    } else {
        lineTo(armWidth, barHeight)
    }
    lineTo(armWidth, legBottom)
    close()
}

private fun buildBottomRightElbowPath(
    width: Float,
    height: Float,
    outerRadius: Float,
    innerRadius: Float,
    armWidth: Float,
    barHeight: Float,
    barLeft: Float,
    legTop: Float
): Path = Path().apply {
    val safeOuter = outerRadius.coerceAtMost(minOf(width - barLeft, height - legTop))
    val safeInner = innerRadius.coerceAtMost(
        minOf((width - armWidth) - barLeft, (height - barHeight) - legTop).coerceAtLeast(0f)
    )

    moveTo(width, legTop)
    lineTo(width, height - safeOuter)
    arcTo(
        rect = Rect(width - safeOuter * 2f, height - safeOuter * 2f, width, height),
        startAngleDegrees = 0f,
        sweepAngleDegrees = 90f,
        forceMoveTo = false
    )
    lineTo(barLeft, height)
    lineTo(barLeft, height - barHeight)
    lineTo(width - armWidth - safeInner, height - barHeight)
    if (safeInner > 0.5f) {
        arcTo(
            rect = Rect(
                width - armWidth - safeInner * 2f,
                height - barHeight - safeInner * 2f,
                width - armWidth,
                height - barHeight
            ),
            startAngleDegrees = 90f,
            sweepAngleDegrees = -90f,
            forceMoveTo = false
        )
    } else {
        lineTo(width - armWidth, height - barHeight)
    }
    lineTo(width - armWidth, legTop)
    close()
}
