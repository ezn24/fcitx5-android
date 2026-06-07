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

data class EnglishCustomPhrasePrediction(
    val phrase: String,
    val score: Int = DefaultScore
) {
    fun serialize(): String = listOf(
        phrase,
        score.coerceAtLeast(1).toString()
    ).joinToString("\t")

    companion object {
        const val DefaultScore = 5

        fun parse(line: String): EnglishCustomPhrasePrediction? {
            val parts = line.split('\t', limit = 2)
            val phrase = EnglishWordManager.normalizePredictionPhrase(parts[0])
            if (phrase.isEmpty()) return null
            val score = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: DefaultScore
            return EnglishCustomPhrasePrediction(phrase, score)
        }
    }
}

data class EnglishPhrasePredictionWeightInfo(
    val prefix: String,
    val candidates: List<Pair<String, Int>>
) {
    val highestScore: Int get() = candidates.firstOrNull()?.second ?: 0
    val visibleThresholdScore: Int get() =
        candidates.getOrNull(EnglishWordManager.DefaultPhrasePredictionSize - 1)?.second ?: 0
}

object EnglishWordManager {

    const val DefaultPhrasePredictionSize = 10

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
    private val phrasePredictionsFile = File(dataDir, "phrase_predictions.txt")
    private val learnedPhrasePredictionsFile = File(dataDir, "learned_phrase_predictions.txt")

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

    fun loadCustomPhrasePredictions(): List<EnglishCustomPhrasePrediction> {
        if (!customPhrasePredictionsFile.exists()) return emptyList()
        return customPhrasePredictionsFile.readLines()
            .mapNotNull { EnglishCustomPhrasePrediction.parse(it) }
            .distinctBy { it.phrase }
    }

    fun saveCustomPhrasePredictions(entries: List<EnglishCustomPhrasePrediction>) {
        dataDir.mkdirs()
        customPhrasePredictionsFile.writeText(
            entries.asSequence()
                .mapNotNull {
                    val phrase = normalizePredictionPhrase(it.phrase)
                    if (phrase.isEmpty()) {
                        null
                    } else {
                        it.copy(phrase = phrase, score = it.score.coerceAtLeast(1)).serialize()
                    }
                }
                .distinctBy { it.substringBefore('\t') }
                .joinToString("\n")
        )
    }

    fun phrasePredictionWeightInfo(rawPhrase: String): EnglishPhrasePredictionWeightInfo? {
        val phrase = normalizePredictionPhrase(rawPhrase)
        if (phrase.isEmpty()) return null
        val words = phrase.split(' ')
        if (words.size < 2) return null
        val prefixWords = words.dropLast(1).takeLast(MaxPrefixWords)
        val prefix = prefixWords.joinToString(" ")
        val scores = linkedMapOf<String, Int>()

        fun add(prefixCandidate: String, next: String, score: Int) {
            if (prefixCandidate != prefix || next.isEmpty()) return
            scores[next] = (scores[next] ?: 0) + score.coerceAtLeast(1)
        }

        fun addPhrase(phraseWords: List<String>, score: Int) {
            if (phraseWords.size !in 2..12) return
            for (begin in 0 until phraseWords.lastIndex) {
                val maxLen = minOf(MaxPrefixWords, phraseWords.size - begin - 1)
                for (len in 1..maxLen) {
                    add(
                        phraseWords.subList(begin, begin + len).joinToString(" "),
                        phraseWords[begin + len],
                        score
                    )
                }
            }
        }

        fun readLines(lines: Sequence<String>, baseScore: Int) {
            lines.forEach { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size >= 2 && line.contains('\t')) {
                    val prefixWordsFromLine = predictionWords(parts[0])
                    val nextWords = predictionWords(parts[1])
                    if (prefixWordsFromLine.isNotEmpty() && nextWords.isNotEmpty()) {
                        val score = parts.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: baseScore
                        add(prefixWordsFromLine.joinToString(" "), nextWords.first(), score)
                    } else if (prefixWordsFromLine.isNotEmpty()) {
                        val score = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1)
                        if (score != null) {
                            addPhrase(prefixWordsFromLine, score)
                        }
                    }
                } else {
                    addPhrase(predictionWords(line), baseScore)
                }
            }
        }

        runCatching {
            appContext.assets.open("usr/share/fcitx5/androidkeyboard/phrase_predictions.txt")
                .bufferedReader()
                .useLines { readLines(it, 1) }
        }
        if (phrasePredictionsFile.isFile) {
            phrasePredictionsFile.useLines { readLines(it, EnglishCustomPhrasePrediction.DefaultScore) }
        }
        if (customPhrasePredictionsFile.isFile) {
            customPhrasePredictionsFile.useLines {
                readLines(it, EnglishCustomPhrasePrediction.DefaultScore)
            }
        }
        if (learnedPhrasePredictionsFile.isFile) {
            learnedPhrasePredictionsFile.useLines { readLines(it, 1) }
        }
        listPhraseBooks().forEach { file ->
            file.useLines { readLines(it, EnglishCustomPhrasePrediction.DefaultScore) }
        }

        val candidates = scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(DefaultPhrasePredictionSize)
            .map { it.key to it.value }
        return EnglishPhrasePredictionWeightInfo(prefix, candidates)
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

    private const val MaxPrefixWords = 4

    private fun predictionWords(raw: String): List<String> {
        return raw.substringBefore('#')
            .lowercase()
            .split(Regex("[^a-z'-]+"))
            .map { it.trim('-') }
            .filter { word ->
                word.isNotEmpty() &&
                    word.length <= 32 &&
                    word.all { it.isLetter() || it == '\'' || it == '-' }
            }
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
