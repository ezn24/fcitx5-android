/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

/** Emoji 17.0 skin-tone families that older Android ICU data may not recognize. */
internal object RecentSkinToneSequences {
    fun isGroupModifierBase(codePoint: Int): Boolean = codePoint == 0x1F46F || codePoint == 0x1F93C

    fun isStandardSequence(emoji: String): Boolean {
        val points = emoji.codePoints().toArray()
        if (points.isEmpty()) return false
        val suffix = if (points.size > 1 && points[1] in 0x1F3FB..0x1F3FF) 2 else 1
        if (isGroupModifierBase(points[0])) {
            return points.size == suffix ||
                (points.size == suffix + 3 && points[suffix] == 0x200D &&
                    (points[suffix + 1] == 0x2640 || points[suffix + 1] == 0x2642) &&
                    points[suffix + 2] == 0xFE0F)
        }
        // Ballet dancer uses the existing person modifier base, but a new ZWJ sequence.
        return points[0] == 0x1F9D1 && points.size == suffix + 2 &&
            points[suffix] == 0x200D && points[suffix + 1] == 0x1FA70
    }
}
