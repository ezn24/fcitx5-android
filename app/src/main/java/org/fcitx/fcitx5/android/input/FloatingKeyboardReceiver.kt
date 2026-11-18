/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

class FloatingKeyboardReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AppPrefs.getInstance()
        val requested = runCatching {
            FloatingKeyboardIntent.requestedMode(
                intent.action, BuildConfig.APPLICATION_ID,
                intent.extras?.get(FloatingKeyboardIntent.EXTRA_ENABLED),
                prefs.keyboard.allowFloatingKeyboardIntent.getValue(),
            )
        }.getOrNull() ?: return
        if (requested) prefs.internal.oneHandModeEnabled.setValue(false)
        prefs.internal.floatingModeEnabled.setValue(requested)
    }
}
