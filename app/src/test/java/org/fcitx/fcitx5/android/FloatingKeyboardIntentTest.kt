/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.FloatingKeyboardIntent
import org.junit.Assert.*
import org.junit.Test

class FloatingKeyboardIntentTest {
    private val appId = "org.fcitx.fcitx5.android.ez"
    private val action = appId + FloatingKeyboardIntent.ACTION_SUFFIX

    @Test
    fun acceptsBothExplicitBooleanModes() {
        assertEquals(true, FloatingKeyboardIntent.requestedMode(action, appId, true, true))
        assertEquals(false, FloatingKeyboardIntent.requestedMode(action, appId, false, true))
    }

    @Test
    fun ignoresRequestsWhenAccessIsDisabled() {
        assertNull(FloatingKeyboardIntent.requestedMode(action, appId, true, false))
        assertNull(FloatingKeyboardIntent.requestedMode(action, appId, false, false))
    }

    @Test
    fun rejectsMissingAndMistypedValues() {
        for (value in listOf(null, "true", "false", 1, 0)) {
            assertNull(FloatingKeyboardIntent.requestedMode(action, appId, value, true))
        }
    }

    @Test
    fun isolatesBuildVariantsAndIgnoresUnrelatedActions() {
        assertNull(FloatingKeyboardIntent.requestedMode(null, appId, true, true))
        assertNull(FloatingKeyboardIntent.requestedMode("other.action", appId, true, true))
        assertNull(FloatingKeyboardIntent.requestedMode(action, "$appId.debug", true, true))
        val debugId = "$appId.debug"
        assertEquals(true, FloatingKeyboardIntent.requestedMode(
            debugId + FloatingKeyboardIntent.ACTION_SUFFIX, debugId, true, true
        ))
    }
}
