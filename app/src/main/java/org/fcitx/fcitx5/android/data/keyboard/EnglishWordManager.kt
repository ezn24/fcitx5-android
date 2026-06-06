/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.keyboard

import android.os.Build
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.io.InputStream

data class EnglishCustomPhrase(
    val key: String,
    val order: Int,
    val phrase: String,
    val enabled: Boolean = true
) {
    fun serialize(): String = listOf(
        if (enabled) "1" else "0",
        key,
        order.toString(),
        phrase
    ).joinToString("\t")

    fun copyEnabled(value: Boolean): EnglishCustomPhrase = copy(enabled = value)

    companion object {
        fun parse(line: String): EnglishCustomPhrase? {
            val parts = line.split('\t', limit = 4)
            if (parts.size < 4) return null
            val enabled = parts[0] != "0"
            val key = EnglishWordManager.normalizeKey(parts[1])
            if (key.isEmpty()) return null
            val order = parts[2].toIntOrNull() ?: 1
            val phrase = EnglishWordManager.normalizePhrase(parts[3])
            if (phrase.isEmpty()) return null
            return EnglishCustomPhrase(key, order, phrase, enabled)
        }
    }
}

object EnglishWordManager {

    private val dataDir = File(
        appContext.getExternalFilesDir(null)!!,
        "data/androidkeyboard"
    ).also {
        migrateLegacyData(it)
        it.mkdirs()
    }

    private val dictionariesDir = File(dataDir, "dictionaries").also { it.mkdirs() }
    private val phraseBooksDir = File(dataDir, "phrasebooks").also { it.mkdirs() }

    private val userWordsFile = File(dataDir, "user_words.txt")
    private val customPhrasesFile = File(dataDir, "custom_phrases.txt")
    private val customPhrasePredictionsFile = File(dataDir, "custom_phrase_predictions.txt")

    fun loadUserWords(): List<String> {
        if (!userWordsFile.exists()) return emptyList()
        return userWordsFile.readLines()
            .map { normalizeWord(it) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun saveUserWords(words: List<String>) {
        dataDir.mkdirs()
        userWordsFile.writeText(
            words.map { normalizeWord(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString("\n")
        )
    }

    fun loadCustomPhrases(): List<EnglishCustomPhrase> {
        if (!customPhrasesFile.exists()) return emptyList()
        return customPhrasesFile.readLines()
            .mapNotNull { EnglishCustomPhrase.parse(it) }
    }

    fun saveCustomPhrases(entries: List<EnglishCustomPhrase>) {
        dataDir.mkdirs()
        customPhrasesFile.writeText(
            entries.asSequence()
                .mapNotNull {
                    val key = normalizeKey(it.key)
                    val phrase = normalizePhrase(it.phrase)
                    if (key.isEmpty() || phrase.isEmpty()) {
                        null
                    } else {
                        it.copy(key = key, phrase = phrase).serialize()
                    }
                }
                .joinToString("\n")
        )
    }

    fun loadCustomPhrasePredictions(): List<String> {
        if (!customPhrasePredictionsFile.exists()) return emptyList()
        return customPhrasePredictionsFile.readLines()
            .map { normalizePredictionPhrase(it) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun saveCustomPhrasePredictions(entries: List<String>) {
        dataDir.mkdirs()
        customPhrasePredictionsFile.writeText(
            entries.asSequence()
                .map { normalizePredictionPhrase(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString("\n")
        )
    }

    fun importWords(stream: InputStream, fileName: String): Int {
        val imported = stream.bufferedReader().useLines { lines ->
            lines.map { normalizeWord(it) }
                .filter { it.isNotEmpty() }
                .toList()
        }
        if (imported.isEmpty()) return 0
        val target = File(dictionariesDir, dictionaryFileName(fileName).ifBlank { "dictionary.txt" })
        target.writeText(imported.distinct().joinToString("\n"))
        return imported.size
    }

    fun listDictionaries(): List<File> {
        return dictionariesDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    fun deleteDictionary(file: File): Boolean {
        if (file.parentFile?.canonicalFile != dictionariesDir.canonicalFile) return false
        return file.isFile && file.extension.equals("txt", ignoreCase = true) && file.delete()
    }

    fun importPhraseBook(stream: InputStream, fileName: String): Int {
        val imported = stream.bufferedReader().useLines { lines ->
            lines.map { normalizePredictionPhrase(it) }
                .filter { it.isNotEmpty() }
                .toList()
        }
        if (imported.isEmpty()) return 0
        val target = File(phraseBooksDir, dictionaryFileName(fileName).ifBlank { "phrasebook.txt" })
        target.writeText(imported.distinct().joinToString("\n"))
        return imported.size
    }

    fun listPhraseBooks(): List<File> {
        return phraseBooksDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    fun deletePhraseBook(file: File): Boolean {
        if (file.parentFile?.canonicalFile != phraseBooksDir.canonicalFile) return false
        return file.isFile && file.extension.equals("txt", ignoreCase = true) && file.delete()
    }

    private fun normalizeWord(raw: String): String {
        return raw.substringBefore('#')
            .substringBefore('\t')
            .trim()
            .trim { it == ',' || it == ';' }
            .takeIf { it.length <= 64 }
            ?.takeIf { word -> word.all { it.isLetter() || it == '\'' || it == '-' } }
            .orEmpty()
    }

    fun normalizeKey(raw: String): String {
        return raw.trim()
            .lowercase()
            .takeIf { it.length <= 32 }
            ?.takeIf { key -> key.all { it.isLetter() || it == '\'' || it == '-' } }
            .orEmpty()
    }

    fun normalizePhrase(raw: String): String {
        return raw.replace('\t', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .takeIf { it.length <= 128 }
            .orEmpty()
    }

    fun normalizePredictionPhrase(raw: String): String {
        val sentence = raw.substringBefore('#')
            .replace('\t', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .lowercase()
        val words = sentence.split(Regex("\\s+"))
            .map { it.trim { ch -> ch == ',' || ch == ';' || ch == ':' || ch == '.' || ch == '!' || ch == '?' || ch == '"' } }
            .filter { it.isNotEmpty() }
        if (words.size !in 2..12) return ""
        if (words.any { word -> word.length > 32 || word.any { !it.isLetter() && it != '\'' && it != '-' } }) {
            return ""
        }
        return words.joinToString(" ")
    }

    fun dictionaryFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .ifBlank { "dictionary.txt" }
        return if (base.endsWith(".txt", ignoreCase = true)) base else "$base.txt"
    }

    private fun migrateLegacyData(newDir: File) {
        val legacyBase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appContext.createDeviceProtectedStorageContext().dataDir
        } else {
            File(appContext.applicationInfo.dataDir)
        }
        val legacyDir = File(legacyBase, "androidkeyboard")
        if (!legacyDir.exists() || legacyDir.canonicalFile == newDir.canonicalFile) return

        fun migrateFile(name: String) {
            val src = File(legacyDir, name)
            val dst = File(newDir, name)
            if (src.isFile && !dst.exists()) {
                dst.parentFile?.mkdirs()
                src.copyTo(dst)
            }
        }

        migrateFile("user_words.txt")
        migrateFile("custom_phrases.txt")
        migrateFile("custom_phrase_predictions.txt")
        val legacyDictionaries = File(legacyDir, "dictionaries")
        val newDictionaries = File(newDir, "dictionaries")
        legacyDictionaries.listFiles()
            ?.filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
            ?.forEach { src ->
                val dst = File(newDictionaries, src.name)
                if (!dst.exists()) {
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst)
                }
            }
        val legacyPhraseBooks = File(legacyDir, "phrasebooks")
        val newPhraseBooks = File(newDir, "phrasebooks")
        legacyPhraseBooks.listFiles()
            ?.filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
            ?.forEach { src ->
                val dst = File(newPhraseBooks, src.name)
                if (!dst.exists()) {
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst)
                }
            }
    }
}
