/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.voice.SpeechEndTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEndTrackerTest {
    @Test
    fun usesCustomSilenceDelayBelowPreviousMinimum() {
        val tracker = SpeechEndTracker(100)
        repeat(6) { assertFalse(tracker.accept(true)) }
        repeat(4) { assertFalse(tracker.accept(false)) }
        assertTrue(tracker.accept(false))
    }

    @Test
    fun ignoresInitialSilenceAndBriefNoise() {
        val tracker = SpeechEndTracker(1500)
        repeat(100) { assertFalse(tracker.accept(false)) }
        repeat(5) { assertFalse(tracker.accept(true)) }
        repeat(100) { assertFalse(tracker.accept(false)) }
    }

    @Test
    fun restartsSilenceTimerWhenSpeechResumesAndEndsOnlyOnce() {
        val tracker = SpeechEndTracker(1500)
        repeat(6) { assertFalse(tracker.accept(true)) }
        repeat(74) { assertFalse(tracker.accept(false)) }
        assertFalse(tracker.accept(true))
        repeat(74) { assertFalse(tracker.accept(false)) }
        assertTrue(tracker.accept(false))
        assertFalse(tracker.accept(false))
    }
}
