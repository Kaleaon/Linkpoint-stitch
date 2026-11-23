package com.example.linkpoint.cache

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.linkpoint.designsystem.AccentPreset
import com.example.linkpoint.designsystem.DesignLanguage
import com.example.linkpoint.designsystem.DockPosition
import com.example.linkpoint.designsystem.DockToolId
import com.example.linkpoint.designsystem.JoystickSide
import com.example.linkpoint.designsystem.PersonalDesignState
import com.example.linkpoint.designsystem.TelemetryPosition
import com.example.linkpoint.designsystem.UiDensity
import com.example.linkpoint.protocol.XmlRpcLoginClient
import com.example.linkpoint.scene.EnvironmentPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.linkpointDataStore: DataStore<Preferences> by preferencesDataStore(name = "linkpoint_prefs")

/**
 * Preferences DataStore repository persisting user's Design Language,
 * Personal Layout configuration, custom saved preset, and graphics/HUD settings.
 */
class PreferencesRepository(private val context: Context) {

    private object Keys {
        val DESIGN_LANGUAGE = stringPreferencesKey("design_language")
        val ACCENT_PRESET = stringPreferencesKey("accent_preset")
        val LIGHT_POSTURE = booleanPreferencesKey("light_posture")
        val DOCK_POSITION = stringPreferencesKey("dock_position")
        val TELEMETRY_POSITION = stringPreferencesKey("telemetry_position")
        val JOYSTICK_SIDE = stringPreferencesKey("joystick_side")
        val JOYSTICK_SCALE = floatPreferencesKey("joystick_scale")
        val JOYSTICK_OPACITY = floatPreferencesKey("joystick_opacity")
        val PANEL_OPACITY = floatPreferencesKey("panel_opacity")
        val UI_DENSITY = stringPreferencesKey("ui_density")
        val HUD_GLOBAL_SCALE = floatPreferencesKey("hud_global_scale")
        val HUD_TOUCH_CAPTURE = booleanPreferencesKey("hud_touch_capture")
        val PINNED_TOOLS = stringPreferencesKey("pinned_tools")
        val DRAW_DISTANCE = floatPreferencesKey("draw_distance")
        val ENVIRONMENT_PRESET = stringPreferencesKey("environment_preset")
        val RLVA_ENABLED = booleanPreferencesKey("rlva_enabled")
        val CUSTOM_PRESET_BLOB = stringPreferencesKey("custom_preset_blob")
        val CLIENT_MAC_HASH = stringPreferencesKey("client_mac_hash")
        val CLIENT_ID0_HASH = stringPreferencesKey("client_id0_hash")
    }

    val designStateFlow: Flow<PersonalDesignState> = context.linkpointDataStore.data.map { prefs ->
        val pinnedRaw = prefs[Keys.PINNED_TOOLS]
        val pinned = if (pinnedRaw.isNullOrBlank()) {
            DockToolId.entries.toList()
        } else {
            pinnedRaw.split(",").mapNotNull { name ->
                runCatching { DockToolId.valueOf(name) }.getOrNull()
            }.ifEmpty { DockToolId.entries.toList() }
        }
        PersonalDesignState(
            designLanguage = enumOrDefault(prefs[Keys.DESIGN_LANGUAGE], DesignLanguage.POLISHED),
            accentPreset = enumOrDefault(prefs[Keys.ACCENT_PRESET], AccentPreset.AZURE_CYAN),
            useLightPosture = prefs[Keys.LIGHT_POSTURE] ?: false,
            dockPosition = enumOrDefault(prefs[Keys.DOCK_POSITION], DockPosition.RIGHT),
            telemetryPosition = enumOrDefault(prefs[Keys.TELEMETRY_POSITION], TelemetryPosition.TOP),
            joystickSide = enumOrDefault(prefs[Keys.JOYSTICK_SIDE], JoystickSide.LEFT),
            joystickScale = prefs[Keys.JOYSTICK_SCALE] ?: 1.0f,
            joystickOpacity = prefs[Keys.JOYSTICK_OPACITY] ?: 0.86f,
            panelOpacity = prefs[Keys.PANEL_OPACITY] ?: 0.90f,
            uiDensity = enumOrDefault(prefs[Keys.UI_DENSITY], UiDensity.BALANCED),
            hudGlobalScale = prefs[Keys.HUD_GLOBAL_SCALE] ?: 1.0f,
            hudTouchCaptureEnabled = prefs[Keys.HUD_TOUCH_CAPTURE] ?: true,
            pinnedTools = pinned,
            drawDistance = prefs[Keys.DRAW_DISTANCE] ?: 128f,
            environmentPreset = enumOrDefault(prefs[Keys.ENVIRONMENT_PRESET], EnvironmentPreset.TWILIGHT),
            rlvaEnabled = prefs[Keys.RLVA_ENABLED] ?: true
        )
    }

    val hasCustomPresetFlow: Flow<Boolean> = context.linkpointDataStore.data.map { prefs ->
        !prefs[Keys.CUSTOM_PRESET_BLOB].isNullOrBlank()
    }

    suspend fun saveDesignState(state: PersonalDesignState) {
        context.linkpointDataStore.edit { prefs ->
            prefs[Keys.DESIGN_LANGUAGE] = state.designLanguage.name
            prefs[Keys.ACCENT_PRESET] = state.accentPreset.name
            prefs[Keys.LIGHT_POSTURE] = state.useLightPosture
            prefs[Keys.DOCK_POSITION] = state.dockPosition.name
            prefs[Keys.TELEMETRY_POSITION] = state.telemetryPosition.name
            prefs[Keys.JOYSTICK_SIDE] = state.joystickSide.name
            prefs[Keys.JOYSTICK_SCALE] = state.joystickScale
            prefs[Keys.JOYSTICK_OPACITY] = state.joystickOpacity
            prefs[Keys.PANEL_OPACITY] = state.panelOpacity
            prefs[Keys.UI_DENSITY] = state.uiDensity.name
            prefs[Keys.HUD_GLOBAL_SCALE] = state.hudGlobalScale
            prefs[Keys.HUD_TOUCH_CAPTURE] = state.hudTouchCaptureEnabled
            prefs[Keys.PINNED_TOOLS] = state.pinnedTools.joinToString(",") { it.name }
            prefs[Keys.DRAW_DISTANCE] = state.drawDistance
            prefs[Keys.ENVIRONMENT_PRESET] = state.environmentPreset.name
            prefs[Keys.RLVA_ENABLED] = state.rlvaEnabled
        }
    }

    suspend fun saveCustomUserPreset(state: PersonalDesignState) {
        val serialized = listOf(
            state.designLanguage.name,
            state.accentPreset.name,
            state.useLightPosture.toString(),
            state.dockPosition.name,
            state.telemetryPosition.name,
            state.joystickSide.name,
            state.joystickScale.toString(),
            state.panelOpacity.toString(),
            state.uiDensity.name,
            state.hudGlobalScale.toString()
        ).joinToString("|")
        context.linkpointDataStore.edit { it[Keys.CUSTOM_PRESET_BLOB] = serialized }
    }

    suspend fun loadCustomUserPreset(fallback: PersonalDesignState): PersonalDesignState {
        var loaded = fallback
        context.linkpointDataStore.edit { prefs ->
            val blob = prefs[Keys.CUSTOM_PRESET_BLOB] ?: return@edit
            val parts = blob.split("|")
            if (parts.size >= 10) {
                loaded = fallback.copy(
                    designLanguage = enumOrDefault(parts[0], fallback.designLanguage),
                    accentPreset = enumOrDefault(parts[1], fallback.accentPreset),
                    useLightPosture = parts[2].toBooleanStrictOrNull() ?: fallback.useLightPosture,
                    dockPosition = enumOrDefault(parts[3], fallback.dockPosition),
                    telemetryPosition = enumOrDefault(parts[4], fallback.telemetryPosition),
                    joystickSide = enumOrDefault(parts[5], fallback.joystickSide),
                    joystickScale = parts[6].toFloatOrNull() ?: fallback.joystickScale,
                    panelOpacity = parts[7].toFloatOrNull() ?: fallback.panelOpacity,
                    uiDensity = enumOrDefault(parts[8], fallback.uiDensity),
                    hudGlobalScale = parts[9].toFloatOrNull() ?: fallback.hudGlobalScale
                )
            }
        }
        return loaded
    }

    suspend fun getOrCreateClientIdentity(): Pair<String, String> {
        val current = context.linkpointDataStore.data.first()
        val existingMac = current[Keys.CLIENT_MAC_HASH]
        val existingId0 = current[Keys.CLIENT_ID0_HASH]
        if (!existingMac.isNullOrBlank() && !existingId0.isNullOrBlank()) {
            return existingMac to existingId0
        }
        val newMac = existingMac ?: XmlRpcLoginClient.md5Hex("lp-mac-${UUID.randomUUID()}")
        val newId0 = existingId0 ?: XmlRpcLoginClient.md5Hex("lp-id0-${UUID.randomUUID()}")
        context.linkpointDataStore.edit { prefs ->
            prefs[Keys.CLIENT_MAC_HASH] = newMac
            prefs[Keys.CLIENT_ID0_HASH] = newId0
        }
        return newMac to newId0
    }

    suspend fun getMfaHash(accountKey: String): String {
        val key = stringPreferencesKey("mfa_hash_${XmlRpcLoginClient.md5Hex(accountKey)}")
        return context.linkpointDataStore.data.first()[key].orEmpty()
    }

    suspend fun saveMfaHash(accountKey: String, hash: String) {
        if (hash.isBlank()) return
        val key = stringPreferencesKey("mfa_hash_${XmlRpcLoginClient.md5Hex(accountKey)}")
        context.linkpointDataStore.edit { prefs -> prefs[key] = hash }
    }

    suspend fun clearMfaHash(accountKey: String) {
        val key = stringPreferencesKey("mfa_hash_${XmlRpcLoginClient.md5Hex(accountKey)}")
        context.linkpointDataStore.edit { prefs -> prefs.remove(key) }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
        if (raw == null) default else runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
}
