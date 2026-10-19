/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import java.io.DataInputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale

/** Compact lookup data, used by the dictionary editor only. */
class EmojiPinyinConverter private constructor(
    private val points: IntArray,
    private val readings: ShortArray,
    private val syllables: Array<String>
) {
    fun convert(input: String): String? {
        val normalized = Normalizer.normalize(input.trim(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace('ü', 'v').replace('’', '\'')
        if (normalized.isBlank()) return null
        val tokens = mutableListOf<String>()
        val latin = StringBuilder()
        fun flushLatin() {
            if (latin.isNotEmpty()) {
                tokens.add(latin.toString().replace("nue", "nve").replace("lue", "lve"))
                latin.setLength(0)
            }
        }
        var offset = 0
        while (offset < normalized.length) {
            val point = normalized.codePointAt(offset)
            offset += Character.charCount(point)
            when {
                point in 'a'.code..'z'.code -> latin.append(point.toChar())
                point == '\''.code || Character.isWhitespace(point) -> flushLatin()
                else -> {
                    flushLatin()
                    val index = points.binarySearch(point)
                    if (index < 0) return null
                    tokens.add(syllables[readings[index].toInt() and 0xffff])
                }
            }
        }
        flushLatin()
        if (tokens.isEmpty() || tokens.any { syllables.binarySearch(it) < 0 }) return null
        return tokens.joinToString("'")
    }

    companion object {
        fun load(input: InputStream): EmojiPinyinConverter = DataInputStream(input.buffered()).use { data ->
            require(data.readInt() == 0x45505931) { "Invalid Hanzi Pinyin data" }
            val syllables = Array(data.readUnsignedShort()) { data.readUTF() }
            require(syllables.isNotEmpty() && syllables.size <= 1024)
            val count = data.readInt()
            require(count in 1..100000)
            val points = IntArray(count)
            val readings = ShortArray(count)
            repeat(count) { index ->
                points[index] = data.readInt()
                val reading = data.readUnsignedShort()
                require(reading < syllables.size && (index == 0 || points[index] > points[index - 1]))
                readings[index] = reading.toShort()
            }
            EmojiPinyinConverter(points, readings, syllables)
        }
    }
}
