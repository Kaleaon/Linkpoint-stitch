package com.example.linkpoint.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * WCAG 2.1 / 2.2 relative luminance and contrast utilities matching
 * `linkpoint-design` / `react-linkpoint/src/theme/contrast.ts`.
 *
 * Ensures every combination of the 6 layout packs × 28+ Ktheme palettes maintains
 * accessible foreground-on-surface and on-accent contrast ratios.
 */
object LinkpointContrast {

    const val WCAG_AA_NORMAL_TEXT: Double = 4.5
    const val WCAG_AA_LARGE_TEXT: Double = 3.0
    const val WCAG_AA_UI_COMPONENT: Double = 3.0
    const val WCAG_AAA_NORMAL_TEXT: Double = 7.0

    val PureBlack = Color(0xFF000000)
    val PureWhite = Color(0xFFFFFFFF)
    val DefaultDarkInk = Color(0xFF0A0D12)
    val DefaultLightInk = Color(0xFFF6F8FC)

    /**
     * Converts a normalized [0.0..1.0] sRGB channel into linear-light intensity
     * using the standard IEC 61966-2-1 / WCAG transfer threshold (0.03928).
     */
    fun srgbChannelToLinear(channel01: Double): Double {
        val clamped = channel01.coerceIn(0.0, 1.0)
        return if (clamped <= 0.03928) {
            clamped / 12.92
        } else {
            ((clamped + 0.055) / 1.055).pow(2.4)
        }
    }

    /**
     * Computes WCAG relative luminance Y in [0.0..1.0] for an opaque sRGB [color].
     * If [color] is translucent and [backdrop] is provided, composites [color] over [backdrop] first.
     */
    fun relativeLuminance(color: Color, backdrop: Color? = null): Double {
        val resolved = if (backdrop != null && color.alpha < 0.999f) {
            compositeOver(color, backdrop)
        } else {
            color
        }
        val r = srgbChannelToLinear(resolved.red.toDouble())
        val g = srgbChannelToLinear(resolved.green.toDouble())
        val b = srgbChannelToLinear(resolved.blue.toDouble())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * Alpha-composites [foreground] over an opaque [background] in sRGB space.
     */
    fun compositeOver(foreground: Color, background: Color): Color {
        val fgA = foreground.alpha.coerceIn(0f, 1f)
        if (fgA >= 0.999f) return foreground.copy(alpha = 1f)
        val invA = 1f - fgA
        return Color(
            red = (foreground.red * fgA + background.red * invA).coerceIn(0f, 1f),
            green = (foreground.green * fgA + background.green * invA).coerceIn(0f, 1f),
            blue = (foreground.blue * fgA + background.blue * invA).coerceIn(0f, 1f),
            alpha = 1f
        )
    }

    /**
     * Computes WCAG contrast ratio in [1.0..21.0] between [fg] and [bg].
     * Transparent foreground colors are composited over [bg] before evaluation.
     */
    fun contrastRatio(fg: Color, bg: Color): Double {
        val lumFg = relativeLuminance(fg, backdrop = bg)
        val lumBg = relativeLuminance(bg)
        val lighter = max(lumFg, lumBg)
        val darker = min(lumFg, lumBg)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Checks whether [fg] on [bg] satisfies WCAG AA (4.5:1 normal text, 3.0:1 large text / UI).
     */
    fun meetsAA(fg: Color, bg: Color, largeText: Boolean = false): Boolean {
        val threshold = if (largeText) WCAG_AA_LARGE_TEXT else WCAG_AA_NORMAL_TEXT
        return contrastRatio(fg, bg) >= threshold
    }

    fun meetsAA(fg: Color, bg: Color, minRatio: Double): Boolean =
        contrastRatio(fg, bg) >= minRatio

    /**
     * Checks whether [fg] on [bg] satisfies WCAG AAA (7.0:1 normal text, 4.5:1 large text).
     */
    fun meetsAAA(fg: Color, bg: Color, largeText: Boolean = false): Boolean {
        val threshold = if (largeText) WCAG_AA_NORMAL_TEXT else WCAG_AAA_NORMAL_TEXT
        return contrastRatio(fg, bg) >= threshold
    }

    /**
     * Picks the first candidate ink color achieving [minRatio] against [bg],
     * or falls back to whichever candidate has the highest contrast ratio.
     */
    fun inkOn(
        bg: Color,
        candidates: List<Color> = listOf(DefaultDarkInk, DefaultLightInk, PureBlack, PureWhite),
        minRatio: Double = WCAG_AA_NORMAL_TEXT
    ): Color {
        for (candidate in candidates) {
            if (contrastRatio(candidate, bg) >= minRatio) {
                return candidate
            }
        }
        return candidates.maxByOrNull { contrastRatio(it, bg) } ?: PureWhite
    }

    /**
     * Shifts HSL lightness in 1% increments while preserving hue and saturation so [fg]
     * achieves at least [minRatio] against [bg], matching `ensureMinContrast` in `contrast.ts`.
     */
    fun ensureMinContrast(
        fg: Color,
        bg: Color,
        minRatio: Double = WCAG_AA_NORMAL_TEXT
    ): Color {
        val opaqueBg = bg.copy(alpha = 1f)
        val compositedFg = if (fg.alpha < 0.999f) compositeOver(fg, opaqueBg) else fg
        if (contrastRatio(compositedFg, opaqueBg) >= minRatio) {
            return compositedFg
        }

        val (hue, sat, startLightness) = rgbToHsl(compositedFg)
        val bgLum = relativeLuminance(opaqueBg)
        // Prefer brightening on dark surfaces (luminance <= ~0.179 favors white, < 0.5 in general)
        val preferLighter = bgLum < 0.5
        val primaryDelta = if (preferLighter) 0.01 else -0.01

        searchDirection(hue, sat, startLightness, primaryDelta, opaqueBg, minRatio)?.let { return it }
        searchDirection(hue, sat, startLightness, -primaryDelta, opaqueBg, minRatio)?.let { return it }

        // Desaturate near extremes when chromatic saturation caps luminance
        val nearWhite = hslToColor(hue, 0.0, 0.98)
        val nearBlack = hslToColor(hue, 0.0, 0.02)
        val firstNeutral = if (preferLighter) nearWhite else nearBlack
        val secondNeutral = if (preferLighter) nearBlack else nearWhite
        if (contrastRatio(firstNeutral, opaqueBg) >= minRatio) return firstNeutral
        if (contrastRatio(secondNeutral, opaqueBg) >= minRatio) return secondNeutral

        return inkOn(opaqueBg, listOf(PureWhite, PureBlack), minRatio)
    }

    private fun searchDirection(
        hue: Double,
        sat: Double,
        startL: Double,
        step: Double,
        bg: Color,
        minRatio: Double
    ): Color? {
        var currentL = startL
        repeat(100) {
            currentL = (currentL + step).coerceIn(0.0, 1.0)
            val candidate = hslToColor(hue, sat, currentL)
            if (contrastRatio(candidate, bg) >= minRatio) {
                return candidate
            }
            if (currentL <= 0.0 || currentL >= 1.0) return null
        }
        return null
    }

    private data class Hsl(val h: Double, val s: Double, val l: Double)

    private fun rgbToHsl(color: Color): Hsl {
        val r = color.red.toDouble().coerceIn(0.0, 1.0)
        val g = color.green.toDouble().coerceIn(0.0, 1.0)
        val b = color.blue.toDouble().coerceIn(0.0, 1.0)
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) / 2.0
        if (abs(maxC - minC) < 1e-7) {
            return Hsl(0.0, 0.0, l)
        }
        val d = maxC - minC
        val s = if (l > 0.5) d / (2.0 - maxC - minC) else d / (maxC + minC)
        val hRaw = when (maxC) {
            r -> ((g - b) / d + if (g < b) 6.0 else 0.0)
            g -> ((b - r) / d + 2.0)
            else -> ((r - g) / d + 4.0)
        }
        return Hsl((hRaw * 60.0) % 360.0, s.coerceIn(0.0, 1.0), l.coerceIn(0.0, 1.0))
    }

    private fun hslToColor(hueDeg: Double, sat: Double, lightness: Double): Color {
        val h = ((hueDeg % 360.0) + 360.0) % 360.0
        val s = sat.coerceIn(0.0, 1.0)
        val l = lightness.coerceIn(0.0, 1.0)
        val c = (1.0 - abs(2.0 * l - 1.0)) * s
        val x = c * (1.0 - abs((h / 60.0) % 2.0 - 1.0))
        val m = l - c / 2.0
        val (r1, g1, b1) = when {
            h < 60.0 -> Triple(c, x, 0.0)
            h < 120.0 -> Triple(x, c, 0.0)
            h < 180.0 -> Triple(0.0, c, x)
            h < 240.0 -> Triple(0.0, x, c)
            h < 300.0 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return Color(
            red = ((r1 + m) * 255.0).roundToInt().coerceIn(0, 255) / 255f,
            green = ((g1 + m) * 255.0).roundToInt().coerceIn(0, 255) / 255f,
            blue = ((b1 + m) * 255.0).roundToInt().coerceIn(0, 255) / 255f,
            alpha = 1f
        )
    }

    /**
     * Summary of audited WCAG contrast ratios for a resolved [LinkpointDesignTokens] instance.
     */
    data class ContrastAuditReport(
        val textOnFloater: Double,
        val textOnCanvas: Double,
        val secondaryTextOnFloater: Double,
        val onAccentOnPrimary: Double,
        val accentOnFloater: Double
    ) {
        val passesAA: Boolean
            get() = textOnFloater >= WCAG_AA_NORMAL_TEXT &&
                textOnCanvas >= WCAG_AA_NORMAL_TEXT &&
                secondaryTextOnFloater >= WCAG_AA_NORMAL_TEXT &&
                onAccentOnPrimary >= WCAG_AA_NORMAL_TEXT &&
                accentOnFloater >= WCAG_AA_UI_COMPONENT

        val passesAllAA: Boolean get() = passesAA
    }

    fun auditTokens(tokens: LinkpointDesignTokens): ContrastAuditReport {
        val floaterBg = compositeOver(tokens.surfaceFloater, tokens.canvasVoid)
        return ContrastAuditReport(
            textOnFloater = contrastRatio(tokens.textPrimary, floaterBg),
            textOnCanvas = contrastRatio(tokens.textPrimary, tokens.canvasVoid),
            secondaryTextOnFloater = contrastRatio(tokens.textSecondary, floaterBg),
            onAccentOnPrimary = contrastRatio(tokens.onAccent, tokens.accentPrimary),
            accentOnFloater = contrastRatio(tokens.accentPrimary, floaterBg)
        )
    }
}
