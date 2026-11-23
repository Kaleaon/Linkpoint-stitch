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

@Immutable
data class LinkpointDesignTokens(
    val language: DesignLanguage,
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
    fun formatHeader(raw: String): String = when (language) {
        DesignLanguage.LCARS -> raw.uppercase()
        DesignLanguage.METRO -> raw.lowercase()
        DesignLanguage.TERMINAL -> "[${raw.uppercase()}]"
        DesignLanguage.POLISHED -> raw
    }

    fun formatAction(raw: String): String = when (language) {
        DesignLanguage.LCARS -> raw.uppercase()
        DesignLanguage.TERMINAL -> "> ${raw.uppercase()}"
        DesignLanguage.METRO -> raw.lowercase()
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

fun resolveLinkpointTokens(designState: PersonalDesignState): LinkpointDesignTokens {
    val (accent1, accent2, accent3) = resolveAccentColor(designState.accentPreset)
    val opacity = designState.panelOpacity.coerceIn(0.55f, 1.0f)
    val light = designState.useLightPosture &&
        (designState.designLanguage == DesignLanguage.POLISHED || designState.designLanguage == DesignLanguage.METRO)

    return when (designState.designLanguage) {
        DesignLanguage.POLISHED -> if (light) {
            LinkpointDesignTokens(
                language = DesignLanguage.POLISHED,
                isLight = true,
                canvasVoid = Color(0xFFEBF1F8),
                surfaceChrome = Color(0xFFF7FAFF).copy(alpha = opacity),
                surfaceFloater = Color(0xFFFFFFFF).copy(alpha = (opacity + 0.04f).coerceAtMost(1f)),
                surfaceElevated = Color(0xFFE2EBF5),
                borderHairline = Color(0xFFCBD5E1),
                borderSpecular = Color(0xFFFFFFFF),
                accentPrimary = accent1,
                accentSecondary = accent2,
                accentTertiary = accent3,
                statusOnline = Color(0xFF059669),
                statusWarning = Color(0xFFD97706),
                statusError = Color(0xFFE11D48),
                textPrimary = Color(0xFF0F172A),
                textSecondary = Color(0xFF334155),
                textMuted = Color(0xFF64748B),
                onAccent = Color(0xFFFFFFFF),
                cornerRadius = 16.dp,
                buttonRadius = 12.dp,
                borderWidth = 1.dp,
                panelOpacity = opacity,
                densityScale = designState.uiDensity.scale,
                displayFont = SpaceGroteskFont,
                bodyFont = PlusJakartaSansFont,
                monoFont = JetBrainsMonoFont
            )
        } else {
            LinkpointDesignTokens(
                language = DesignLanguage.POLISHED,
                isLight = false,
                canvasVoid = Color(0xFF080C12),
                surfaceChrome = Color(0xFF101722).copy(alpha = opacity),
                surfaceFloater = Color(0xFF151F2E).copy(alpha = (opacity + 0.03f).coerceAtMost(0.98f)),
                surfaceElevated = Color(0xFF1D2A3D),
                borderHairline = Color(0xFF243247),
                borderSpecular = Color(0xFF3A5273),
                accentPrimary = accent1,
                accentSecondary = accent2,
                accentTertiary = accent3,
                statusOnline = Color(0xFF10B981),
                statusWarning = Color(0xFFF59E0B),
                statusError = Color(0xFFF43F5E),
                textPrimary = Color(0xFFF1F5F9),
                textSecondary = Color(0xFF94A3B8),
                textMuted = Color(0xFF64748B),
                onAccent = Color(0xFF04101B),
                cornerRadius = 16.dp,
                buttonRadius = 12.dp,
                borderWidth = 1.dp,
                panelOpacity = opacity,
                densityScale = designState.uiDensity.scale,
                displayFont = SpaceGroteskFont,
                bodyFont = PlusJakartaSansFont,
                monoFont = JetBrainsMonoFont
            )
        }

        DesignLanguage.TERMINAL -> LinkpointDesignTokens(
            language = DesignLanguage.TERMINAL,
            isLight = false,
            canvasVoid = Color(0xFF030604),
            surfaceChrome = Color(0xFF050A06).copy(alpha = (opacity + 0.04f).coerceAtMost(0.98f)),
            surfaceFloater = Color(0xFF071009).copy(alpha = (opacity + 0.05f).coerceAtMost(0.99f)),
            surfaceElevated = Color(0xFF0D1D11),
            borderHairline = accent1.copy(alpha = 0.55f),
            borderSpecular = accent1,
            accentPrimary = accent1,
            accentSecondary = accent2,
            accentTertiary = accent3,
            statusOnline = Color(0xFF22C55E),
            statusWarning = Color(0xFFFACC15),
            statusError = Color(0xFFEF4444),
            textPrimary = Color(0xFFE2FBE8),
            textSecondary = accent1.copy(alpha = 0.82f),
            textMuted = Color(0xFF4D7C5B),
            onAccent = Color(0xFF031006),
            cornerRadius = 2.dp,
            buttonRadius = 2.dp,
            borderWidth = 1.25.dp,
            panelOpacity = opacity,
            densityScale = designState.uiDensity.scale,
            displayFont = JetBrainsMonoFont,
            bodyFont = JetBrainsMonoFont,
            monoFont = JetBrainsMonoFont
        )

        DesignLanguage.LCARS -> LinkpointDesignTokens(
            language = DesignLanguage.LCARS,
            isLight = false,
            canvasVoid = Color(0xFF000000),
            surfaceChrome = Color(0xFF050508).copy(alpha = (opacity + 0.05f).coerceAtMost(0.99f)),
            surfaceFloater = Color(0xFF08080E).copy(alpha = (opacity + 0.06f).coerceAtMost(0.99f)),
            surfaceElevated = Color(0xFF141422),
            borderHairline = accent1,
            borderSpecular = Color(0xFFFFCC66),
            accentPrimary = accent1, // Apricot / user accent
            accentSecondary = Color(0xFFCC99CC), // Classic LCARS Lilac
            accentTertiary = Color(0xFF9999FF), // Classic LCARS Periwinkle
            statusOnline = Color(0xFF66CC99),
            statusWarning = Color(0xFFFFCC66),
            statusError = Color(0xFFCC5555),
            textPrimary = Color(0xFFFFF3E0),
            textSecondary = Color(0xFFFFCC99),
            textMuted = Color(0xFFA388AA),
            onAccent = Color(0xFF000000),
            cornerRadius = 24.dp,
            buttonRadius = 99.dp, // Signature LCARS capsule pills
            borderWidth = 2.dp,
            panelOpacity = opacity,
            densityScale = designState.uiDensity.scale,
            displayFont = SpaceGroteskFont,
            bodyFont = SpaceGroteskFont,
            monoFont = JetBrainsMonoFont
        )

        DesignLanguage.METRO -> if (light) {
            LinkpointDesignTokens(
                language = DesignLanguage.METRO,
                isLight = true,
                canvasVoid = Color(0xFFF2F2F2),
                surfaceChrome = Color(0xFFFFFFFF).copy(alpha = (opacity + 0.04f).coerceAtMost(1f)),
                surfaceFloater = Color(0xFFFAFAFA).copy(alpha = (opacity + 0.06f).coerceAtMost(1f)),
                surfaceElevated = Color(0xFFE6E6E6),
                borderHairline = Color(0xFFD0D0D0),
                borderSpecular = accent1,
                accentPrimary = accent1,
                accentSecondary = accent2,
                accentTertiary = accent3,
                statusOnline = Color(0xFF107C10),
                statusWarning = Color(0xFFD83B01),
                statusError = Color(0xFFE81123),
                textPrimary = Color(0xFF111111),
                textSecondary = Color(0xFF444444),
                textMuted = Color(0xFF767676),
                onAccent = Color(0xFFFFFFFF),
                cornerRadius = 0.dp, // Pure sharp Metro edges
                buttonRadius = 0.dp,
                borderWidth = 2.dp,
                panelOpacity = opacity,
                densityScale = designState.uiDensity.scale,
                displayFont = SpaceGroteskFont,
                bodyFont = PlusJakartaSansFont,
                monoFont = JetBrainsMonoFont
            )
        } else {
            LinkpointDesignTokens(
                language = DesignLanguage.METRO,
                isLight = false,
                canvasVoid = Color(0xFF0A0A0A),
                surfaceChrome = Color(0xFF111111).copy(alpha = (opacity + 0.04f).coerceAtMost(0.99f)),
                surfaceFloater = Color(0xFF161616).copy(alpha = (opacity + 0.06f).coerceAtMost(0.99f)),
                surfaceElevated = Color(0xFF222222),
                borderHairline = Color(0xFF333333),
                borderSpecular = accent1,
                accentPrimary = accent1,
                accentSecondary = accent2,
                accentTertiary = accent3,
                statusOnline = Color(0xFF107C10),
                statusWarning = Color(0xFFF09609),
                statusError = Color(0xFFE81123),
                textPrimary = Color(0xFFFFFFFF),
                textSecondary = Color(0xFFC8C8C8),
                textMuted = Color(0xFF8A8A8A),
                onAccent = Color(0xFFFFFFFF),
                cornerRadius = 0.dp,
                buttonRadius = 0.dp,
                borderWidth = 2.dp,
                panelOpacity = opacity,
                densityScale = designState.uiDensity.scale,
                displayFont = SpaceGroteskFont,
                bodyFont = PlusJakartaSansFont,
                monoFont = JetBrainsMonoFont
            )
        }
    }
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

    val typography = Typography(
        headlineMedium = TextStyle(
            fontFamily = tokens.displayFont,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            lineHeight = 30.sp
        ),
        titleLarge = TextStyle(
            fontFamily = tokens.displayFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = 24.sp
        ),
        titleMedium = TextStyle(
            fontFamily = tokens.displayFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = 20.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = tokens.bodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp
        ),
        bodySmall = TextStyle(
            fontFamily = tokens.bodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp
        ),
        labelMedium = TextStyle(
            fontFamily = tokens.monoFont,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp
        ),
        labelSmall = TextStyle(
            fontFamily = tokens.monoFont,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            lineHeight = 14.sp
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
