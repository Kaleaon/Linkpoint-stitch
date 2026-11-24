package com.example.linkpoint.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

val SpaceGroteskFont = FontFamily(Font(R.font.space_grotesk, FontWeight.Normal))
val PlusJakartaSansFont = FontFamily(Font(R.font.plus_jakarta_sans, FontWeight.Normal))
val JetBrainsMonoFont = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))
val EditorialSerifFont = FontFamily.Serif

@Immutable
data class LinkpointDesignTokens(
    val language: DesignLanguage,
    val kthemeId: String,
    val isLight: Boolean,
    val canvasVoid: Color,
    val surfaceChrome: Color,
    val surfaceFloater: Color,
    val surfaceElevated: Color,
    val borderHairline: Color,
    val borderSpecular: Color,
    val accentPrimary: Color,
    val accentSecondary: Color,
    val accentTertiary: Color,
    val statusOnline: Color,
    val statusWarning: Color,
    val statusError: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val onAccent: Color,
    val cornerRadius: Dp,
    val buttonRadius: Dp,
    val borderWidth: Dp,
    val panelOpacity: Float,
    val densityScale: Float,
    val displayFont: FontFamily,
    val bodyFont: FontFamily,
    val monoFont: FontFamily
) {
    val surfaceCanvas: Color get() = canvasVoid
    fun formatHeader(raw: String): String = when (language) {
        DesignLanguage.LCARS -> raw.uppercase()
        DesignLanguage.METRO -> raw.lowercase()
        DesignLanguage.TERMINAL -> "[${raw.uppercase()}]"
        DesignLanguage.RULE_AND_RAIL -> raw.uppercase()
        DesignLanguage.PRESS -> raw
        DesignLanguage.POLISHED -> raw
    }

    fun formatAction(raw: String): String = when (language) {
        DesignLanguage.LCARS -> raw.uppercase()
        DesignLanguage.TERMINAL -> "> ${raw.uppercase()}"
        DesignLanguage.METRO -> raw.lowercase()
        DesignLanguage.RULE_AND_RAIL -> raw.uppercase()
        DesignLanguage.PRESS -> raw
        DesignLanguage.POLISHED -> raw
    }
}

val LocalLinkpointDesign = staticCompositionLocalOf {
    resolveLinkpointTokens(PersonalDesignState())
}

fun resolveAccentColor(preset: AccentPreset): Triple<Color, Color, Color> = when (preset) {
    AccentPreset.AZURE_CYAN -> Triple(Color(0xFF38BDF8), Color(0xFF22D3EE), Color(0xFFF59E0B))
    AccentPreset.PHOSPHOR_GREEN -> Triple(Color(0xFF22C55E), Color(0xFF4ADE80), Color(0xFFFACC15))
    AccentPreset.LCARS_APRICOT -> Triple(Color(0xFFFF9966), Color(0xFFCC99CC), Color(0xFF9999FF))
    AccentPreset.METRO_COBALT -> Triple(Color(0xFF0078D7), Color(0xFF00B294), Color(0xFFE3008C))
    AccentPreset.BEACON_AMBER -> Triple(Color(0xFFF59E0B), Color(0xFFFB923C), Color(0xFF38BDF8))
    AccentPreset.NEON_MAGENTA -> Triple(Color(0xFFE3008C), Color(0xFFA855F7), Color(0xFF22D3EE))
}

/**
 * Resolves full semantic + layout tokens for any combination of the 6 [DesignLanguage] packs
 * and 28+ [KthemeCatalog] palettes, enforcing WCAG AA contrast via [LinkpointContrast].
 */
fun resolveLinkpointTokens(designState: PersonalDesignState): LinkpointDesignTokens {
    val explicitPalette = KthemeCatalog.byId(designState.kthemeId)
    val fallbackPaletteId = KthemeCatalog.defaultIdForLanguage(
        designState.designLanguage,
        designState.useLightPosture && designState.designLanguage.supportsLightPosture
    )
    val palette = explicitPalette ?: KthemeCatalog.byIdOrDefault(fallbackPaletteId)

    val (presetAccent1, presetAccent2, presetAccent3) = resolveAccentColor(designState.accentPreset)
    val usePaletteAccents = explicitPalette != null
    val rawAccent1 = if (usePaletteAccents) palette.pri else presetAccent1
    val rawAccent2 = if (usePaletteAccents) palette.sec else presetAccent2
    val rawAccent3 = if (usePaletteAccents) palette.sec2 else presetAccent3

    val opacity = designState.panelOpacity.coerceIn(0.55f, 1.0f)

    // CleverFerret's revised LCARS split-frame always sits on a pure #000000 black field.
    val isLcarsBlackField = designState.designLanguage == DesignLanguage.LCARS
    val effectiveLight = if (isLcarsBlackField) {
        false
    } else if (explicitPalette != null) {
        palette.isLight
    } else {
        designState.useLightPosture && designState.designLanguage.supportsLightPosture
    }

    val canvasVoid = when {
        isLcarsBlackField -> LcarsBlackField
        else -> palette.bg
    }
    val rawFloaterBase = when {
        isLcarsBlackField -> LcarsBlackField
        designState.designLanguage == DesignLanguage.RULE_AND_RAIL -> palette.bg
        else -> palette.surf
    }
    val surfaceChrome = if (isLcarsBlackField) {
        LcarsBlackField
    } else {
        palette.surf.copy(alpha = (opacity + 0.04f).coerceAtMost(1f))
    }
    val surfaceFloater = if (isLcarsBlackField) {
        LcarsBlackField
    } else {
        rawFloaterBase.copy(alpha = (opacity + 0.06f).coerceAtMost(1f))
    }
    val surfaceElevated = if (isLcarsBlackField) {
        Color(0xFF101018)
    } else {
        palette.surf2
    }

    // Composite surface over canvasVoid so WCAG contrast holds at user's chosen panelOpacity.
    val effectiveFloaterBg = LinkpointContrast.compositeOver(surfaceFloater, canvasVoid)

    val textPrimaryOnFloater = LinkpointContrast.ensureMinContrast(
        fg = if (isLcarsBlackField && palette.isLight) Color(0xFFFFF3E0) else palette.ink,
        bg = effectiveFloaterBg,
        minRatio = LinkpointContrast.WCAG_AA_NORMAL_TEXT
    )
    val textPrimary = LinkpointContrast.ensureMinContrast(
        fg = textPrimaryOnFloater,
        bg = canvasVoid,
        minRatio = LinkpointContrast.WCAG_AA_NORMAL_TEXT
    )

    val textSecondary = LinkpointContrast.ensureMinContrast(
        fg = if (isLcarsBlackField && palette.isLight) Color(0xFFFFCC99) else palette.ink2,
        bg = effectiveFloaterBg,
        minRatio = LinkpointContrast.WCAG_AA_NORMAL_TEXT
    )

    val textMuted = LinkpointContrast.ensureMinContrast(
        fg = palette.ink2.copy(alpha = 0.78f),
        bg = effectiveFloaterBg,
        minRatio = LinkpointContrast.WCAG_AA_LARGE_TEXT
    )

    val accentPrimary = LinkpointContrast.ensureMinContrast(
        fg = rawAccent1,
        bg = effectiveFloaterBg,
        minRatio = LinkpointContrast.WCAG_AA_UI_COMPONENT
    )
    val accentSecondary = LinkpointContrast.ensureMinContrast(
        fg = rawAccent2,
        bg = effectiveFloaterBg,
        minRatio = LinkpointContrast.WCAG_AA_UI_COMPONENT
    )
    val accentTertiary = LinkpointContrast.ensureMinContrast(
        fg = rawAccent3,
        bg = effectiveFloaterBg,
        minRatio = LinkpointContrast.WCAG_AA_UI_COMPONENT
    )

    val rawOnAccentCandidate = if (usePaletteAccents) palette.onPri else {
        if (effectiveLight) Color.White else Color(0xFF06101A)
    }
    val onAccent = LinkpointContrast.ensureMinContrast(
        fg = rawOnAccentCandidate,
        bg = accentPrimary,
        minRatio = LinkpointContrast.WCAG_AA_NORMAL_TEXT
    )

    val statusOnline = LinkpointContrast.ensureMinContrast(palette.ok, effectiveFloaterBg, 3.0)
    val statusWarning = LinkpointContrast.ensureMinContrast(palette.warn, effectiveFloaterBg, 3.0)
    val statusError = LinkpointContrast.ensureMinContrast(palette.err, effectiveFloaterBg, 3.0)

    val borderHairline = when (designState.designLanguage) {
        DesignLanguage.LCARS -> accentPrimary
        DesignLanguage.TERMINAL -> accentPrimary.copy(alpha = 0.6f)
        DesignLanguage.RULE_AND_RAIL -> accentPrimary.copy(alpha = 0.72f)
        DesignLanguage.PRESS -> LinkpointContrast.ensureMinContrast(palette.outlineVariant, effectiveFloaterBg, 2.2)
        else -> palette.outlineVariant
    }

    val borderSpecular = when (designState.designLanguage) {
        DesignLanguage.LCARS -> accentSecondary
        DesignLanguage.RULE_AND_RAIL -> accentPrimary
        DesignLanguage.PRESS -> textPrimary.copy(alpha = 0.45f)
        else -> if (effectiveLight) Color.White else accentPrimary.copy(alpha = 0.65f)
    }

    val (cornerRadius, buttonRadius, borderWidth) = when (designState.designLanguage) {
        DesignLanguage.POLISHED -> Triple(16.dp, 12.dp, 1.dp)
        DesignLanguage.TERMINAL -> Triple(2.dp, 2.dp, 1.25.dp)
        DesignLanguage.LCARS -> Triple(24.dp, 99.dp, 2.dp)
        DesignLanguage.METRO -> Triple(0.dp, 0.dp, 2.dp)
        DesignLanguage.RULE_AND_RAIL -> Triple(0.dp, 0.dp, 1.dp)
        DesignLanguage.PRESS -> Triple(3.dp, 2.dp, 1.dp)
    }

    val (displayFont, bodyFont) = when (designState.designLanguage) {
        DesignLanguage.TERMINAL -> JetBrainsMonoFont to JetBrainsMonoFont
        DesignLanguage.LCARS -> SpaceGroteskFont to SpaceGroteskFont
        DesignLanguage.PRESS -> SpaceGroteskFont to EditorialSerifFont
        DesignLanguage.RULE_AND_RAIL -> SpaceGroteskFont to PlusJakartaSansFont
        DesignLanguage.METRO -> SpaceGroteskFont to PlusJakartaSansFont
        DesignLanguage.POLISHED -> SpaceGroteskFont to PlusJakartaSansFont
    }

    return LinkpointDesignTokens(
        language = designState.designLanguage,
        kthemeId = palette.id,
        isLight = effectiveLight,
        canvasVoid = canvasVoid,
        surfaceChrome = surfaceChrome,
        surfaceFloater = surfaceFloater,
        surfaceElevated = surfaceElevated,
        borderHairline = borderHairline,
        borderSpecular = borderSpecular,
        accentPrimary = accentPrimary,
        accentSecondary = accentSecondary,
        accentTertiary = accentTertiary,
        statusOnline = statusOnline,
        statusWarning = statusWarning,
        statusError = statusError,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textMuted = textMuted,
        onAccent = onAccent,
        cornerRadius = cornerRadius,
        buttonRadius = buttonRadius,
        borderWidth = borderWidth,
        panelOpacity = opacity,
        densityScale = designState.uiDensity.scale,
        displayFont = displayFont,
        bodyFont = bodyFont,
        monoFont = JetBrainsMonoFont
    )
}

@Composable
fun LinkpointTheme(
    designState: PersonalDesignState,
    content: @Composable () -> Unit
) {
    val tokens = resolveLinkpointTokens(designState)
    val colorScheme = if (tokens.isLight) {
        lightColorScheme(
            primary = tokens.accentPrimary,
            onPrimary = tokens.onAccent,
            secondary = tokens.accentSecondary,
            tertiary = tokens.accentTertiary,
            background = tokens.canvasVoid,
            onBackground = tokens.textPrimary,
            surface = tokens.surfaceFloater,
            onSurface = tokens.textPrimary,
            surfaceVariant = tokens.surfaceElevated,
            onSurfaceVariant = tokens.textSecondary,
            outline = tokens.borderHairline,
            error = tokens.statusError
        )
    } else {
        darkColorScheme(
            primary = tokens.accentPrimary,
            onPrimary = tokens.onAccent,
            secondary = tokens.accentSecondary,
            tertiary = tokens.accentTertiary,
            background = tokens.canvasVoid,
            onBackground = tokens.textPrimary,
            surface = tokens.surfaceFloater,
            onSurface = tokens.textPrimary,
            surfaceVariant = tokens.surfaceElevated,
            onSurfaceVariant = tokens.textSecondary,
            outline = tokens.borderHairline,
            error = tokens.statusError
        )
    }

    val s = tokens.densityScale
    val headerTracking = when (tokens.language) {
        DesignLanguage.RULE_AND_RAIL -> 2.2.sp
        DesignLanguage.LCARS -> 1.2.sp
        DesignLanguage.PRESS -> 0.4.sp
        else -> 0.sp
    }
    val bodyLineHeightMultiplier = if (tokens.language == DesignLanguage.PRESS) 1.52f else 1.38f

    val typography = Typography(
        headlineMedium = TextStyle(
            fontFamily = tokens.displayFont,
            fontWeight = if (tokens.language == DesignLanguage.METRO) FontWeight.Light else FontWeight.Bold,
            fontSize = (22 * s).sp,
            lineHeight = (28 * s).sp,
            letterSpacing = headerTracking
        ),
        titleLarge = TextStyle(
            fontFamily = tokens.displayFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = (18 * s).sp,
            lineHeight = (24 * s).sp,
            letterSpacing = headerTracking
        ),
        titleMedium = TextStyle(
            fontFamily = tokens.displayFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = (15 * s).sp,
            lineHeight = (20 * s).sp
        ),
        bodyLarge = TextStyle(
            fontFamily = tokens.bodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = (14 * s).sp,
            lineHeight = (14 * s * bodyLineHeightMultiplier).sp
        ),
        bodyMedium = TextStyle(
            fontFamily = tokens.bodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = (13 * s).sp,
            lineHeight = (13 * s * bodyLineHeightMultiplier).sp
        ),
        labelLarge = TextStyle(
            fontFamily = tokens.displayFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = (13 * s).sp,
            lineHeight = (16 * s).sp
        ),
        labelSmall = TextStyle(
            fontFamily = tokens.monoFont,
            fontWeight = FontWeight.Medium,
            fontSize = (11 * s).sp,
            lineHeight = (14 * s).sp
        )
    )

    CompositionLocalProvider(LocalLinkpointDesign provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}

object LinkpointTheme {
    val tokens: LinkpointDesignTokens
        @Composable
        get() = LocalLinkpointDesign.current
}
