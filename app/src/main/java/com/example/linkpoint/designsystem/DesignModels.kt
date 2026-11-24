package com.example.linkpoint.designsystem

import com.example.linkpoint.scene.EnvironmentPreset

/**
 * The 6 interchangeable `linkpoint-design` layout packs.
 */
enum class DesignLanguage(
    val displayName: String,
    val tagline: String,
    val layoutKey: String = "glass"
) {
    POLISHED(
        displayName = "Polished",
        tagline = "Refined Linkpoint spatial glass & specular chrome",
        layoutKey = "glass"
    ),
    TERMINAL(
        displayName = "Terminal",
        tagline = "Phosphor telemetry console & monospace CLI",
        layoutKey = "terminal"
    ),
    LCARS(
        displayName = "Sweep Console",
        tagline = "Black-field LCARS opposing elbows & unboxed readouts",
        layoutKey = "sweep"
    ),
    METRO(
        displayName = "Metro",
        tagline = "Sharp flat typographic panorama & live tiles",
        layoutKey = "tiles"
    ),
    RULE_AND_RAIL(
        displayName = "Rule & Rail",
        tagline = "Deco discipline hairline rules & wide-tracked uppercase headers",
        layoutKey = "rules"
    ),
    PRESS(
        displayName = "Press",
        tagline = "Editorial serif body, double masthead rules & quiet borders",
        layoutKey = "press"
    );

    val supportsLightPosture: Boolean
        get() = this == POLISHED || this == METRO || this == RULE_AND_RAIL || this == PRESS

    companion object {
        /** Alias matching `POLISHED_DARK` / Glass layout pack nomenclature. */
        val POLISHED_DARK: DesignLanguage = POLISHED

        fun fromKeyOrDefault(raw: String?, default: DesignLanguage = POLISHED): DesignLanguage {
            if (raw.isNullOrBlank()) return default
            val normalized = raw.trim().uppercase().replace('-', '_')
            if (normalized == "POLISHED_DARK" || normalized == "GLASS") return POLISHED
            if (normalized == "SWEEP") return LCARS
            if (normalized == "TILES") return METRO
            if (normalized == "RULES" || normalized == "RULE_RAIL") return RULE_AND_RAIL
            return entries.firstOrNull {
                it.name == normalized || it.layoutKey.equals(raw.trim(), ignoreCase = true)
            } ?: default
        }
    }
}

typealias LinkpointDesignLanguage = DesignLanguage

enum class AccentPreset(val label: String) {
    AZURE_CYAN("Azure Cyan"),
    PHOSPHOR_GREEN("Phosphor Green"),
    LCARS_APRICOT("Sweep Apricot"),
    METRO_COBALT("Metro Cobalt"),
    BEACON_AMBER("Beacon Amber"),
    NEON_MAGENTA("Neon Magenta")
}

enum class DockPosition(val label: String) {
    RIGHT("Right Rail"),
    LEFT("Left Rail"),
    BOTTOM("Bottom Bar")
}

enum class TelemetryPosition(val label: String) {
    TOP("Top Edge"),
    BOTTOM("Bottom Edge"),
    COMPACT("Compact Pill")
}

enum class JoystickSide(val label: String) {
    LEFT("Left Thumb"),
    RIGHT("Right Thumb"),
    HIDDEN("Hidden")
}

enum class UiDensity(val label: String, val scale: Float) {
    COMPACT("Compact", 0.88f),
    BALANCED("Balanced", 1.0f),
    SPACIOUS("Spacious", 1.12f)
}

enum class DockToolId(val shortLabel: String, val lcarsCode: String) {
    CHAT("Chat", "01-COM"),
    RADAR("Radar", "02-SEN"),
    MAP("Map", "03-NAV"),
    WORLD("World", "04-WLD"),
    INVENTORY("Inv", "05-INV"),
    INSPECTOR("Inspect", "06-OBJ"),
    HUDS("HUDs", "07-HUD"),
    STUDIO("Design", "08-SYS")
}

data class PersonalDesignState(
    val designLanguage: DesignLanguage = DesignLanguage.POLISHED,
    val accentPreset: AccentPreset = AccentPreset.AZURE_CYAN,
    val kthemeId: String = "",
    val useLightPosture: Boolean = false,
    val dockPosition: DockPosition = DockPosition.BOTTOM,
    val telemetryPosition: TelemetryPosition = TelemetryPosition.COMPACT,
    val joystickSide: JoystickSide = JoystickSide.LEFT,
    val joystickScale: Float = 0.85f,
    val joystickOpacity: Float = 0.62f,
    val panelOpacity: Float = 0.84f,
    val uiDensity: UiDensity = UiDensity.COMPACT,
    val hudGlobalScale: Float = 1.0f,
    val hudTouchCaptureEnabled: Boolean = true,
    val pinnedTools: List<DockToolId> = DockToolId.entries.toList(),
    val drawDistance: Float = 128f,
    val environmentPreset: EnvironmentPreset = EnvironmentPreset.TWILIGHT
) {
    val effectiveKthemeId: String
        get() = kthemeId.ifBlank {
            KthemeCatalog.defaultIdForLanguage(designLanguage, useLightPosture)
        }
}

data class DesignStarterPreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val state: PersonalDesignState
)

object DesignPresets {
    val STARTERS = listOf(
        DesignStarterPreset(
            id = "linkpoint_polished",
            name = "Linkpoint Polished",
            subtitle = "Unobstructed 3D glass • Slim bottom bar • Compact HUD",
            state = PersonalDesignState(
                designLanguage = DesignLanguage.POLISHED,
                accentPreset = AccentPreset.AZURE_CYAN,
                kthemeId = "aurora-glass-night",
                dockPosition = DockPosition.BOTTOM,
                telemetryPosition = TelemetryPosition.COMPACT,
                joystickSide = JoystickSide.LEFT,
                joystickScale = 0.85f,
                joystickOpacity = 0.62f,
                panelOpacity = 0.84f
            )
        ),
        DesignStarterPreset(
            id = "lcars_bridge",
            name = "Sweep Console (LCARS)",
            subtitle = "Black-field opposing elbows • Unboxed readouts & pills",
            state = PersonalDesignState(
                designLanguage = DesignLanguage.LCARS,
                accentPreset = AccentPreset.LCARS_APRICOT,
                kthemeId = "lcars",
                dockPosition = DockPosition.LEFT,
                telemetryPosition = TelemetryPosition.TOP,
                joystickSide = JoystickSide.RIGHT,
                panelOpacity = 1.0f
            )
        ),
        DesignStarterPreset(
            id = "metro_live",
            name = "Metro Live Hub",
            subtitle = "Sharp 0dp live telemetry tiles • Bottom action bar",
            state = PersonalDesignState(
                designLanguage = DesignLanguage.METRO,
                accentPreset = AccentPreset.METRO_COBALT,
                kthemeId = "windows-phone-metro",
                dockPosition = DockPosition.BOTTOM,
                telemetryPosition = TelemetryPosition.TOP,
                joystickSide = JoystickSide.LEFT,
                panelOpacity = 0.96f
            )
        ),
        DesignStarterPreset(
            id = "terminal_ops",
            name = "Terminal Console",
            subtitle = "Phosphor green monospace CLI • High-contrast scanlines",
            state = PersonalDesignState(
                designLanguage = DesignLanguage.TERMINAL,
                accentPreset = AccentPreset.PHOSPHOR_GREEN,
                kthemeId = "ink-terminal-modern",
                dockPosition = DockPosition.RIGHT,
                telemetryPosition = TelemetryPosition.TOP,
                joystickSide = JoystickSide.LEFT,
                uiDensity = UiDensity.COMPACT
            )
        ),
        DesignStarterPreset(
            id = "deco_rule_rail",
            name = "Art Deco Rule & Rail",
            subtitle = "Hairline gold rules • Wide-tracked Deco headers • Left rail",
            state = PersonalDesignState(
                designLanguage = DesignLanguage.RULE_AND_RAIL,
                accentPreset = AccentPreset.BEACON_AMBER,
                kthemeId = "art-deco",
                dockPosition = DockPosition.LEFT,
                telemetryPosition = TelemetryPosition.TOP,
                joystickSide = JoystickSide.RIGHT,
                panelOpacity = 0.94f
            )
        ),
        DesignStarterPreset(
            id = "editorial_press",
            name = "Editorial Press",
            subtitle = "Paper & Ink daylight • Serif body & double masthead rules",
            state = PersonalDesignState(
                designLanguage = DesignLanguage.PRESS,
                accentPreset = AccentPreset.AZURE_CYAN,
                kthemeId = "paper-ink",
                useLightPosture = true,
                dockPosition = DockPosition.BOTTOM,
                telemetryPosition = TelemetryPosition.COMPACT,
                joystickSide = JoystickSide.LEFT,
                panelOpacity = 0.96f
            )
        )
    )
}
