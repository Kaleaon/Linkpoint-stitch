package com.example.linkpoint.designsystem

import com.example.linkpoint.scene.EnvironmentPreset

enum class DesignLanguage(
    val displayName: String,
    val tagline: String
) {
    POLISHED("Polished", "Refined Linkpoint spatial glass & specular chrome"),
    TERMINAL("Terminal", "Phosphor telemetry console & monospace CLI"),
    LCARS("Sweep Console", "LCARS curved elbow rails & capsule bridge HUD"),
    METRO("Metro", "Sharp flat typographic panorama & live tiles")
}

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
    INVENTORY("Inv", "04-INV"),
    INSPECTOR("Inspect", "05-OBJ"),
    HUDS("HUDs", "06-HUD"),
    STUDIO("Design", "07-SYS")
}

data class PersonalDesignState(
    val designLanguage: DesignLanguage = DesignLanguage.POLISHED,
    val accentPreset: AccentPreset = AccentPreset.AZURE_CYAN,
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
    val environmentPreset: EnvironmentPreset = EnvironmentPreset.TWILIGHT,
    val rlvaEnabled: Boolean = false
)

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
            subtitle = "Curved elbow bridge frame • Apricot & lilac pills",
            state = PersonalDesignState(
                designLanguage = DesignLanguage.LCARS,
                accentPreset = AccentPreset.LCARS_APRICOT,
                dockPosition = DockPosition.LEFT,
                telemetryPosition = TelemetryPosition.TOP,
                joystickSide = JoystickSide.RIGHT,
                panelOpacity = 0.94f
            )
        ),
        DesignStarterPreset(
            id = "metro_live",
            name = "Metro Live Hub",
            subtitle = "Sharp 0dp live telemetry tiles • Bottom action bar",
            state = PersonalDesignState(
                designLanguage = DesignLanguage.METRO,
                accentPreset = AccentPreset.METRO_COBALT,
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
                dockPosition = DockPosition.RIGHT,
                telemetryPosition = TelemetryPosition.TOP,
                joystickSide = JoystickSide.LEFT,
                uiDensity = UiDensity.COMPACT
            )
        ),
        DesignStarterPreset(
            id = "one_handed",
            name = "One-Handed Explorer",
            subtitle = "Bottom bar • Right-thumb stick • Compact telemetry",
            state = PersonalDesignState(
                designLanguage = DesignLanguage.POLISHED,
                accentPreset = AccentPreset.BEACON_AMBER,
                dockPosition = DockPosition.BOTTOM,
                telemetryPosition = TelemetryPosition.COMPACT,
                joystickSide = JoystickSide.RIGHT,
                uiDensity = UiDensity.COMPACT
            )
        )
    )
}
