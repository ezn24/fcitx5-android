/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

data class PinyinEmojiDictionaryEntry(
    val emoji: String,
    val code: String,
    val weight: String
) {
    fun serialize(): String = buildString {
        append(emoji)
        append('\t')
        append(code)
        if (weight.isNotBlank()) {
            append('\t')
            append(weight)
        }
    }

    companion object {
        fun fromLine(line: String): PinyinEmojiDictionaryEntry? {
            if (line.isBlank() || line.startsWith("#")) return null
            val fields = line.split('\t')
            if (fields.size < 2) return null
            val emoji = fields[0].trim()
            val code = fields[1].trim()
            if (emoji.isBlank() || code.isBlank()) return null
            return PinyinEmojiDictionaryEntry(
                emoji = emoji,
                code = code,
                weight = fields.getOrNull(2)?.trim().orEmpty()
            )
        }
    }
}

class PinyinEmojiDictionaryData(
    private val data: List<PinyinEmojiDictionaryEntry>
) : List<PinyinEmojiDictionaryEntry> by data {

    fun serialize(): String = joinToString("\n") { it.serialize() }

    companion object {
        fun fromLines(lines: List<String>): PinyinEmojiDictionaryData {
            return PinyinEmojiDictionaryData(lines.mapNotNull { PinyinEmojiDictionaryEntry.fromLine(it) })
        }
    }
}
