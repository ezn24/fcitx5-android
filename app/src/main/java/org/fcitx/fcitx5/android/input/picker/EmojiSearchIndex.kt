/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import java.text.Normalizer
import java.util.Locale

/** Immutable, normalized search data; constructed once, not on each keystroke. */
class EmojiSearchIndex private constructor(private val entries: List<Entry>) {
    private data class Entry(
        val emoji: String,
        val names: List<String>,
        val keywords: List<String>,
        val pinyin: List<String>,
        val aliases: List<String>,
        val key: String = emojiKey(emoji)
    )

    fun withCustomAliases(aliases: Iterable<Pair<String, String>>): EmojiSearchIndex {
        // A customized dictionary replaces legacy aliases, including deletions.
        val merged = entries.associateByTo(linkedMapOf(), { emojiKey(it.emoji) }) { it.copy(aliases = emptyList()) }
        aliases.forEach { (emoji, code) ->
            val alias = normalizePinyin(code)
            if (emoji.isBlank() || alias.isBlank()) return@forEach
            val key = emojiKey(emoji)
            val current = merged[key] ?: Entry(emoji, emptyList(), emptyList(), emptyList(), emptyList())
            merged[key] = current.copy(aliases = (current.aliases + alias).distinct())
        }
        return EmojiSearchIndex(merged.values.toList())
    }

    fun search(query: String, limit: Int = 120): List<String> {
        val text = normalize(query)
        if (text.isBlank() || limit <= 0) return emptyList()
        val pinyinQuery = normalizePinyin(text)
        val isPinyin = text.all { it in 'a'..'z' || it == '\'' || it == ' ' || it == '-' }
        val words = text.split(' ').filter(String::isNotEmpty)
        val queryKey = emojiKey(query.trim())
        return entries.mapIndexedNotNull { index, entry ->
            val score = when {
                queryKey == entry.key -> 0
                text in entry.names -> 0
                text in entry.keywords -> 10
                isPinyin && pinyinQuery in entry.aliases -> 12
                isPinyin && pinyinQuery in entry.pinyin -> 15
                entry.names.any { it.startsWith(text) } -> 20
                entry.keywords.any { it.startsWith(text) } -> 30
                isPinyin && (entry.aliases.any { it.startsWith(pinyinQuery) } ||
                    entry.pinyin.any { it.startsWith(pinyinQuery) }) -> 35
                entry.names.any { text in it } -> 40
                entry.keywords.any { text in it } -> 50
                words.size > 1 && words.all { word ->
                    entry.names.any { word in it } || entry.keywords.any { word in it }
                } -> 60
                else -> return@mapIndexedNotNull null
            }
            Triple(score, index, entry.emoji)
        }.sortedWith(compareBy({ it.first }, { it.second })).take(limit).map { it.third }
    }

    companion object {
        private val combiningMarks = Regex("\\p{M}+")
        private val whitespace = Regex("\\s+")
        internal fun normalize(text: String): String = Normalizer.normalize(
            Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('ü', 'v'),
            Normalizer.Form.NFD
        ).replace(combiningMarks, "").trim().replace(whitespace, " ")

        private fun normalizePinyin(text: String) = normalize(text).replace("'", "").replace(" ", "").replace("-", "")

        private fun emojiKey(text: String): String = buildString {
            text.codePoints().forEach { point ->
                if (point != 0xFE0F && point !in 0x1F3FB..0x1F3FF) appendCodePoint(point)
            }
        }

        fun fromLines(lines: Sequence<String>): EmojiSearchIndex {
            val entries = linkedMapOf<String, Entry>()
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                val fields = line.split('\t')
                require(fields.size == 5) { "Invalid emoji search row" }
                fun terms(index: Int) = fields[index].split('|').map(::normalize).filter(String::isNotBlank).distinct()
                fun pinyin(index: Int) = fields[index].split('|').map(::normalizePinyin).filter(String::isNotBlank).distinct()
                val entry = Entry(fields[0], terms(1), terms(2), pinyin(3), pinyin(4))
                entries.putIfAbsent(emojiKey(entry.emoji), entry)
            }
            return EmojiSearchIndex(entries.values.toList())
        }
    }
}
