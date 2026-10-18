/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.content.Context
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager

internal object EmojiSearchRepository {
    @Volatile private var cached: EmojiSearchIndex? = null

    /** Call on Dispatchers.IO. Custom aliases are reloaded once per search session. */
    fun load(context: Context): EmojiSearchIndex {
        val base = cached ?: synchronized(this) {
            cached ?: context.assets.open("emoji/search.tsv").bufferedReader().use {
                EmojiSearchIndex.fromLines(it.lineSequence())
            }.also { cached = it }
        }
        return if (PinyinDictManager.isEmojiDictionaryCustomized()) {
            base.withCustomAliases(PinyinDictManager.loadEmojiDictionaryData().map { it.emoji to it.code })
        } else base
    }
}
