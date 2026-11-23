package com.example.linkpoint.protocol

import com.example.linkpoint.scene.ColorRgba
import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.RegionEnvironmentSettings
import com.example.linkpoint.scene.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Modular parser for Second Life & OpenSimulator `EnvironmentSettings` / `RegionEnvironment` (EEP & WindLight)
 * LLSD payloads returned by the `EnvironmentSettings` / `ExtEnvironment` HTTP capability or `EventQueueGet`.
 */
object EepEnvironmentParser {

    /**
     * Parses an LLSD `EnvironmentSettings` or `RegionEnvironment` map into [RegionEnvironmentSettings],
     * returning `null` if the LLSD does not contain recognizable sky or water environment parameters.
     */
    fun parse(root: LlsdValue): RegionEnvironmentSettings? {
        val topMap = root.asMap()
        if (topMap.isEmpty()) return null

        val envNode = unwrapEnvironmentRoot(root)
        val dayFraction = extractDayFraction(root, envNode)

        val skyMap = resolveSkyMap(envNode, dayFraction)
        val waterMap = resolveWaterMap(envNode, dayFraction)
        if (skyMap.isEmpty() && waterMap.isEmpty() && !hasDirectEnvKeys(envNode.asMap())) {
            return null
        }

        val combinedSky = if (skyMap.isNotEmpty()) skyMap else envNode.asMap()
        val combinedWater = if (waterMap.isNotEmpty()) waterMap else envNode.asMap()

        // 1. Sun & Moon directions / azimuth / elevation
        val rawSunDir = parseLightDirection(
            map = combinedSky,
            vecKeys = listOf("sun_direction", "lightnorm", "sun_vec", "sunDirection"),
            rotKeys = listOf("sun_rotation", "sunRotation"),
            azimKeys = listOf("sun_azimuth", "sunAzimuth", "east_angle"),
            elevKeys = listOf("sun_elevation", "sunElevation", "sun_angle")
        )
        val rawMoonDir = parseLightDirection(
            map = combinedSky,
            vecKeys = listOf("moon_direction", "moon_vec", "moonDirection"),
            rotKeys = listOf("moon_rotation", "moonRotation"),
            azimKeys = listOf("moon_azimuth", "moonAzimuth"),
            elevKeys = listOf("moon_elevation", "moonElevation")
        ) ?: Vec3(-0.40f, -0.35f, 0.78f).normalized()

        val sunDir = rawSunDir ?: deriveSunDirectionFromDayFraction(dayFraction)
        val isDaylight = sunDir.z >= -0.04f
        val activeLightDir = if (isDaylight) {
            Vec3(sunDir.x, sunDir.y, sunDir.z.coerceAtLeast(0.06f)).normalized()
        } else {
            Vec3(rawMoonDir.x, rawMoonDir.y, rawMoonDir.z.coerceAtLeast(0.15f)).normalized()
        }

        val sunAzimuth = atan2(sunDir.y, sunDir.x)
        val sunElevation = asin(sunDir.z.coerceIn(-1f, 1f))

        // 2. Sky colors, haze & cloud density
        val ambient = parseColor(combinedSky, "ambient", "ambient_color", "ambientColor")
            ?: if (isDaylight) ColorRgba(0.38f, 0.42f, 0.48f, 1f) else ColorRgba(0.12f, 0.16f, 0.26f, 1f)

        val sunlight = parseColor(combinedSky, "sunlight_color", "sun_color", "sunlightColor")
            ?: if (isDaylight) ColorRgba(1.0f, 0.95f, 0.86f, 1f) else ColorRgba(0.56f, 0.70f, 0.95f, 1f)

        val blueHorizon = parseColor(combinedSky, "blue_horizon", "blueHorizon", "horizon_color")
            ?: if (isDaylight) ColorRgba(0.60f, 0.78f, 0.94f, 1f) else ColorRgba(0.07f, 0.13f, 0.24f, 1f)

        val blueDensity = parseColor(combinedSky, "blue_density", "blueDensity", "zenith_color")
            ?: if (isDaylight) ColorRgba(0.14f, 0.42f, 0.82f, 1f) else ColorRgba(0.02f, 0.05f, 0.12f, 1f)

        val hazeHorizon = parseScalar(combinedSky, 0.20f, "haze_horizon", "hazeHorizon").coerceIn(0f, 2f)
        val hazeDensity = parseScalar(combinedSky, 0.18f, "haze_density", "hazeDensity").coerceIn(0f, 2f)

        val cloudColor = parseColor(combinedSky, "cloud_color", "cloudColor")
            ?: ColorRgba(0.92f, 0.94f, 0.97f, 1f)

        val cloudDensity = parseCloudDensity(combinedSky).coerceIn(0f, 1f)

        // Synthesize physical horizon & zenith colors combining Rayleigh scatter, haze, and sun warmth
        val hazeBlend = (hazeDensity * (0.35f + hazeHorizon * 0.45f)).coerceIn(0f, 0.65f)
        val horizonR = lerp(blueHorizon.r, (sunlight.r * 0.65f + ambient.r * 0.35f), hazeBlend).coerceIn(0.02f, 1f)
        val horizonG = lerp(blueHorizon.g, (sunlight.g * 0.65f + ambient.g * 0.35f), hazeBlend).coerceIn(0.02f, 1f)
        val horizonB = lerp(blueHorizon.b, (sunlight.b * 0.65f + ambient.b * 0.35f), hazeBlend).coerceIn(0.03f, 1f)

        val zenithR = (blueDensity.r * 0.78f + ambient.r * 0.22f).coerceIn(0.01f, 1f)
        val zenithG = (blueDensity.g * 0.78f + ambient.g * 0.22f).coerceIn(0.01f, 1f)
        val zenithB = (blueDensity.b * 0.82f + ambient.b * 0.18f).coerceIn(0.02f, 1f)

        val ambientLuma = (ambient.r * 0.299f + ambient.g * 0.587f + ambient.b * 0.114f)
        val ambientStrength = (0.18f + ambientLuma * 0.55f).coerceIn(0.18f, 0.72f)

        // 3. Water fog color, density, normal scale & Fresnel
        val waterFogRaw = parseColor(combinedWater, "water_fog_color", "waterFogColor", "fog_color", "water_color")
            ?: ColorRgba(0.05f, 0.30f, 0.50f, 0.80f)
        val waterFogDensity = parseScalar(combinedWater, 4.0f, "water_fog_density", "waterFogDensity", "fog_density", "water_fog_density_exponent")
            .coerceIn(0.2f, 16f)
        val waterAlpha = (0.68f + (waterFogDensity / 16f) * 0.22f).coerceIn(0.62f, 0.92f)
        val waterFogColor = ColorRgba(waterFogRaw.r, waterFogRaw.g, waterFogRaw.b, waterAlpha)

        val waterNormalScale = parseVec3(combinedWater, "norm_scale", "normal_scale", "water_normal_scale", "wave_scale")
            ?.let {
                Vec3(
                    it.x.coerceIn(0.2f, 8.0f),
                    it.y.coerceIn(0.2f, 8.0f),
                    it.z.coerceIn(0.2f, 8.0f)
                )
            } ?: Vec3(2.0f, 2.0f, 1.0f)

        val fresnelScale = parseScalar(combinedWater, 0.40f, "fresnel_scale", "fresnelScale").coerceIn(0.05f, 1.0f)
        val fresnelOffset = parseScalar(combinedWater, 0.50f, "fresnel_offset", "fresnelOffset").coerceIn(0.0f, 0.95f)

        return RegionEnvironmentSettings(
            sunDirection = activeLightDir,
            moonDirection = rawMoonDir,
            sunAzimuthRad = sunAzimuth,
            sunElevationRad = sunElevation,
            ambientColor = ambient,
            sunlightColor = sunlight,
            blueHorizon = blueHorizon,
            blueDensity = blueDensity,
            zenithColor = ColorRgba(zenithR, zenithG, zenithB, 1f),
            horizonColor = ColorRgba(horizonR, horizonG, horizonB, 1f),
            hazeHorizon = hazeHorizon,
            hazeDensity = (hazeDensity * 0.5f).coerceIn(0f, 0.85f),
            cloudDensity = cloudDensity,
            cloudColor = cloudColor,
            waterFogColor = waterFogColor,
            waterFogDensity = waterFogDensity,
            waterNormalScale = waterNormalScale,
            fresnelScale = fresnelScale,
            fresnelOffset = fresnelOffset,
            ambientStrength = ambientStrength,
            isDaylight = isDaylight
        )
    }

    private fun unwrapEnvironmentRoot(root: LlsdValue): LlsdValue {
        val map = root.asMap()
        for (key in listOf("environment", "current_environment", "Environment", "RegionEnvironment", "content")) {
            val child = map[key]
            if (child is LlsdValue.MapVal && child.entries.isNotEmpty()) {
                return child
            }
        }
        return root
    }

    private fun extractDayFraction(root: LlsdValue, envNode: LlsdValue): Float {
        val m1 = envNode.asMap()
        val m0 = root.asMap()
        val length = (m1["day_length"] ?: m0["day_length"])?.asDouble(14400.0)?.toFloat()?.takeIf { it > 1f } ?: 14400f
        val offset = (m1["day_offset"] ?: m0["day_offset"])?.asDouble(0.0)?.toFloat() ?: 0f
        val explicitFrac = (m1["day_fraction"] ?: m0["day_fraction"] ?: m1["time_of_day"] ?: m0["time_of_day"])
            ?.asDouble(-1.0)?.toFloat()
        if (explicitFrac != null && explicitFrac in 0f..1f) return explicitFrac
        // Default midday-afternoon fraction when no live clock offset is given
        if (offset == 0f && !m1.containsKey("day_offset") && !m0.containsKey("day_offset")) return 0.45f
        val mod = ((offset % length) + length) % length
        return (mod / length).coerceIn(0f, 1f)
    }

    private fun resolveSkyMap(envNode: LlsdValue, dayFraction: Float): Map<String, LlsdValue> {
        val map = envNode.asMap()
        for (key in listOf("sky", "Sky", "current_sky", "sky_preset")) {
            val node = map[key]?.asMap()
            if (!node.isNullOrEmpty()) return node
        }
        // Check EEP day_cycle tracks (track 1..4 are sky tracks, track 0 is water)
        val dayCycle = (map["day_cycle"] ?: map["dayCycle"])?.asMap()
        if (dayCycle != null) {
            val tracks = dayCycle["tracks"]?.asList().orEmpty()
            // Sky track is typically index 1 (or first non-water track)
            for (idx in listOf(1, 0)) {
                if (idx < tracks.size) {
                    val kf = pickKeyframe(tracks[idx], dayFraction)
                    if (kf.isNotEmpty() && (kf.containsKey("blue_horizon") || kf.containsKey("sun_direction") || kf.containsKey("sun_rotation") || kf.containsKey("ambient"))) {
                        return kf
                    }
                }
            }
            val skyTrack = dayCycle["sky_track"] ?: dayCycle["sky"]
            if (skyTrack != null) {
                val kf = pickKeyframe(skyTrack, dayFraction)
                if (kf.isNotEmpty()) return kf
            }
        }
        return emptyMap()
    }

    private fun resolveWaterMap(envNode: LlsdValue, dayFraction: Float): Map<String, LlsdValue> {
        val map = envNode.asMap()
        for (key in listOf("water", "Water", "current_water", "water_preset")) {
            val node = map[key]?.asMap()
            if (!node.isNullOrEmpty()) return node
        }
        val dayCycle = (map["day_cycle"] ?: map["dayCycle"])?.asMap()
        if (dayCycle != null) {
            val waterTrack = dayCycle["water_track"] ?: dayCycle["water"]
            if (waterTrack != null) {
                val kf = pickKeyframe(waterTrack, dayFraction)
                if (kf.isNotEmpty()) return kf
            }
            val tracks = dayCycle["tracks"]?.asList().orEmpty()
            if (tracks.isNotEmpty()) {
                val kf = pickKeyframe(tracks[0], dayFraction)
                if (kf.containsKey("water_fog_color") || kf.containsKey("norm_scale") || kf.containsKey("normal_scale")) {
                    return kf
                }
            }
        }
        return emptyMap()
    }

    private fun pickKeyframe(trackNode: LlsdValue, dayFraction: Float): Map<String, LlsdValue> {
        if (trackNode is LlsdValue.MapVal) {
            val keyframes = trackNode.entries["keyframes"]?.asList() ?: trackNode.entries["frames"]?.asList()
            if (keyframes == null) return trackNode.entries
            return selectClosestFrame(keyframes, dayFraction)
        }
        if (trackNode is LlsdValue.ArrayVal) {
            return selectClosestFrame(trackNode.items, dayFraction)
        }
        return emptyMap()
    }

    private fun selectClosestFrame(frames: List<LlsdValue>, dayFraction: Float): Map<String, LlsdValue> {
        var bestMap: Map<String, LlsdValue> = emptyMap()
        var bestDist = Float.MAX_VALUE
        for (f in frames) {
            val fm = f.asMap()
            if (fm.isEmpty()) continue
            val t = (fm["time"] ?: fm["key"] ?: fm["position"])?.asDouble(0.5)?.toFloat() ?: 0.5f
            val dist = abs(t - dayFraction)
            val content = (fm["frame"] ?: fm["sky"] ?: fm["water"] ?: fm["settings"])?.asMap() ?: fm
            if (dist < bestDist && content.isNotEmpty()) {
                bestDist = dist
                bestMap = content
            }
        }
        return bestMap
    }

    private fun hasDirectEnvKeys(map: Map<String, LlsdValue>): Boolean {
        val known = setOf(
            "sun_direction", "lightnorm", "sun_rotation", "sun_azimuth",
            "ambient", "ambient_color", "sunlight_color",
            "blue_horizon", "blue_density", "haze_horizon", "haze_density",
            "water_fog_color", "waterFogColor", "norm_scale", "normal_scale"
        )
        return map.keys.any { it in known }
    }

    private fun parseLightDirection(
        map: Map<String, LlsdValue>,
        vecKeys: List<String>,
        rotKeys: List<String>,
        azimKeys: List<String>,
        elevKeys: List<String>
    ): Vec3? {
        for (k in vecKeys) {
            val v = parseVec3(map, k)
            if (v != null && v.length() > 1e-4f) {
                return v.normalized()
            }
        }
        for (k in rotKeys) {
            val q = map[k]?.asQuatOrNull()
            if (q != null) {
                // In SL EEP, sun_rotation rotates +X (East) into the world light direction vector
                val dir = q.normalized().rotate(Vec3(1f, 0f, 0f))
                if (dir.length() > 1e-4f) return dir.normalized()
            }
        }
        var azim: Float? = null
        for (k in azimKeys) {
            val node = map[k]
            if (node != null && node !is LlsdValue.Undef) {
                azim = normalizeAngleRadians(node.asDouble(0.0).toFloat())
                break
            }
        }
        var elev: Float? = null
        for (k in elevKeys) {
            val node = map[k]
            if (node != null && node !is LlsdValue.Undef) {
                elev = normalizeAngleRadians(node.asDouble(0.75).toFloat())
                break
            }
        }
        if (azim != null || elev != null) {
            val a = azim ?: 0.6f
            val e = (elev ?: 0.8f).coerceIn(-PI.toFloat() * 0.5f, PI.toFloat() * 0.5f)
            val cosE = cos(e)
            return Vec3(cosE * cos(a), cosE * sin(a), sin(e)).normalized()
        }
        return null
    }

    private fun normalizeAngleRadians(raw: Float): Float {
        if (!raw.isFinite()) return 0f
        // Convert degrees to radians if magnitude clearly exceeds 2*PI (e.g. 45.0 or 135.0 degrees)
        return if (abs(raw) > (2.0 * PI + 0.01)) {
            (raw * PI / 180.0).toFloat()
        } else {
            raw
        }
    }

    private fun deriveSunDirectionFromDayFraction(dayFraction: Float): Vec3 {
        val angle = (dayFraction - 0.25f) * (2f * PI.toFloat())
        return Vec3(cos(angle) * 0.85f, 0.32f, sin(angle)).normalized()
    }

    private fun parseColor(map: Map<String, LlsdValue>, vararg keys: String): ColorRgba? {
        for (k in keys) {
            val node = map[k] ?: continue
            if (node is LlsdValue.Undef) continue
            when (node) {
                is LlsdValue.ArrayVal -> {
                    if (node.items.size >= 3) {
                        var r = node.items[0].asDouble(0.0).toFloat()
                        var g = node.items[1].asDouble(0.0).toFloat()
                        var b = node.items[2].asDouble(0.0).toFloat()
                        var a = if (node.items.size >= 4) node.items[3].asDouble(1.0).toFloat() else 1f
                        val maxChan = maxOf(r, g, b)
                        if (maxChan > 4.0f) {
                            // 0..255 integer RGB array
                            r /= 255f; g /= 255f; b /= 255f
                            if (a > 1.5f) a /= 255f
                        } else if (maxChan > 1.0f) {
                            // WindLight HDR 0..3 float scale: soft Reinhard-style compression preserving hue
                            val inv = 1f / maxChan.coerceAtMost(3.5f)
                            r *= inv; g *= inv; b *= inv
                        }
                        return ColorRgba(
                            r.coerceIn(0f, 1f),
                            g.coerceIn(0f, 1f),
                            b.coerceIn(0f, 1f),
                            a.coerceIn(0f, 1f)
                        )
                    }
                }
                is LlsdValue.MapVal -> {
                    val rNode = node.entries["r"] ?: node.entries["R"] ?: node.entries["x"]
                    val gNode = node.entries["g"] ?: node.entries["G"] ?: node.entries["y"]
                    val bNode = node.entries["b"] ?: node.entries["B"] ?: node.entries["z"]
                    if (rNode != null && gNode != null && bNode != null &&
                        rNode !is LlsdValue.Undef && gNode !is LlsdValue.Undef && bNode !is LlsdValue.Undef
                    ) {
                        var r = rNode.asDouble(0.0).toFloat()
                        var g = gNode.asDouble(0.0).toFloat()
                        var b = bNode.asDouble(0.0).toFloat()
                        if (maxOf(r, g, b) > 4.0f) {
                            r /= 255f; g /= 255f; b /= 255f
                        } else if (maxOf(r, g, b) > 1.0f) {
                            val inv = 1f / maxOf(r, g, b)
                            r *= inv; g *= inv; b *= inv
                        }
                        return ColorRgba(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), 1f)
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun parseVec3(map: Map<String, LlsdValue>, vararg keys: String): Vec3? {
        for (k in keys) {
            val node = map[k] ?: continue
            if (node is LlsdValue.Undef) continue
            node.asVec3OrNull()?.let { return it }
            if (node is LlsdValue.MapVal) {
                val x = (node.entries["x"] ?: node.entries["X"])?.asDouble(Double.NaN)?.toFloat()
                val y = (node.entries["y"] ?: node.entries["Y"])?.asDouble(Double.NaN)?.toFloat()
                val z = (node.entries["z"] ?: node.entries["Z"])?.asDouble(Double.NaN)?.toFloat()
                if (x != null && y != null && z != null && x.isFinite() && y.isFinite() && z.isFinite()) {
                    return Vec3(x, y, z)
                }
            }
        }
        return null
    }

    private fun parseScalar(map: Map<String, LlsdValue>, default: Float, vararg keys: String): Float {
        for (k in keys) {
            val node = map[k] ?: continue
            if (node is LlsdValue.Undef) continue
            when (node) {
                is LlsdValue.Real -> if (node.value.isFinite()) return node.value.toFloat()
                is LlsdValue.IntVal -> return node.value.toFloat()
                is LlsdValue.ArrayVal -> {
                    if (node.items.isNotEmpty()) {
                        val v = node.items[0].asDouble(Double.NaN).toFloat()
                        if (v.isFinite()) return v
                    }
                }
                else -> Unit
            }
        }
        return default
    }

    private fun parseCloudDensity(skyMap: Map<String, LlsdValue>): Float {
        for (k in listOf("cloud_shadow", "cloud_density", "cloudDensity")) {
            val node = skyMap[k]
            if (node != null && node !is LlsdValue.Undef) {
                return parseScalar(skyMap, 0.28f, k)
            }
        }
        // In WindLight/EEP, cloud_pos_density1 is [scrollX, scrollY, density]
        val posDens = skyMap["cloud_pos_density1"]?.asList()
        if (posDens != null && posDens.size >= 3) {
            val d = posDens[2].asDouble(0.28).toFloat()
            if (d.isFinite()) return d
        }
        return 0.28f
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
