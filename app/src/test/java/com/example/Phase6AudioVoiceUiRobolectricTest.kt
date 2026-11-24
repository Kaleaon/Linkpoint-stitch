package com.example

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.linkpoint.audio.AudioCategory
import com.example.linkpoint.audio.SlVoiceWebRtcProtocol.EarLocation
import com.example.linkpoint.client.ViewerSessionViewModel
import com.example.linkpoint.designsystem.LinkpointTheme
import com.example.linkpoint.ui.floaters.AudioAndVoiceFloater
import com.example.linkpoint.ui.floaters.CompactAudioVoicePill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w440dp-h960dp-xxhdpi")
class Phase6AudioVoiceUiRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `renders cockpit audio pill and mixer floater and updates sandbox voice parcel stream and category state`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = ViewerSessionViewModel(app)
        viewModel.enterLocalSandbox()
        viewModel.stopSimLoopForTesting()

        // Verify local sandbox populated sample voice participants & parcel stream URL
        val initialAudio = viewModel.state.value.audioVoice
        assertTrue(initialAudio.voiceConnected)
        assertTrue(initialAudio.participants.size >= 2)
        assertEquals(1, initialAudio.activeSpeakers.size)
        assertEquals("https://streams.linkpoint.example/archipelago-ambient.ogg", initialAudio.parcelMusicUrl)

        composeRule.setContent {
            val state by viewModel.state.collectAsState()
            LinkpointTheme(designState = state.design) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF0E1624))
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.width(390.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        CompactAudioVoicePill(
                            audioState = state.audioVoice,
                            onToggleMic = viewModel::toggleMicMuted,
                            onToggleMixer = viewModel::toggleAudioMixer
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        if (state.audioVoice.isMixerOpen) {
                            AudioAndVoiceFloater(
                                audioState = state.audioVoice,
                                client = viewModel,
                                onClose = viewModel::toggleAudioMixer,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()

        // 1. Open Audio & Voice Mixer via cockpit pill
        composeRule.onNodeWithTag("cockpit_audio_mixer_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(viewModel.state.value.audioVoice.isMixerOpen)
        composeRule.onNodeWithTag("audio_voice_floater").assertIsDisplayed()
        composeRule.onNodeWithText("https://streams.linkpoint.example/archipelago-ambient.ogg").assertIsDisplayed()

        // 2. Unmute microphone from cockpit pill
        assertTrue(viewModel.state.value.audioVoice.micMuted)
        composeRule.onNodeWithTag("cockpit_mic_toggle_button").performClick()
        composeRule.waitForIdle()
        assertFalse(viewModel.state.value.audioVoice.micMuted)

        // 3. Switch EarLocation to Mixed (2) and Avatar (1)
        composeRule.onNodeWithTag("ear_location_mixed").performClick()
        composeRule.waitForIdle()
        assertEquals(EarLocation.MIXED, viewModel.state.value.audioVoice.earLocation)

        composeRule.onNodeWithTag("ear_location_avatar").performClick()
        composeRule.waitForIdle()
        assertEquals(EarLocation.AVATAR, viewModel.state.value.audioVoice.earLocation)

        // 4. Toggle Parcel Audio Stream Play / Stop
        assertFalse(viewModel.state.value.audioVoice.parcelMusicPlaying)
        composeRule.onNodeWithTag("parcel_music_toggle_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(viewModel.state.value.audioVoice.parcelMusicPlaying)

        // Capture a native Robolectric render screenshot for PR visual verification
        runCatching {
            val rootView: View = composeRule.activity.findViewById<View>(android.R.id.content)
                ?: composeRule.activity.window.decorView
            if (rootView.width > 0 && rootView.height > 0) {
                val captureHeight = rootView.height.coerceAtMost(2340)
                val bitmap = Bitmap.createBitmap(rootView.width, captureHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                rootView.draw(canvas)
                FileOutputStream(File("/tmp/phase6_audio_voice_floater.png")).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        }

        // 5. Toggle Master Mute and verify category volume update
        composeRule.onNodeWithTag("audio_master_mute_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(viewModel.state.value.audioVoice.masterMuted)
        assertEquals(0f, viewModel.state.value.audioVoice.effectiveMultiplier(AudioCategory.VOICE), 1e-4f)

        viewModel.setAudioCategoryVolume(AudioCategory.VOICE, 0.42f)
        composeRule.waitForIdle()
        assertEquals(0.42f, viewModel.state.value.audioVoice.volumeFor(AudioCategory.VOICE), 1e-3f)

        // 6. Mute active speaking participant
        val speakerId = initialAudio.activeSpeakers.first().agentId
        composeRule.onNodeWithTag("voice_peer_mute_$speakerId").performScrollTo().performClick()
        composeRule.waitForIdle()
        val updatedSpeaker = viewModel.state.value.audioVoice.participants.first { it.agentId == speakerId }
        assertTrue(updatedSpeaker.isMuted)

        viewModel.disconnect()
    }
}
