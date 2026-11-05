/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.keyboard

import android.util.AtomicFile
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

internal object EnglishEmojiDictionary {
    private const val fileName = "emoji_keywords.tsv"
    private val customFile = AtomicFile(File(appContext.getExternalFilesDir(null)!!, "data/androidkeyboard/$fileName"))
    private val keywordPattern = Regex("[a-z][a-z'-]{1,63}")

    data class Entry(val keyword: String, val emoji: String)

    fun normalizeKeyword(input: String): String? =
        input.trim().lowercase(Locale.ROOT).takeIf {
            keywordPattern.matches(it) && it.count { ch -> ch in 'a'..'z' } >= 2
        }

    fun load(): List<Entry> {
        val stream = try {
            customFile.openRead()
        } catch (_: FileNotFoundException) {
            appContext.assets.open("usr/share/fcitx5/androidkeyboard/$fileName")
        }
        return stream.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size != 2 || parts[1].isBlank()) null
                else normalizeKeyword(parts[0])?.let { Entry(it, parts[1]) }
            }.distinct().toList()
        }
    }

    fun save(entries: List<Entry>) {
        val output = customFile.startWrite()
        try {
            val writer = output.bufferedWriter()
            entries.distinct().forEach { writer.append(it.keyword).append('\t').append(it.emoji).append('\n') }
            writer.flush()
            customFile.finishWrite(output)
        } catch (e: Exception) {
            customFile.failWrite(output)
            throw e
        }
    }

    fun reset() {
        customFile.delete()
        check(!customFile.baseFile.exists()) { "Could not remove the custom emoji dictionary" }
    }
}
