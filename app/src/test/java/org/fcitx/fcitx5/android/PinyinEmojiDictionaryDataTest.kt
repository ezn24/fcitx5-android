/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.pinyin.PinyinEmojiDictionaryData
import org.fcitx.fcitx5.android.data.pinyin.PinyinEmojiDictionaryEntry
import org.junit.Assert.*
import org.junit.Test

class PinyinEmojiDictionaryDataTest {
    @Test
    fun acceptsNativeWhitespaceFormatAndPreservesWeights() {
        val data = PinyinEmojiDictionaryData.fromLines(listOf(
            "# comment", "", "invalid",
            "😀\tkai'xin\t0.3", "🎉   gong'xi   0.6", "🎊 gong'xi"
        ))
        assertEquals(3, data.size)
        assertEquals(PinyinEmojiDictionaryEntry("🎉", "gong'xi", "0.6"), data[1])
        assertEquals("", data[2].weight)
        assertEquals(data.toList(), PinyinEmojiDictionaryData.fromLines(data.serialize().lines()).toList())
    }

    @Test
    fun recoversTheWeightFromLegacyRowsWithAnExtraColumn() {
        val entry = PinyinEmojiDictionaryEntry.fromLine("🧚🏻 jing'ling\t0.3\t0.3")
        assertEquals("jing'ling", entry?.code)
        assertEquals("0.3", entry?.weight)
    }
}
