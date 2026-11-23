package com.example.linkpoint.designsystem

import androidx.compose.ui.graphics.Color

/**
 * The 4 aesthetic palette families defined by `linkpoint-design` / `@linkpoint/design-system`.
 */
enum class KthemeFamily(
    val id: String,
    val displayName: String,
    val subtitle: String
) {
    TERMINAL_AND_NEON(
        id = "terminal-neon",
        displayName = "Terminal & Neon",
        subtitle = "High-contrast phosphor, viewer classics & cybernetic night glass"
    ),
    CONSOLE_AND_AMBER(
        id = "console-amber",
        displayName = "Console & Amber",
        subtitle = "Starship sweep consoles, warm amber telemetry & flat Metro fields"
    ),
    METAL_AND_JEWEL(
        id = "metal-jewel",
        displayName = "Metal & Jewel",
        subtitle = "Architectural Deco, precious metals & deep jewel-toned luxury"
    ),
    DAYLIGHT(
        id = "daylight",
        displayName = "Daylight",
        subtitle = "High-legibility paper, civic greens, botanical & clinical light modes"
    )
}

/**
 * App-neutral Ktheme semantic color tokens synchronized with `linkpoint-design`.
 */
data class KthemePalette(
    val id: String,
    val name: String,
    val family: KthemeFamily,
    val note: String,
    val isLight: Boolean,
    val bg: Color,
    val surf: Color,
    val surf2: Color,
    val ink: Color,
    val ink2: Color,
    val pri: Color,
    val onPri: Color,
    val priContainer: Color,
    val onPriContainer: Color,
    val sec: Color,
    val onSec: Color,
    val sec2: Color,
    val badge: Color,
    val onBadge: Color,
    val info: Color,
    val outlineVariant: Color,
    val ok: Color,
    val err: Color,
    val warn: Color,
    val skyTop: Color = surf2,
    val skyHorizon: Color = surf,
    val ground: Color = bg,
    val isCommunity: Boolean = false
) {
    val accentPrimary: Color get() = pri
    val isDaylight: Boolean get() = isLight
}

/**
 * Complete catalog of 32 Ktheme palettes (all 28 core `linkpoint-design` palettes + 4 community themes)
 * grouped across the 4 aesthetic families.
 */
object KthemeCatalog {

    private fun hex(value: Long): Color = Color(value)

    val ALL: List<KthemePalette> = listOf(
        // ── 1. TERMINAL & NEON ─────────────────────────────────────────────
        KthemePalette(
            id = "linkpoint-default",
            name = "Linkpoint Blue",
            family = KthemeFamily.TERMINAL_AND_NEON,
            note = "Default Linkpoint viewer palette with crisp cobalt & cyan accents",
            isLight = false,
            bg = hex(0xFF1A1A1A), surf = hex(0xFF242830), surf2 = hex(0xFF2D333D),
            ink = hex(0xFFFFFFFF), ink2 = hex(0xFFB8C2CC),
            pri = hex(0xFF1976D2), onPri = hex(0xFFFFFFFF),
            priContainer = hex(0xFF1976D2), onPriContainer = hex(0xFFFFFFFF),
            sec = hex(0xFF00BCD4), onSec = hex(0xFF000000), sec2 = hex(0xFF26C6DA),
            badge = hex(0xFF00BCD4), onBadge = hex(0xFF000000),
            info = hex(0xFF00BCD4), outlineVariant = hex(0xFF38414E),
            ok = hex(0xFF22C55E), err = hex(0xFFCF6679), warn = hex(0xFFF59E0B)
        ),
        KthemePalette(
            id = "firestorm",
            name = "Firestorm Viewer",
            family = KthemeFamily.TERMINAL_AND_NEON,
            note = "Firestorm third-party viewer tribute — ember orange on near-black",
            isLight = false,
            bg = hex(0xFF121212), surf = hex(0xFF1E1E1E), surf2 = hex(0xFF2A2420),
            ink = hex(0xFFF5F5F5), ink2 = hex(0xFFBDBDBD),
            pri = hex(0xFFE85D00), onPri = hex(0xFF1A0E00),
            priContainer = hex(0xFFE85D00), onPriContainer = hex(0xFFF5F5F5),
            sec = hex(0xFFFFB366), onSec = hex(0xFF1A0E00), sec2 = hex(0xFFFFB366),
            badge = hex(0xFFFFB366), onBadge = hex(0xFF1A0E00),
            info = hex(0xFFFFB366), outlineVariant = hex(0xFF382C24),
            ok = hex(0xFFE85D00), err = hex(0xFFCF6679), warn = hex(0xFFFFB366)
        ),
        KthemePalette(
            id = "sl-classic",
            name = "Second Life Viewer",
            family = KthemeFamily.TERMINAL_AND_NEON,
            note = "Classic Linden Lab viewer — Linden sky blue on deep slate",
            isLight = false,
            bg = hex(0xFF1B2430), surf = hex(0xFF263548), surf2 = hex(0xFF31445C),
            ink = hex(0xFFE6ECF2), ink2 = hex(0xFFAEBECF),
            pri = hex(0xFF4AA3DF), onPri = hex(0xFF0B1520),
            priContainer = hex(0xFF4AA3DF), onPriContainer = hex(0xFFE6ECF2),
            sec = hex(0xFF7FB3D5), onSec = hex(0xFF0B1520), sec2 = hex(0xFF7FB3D5),
            badge = hex(0xFF7FB3D5), onBadge = hex(0xFF0B1520),
            info = hex(0xFF7FB3D5), outlineVariant = hex(0xFF3B506B),
            ok = hex(0xFF4AA3DF), err = hex(0xFFCF6679), warn = hex(0xFFF59E0B)
        ),
        KthemePalette(
            id = "aurora-glass-night",
            name = "Aurora Glass Night",
            family = KthemeFamily.TERMINAL_AND_NEON,
            note = "Night-first spatial glass aesthetic with aurora cyan & violet",
            isLight = false,
            bg = hex(0xFF0A1224), surf = hex(0xFF101C33), surf2 = hex(0xFF1D2B4A),
            ink = hex(0xFFE7F0FF), ink2 = hex(0xFFB5C7E9),
            pri = hex(0xFF6DE8FF), onPri = hex(0xFF0A1224),
            priContainer = hex(0xFF2A6F85), onPriContainer = hex(0xFFD6F7FF),
            sec = hex(0xFF8C7CFF), onSec = hex(0xFF0A1224), sec2 = hex(0xFF7CFFD8),
            badge = hex(0xFF8C7CFF), onBadge = hex(0xFF0A1224),
            info = hex(0xFF7CFFD8), outlineVariant = hex(0xFF334364),
            ok = hex(0xFF6DE8FF), err = hex(0xFFCF6679), warn = hex(0xFF7CFFD8)
        ),
        KthemePalette(
            id = "ink-terminal-modern",
            name = "Ink Terminal Modern",
            family = KthemeFamily.TERMINAL_AND_NEON,
            note = "Retro phosphor green terminal tuned for modern WCAG contrast",
            isLight = false,
            bg = hex(0xFF0A1112), surf = hex(0xFF101A1C), surf2 = hex(0xFF1B2A2D),
            ink = hex(0xFFD7F5E6), ink2 = hex(0xFFA7C8BC),
            pri = hex(0xFF6CFF9A), onPri = hex(0xFF0A1112),
            priContainer = hex(0xFF1F6640), onPriContainer = hex(0xFFD7FFE4),
            sec = hex(0xFF3E4E5E), onSec = hex(0xFFD7F5E6), sec2 = hex(0xFF8AD0B0),
            badge = hex(0xFF8AD0B0), onBadge = hex(0xFF0A1112),
            info = hex(0xFF8AD0B0), outlineVariant = hex(0xFF365047),
            ok = hex(0xFF6CFF9A), err = hex(0xFFCF6679), warn = hex(0xFFFFC98A)
        ),
        KthemePalette(
            id = "neo-noir-neon",
            name = "Neo-Noir Neon",
            family = KthemeFamily.TERMINAL_AND_NEON,
            note = "Dark cinematic control surface with electric violet & cyan glow",
            isLight = false,
            bg = hex(0xFF090A10), surf = hex(0xFF111420), surf2 = hex(0xFF1C2130),
            ink = hex(0xFFE7EAF7), ink2 = hex(0xFFB9C0D8),
            pri = hex(0xFFAA43FF), onPri = hex(0xFF090A10),
            priContainer = hex(0xFF4B1D73), onPriContainer = hex(0xFFE7CBFF),
            sec = hex(0xFF00D1FF), onSec = hex(0xFF090A10), sec2 = hex(0xFFFF3D9E),
            badge = hex(0xFF00D1FF), onBadge = hex(0xFF090A10),
            info = hex(0xFFFF3D9E), outlineVariant = hex(0xFF363D52),
            ok = hex(0xFF00D1FF), err = hex(0xFFFF6B6B), warn = hex(0xFFFF3D9E)
        ),
        KthemePalette(
            id = "obsidian-crimson",
            name = "Obsidian Crimson",
            family = KthemeFamily.TERMINAL_AND_NEON,
            note = "Dramatic obsidian black with high-visibility crimson accents",
            isLight = false,
            bg = hex(0xFF0A0A0A), surf = hex(0xFF141414), surf2 = hex(0xFF2D2D2D),
            ink = hex(0xFFF5F5F5), ink2 = hex(0xFFD0D0D0),
            pri = hex(0xFFDC143C), onPri = hex(0xFFF5F5F5),
            priContainer = hex(0xFFB00F30), onPriContainer = hex(0xFFF5F5F5),
            sec = hex(0xFFA8505A), onSec = hex(0xFFF5F5F5), sec2 = hex(0xFFE5395F),
            badge = hex(0xFFA8505A), onBadge = hex(0xFFF5F5F5),
            info = hex(0xFFD0D0D0), outlineVariant = hex(0xFF3D3D3D),
            ok = hex(0xFFDC143C), err = hex(0xFFFF6B6B), warn = hex(0xFFA8505A)
        ),
        KthemePalette(
            id = "slate-cyan",
            name = "Slate Cyan",
            family = KthemeFamily.TERMINAL_AND_NEON,
            note = "Cool modern slate gray with vibrant cyan telemetry",
            isLight = false,
            bg = hex(0xFF1A1F24), surf = hex(0xFF232930), surf2 = hex(0xFF3D4854),
            ink = hex(0xFFE8F0F5), ink2 = hex(0xFFB8CAD6),
            pri = hex(0xFF00D9FF), onPri = hex(0xFF1A1F24),
            priContainer = hex(0xFF00A8CC), onPriContainer = hex(0xFF4DE2FF),
            sec = hex(0xFF2A333D), onSec = hex(0xFFE8F0F5), sec2 = hex(0xFF6BA5B8),
            badge = hex(0xFF2A333D), onBadge = hex(0xFFE8F0F5),
            info = hex(0xFF6BA5B8), outlineVariant = hex(0xFF4D5A66),
            ok = hex(0xFF00D9FF), err = hex(0xFFCF6679), warn = hex(0xFF6BA5B8)
        ),

        // ── 2. CONSOLE & AMBER ─────────────────────────────────────────────
        KthemePalette(
            id = "lcars",
            name = "LCARS Amber",
            family = KthemeFamily.CONSOLE_AND_AMBER,
            note = "Starship console amber, lilac & rose on deep void",
            isLight = false,
            bg = hex(0xFF120C1C), surf = hex(0xFF1C132A), surf2 = hex(0xFF3D1F5C),
            ink = hex(0xFFF3E9FF), ink2 = hex(0xFFD0B3E6),
            pri = hex(0xFFF2A65A), onPri = hex(0xFF120C1C),
            priContainer = hex(0xFFCC7A2B), onPriContainer = hex(0xFF120C1C),
            sec = hex(0xFFA485F7), onSec = hex(0xFF120C1C), sec2 = hex(0xFFC5678D),
            badge = hex(0xFFA485F7), onBadge = hex(0xFF120C1C),
            info = hex(0xFFC5678D), outlineVariant = hex(0xFF4D2F5C),
            ok = hex(0xFFF2A65A), err = hex(0xFFCF6679), warn = hex(0xFFFFC46B)
        ),
        KthemePalette(
            id = "cleverferret-gold",
            name = "CleverFerret Gold",
            family = KthemeFamily.CONSOLE_AND_AMBER,
            note = "CleverFerret signature warm gold console accents on dark charcoal",
            isLight = false,
            bg = hex(0xFF1E1E1E), surf = hex(0xFF2A2A2A), surf2 = hex(0xFF38352E),
            ink = hex(0xFFE6E1E5), ink2 = hex(0xFFCAC4D0),
            pri = hex(0xFFE5A00D), onPri = hex(0xFF1E1E1E),
            priContainer = hex(0xFFE5A00D), onPriContainer = hex(0xFFE6E1E5),
            sec = hex(0xFFB8860B), onSec = hex(0xFF1E1E1E), sec2 = hex(0xFFD4AF37),
            badge = hex(0xFFB8860B), onBadge = hex(0xFF1E1E1E),
            info = hex(0xFFB8860B), outlineVariant = hex(0xFF453F33),
            ok = hex(0xFFE5A00D), err = hex(0xFFCF6679), warn = hex(0xFFE5A00D)
        ),
        KthemePalette(
            id = "windows-phone-metro",
            name = "Windows Phone Metro",
            family = KthemeFamily.CONSOLE_AND_AMBER,
            note = "Flat tile-first Metro panorama interface on deep cobalt blue",
            isLight = false,
            bg = hex(0xFF001A33), surf = hex(0xFF002448), surf2 = hex(0xFF3D4854),
            ink = hex(0xFFF0F8FF), ink2 = hex(0xFFB8CAD6),
            pri = hex(0xFF00AEEF), onPri = hex(0xFF00151F),
            priContainer = hex(0xFF0078D7), onPriContainer = hex(0xFFE8F7FF),
            sec = hex(0xFF005A9E), onSec = hex(0xFFEAF4FF), sec2 = hex(0xFF2D89EF),
            badge = hex(0xFF005A9E), onBadge = hex(0xFFEAF4FF),
            info = hex(0xFF2D89EF), outlineVariant = hex(0xFF4D5A66),
            ok = hex(0xFF00AEEF), err = hex(0xFFCF6679), warn = hex(0xFF2D89EF)
        ),
        KthemePalette(
            id = "midnight-amber",
            name = "Midnight Amber",
            family = KthemeFamily.CONSOLE_AND_AMBER,
            note = "Midnight blue deck instrumentation with warm amber accents",
            isLight = false,
            bg = hex(0xFF0C1824), surf = hex(0xFF15202E), surf2 = hex(0xFF253447),
            ink = hex(0xFFE8EEF5), ink2 = hex(0xFFB8C5D6),
            pri = hex(0xFFFFBF00), onPri = hex(0xFF0C1824),
            priContainer = hex(0xFFCC9900), onPriContainer = hex(0xFFFFD14D),
            sec = hex(0xFFD4A76A), onSec = hex(0xFF0C1824), sec2 = hex(0xFFD4A76A),
            badge = hex(0xFFD4A76A), onBadge = hex(0xFF0C1824),
            info = hex(0xFFD4A76A), outlineVariant = hex(0xFF3D4854),
            ok = hex(0xFFFFBF00), err = hex(0xFFCF6679), warn = hex(0xFFD4A76A)
        ),
        KthemePalette(
            id = "slate-gunmetal",
            name = "Slate Gunmetal",
            family = KthemeFamily.CONSOLE_AND_AMBER,
            note = "Industrial slate gray bulkhead with brushed gunmetal accents",
            isLight = false,
            bg = hex(0xFF1A2029), surf = hex(0xFF232C38), surf2 = hex(0xFF3D4854),
            ink = hex(0xFFE6ECF2), ink2 = hex(0xFFB8C5D6),
            pri = hex(0xFF8F9CA8), onPri = hex(0xFF1A2029),
            priContainer = hex(0xFF6F7D87), onPriContainer = hex(0xFFB0BDC9),
            sec = hex(0xFF2D3844), onSec = hex(0xFFE6ECF2), sec2 = hex(0xFF9DAAB6),
            badge = hex(0xFF2D3844), onBadge = hex(0xFFE6ECF2),
            info = hex(0xFF9DAAB6), outlineVariant = hex(0xFF4D5A66),
            ok = hex(0xFF8F9CA8), err = hex(0xFFCF6679), warn = hex(0xFF9DAAB6)
        ),
        KthemePalette(
            id = "lcars-tng",
            name = "LCARS — TNG",
            family = KthemeFamily.CONSOLE_AND_AMBER,
            note = "Community TNG-era amber & lilac bridge console palette",
            isLight = false,
            bg = hex(0xFF120C1C), surf = hex(0xFF1C132A), surf2 = hex(0xFF3D1F5C),
            ink = hex(0xFFF3E9FF), ink2 = hex(0xFFD0B3E6),
            pri = hex(0xFFF2A65A), onPri = hex(0xFF120C1C),
            priContainer = hex(0xFFCC7A2B), onPriContainer = hex(0xFFFFE6CC),
            sec = hex(0xFFA485F7), onSec = hex(0xFF120C1C), sec2 = hex(0xFF7A2454),
            badge = hex(0xFFA485F7), onBadge = hex(0xFF120C1C),
            info = hex(0xFFA485F7), outlineVariant = hex(0xFF5C3D1F),
            ok = hex(0xFFF2A65A), err = hex(0xFFCF6679), warn = hex(0xFFA485F7),
            isCommunity = true
        ),
        KthemePalette(
            id = "metro-cyan",
            name = "Metro Cyan",
            family = KthemeFamily.CONSOLE_AND_AMBER,
            note = "Community high-contrast Metro variant with vivid cyan tile accent",
            isLight = false,
            bg = hex(0xFF001A33), surf = hex(0xFF002448), surf2 = hex(0xFF3D4854),
            ink = hex(0xFFF0F8FF), ink2 = hex(0xFFB8CAD6),
            pri = hex(0xFF00AEEF), onPri = hex(0xFF001A33),
            priContainer = hex(0xFF0078D7), onPriContainer = hex(0xFFE8F7FF),
            sec = hex(0xFF2D89EF), onSec = hex(0xFF001A33), sec2 = hex(0xFF005A9E),
            badge = hex(0xFF2D89EF), onBadge = hex(0xFF001A33),
            info = hex(0xFF00CC6A), outlineVariant = hex(0xFF2D465E),
            ok = hex(0xFF00AEEF), err = hex(0xFFE81123), warn = hex(0xFF00CC6A),
            isCommunity = true
        ),
        KthemePalette(
            id = "stargate-sg1",
            name = "Stargate SG-1",
            family = KthemeFamily.CONSOLE_AND_AMBER,
            note = "Community iris-bronze & event-horizon teal gate-room console",
            isLight = false,
            bg = hex(0xFF04141C), surf = hex(0xFF0E2C3E), surf2 = hex(0xFF143A52),
            ink = hex(0xFFE8F4FF), ink2 = hex(0xFF94B5C9),
            pri = hex(0xFFFFC15A), onPri = hex(0xFF1A0E04),
            priContainer = hex(0xFFB07820), onPriContainer = hex(0xFFFFF1D6),
            sec = hex(0xFF4FE0FF), onSec = hex(0xFF001E2B), sec2 = hex(0xFF0E5D78),
            badge = hex(0xFF4FE0FF), onBadge = hex(0xFF001E2B),
            info = hex(0xFFB85AFF), outlineVariant = hex(0xFF1A4359),
            ok = hex(0xFFFFC15A), err = hex(0xFFFF6B6B), warn = hex(0xFFB85AFF),
            isCommunity = true
        ),

        // ── 3. METAL & JEWEL ───────────────────────────────────────────────
        KthemePalette(
            id = "emerald-silver",
            name = "Emerald Silver",
            family = KthemeFamily.METAL_AND_JEWEL,
            note = "Rich emerald green with sterling silver metallic accents",
            isLight = false,
            bg = hex(0xFF0D3B2E), surf = hex(0xFF1A5544), surf2 = hex(0xFF2A6554),
            ink = hex(0xFFE8F5E8), ink2 = hex(0xFFC9E4D9),
            pri = hex(0xFFC0C0C0), onPri = hex(0xFF0D3B2E),
            priContainer = hex(0xFF505050), onPriContainer = hex(0xFFF5F5F5),
            sec = hex(0xFF50C878), onSec = hex(0xFF0D3B2E), sec2 = hex(0xFF8BA888),
            badge = hex(0xFF50C878), onBadge = hex(0xFF0D3B2E),
            info = hex(0xFF8BA888), outlineVariant = hex(0xFF3E4E44),
            ok = hex(0xFF50C878), err = hex(0xFFCF6679), warn = hex(0xFFE0B84C)
        ),
        KthemePalette(
            id = "royal-bronze",
            name = "Royal Bronze",
            family = KthemeFamily.METAL_AND_JEWEL,
            note = "Regal deep purple with warm cast-bronze metallic accents",
            isLight = false,
            bg = hex(0xFF1A0A30), surf = hex(0xFF220D40), surf2 = hex(0xFF3D1F5C),
            ink = hex(0xFFF0E6FF), ink2 = hex(0xFFD0B3E6),
            pri = hex(0xFFCD7F32), onPri = hex(0xFF1A0A30),
            priContainer = hex(0xFFA86428), onPriContainer = hex(0xFFD99952),
            sec = hex(0xFF2D1550), onSec = hex(0xFFF0E6FF), sec2 = hex(0xFF9B7A5F),
            badge = hex(0xFF2D1550), onBadge = hex(0xFFF0E6FF),
            info = hex(0xFF9B7A5F), outlineVariant = hex(0xFF4D2F5C),
            ok = hex(0xFFCD7F32), err = hex(0xFFCF6679), warn = hex(0xFF9B7A5F)
        ),
        KthemePalette(
            id = "royal-silver",
            name = "Royal Silver",
            family = KthemeFamily.METAL_AND_JEWEL,
            note = "Royal purple velvet with polished silver accents",
            isLight = false,
            bg = hex(0xFF1A1535), surf = hex(0xFF211A40), surf2 = hex(0xFF3D2F5C),
            ink = hex(0xFFF0EBFF), ink2 = hex(0xFFC8BFE6),
            pri = hex(0xFFC0C0C0), onPri = hex(0xFF1A1535),
            priContainer = hex(0xFF9A9A9A), onPriContainer = hex(0xFFE0E0E0),
            sec = hex(0xFF2A1F50), onSec = hex(0xFFF0EBFF), sec2 = hex(0xFFA89BC9),
            badge = hex(0xFF2A1F50), onBadge = hex(0xFFF0EBFF),
            info = hex(0xFFA89BC9), outlineVariant = hex(0xFF4D3F66),
            ok = hex(0xFFC0C0C0), err = hex(0xFFCF6679), warn = hex(0xFFA89BC9)
        ),
        KthemePalette(
            id = "navy-gold",
            name = "Navy Gold",
            family = KthemeFamily.METAL_AND_JEWEL,
            note = "Admiralty navy background with burnished gold accents",
            isLight = false,
            bg = hex(0xFF0A1630), surf = hex(0xFF1A2645), surf2 = hex(0xFF2A3655),
            ink = hex(0xFFE8E3D8), ink2 = hex(0xFFC9C4B9),
            pri = hex(0xFFD4AF37), onPri = hex(0xFF0A1630),
            priContainer = hex(0xFF856D34), onPriContainer = hex(0xFFFFF8DC),
            sec = hex(0xFF4A90E2), onSec = hex(0xFF0A1630), sec2 = hex(0xFF9C8970),
            badge = hex(0xFF4A90E2), onBadge = hex(0xFF0A1630),
            info = hex(0xFF9C8970), outlineVariant = hex(0xFF44483E),
            ok = hex(0xFFD4AF37), err = hex(0xFFCF6679), warn = hex(0xFFE0B84C)
        ),
        KthemePalette(
            id = "deep-purple-platinum",
            name = "Deep Purple Platinum",
            family = KthemeFamily.METAL_AND_JEWEL,
            note = "Deep amethyst night with luminous platinum metallic trim",
            isLight = false,
            bg = hex(0xFF1A0F2E), surf = hex(0xFF24153D), surf2 = hex(0xFF3D2A5C),
            ink = hex(0xFFF0EBFF), ink2 = hex(0xFFD0C0E6),
            pri = hex(0xFFE5E4E2), onPri = hex(0xFF1A0F2E),
            priContainer = hex(0xFFB8B7B5), onPriContainer = hex(0xFFF5F4F2),
            sec = hex(0xFF2E1A50), onSec = hex(0xFFF0EBFF), sec2 = hex(0xFFC8BFE0),
            badge = hex(0xFF2E1A50), onBadge = hex(0xFFF0EBFF),
            info = hex(0xFFC8BFE0), outlineVariant = hex(0xFF4D3F6D),
            ok = hex(0xFFE5E4E2), err = hex(0xFFCF6679), warn = hex(0xFFC8BFE0)
        ),
        KthemePalette(
            id = "burgundy-rose-gold",
            name = "Burgundy Rose Gold",
            family = KthemeFamily.METAL_AND_JEWEL,
            note = "Rich burgundy lacquer paired with rose gold metallic trim",
            isLight = false,
            bg = hex(0xFF2D0F1A), surf = hex(0xFF3D1525), surf2 = hex(0xFF5C2A3D),
            ink = hex(0xFFFFE6ED), ink2 = hex(0xFFE6C0CC),
            pri = hex(0xFFB76E79), onPri = hex(0xFF2D0F1A),
            priContainer = hex(0xFF93575F), onPriContainer = hex(0xFFD4969E),
            sec = hex(0xFF4D1A2A), onSec = hex(0xFFFFE6ED), sec2 = hex(0xFFC99BA5),
            badge = hex(0xFF4D1A2A), onBadge = hex(0xFFFFE6ED),
            info = hex(0xFFC99BA5), outlineVariant = hex(0xFF6D3F4D),
            ok = hex(0xFFB76E79), err = hex(0xFFFFB4AB), warn = hex(0xFFC99BA5)
        ),
        KthemePalette(
            id = "charcoal-champagne",
            name = "Charcoal Champagne",
            family = KthemeFamily.METAL_AND_JEWEL,
            note = "Architectural charcoal gray with warm champagne accents",
            isLight = false,
            bg = hex(0xFF1F1F1F), surf = hex(0xFF2A2A2A), surf2 = hex(0xFF3D3D3D),
            ink = hex(0xFFF5F5F5), ink2 = hex(0xFFD0D0D0),
            pri = hex(0xFFF7E7CE), onPri = hex(0xFF1F1F1F),
            priContainer = hex(0xFFC5B8A5), onPriContainer = hex(0xFFFFF5E6),
            sec = hex(0xFF3D3D3D), onSec = hex(0xFFF5F5F5), sec2 = hex(0xFFD4C4A8),
            badge = hex(0xFF3D3D3D), onBadge = hex(0xFFF5F5F5),
            info = hex(0xFFD4C4A8), outlineVariant = hex(0xFF4D4D4D),
            ok = hex(0xFFF7E7CE), err = hex(0xFFCF6679), warn = hex(0xFFD4C4A8)
        ),
        KthemePalette(
            id = "forest-copper",
            name = "Forest Copper",
            family = KthemeFamily.METAL_AND_JEWEL,
            note = "Deep evergreen canopy with hammered copper accents",
            isLight = false,
            bg = hex(0xFF0D1F0D), surf = hex(0xFF152915), surf2 = hex(0xFF2A4D2A),
            ink = hex(0xFFE8F5E8), ink2 = hex(0xFFB8D9B8),
            pri = hex(0xFFB87333), onPri = hex(0xFF0D1F0D),
            priContainer = hex(0xFF935E29), onPriContainer = hex(0xFFD4965A),
            sec = hex(0xFF1A3D1A), onSec = hex(0xFFE8F5E8), sec2 = hex(0xFF8FA886),
            badge = hex(0xFF1A3D1A), onBadge = hex(0xFFE8F5E8),
            info = hex(0xFF8FA886), outlineVariant = hex(0xFF3D5A3D),
            ok = hex(0xFFB87333), err = hex(0xFFCF6679), warn = hex(0xFF8FA886)
        ),
        KthemePalette(
            id = "art-deco",
            name = "Art Deco",
            family = KthemeFamily.METAL_AND_JEWEL,
            note = "Geometric symmetry, stepped hairline rules & gold-ivory-black contrast",
            isLight = false,
            bg = hex(0xFF0B0A0A), surf = hex(0xFF141314), surf2 = hex(0xFF232124),
            ink = hex(0xFFF3E8D0), ink2 = hex(0xFFC9BDA2),
            pri = hex(0xFFD4AF37), onPri = hex(0xFF0B0A0A),
            priContainer = hex(0xFF8F7121), onPriContainer = hex(0xFFFFF2C6),
            sec = hex(0xFFF4E7CF), onSec = hex(0xFF0B0A0A), sec2 = hex(0xFFB8A17A),
            badge = hex(0xFFF4E7CF), onBadge = hex(0xFF0B0A0A),
            info = hex(0xFFB8A17A), outlineVariant = hex(0xFF4B4332),
            ok = hex(0xFFD4AF37), err = hex(0xFFFFB4AB), warn = hex(0xFFE0B84C)
        ),
        KthemePalette(
            id = "rose-gold",
            name = "Rose Gold",
            family = KthemeFamily.METAL_AND_JEWEL,
            note = "Warm brushed rose gold with velvet burgundy undertones",
            isLight = false,
            bg = hex(0xFF3D1F2B), surf = hex(0xFF4D2F3B), surf2 = hex(0xFF5D3F4B),
            ink = hex(0xFFF5E5E8), ink2 = hex(0xFFE5D5D8),
            pri = hex(0xFFC1818B), onPri = hex(0xFF3D1F2B),
            priContainer = hex(0xFF7D4A52), onPriContainer = hex(0xFFF5D5D8),
            sec = hex(0xFFD4A5A5), onSec = hex(0xFF3D1F2B), sec2 = hex(0xFFC9A9A9),
            badge = hex(0xFFD4A5A5), onBadge = hex(0xFF3D1F2B),
            info = hex(0xFFC9A9A9), outlineVariant = hex(0xFF6B4F55),
            ok = hex(0xFFC1818B), err = hex(0xFFFFB4AB), warn = hex(0xFFC9A9A9)
        ),
        KthemePalette(
            id = "stargate-atlantis",
            name = "Stargate Atlantis",
            family = KthemeFamily.METAL_AND_JEWEL,
            note = "Community Atlantean blue glass & bronze with Deco prairie banding",
            isLight = false,
            bg = hex(0xFF0B1B2E), surf = hex(0xFF133355), surf2 = hex(0xFF1A4068),
            ink = hex(0xFFE8EFF7), ink2 = hex(0xFF8FB0CC),
            pri = hex(0xFFC4A062), onPri = hex(0xFF0B1B2E),
            priContainer = hex(0xFF8A6E3D), onPriContainer = hex(0xFFFFF1D6),
            sec = hex(0xFF6FCFE8), onSec = hex(0xFF001E2B), sec2 = hex(0xFF1A4E66),
            badge = hex(0xFF6FCFE8), onBadge = hex(0xFF001E2B),
            info = hex(0xFFE8D7A3), outlineVariant = hex(0xFF1F4E7A),
            ok = hex(0xFFC4A062), err = hex(0xFFC46462), warn = hex(0xFFE8D7A3),
            isCommunity = true
        ),

        // ── 4. DAYLIGHT ────────────────────────────────────────────────────
        KthemePalette(
            id = "frutiger-aero",
            name = "Frutiger Aero",
            family = KthemeFamily.DAYLIGHT,
            note = "Glossy sky-and-nature daylight glass palette",
            isLight = true,
            bg = hex(0xFFEAF7FF), surf = hex(0xFFF7FCFF), surf2 = hex(0xFFDDF1FF),
            ink = hex(0xFF173A52), ink2 = hex(0xFF34566E),
            pri = hex(0xFF39B6F0), onPri = hex(0xFF173A52),
            priContainer = hex(0xFFA9E6FF), onPriContainer = hex(0xFF00314D),
            sec = hex(0xFF79D87E), onSec = hex(0xFF173A52), sec2 = hex(0xFF0A6FA0),
            badge = hex(0xFF0A6FA0), onBadge = hex(0xFFEAF7FF),
            info = hex(0xFF0A6FA0), outlineVariant = hex(0xFFA9C7DA),
            ok = hex(0xFF0A6FA0), err = hex(0xFFBA1A1A), warn = hex(0xFF8A5A00)
        ),
        KthemePalette(
            id = "paper-ink",
            name = "Paper & Ink",
            family = KthemeFamily.DAYLIGHT,
            note = "Editorial warm newsprint & deep carbon ink for maximum legibility",
            isLight = true,
            bg = hex(0xFFF0F0EB), surf = hex(0xFFFAF9F6), surf2 = hex(0xFFEBEAE4),
            ink = hex(0xFF2C2C2C), ink2 = hex(0xFF454545),
            pri = hex(0xFF2C2C2C), onPri = hex(0xFFF0F0EB),
            priContainer = hex(0xFF454545), onPriContainer = hex(0xFFFAF9F6),
            sec = hex(0xFF595959), onSec = hex(0xFFF0F0EB), sec2 = hex(0xFF737373),
            badge = hex(0xFF2C2C2C), onBadge = hex(0xFFF0F0EB),
            info = hex(0xFF454545), outlineVariant = hex(0xFFC9C9C9),
            ok = hex(0xFF2C6B3F), err = hex(0xFFBA1A1A), warn = hex(0xFF8A5A00)
        ),
        KthemePalette(
            id = "calm-clinical",
            name = "Calm Clinical",
            family = KthemeFamily.DAYLIGHT,
            note = "Low-stress daylight administrative palette with crisp status clarity",
            isLight = true,
            bg = hex(0xFFF5FAFD), surf = hex(0xFFFFFFFF), surf2 = hex(0xFFE5EEF4),
            ink = hex(0xFF1F394B), ink2 = hex(0xFF445F72),
            pri = hex(0xFF38779E), onPri = hex(0xFFF5FAFD),
            priContainer = hex(0xFFC4DFF2), onPriContainer = hex(0xFF17374D),
            sec = hex(0xFF61B48B), onSec = hex(0xFF1F394B), sec2 = hex(0xFF7D9AB2),
            badge = hex(0xFF61B48B), onBadge = hex(0xFF1F394B),
            info = hex(0xFF7D9AB2), outlineVariant = hex(0xFFB5C8D5),
            ok = hex(0xFF2E7D5A), err = hex(0xFFBA1A1A), warn = hex(0xFF8A5A00)
        ),
        KthemePalette(
            id = "solarpunk-civic",
            name = "Solarpunk Civic",
            family = KthemeFamily.DAYLIGHT,
            note = "Optimistic daylight botanical greens & sunlit sky clarity",
            isLight = true,
            bg = hex(0xFFF2FBF4), surf = hex(0xFFFBFFFC), surf2 = hex(0xFFE2F2E8),
            ink = hex(0xFF1E3A27), ink2 = hex(0xFF456355),
            pri = hex(0xFF38B56A), onPri = hex(0xFF1E3A27),
            priContainer = hex(0xFFBFEFD0), onPriContainer = hex(0xFF0E3F20),
            sec = hex(0xFF4FAEEA), onSec = hex(0xFF1E3A27), sec2 = hex(0xFFF2C46C),
            badge = hex(0xFF4FAEEA), onBadge = hex(0xFF1E3A27),
            info = hex(0xFF2B6CB0), outlineVariant = hex(0xFFB3D0BF),
            ok = hex(0xFF237A48), err = hex(0xFFBA1A1A), warn = hex(0xFF8A5A00)
        ),
        KthemePalette(
            id = "art-nouveau",
            name = "Art Nouveau",
            family = KthemeFamily.DAYLIGHT,
            note = "Warm parchment, botanical sage & espresso ink decorative palette",
            isLight = true,
            bg = hex(0xFFF6F0E6), surf = hex(0xFFFFF8EE), surf2 = hex(0xFFE7D8C7),
            ink = hex(0xFF2C2218), ink2 = hex(0xFF584638),
            pri = hex(0xFF7B5737), onPri = hex(0xFFF6F0E6),
            priContainer = hex(0xFFD9C2A9), onPriContainer = hex(0xFF352214),
            sec = hex(0xFF769762), onSec = hex(0xFF2C2218), sec2 = hex(0xFFC57C52),
            badge = hex(0xFF769762), onBadge = hex(0xFF2C2218),
            info = hex(0xFFC57C52), outlineVariant = hex(0xFFCBB8A5),
            ok = hex(0xFF4E6F3A), err = hex(0xFFBA1A1A), warn = hex(0xFF9C5428)
        )
    )

    private val byIdMap: Map<String, KthemePalette> = buildMap {
        ALL.forEach { palette ->
            put(palette.id, palette)
            put(palette.id.replace('-', '_'), palette)
        }
        // Short upstream aliases from `@linkpoint/design-system`
        put("cleverferret", ALL.first { it.id == "cleverferret-gold" })
        put("ink", ALL.first { it.id == "ink-terminal-modern" })
        put("metro", ALL.first { it.id == "windows-phone-metro" })
        put("aero", ALL.first { it.id == "frutiger-aero" })
        put("navy", ALL.first { it.id == "navy-gold" })
        put("paper", ALL.first { it.id == "paper-ink" })
        put("deco", ALL.first { it.id == "art-deco" })
        put("noir", ALL.first { it.id == "neo-noir-neon" })
        put("emerald", ALL.first { it.id == "emerald-silver" })
        put("amber", ALL.first { it.id == "midnight-amber" })
        put("crimson", ALL.first { it.id == "obsidian-crimson" })
        put("nouveau", ALL.first { it.id == "art-nouveau" })
        put("aurora", ALL.first { it.id == "aurora-glass-night" })
        put("burgundy", ALL.first { it.id == "burgundy-rose-gold" })
        put("calm", ALL.first { it.id == "calm-clinical" })
        put("charcoal", ALL.first { it.id == "charcoal-champagne" })
        put("deep", ALL.first { it.id == "deep-purple-platinum" })
        put("forest", ALL.first { it.id == "forest-copper" })
        put("rose", ALL.first { it.id == "rose-gold" })
        put("royalb", ALL.first { it.id == "royal-bronze" })
        put("royals", ALL.first { it.id == "royal-silver" })
        put("slatec", ALL.first { it.id == "slate-cyan" })
        put("slateg", ALL.first { it.id == "slate-gunmetal" })
        put("solarpunk", ALL.first { it.id == "solarpunk-civic" })
    }

    val groupedByFamily: Map<KthemeFamily, List<KthemePalette>> =
        KthemeFamily.entries.associateWith { fam -> ALL.filter { it.family == fam } }

    fun byId(id: String?): KthemePalette? {
        if (id.isNullOrBlank()) return null
        return byIdMap[id.trim().lowercase()]
    }

    fun findById(id: String?): KthemePalette? = byId(id)

    fun byIdOrDefault(id: String?): KthemePalette =
        byId(id) ?: ALL.first()

    fun byFamily(family: KthemeFamily): List<KthemePalette> =
        groupedByFamily[family].orEmpty()

    fun defaultIdForLanguage(language: DesignLanguage, lightPosture: Boolean = false): String =
        when (language) {
            DesignLanguage.POLISHED -> if (lightPosture) "frutiger-aero" else "aurora-glass-night"
            DesignLanguage.TERMINAL -> "ink-terminal-modern"
            DesignLanguage.LCARS -> "lcars"
            DesignLanguage.METRO -> if (lightPosture) "calm-clinical" else "windows-phone-metro"
            DesignLanguage.RULE_AND_RAIL -> if (lightPosture) "art-nouveau" else "art-deco"
            DesignLanguage.PRESS -> if (lightPosture) "paper-ink" else "charcoal-champagne"
        }
}
