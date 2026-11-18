/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

internal object FloatingKeyboardIntent {
    const val ACTION_SUFFIX = ".action.SET_FLOATING_KEYBOARD"
    const val EXTRA_ENABLED = "enabled"

    fun requestedMode(action: String?, applicationId: String, value: Any?, allowed: Boolean): Boolean? =
        if (allowed && action == applicationId + ACTION_SUFFIX) value as? Boolean else null
}
