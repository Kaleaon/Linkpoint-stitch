package com.example.linkpoint.ui.floaters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.linkpoint.audio.AudioCategory
import com.example.linkpoint.audio.AudioVoiceState
import com.example.linkpoint.audio.SlVoiceWebRtcProtocol
import com.example.linkpoint.audio.VoiceParticipantState
import com.example.linkpoint.client.ViewerClient
import com.example.linkpoint.designsystem.DesignLanguage
import com.example.linkpoint.designsystem.LinkpointPanelSurface
import com.example.linkpoint.designsystem.LinkpointStatusChip
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import kotlin.math.roundToInt

/**
 * Compact top-bar cockpit control pill providing one-tap Microphone Mute/Unmute,
 * active WebRTC speaker indicator, parcel music badge, and Audio Mixer toggle.
 */
@Composable
fun CompactAudioVoicePill(
    audioState: AudioVoiceState,
    onToggleMic: () -> Unit,
    onToggleMixer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalLinkpointDesign.current
    val pillShape = RoundedCornerShape(
        when (tokens.language) {
            DesignLanguage.LCARS -> 99.dp
            DesignLanguage.METRO -> 0.dp
            DesignLanguage.TERMINAL -> 2.dp
            DesignLanguage.POLISHED -> 20.dp
        }
    )
    val micLive = audioState.voiceConnected && !audioState.micMuted && !audioState.masterMuted
    val speakingCount = audioState.activeSpeakers.size

    Row(
        modifier = modifier
            .clip(pillShape)
            .background(tokens.surfaceChrome.copy(alpha = 0.76f))
            .border(
                width = 1.dp,
                color = if (audioState.isMixerOpen) tokens.accentPrimary else tokens.borderHairline.copy(alpha = 0.75f),
                shape = pillShape
            )
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .testTag("cockpit_audio_voice_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Quick Mic Mute / Live toggle
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(pillShape)
                .clickable(onClick = onToggleMic)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("cockpit_mic_toggle_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (micLive) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = if (micLive) "Mute microphone" else "Unmute microphone",
                tint = when {
                    micLive -> tokens.statusOnline
                    audioState.voiceConnected -> tokens.textSecondary
                    else -> tokens.textMuted
                },
                modifier = Modifier.size(16.dp)
            )
        }

        // Audio & Voice Mixer button with active speaker / parcel music status
        Row(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(pillShape)
                .clickable(onClick = onToggleMixer)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("cockpit_audio_mixer_button"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = when {
                    audioState.masterMuted -> Icons.AutoMirrored.Filled.VolumeOff
                    speakingCount > 0 -> Icons.Default.GraphicEq
                    audioState.parcelMusicPlaying -> Icons.Default.MusicNote
                    else -> Icons.Default.Tune
                },
                contentDescription = "Open Audio and Voice Mixer",
                tint = when {
                    audioState.masterMuted -> tokens.statusError
                    speakingCount > 0 -> tokens.statusOnline
                    audioState.parcelMusicPlaying -> tokens.accentSecondary
                    audioState.isMixerOpen -> tokens.accentPrimary
                    else -> tokens.textSecondary
                },
                modifier = Modifier.size(16.dp)
            )
            if (speakingCount > 0) {
                Text(
                    text = "$speakingCount",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = tokens.monoFont,
                        color = tokens.statusOnline,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                )
            }
        }
    }
}

/**
 * Floating Audio, Spatial Sound, Parcel Stream & Second Life WebRTC Voice Mixer.
 */
@Composable
fun AudioAndVoiceFloater(
    audioState: AudioVoiceState,
    client: ViewerClient,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalLinkpointDesign.current
    val scrollState = rememberScrollState()

    LinkpointPanelSurface(
        modifier = modifier.testTag("audio_voice_floater"),
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Header Row + Master Mute + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (audioState.masterMuted) {
                            Icons.AutoMirrored.Filled.VolumeOff
                        } else {
                            Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = null,
                        tint = if (audioState.masterMuted) tokens.statusError else tokens.accentPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = tokens.formatHeader("Audio & Spatial Voice"),
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = tokens.textPrimary,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LinkpointStatusChip(
                        label = if (audioState.masterMuted) "Muted" else "Mute All",
                        color = if (audioState.masterMuted) tokens.statusError else tokens.textSecondary,
                        onClick = client::toggleMasterMute,
                        modifier = Modifier.testTag("audio_master_mute_button")
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onClose)
                            .testTag("audio_floater_close_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Audio Mixer",
                            tint = tokens.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = tokens.borderHairline.copy(alpha = 0.6f))

            // 2. WebRTC Spatial Voice Channel & Microphone Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(tokens.cornerRadius.coerceAtMost(12.dp)))
                    .background(tokens.surfaceChrome.copy(alpha = 0.55f))
                    .border(
                        1.dp,
                        tokens.borderHairline.copy(alpha = 0.7f),
                        RoundedCornerShape(tokens.cornerRadius.coerceAtMost(12.dp))
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = audioState.voiceChannelName.ifBlank { "Spatial Local Voice" },
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = tokens.textPrimary,
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                audioState.voiceConnecting -> "Negotiating WebRTC Opus 48kHz..."
                                audioState.voiceConnected && !audioState.micMuted -> "WebRTC Live • Mic Open"
                                audioState.voiceConnected -> "WebRTC Connected • Mic Muted"
                                else -> "Voice Disconnected"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (audioState.voiceConnected) tokens.statusOnline else tokens.textMuted,
                                fontSize = 10.sp
                            )
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinkpointStatusChip(
                            label = if (audioState.voiceConnected && !audioState.micMuted) "Mic Live" else "Mic Muted",
                            color = if (audioState.voiceConnected && !audioState.micMuted) {
                                tokens.statusOnline
                            } else {
                                tokens.textSecondary
                            },
                            onClick = client::toggleMicMuted,
                            modifier = Modifier.testTag("voice_mic_mute_button")
                        )
                        LinkpointStatusChip(
                            label = if (audioState.voiceConnected) "Leave" else "Join",
                            color = if (audioState.voiceConnected) tokens.statusWarning else tokens.accentPrimary,
                            onClick = client::toggleVoiceChannel,
                            modifier = Modifier.testTag("voice_channel_toggle_button")
                        )
                    }
                }

                // Ear Location Selector (Camera / Avatar / Mixed)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Ear Location",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = tokens.textSecondary,
                            fontSize = 10.sp
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SlVoiceWebRtcProtocol.EarLocation.entries.forEach { loc ->
                            val selected = audioState.earLocation == loc
                            LinkpointStatusChip(
                                label = loc.label,
                                color = if (selected) tokens.accentPrimary else tokens.textMuted,
                                onClick = { client.setEarLocation(loc) },
                                modifier = Modifier.testTag("ear_location_${loc.name.lowercase()}")
                            )
                        }
                    }
                }

                // Active Voice Speakers & Channel Participants
                if (audioState.participants.isNotEmpty()) {
                    HorizontalDivider(color = tokens.borderHairline.copy(alpha = 0.45f))
                    Text(
                        text = "Voice Participants (${audioState.activeSpeakers.size} speaking / ${audioState.participants.size} in channel)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = tokens.textSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    audioState.participants.forEach { participant ->
                        VoiceParticipantRow(
                            participant = participant,
                            onToggleMute = { client.toggleVoiceParticipantMute(participant.agentId) },
                            onVolumeChange = { vol -> client.setVoiceParticipantVolume(participant.agentId, vol) }
                        )
                    }
                }
            }

            // 3. Parcel Audio / Music Stream Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(tokens.cornerRadius.coerceAtMost(12.dp)))
                    .background(tokens.surfaceChrome.copy(alpha = 0.55f))
                    .border(
                        1.dp,
                        tokens.borderHairline.copy(alpha = 0.7f),
                        RoundedCornerShape(tokens.cornerRadius.coerceAtMost(12.dp))
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = if (audioState.parcelMusicPlaying) tokens.accentSecondary else tokens.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Column {
                            Text(
                                text = audioState.parcelName.ifBlank { "Parcel Audio Stream" },
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = tokens.textPrimary,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = audioState.parcelMusicUrl.ifBlank { "No parcel MusicURL configured" },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = tokens.monoFont,
                                    color = tokens.textMuted,
                                    fontSize = 9.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("parcel_music_url_text")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    val hasStream = audioState.parcelMusicUrl.isNotBlank()
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(tokens.buttonRadius.coerceAtMost(14.dp)))
                            .background(
                                when {
                                    !hasStream -> tokens.surfaceFloater.copy(alpha = 0.4f)
                                    audioState.parcelMusicPlaying -> tokens.statusWarning.copy(alpha = 0.20f)
                                    else -> tokens.accentPrimary.copy(alpha = 0.20f)
                                }
                            )
                            .border(
                                1.dp,
                                when {
                                    !hasStream -> tokens.borderHairline
                                    audioState.parcelMusicPlaying -> tokens.statusWarning
                                    else -> tokens.accentPrimary
                                },
                                RoundedCornerShape(tokens.buttonRadius.coerceAtMost(14.dp))
                            )
                            .clickable(enabled = hasStream) { client.toggleParcelMusicPlayback() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("parcel_music_toggle_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (audioState.parcelMusicPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (audioState.parcelMusicPlaying) "Stop parcel stream" else "Play parcel stream",
                            tint = when {
                                !hasStream -> tokens.textMuted
                                audioState.parcelMusicPlaying -> tokens.statusWarning
                                else -> tokens.accentPrimary
                            },
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (audioState.parcelMusicPlaying) "Stop" else "Play",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = when {
                                    !hasStream -> tokens.textMuted
                                    audioState.parcelMusicPlaying -> tokens.statusWarning
                                    else -> tokens.accentPrimary
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
            }

            // 4. Per-Category Volume Multipliers + Spatial Sound Telemetry
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Volume Mixer",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = tokens.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "3D Sounds: ${audioState.activeObjectSounds.size} active • ${audioState.queuedObjectSoundCount} queued",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = tokens.monoFont,
                            color = tokens.textMuted,
                            fontSize = 9.sp
                        )
                    )
                }

                AudioCategory.entries.forEach { category ->
                    val vol = audioState.volumeFor(category)
                    val pct = (vol * 100f).roundToInt()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = category.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = tokens.textSecondary,
                                    fontSize = 10.sp
                                )
                            )
                            Text(
                                text = if (audioState.masterMuted) "MUTED ($pct%)" else "$pct%",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = tokens.monoFont,
                                    color = if (audioState.masterMuted) tokens.statusError else tokens.textPrimary,
                                    fontSize = 10.sp
                                )
                            )
                        }
                        Slider(
                            value = vol,
                            onValueChange = { next -> client.setAudioCategoryVolume(category, next) },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = tokens.accentPrimary,
                                activeTrackColor = if (audioState.masterMuted) tokens.textMuted else tokens.accentPrimary,
                                inactiveTrackColor = tokens.borderHairline
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .semantics { contentDescription = "${category.label} volume" }
                                .testTag("audio_slider_${category.name.lowercase()}")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceParticipantRow(
    participant: VoiceParticipantState,
    onToggleMute: () -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag("voice_participant_${participant.agentId}"),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                participant.isMuted -> tokens.statusError
                                participant.isSpeaking -> tokens.statusOnline
                                else -> tokens.textMuted
                            }
                        )
                )
                Text(
                    text = buildString {
                        append(participant.displayName)
                        participant.distanceMeters?.let { d ->
                            append(" • ${d.roundToInt()}m")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = tokens.textPrimary,
                        fontWeight = if (participant.isSpeaking) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 10.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "${(participant.userVolume * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = tokens.monoFont,
                        color = tokens.textMuted,
                        fontSize = 9.sp
                    )
                )
                LinkpointStatusChip(
                    label = if (participant.isMuted) "Unmute" else "Mute",
                    color = if (participant.isMuted) tokens.statusError else tokens.textSecondary,
                    onClick = onToggleMute,
                    modifier = Modifier.testTag("voice_peer_mute_${participant.agentId}")
                )
            }
        }

        LinearProgressIndicator(
            progress = { if (participant.isMuted) 0f else participant.audioLevel.coerceIn(0f, 1f) },
            color = if (participant.isSpeaking) tokens.statusOnline else tokens.accentSecondary,
            trackColor = tokens.borderHairline.copy(alpha = 0.45f),
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
        )

        Slider(
            value = participant.userVolume.coerceIn(0f, 2f),
            onValueChange = onVolumeChange,
            valueRange = 0f..2f,
            colors = SliderDefaults.colors(
                thumbColor = tokens.accentSecondary,
                activeTrackColor = tokens.accentSecondary,
                inactiveTrackColor = tokens.borderHairline
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .semantics { contentDescription = "${participant.displayName} gain" }
                .testTag("voice_peer_volume_${participant.agentId}")
        )
    }
}
