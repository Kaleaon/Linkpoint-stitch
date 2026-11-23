package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.linkpoint.client.ViewerSessionViewModel
import com.example.linkpoint.designsystem.DesignLanguage
import com.example.linkpoint.designsystem.KthemeCatalog
import com.example.linkpoint.designsystem.KthemeFamily
import com.example.linkpoint.designsystem.LcarsBlackField
import com.example.linkpoint.designsystem.LcarsOpposingElbowsFrame
import com.example.linkpoint.designsystem.LcarsUnboxedReadout
import com.example.linkpoint.designsystem.LinkpointContrast
import com.example.linkpoint.designsystem.LinkpointDesignLanguage
import com.example.linkpoint.designsystem.LinkpointTheme
import com.example.linkpoint.designsystem.PersonalDesignState
import com.example.linkpoint.designsystem.resolveLinkpointTokens
import com.example.linkpoint.ui.DesignStudioContent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase10DesignSystemTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `exposes all 6 linkpoint-design layout packs and 28+ Ktheme palettes across 4 families`() {
        val languages = LinkpointDesignLanguage.entries
        assertEquals(6, languages.size)
        assertTrue(languages.contains(DesignLanguage.POLISHED))
        assertTrue(languages.contains(DesignLanguage.TERMINAL))
        assertTrue(languages.contains(DesignLanguage.LCARS))
        assertTrue(languages.contains(DesignLanguage.METRO))
        assertTrue(languages.contains(DesignLanguage.RULE_AND_RAIL))
        assertTrue(languages.contains(DesignLanguage.PRESS))
        assertEquals(DesignLanguage.POLISHED, DesignLanguage.POLISHED_DARK)

        val requiredIds = listOf(
            "linkpoint-default",
            "firestorm",
            "sl-classic",
            "lcars",
            "cleverferret-gold",
            "windows-phone-metro",
            "aurora-glass-night",
            "ink-terminal-modern",
            "midnight-amber",
            "neo-noir-neon",
            "obsidian-crimson",
            "slate-cyan",
            "slate-gunmetal",
            "emerald-silver",
            "royal-bronze",
            "royal-silver",
            "navy-gold",
            "deep-purple-platinum",
            "burgundy-rose-gold",
            "charcoal-champagne",
            "forest-copper",
            "art-deco",
            "art-nouveau",
            "frutiger-aero",
            "paper-ink",
            "calm-clinical",
            "solarpunk-civic",
            "rose-gold"
        )
        assertTrue("Expected at least 28 Ktheme palettes", KthemeCatalog.ALL.size >= 28)
        for (id in requiredIds) {
            assertNotNull("Missing required Ktheme ID: $id", KthemeCatalog.findById(id))
        }

        // Verify all 4 aesthetic families are populated
        for (family in KthemeFamily.entries) {
            val palettes = KthemeCatalog.byFamily(family)
            assertTrue("Family $family should have multiple palettes", palettes.size >= 4)
        }

        // Verify aliases resolve cleanly
        assertEquals("cleverferret-gold", KthemeCatalog.findById("cleverferret")?.id)
        assertEquals("windows-phone-metro", KthemeCatalog.findById("metro")?.id)
    }

    @Test
    fun `audits WCAG AA contrast across all 6 layouts x 32 Ktheme palettes and enforces pure black LCARS field`() {
        // Basic luminance & contrast sanity checks matching linkpoint-design contrast.ts
        assertEquals(0.0, LinkpointContrast.relativeLuminance(Color.Black), 1e-6)
        assertEquals(1.0, LinkpointContrast.relativeLuminance(Color.White), 1e-6)
        assertEquals(21.0, LinkpointContrast.contrastRatio(Color.Black, Color.White), 0.05)

        val lowContrastFg = Color(0xFF555960)
        val darkBg = Color(0xFF10141B)
        val corrected = LinkpointContrast.ensureMinContrast(lowContrastFg, darkBg, 4.5)
        assertTrue(LinkpointContrast.meetsAA(corrected, darkBg, 4.5))

        // Exhaustively audit all 6 DesignLanguage x all 32 KthemePalette combinations
        for (language in DesignLanguage.entries) {
            for (palette in KthemeCatalog.ALL) {
                val state = PersonalDesignState(
                    designLanguage = language,
                    kthemeId = palette.id,
                    useLightPosture = palette.isDaylight,
                    panelOpacity = 0.88f
                )
                val tokens = resolveLinkpointTokens(state)
                val audit = LinkpointContrast.auditTokens(tokens)

                assertTrue(
                    "Primary text on floater failed WCAG AA (${audit.textOnFloater}) for $language + ${palette.id}",
                    audit.textOnFloater >= 4.5 - 0.02
                )
                assertTrue(
                    "Primary text on canvas failed WCAG AA (${audit.textOnCanvas}) for $language + ${palette.id}",
                    audit.textOnCanvas >= 4.5 - 0.02
                )
                assertTrue(
                    "Secondary text on floater failed WCAG AA (${audit.secondaryTextOnFloater}) for $language + ${palette.id}",
                    audit.secondaryTextOnFloater >= 4.5 - 0.02
                )
                assertTrue(
                    "onAccent ink failed WCAG AA (${audit.onAccentOnPrimary}) for $language + ${palette.id}",
                    audit.onAccentOnPrimary >= 4.5 - 0.02
                )
                assertTrue(
                    "Accent visibility failed 3:1 (${audit.accentOnFloater}) for $language + ${palette.id}",
                    audit.accentOnFloater >= 3.0 - 0.02
                )

                if (language == DesignLanguage.LCARS) {
                    assertEquals(
                        "LCARS must enforce CleverFerret pure #000000 black field",
                        LcarsBlackField,
                        tokens.surfaceCanvas
                    )
                }
            }
        }
    }

    @Test
    fun `renders DesignStudioContent and switches Rule & Rail, Press, LCARS opposing elbows, and Kthemes`() {
        val vm = ViewerSessionViewModel(ApplicationProvider.getApplicationContext())
        vm.enterLocalSandbox()

        var liveState by mutableStateOf(vm.state.value)

        composeRule.setContent {
            LinkpointTheme(designState = liveState.design) {
                DesignStudioContent(
                    state = liveState,
                    client = vm,
                    onClose = {}
                )
            }
        }

        composeRule.onNodeWithTag("design_studio_floater").assertIsDisplayed()
        composeRule.onNodeWithTag("wcag_audit_badge").assertIsDisplayed()

        // Switch to Rule & Rail layout
        composeRule.onNodeWithTag("studio_lang_rule_and_rail")
            .performScrollTo()
            .performClick()
        liveState = vm.state.value
        composeRule.waitForIdle()
        assertEquals(DesignLanguage.RULE_AND_RAIL, liveState.design.designLanguage)

        // Switch to Press layout
        composeRule.onNodeWithTag("studio_lang_press")
            .performScrollTo()
            .performClick()
        liveState = vm.state.value
        composeRule.waitForIdle()
        assertEquals(DesignLanguage.PRESS, liveState.design.designLanguage)

        // Switch to LCARS (Sweep Console) and apply CleverFerret Gold Ktheme
        composeRule.onNodeWithTag("studio_lang_lcars")
            .performScrollTo()
            .performClick()
        vm.setKthemePalette("cleverferret-gold")
        liveState = vm.state.value
        composeRule.waitForIdle()

        assertEquals(DesignLanguage.LCARS, liveState.design.designLanguage)
        assertEquals("cleverferret-gold", liveState.design.kthemeId)
        composeRule.onNodeWithText("#000000").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `renders standalone LcarsOpposingElbowsFrame with pure black field and unboxed readouts`() {
        composeRule.setContent {
            LinkpointTheme(
                designState = PersonalDesignState(
                    designLanguage = DesignLanguage.LCARS,
                    kthemeId = "lcars"
                )
            ) {
                LcarsOpposingElbowsFrame(
                    title = "WORLD CONSOLE",
                    codeTag = "04-WLD",
                    primaryColor = Color(0xFFFF9C45),
                    secondaryColor = Color(0xFFCC88FF),
                    tertiaryColor = Color(0xFF99CCFF)
                ) {
                    LcarsUnboxedReadout(
                        label = "REGION",
                        value = "LINKPOINT",
                        accentColor = Color(0xFFFFCC66)
                    )
                }
            }
        }
        composeRule.onNodeWithText("LINKPOINT").assertIsDisplayed()
        composeRule.onNodeWithText("REGION").assertIsDisplayed()
    }
}
