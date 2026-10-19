/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.pinyin.EmojiPinyinConverter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class EmojiPinyinConverterTest {
    private fun converter() = EmojiPinyinConverter.load(
        File("src/main/assets/emoji/hanzi-pinyin.bin").inputStream()
    )

    @Test
    fun convertsBothChineseScriptsAndMixedInput() {
        val converter = converter()
        assertEquals("gong'xi", converter.convert("恭喜"))
        assertEquals("kai'xin", converter.convert("開心"))
        assertEquals("kai'xin", converter.convert("开心"))
        assertEquals("xie'xie", converter.convert("謝謝"))
        assertEquals("nv'er", converter.convert("女兒"))
        assertEquals("gong'xi", converter.convert("恭 xi"))
    }

    @Test
    fun preservesManualCorrectionsAndNormalizesSeparators() {
        val converter = converter()
        assertEquals("chong'qing", converter.convert("chong'qing"))
        assertEquals("gong'xi", converter.convert(" GONG XI "))
        assertEquals("gong'xi", converter.convert("ｇｏｎｇ’ｘｉ"))
        assertEquals("nv'er", converter.convert("nü'er"))
        assertEquals("nve'lve", converter.convert("nue'lue"))
    }

    @Test
    fun rejectsInvalidInputWithoutSilentlyDroppingCharacters() {
        val converter = converter()
        for (input in listOf("", " ", "'''", "gongxi", "123", "恭喜!", "😀", "notpinyin")) {
            assertNull(input, converter.convert(input))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidData() {
        EmojiPinyinConverter.load(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)))
    }
}
