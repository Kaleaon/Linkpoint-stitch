package com.example.linkpoint.render

/**
 * Modularized OpenGL ES 3.0 GLSL shader library for Linkpoint:
 * - Sky dome with EEP zenith/horizon scatter, sun/moon corona, haze, and dynamic cloud density.
 * - 256m terrain heightfield with EEP directional sunlight and distance haze.
 * - Hybrid PBR metallic-roughness / legacy SL specular-shininess + emissive glow + UV micro-bump + alpha modes.
 * - Rippled WindLight/EEP water plane with normal scale, Schlick Fresnel sky reflection, and solar glints.
 * - Orthographic Pass-2 Avatar Attachment HUDs.
 *
 * Designed for single-pass 60 FPS mobile rendering without extra FBOs or texture lookups.
 */
object RenderShaders {

    const val SKY_VS = """#version 300 es
        in vec2 aPos;
        out vec2 vUv;
        void main() {
            vUv = aPos * 0.5 + 0.5;
            gl_Position = vec4(aPos, 0.999, 1.0);
        }
    """

    const val SKY_FS = """#version 300 es
        precision mediump float;
        in vec2 vUv;
        uniform vec3 uZenith;
        uniform vec3 uHorizon;
        uniform vec3 uSunColor;
        uniform vec3 uSunDir;
        uniform highp float uTime;
        uniform float uPitch;
        uniform float uCloudDensity;
        uniform float uHazeDensity;
        out vec4 fragColor;
        void main() {
            float elev = clamp(vUv.y + uPitch * 0.35, 0.0, 1.0);
            float scatterCurve = mix(0.68, 0.42, clamp(uHazeDensity, 0.0, 1.0));
            vec3 sky = mix(uHorizon, uZenith, pow(elev, scatterCurve));

            float sunX = clamp(0.5 + uSunDir.x * 0.32, 0.12, 0.88);
            float sunY = clamp(0.28 + max(uSunDir.z, 0.0) * 0.48 + uPitch * 0.25, 0.10, 0.92);
            float d = length(vUv - vec2(sunX, sunY));
            float sunGlow = exp(-d * 8.5) * 0.52 + smoothstep(0.048, 0.014, d) * 0.92;

            float c1 = sin(vUv.x * 11.0 + uTime * 0.07) * cos(vUv.y * 8.5 - uTime * 0.04);
            float c2 = sin(vUv.x * 23.0 - uTime * 0.05 + c1) * cos(vUv.y * 15.0 + uTime * 0.03);
            float cloudNoise = clamp((c1 * 0.6 + c2 * 0.4) * 0.5 + 0.5, 0.0, 1.0);
            float cloudMask = smoothstep(1.0 - clamp(uCloudDensity, 0.0, 0.95), 1.02, cloudNoise);
            float cloudBand = smoothstep(0.24, 0.78, elev) * smoothstep(0.98, 0.52, elev) * cloudMask * 0.42;

            vec3 cloudTint = mix(uHorizon, vec3(0.96, 0.97, 0.99), 0.65);
            vec3 col = mix(sky + uSunColor * sunGlow, cloudTint, cloudBand);
            fragColor = vec4(col, 1.0);
        }
    """

    const val WORLD_VS = """#version 300 es
        in vec3 aPos;
        in vec3 aNormal;
        in vec2 aUv;
        uniform mat4 uMvp;
        uniform mat4 uModel;
        uniform mat3 uNormalMat;
        out vec3 vWorldPos;
        out vec3 vNormal;
        out vec2 vUv;
        void main() {
            vec4 wp = uModel * vec4(aPos, 1.0);
            vWorldPos = wp.xyz;
            vNormal = normalize(uNormalMat * aNormal);
            vUv = aUv;
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """

    const val TERRAIN_FS = """#version 300 es
        precision mediump float;
        in vec3 vWorldPos;
        in vec3 vNormal;
        in vec2 vUv;
        uniform vec3 uSunDir;
        uniform vec3 uSunColor;
        uniform vec3 uFogColor;
        uniform vec3 uEyePos;
        uniform float uAmbient;
        uniform float uHazeDensity;
        out vec4 fragColor;
        void main() {
            float h = vWorldPos.z;
            vec3 sand = vec3(0.76, 0.70, 0.54);
            vec3 grass = vec3(0.22, 0.45, 0.26);
            vec3 rock = vec3(0.38, 0.41, 0.46);
            vec3 snow = vec3(0.86, 0.90, 0.95);
            vec3 base = mix(sand, grass, smoothstep(20.2, 23.0, h));
            base = mix(base, rock, smoothstep(28.0, 35.0, h));
            base = mix(base, snow, smoothstep(40.0, 47.0, h));
            float slope = 1.0 - clamp(vNormal.z, 0.0, 1.0);
            base = mix(base, rock, smoothstep(0.25, 0.65, slope));

            vec2 g = abs(fract(vWorldPos.xy / 16.0 - 0.5) - 0.5);
            float grid = smoothstep(0.025, 0.0, min(g.x, g.y)) * 0.06;

            float diff = max(dot(normalize(vNormal), normalize(uSunDir)), 0.0);
            vec3 lit = (base + vec3(grid)) * (uAmbient + diff * uSunColor * 0.85);
            float dist = length(vWorldPos - uEyePos);
            float hazeScale = mix(150.0, 95.0, clamp(uHazeDensity, 0.0, 1.0));
            float fog = clamp((dist - 50.0) / hazeScale, 0.0, 0.84);
            fragColor = vec4(mix(lit, uFogColor, fog), 1.0);
        }
    """

    /**
     * Hybrid glTF 2.0 Metallic-Roughness + SL Shininess/Bumpiness/Glow/AlphaMode fragment shader:
     * - `uMaterialParams`: x = metallicFactor [0..1], y = roughnessFactor [0.04..1], z = glow [0..1], w = bumpStrength [0..1]
     * - `uAlphaParams`: x = alphaMode (0=NONE/OPAQUE, 1=BLEND, 2=MASK, 3=EMISSIVE), y = alphaCutoff [0..1]
     * - `uMetallicRoughnessTex` (glTF 2.0 ORM packing): `.r` = Ambient Occlusion, `.g` = Roughness, `.b` = Metalness
     * - `uNormalTex`: Second Life / OpenGL right-handed tangent-space normal map (`+X` right, `+Y` green-up relief, `+Z` outward)
     * - `uPbrExtraParams`: x = normalScale, y = occlusionStrength [0..1], z = doubleSided (1.0 = two-sided, 0.0 = single-sided)
     */
    const val PRIM_FS = """#version 300 es
        precision mediump float;
        in vec3 vWorldPos;
        in vec3 vNormal;
        in vec2 vUv;
        uniform vec3 uSunDir;
        uniform vec3 uSunColor;
        uniform vec3 uFogColor;
        uniform vec3 uSkyZenith;
        uniform vec3 uEyePos;
        uniform float uAmbient;
        uniform float uHazeDensity;
        uniform vec4 uColor;
        uniform float uFullbright;
        uniform float uSelectedPulse;
        uniform vec4 uMaterialParams;
        uniform vec2 uAlphaParams;
        uniform vec3 uEmissiveFactor;
        uniform vec3 uPbrExtraParams;
        uniform sampler2D uTex;
        uniform float uUseTex;
        uniform sampler2D uMetallicRoughnessTex;
        uniform float uUseMetallicRoughnessTex;
        uniform sampler2D uNormalTex;
        uniform float uUseNormalTex;
        uniform sampler2D uEmissiveTex;
        uniform float uUseEmissiveTex;
        out vec4 fragColor;

        void main() {
            // Enforce glTF 2.0 doubleSided=false backface discard when single-sided
            if (!gl_FrontFacing && uPbrExtraParams.z < 0.5) {
                discard;
            }

            vec4 baseCol = uColor;
            float emissiveMaskBoost = 0.0;
            int alphaMode = int(uAlphaParams.x + 0.5);

            if (uUseTex > 0.5) {
                vec4 texCol = texture(uTex, vUv);
                if (alphaMode == 0) {
                    baseCol.rgb *= texCol.rgb;
                    baseCol.a = 1.0;
                } else if (alphaMode == 2) {
                    baseCol.rgb *= texCol.rgb;
                    float cutoff = max(uAlphaParams.y, 0.10);
                    if (texCol.a < cutoff) discard;
                    baseCol.a = 1.0;
                } else if (alphaMode == 3) {
                    baseCol.rgb *= texCol.rgb;
                    emissiveMaskBoost = texCol.a;
                    baseCol.a = 1.0;
                } else {
                    baseCol *= texCol;
                    if (baseCol.a < 0.06) discard;
                }
            } else if (alphaMode == 0) {
                baseCol.a = 1.0;
            } else if (alphaMode == 2 && baseCol.a < max(uAlphaParams.y, 0.10)) {
                discard;
            }

            vec3 n = normalize(vNormal);
            if (!gl_FrontFacing) {
                n = -n;
            }

            // Screen-space world & UV derivatives for tangent-space normal maps and SL micro-bump
            vec3 dp1 = dFdx(vWorldPos);
            vec3 dp2 = dFdy(vWorldPos);
            vec2 duv1 = dFdx(vUv);
            vec2 duv2 = dFdy(vUv);

            if (uUseNormalTex > 0.5) {
                // Second Life & glTF 2.0 OpenGL right-handed tangent-space normal map convention:
                // +X = +u tangent, +Y (green channel) = +v bitangent (upward relief, NOT inverted), +Z = +normal
                float det = duv1.x * duv2.y - duv1.y * duv2.x;
                vec3 rawT = dp1 * duv2.y - dp2 * duv1.y;
                vec3 rawB = dp2 * duv1.x - dp1 * duv2.x;
                float invDet = abs(det) > 1e-7 ? (1.0 / det) : 1.0;
                vec3 T = rawT * invDet;
                vec3 B = rawB * invDet;
                T = T - n * dot(n, T);
                B = B - n * dot(n, B);
                float tLen = max(length(T), 1e-5);
                float bLen = max(length(B), 1e-5);
                T /= tLen;
                B /= bLen;

                vec3 tangentNormal = texture(uNormalTex, vUv).xyz * 2.0 - 1.0;
                tangentNormal.xy *= uPbrExtraParams.x;
                n = normalize(T * tangentNormal.x + B * tangentNormal.y + n * max(tangentNormal.z, 1e-3));
            } else {
                // Procedural SL TextureEntry bumpiness fallback
                float bumpAmt = uMaterialParams.w;
                if (bumpAmt > 0.01) {
                    float bu = cos(vUv.x * 37.699) * sin(vUv.y * 37.699);
                    vec3 tangent = normalize(dp1 + vec3(1e-4));
                    vec3 bitangent = normalize(dp2 + vec3(1e-4));
                    n = normalize(n + (tangent * dFdx(bu) + bitangent * dFdy(bu)) * (bumpAmt * 0.45));
                }
            }

            float metallic = clamp(uMaterialParams.x, 0.0, 1.0);
            float roughness = clamp(uMaterialParams.y, 0.04, 1.0);
            float occlusion = 1.0;
            if (uUseMetallicRoughnessTex > 0.5) {
                // Canonical glTF 2.0 / Second Life ORM channel packing:
                // Red (.r) = Occlusion, Green (.g) = Roughness, Blue (.b) = Metalness
                vec4 ormSample = texture(uMetallicRoughnessTex, vUv);
                occlusion = mix(1.0, ormSample.r, clamp(uPbrExtraParams.y, 0.0, 1.0));
                roughness = clamp(roughness * ormSample.g, 0.04, 1.0);
                metallic = clamp(metallic * ormSample.b, 0.0, 1.0);
            }
            float glow = clamp(uMaterialParams.z + emissiveMaskBoost * 0.75, 0.0, 1.5);

            vec3 l = normalize(uSunDir);
            vec3 v = normalize(uEyePos - vWorldPos);
            vec3 hVec = normalize(l + v);

            float nDotL = max(dot(n, l), 0.0);
            float nDotV = max(dot(n, v), 0.02);
            float nDotH = max(dot(n, hVec), 0.0);
            float vDotH = max(dot(v, hVec), 0.0);

            // Fast mobile GGX microfacet specular + Schlick Fresnel
            vec3 f0 = mix(vec3(0.04), baseCol.rgb, metallic);
            vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - vDotH, 5.0);
            float alpha = roughness * roughness;
            float alpha2 = alpha * alpha;
            float denom = nDotH * nDotH * (alpha2 - 1.0) + 1.0;
            float dGgx = alpha2 / max(3.14159 * denom * denom, 0.001);
            float k = (roughness + 1.0) * (roughness + 1.0) * 0.125;
            float gVis = (nDotL / (nDotL * (1.0 - k) + k)) * (nDotV / (nDotV * (1.0 - k) + k));
            vec3 directSpec = (dGgx * gVis * fresnel / max(4.0 * nDotL * nDotV, 0.05)) * nDotL * uSunColor;

            // Analytical sky/horizon IBL reflection attenuated by ORM ambient occlusion (.r)
            vec3 reflDir = reflect(-v, n);
            vec3 envSky = mix(uFogColor, uSkyZenith, clamp(reflDir.z * 0.65 + 0.35, 0.0, 1.0));
            vec3 envFresnel = f0 + (max(vec3(1.0 - roughness), f0) - f0) * pow(1.0 - nDotV, 4.0);
            float smoothness = 1.0 - roughness;
            vec3 envRefl = envSky * envFresnel * (smoothness * smoothness * 0.75) * occlusion;

            float backFill = max(dot(n, -l), 0.0) * 0.18;
            float hemi = mix(0.80, 1.15, n.z * 0.5 + 0.5);
            vec3 kD = (vec3(1.0) - fresnel) * (1.0 - metallic);
            vec3 indirectDiffuse = kD * baseCol.rgb * (uAmbient * hemi + backFill) * occlusion;
            vec3 directDiffuse = kD * baseCol.rgb * (nDotL * uSunColor * 0.86);

            vec3 lit = indirectDiffuse + directDiffuse + directSpec + envRefl;
            lit = mix(lit, baseCol.rgb * 1.10, uFullbright);

            // glTF 2.0 emissiveFactor * emissiveTexture + Second Life Glow
            vec3 emissiveTerm = uEmissiveFactor;
            if (uUseEmissiveTex > 0.5) {
                emissiveTerm *= texture(uEmissiveTex, vUv).rgb;
            }
            lit += emissiveTerm;
            if (glow > 0.001) {
                lit += baseCol.rgb * (glow * 1.15) + vec3(glow * 0.18);
            }

            float edge = smoothstep(0.035, 0.0, min(min(vUv.x, 1.0 - vUv.x), min(vUv.y, 1.0 - vUv.y)));
            float untexturedEdge = (1.0 - uUseTex) * 0.07;
            lit = mix(lit, vec3(0.22, 0.86, 1.0), edge * (untexturedEdge + uSelectedPulse * 0.85));

            float dist = length(vWorldPos - uEyePos);
            float hazeScale = mix(160.0, 100.0, clamp(uHazeDensity, 0.0, 1.0));
            float fog = clamp((dist - 62.0) / hazeScale, 0.0, 0.78) * (1.0 - clamp(glow * 0.6, 0.0, 0.6));
            fragColor = vec4(mix(lit, uFogColor, fog), baseCol.a);
        }
    """

    const val WATER_VS = """#version 300 es
        in vec3 aPos;
        in vec2 aUv;
        uniform mat4 uMvp;
        uniform float uTime;
        uniform vec3 uNormalScale;
        out vec3 vWorldPos;
        out vec2 vUv;
        void main() {
            vec3 p = aPos;
            float sx = max(uNormalScale.x, 0.25) * 0.07;
            float sy = max(uNormalScale.y, 0.25) * 0.06;
            p.z += sin(p.x * sx + uTime * 1.55) * 0.11 + cos(p.y * sy + uTime * 1.20) * 0.09;
            vWorldPos = p;
            vUv = aUv;
            gl_Position = uMvp * vec4(p, 1.0);
        }
    """

    const val WATER_FS = """#version 300 es
        precision mediump float;
        in vec3 vWorldPos;
        in vec2 vUv;
        uniform highp float uTime;
        uniform vec4 uWaterTint;
        uniform vec3 uHorizon;
        uniform vec3 uZenith;
        uniform vec3 uSunDir;
        uniform vec3 uSunColor;
        uniform vec3 uEyePos;
        uniform highp vec3 uNormalScale;
        uniform vec2 uFresnelParams; // x = fresnelScale, y = fresnelOffset
        out vec4 fragColor;
        void main() {
            float wx = vWorldPos.x * (0.28 * max(uNormalScale.x, 0.2));
            float wy = vWorldPos.y * (0.26 * max(uNormalScale.y, 0.2));
            float nx = cos(wx + uTime * 1.95) * 0.14 + cos(wx * 2.1 - uTime * 1.3) * 0.07;
            float ny = -sin(wy - uTime * 1.60) * 0.14 + sin(wy * 1.9 + uTime * 1.1) * 0.07;
            vec3 n = normalize(vec3(nx, ny, 1.0));

            vec3 v = normalize(uEyePos - vWorldPos);
            vec3 l = normalize(uSunDir);
            vec3 r = reflect(-v, n);

            float nDotV = clamp(dot(n, v), 0.0, 1.0);
            float schlick = pow(1.0 - nDotV, 4.0);
            float fresnel = clamp(uFresnelParams.y * 0.55 + uFresnelParams.x * 1.35 * schlick, 0.08, 0.94);

            vec3 skyRefl = mix(uHorizon, uZenith, clamp(r.z * 0.85, 0.0, 1.0));
            float sunGlint = pow(max(dot(r, l), 0.0), 72.0) * 0.95 + pow(max(dot(r, l), 0.0), 16.0) * 0.18;

            vec3 col = mix(uWaterTint.rgb, skyRefl, fresnel) + uSunColor * sunGlint;
            float outAlpha = clamp(mix(uWaterTint.a, 0.94, fresnel * 0.45), 0.55, 0.96);
            fragColor = vec4(col, outAlpha);
        }
    """

    const val HUD_VS = """#version 300 es
        in vec3 aPos;
        in vec2 aUv;
        uniform vec4 uRect; // x, y, w, h in [0..1] top-left screen coordinates
        out vec2 vUv;
        void main() {
            float sx = uRect.x + aPos.x * uRect.z;
            float sy = uRect.y + aPos.y * uRect.w;
            vec2 ndc = vec2(sx * 2.0 - 1.0, 1.0 - sy * 2.0);
            vUv = aUv;
            gl_Position = vec4(ndc, 0.0, 1.0);
        }
    """

    const val HUD_FS = """#version 300 es
        precision mediump float;
        in vec2 vUv;
        uniform vec4 uColor;
        uniform vec4 uBorderColor;
        uniform float uFlash;
        uniform sampler2D uTex;
        uniform float uUseTex;
        out vec4 fragColor;
        void main() {
            vec4 base = uColor;
            if (uUseTex > 0.5) {
                base *= texture(uTex, vUv);
            }
            float edge = step(vUv.x, 0.025) + step(0.975, vUv.x) + step(vUv.y, 0.04) + step(0.96, vUv.y);
            float grad = uUseTex > 0.5 ? 1.0 : (0.85 + (1.0 - vUv.y) * 0.25);
            vec3 body = base.rgb * grad + vec3(uFlash);
            vec3 col = mix(body, uBorderColor.rgb, clamp(edge, 0.0, 1.0) * (uUseTex > 0.5 ? 0.45 : 1.0));
            fragColor = vec4(col, base.a);
        }
    """
}
