/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import java.io.Closeable

internal class VoiceEndpointDetector private constructor(
    private val vad: VadWebRTC,
    frameSamples: Int,
    silenceMs: Long,
) : Closeable {
    private val frame = ByteArray(frameSamples * 2)
    private val silence = SpeechEndTracker(silenceMs)
    private var filled = 0

    fun accept(pcm: ByteArray): Boolean {
        var offset = 0
        while (offset < pcm.size) {
            val count = minOf(frame.size - filled, pcm.size - offset)
            pcm.copyInto(frame, filled, offset, offset + count)
            offset += count
            filled += count
            if (filled == frame.size) {
                filled = 0
                if (silence.accept(vad.isSpeech(frame))) return true
            }
        }
        return false
    }

    override fun close() = vad.close()

    companion object {
        fun create(sampleRate: Int, silenceMs: Long): VoiceEndpointDetector? {
            val rate = SampleRate.entries.firstOrNull { it.value == sampleRate } ?: return null
            val frame = FrameSize.entries.firstOrNull { it.value == sampleRate / 50 } ?: return null
            return VoiceEndpointDetector(
                VadWebRTC(rate, frame, Mode.AGGRESSIVE),
                frame.value,
                silenceMs,
            )
        }
    }
}

internal class SpeechEndTracker(private val silenceMs: Long) {
    private var speechMs = 0
    private var hasSpeech = false
    private var quietMs = 0L
    private var ended = false

    fun accept(speech: Boolean): Boolean {
        if (ended) return false
        if (speech) {
            if (!hasSpeech) {
                speechMs += 20
                hasSpeech = speechMs >= 120
            }
            quietMs = 0
        } else if (hasSpeech) {
            quietMs += 20
            ended = quietMs >= silenceMs
        } else {
            speechMs = 0
        }
        return ended
    }
}
