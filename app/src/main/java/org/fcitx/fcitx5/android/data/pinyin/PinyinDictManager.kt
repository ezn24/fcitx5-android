/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorArg
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream

object PinyinDictManager {

    private val pinyinDicDir = File(
        appContext.getExternalFilesDir(null)!!, "data/pinyin/dictionaries"
    ).also { it.mkdirs() }

    private val builtinPinyinDictDir = File(
        DataManager.dataDir, "usr/share/fcitx5/pinyin/dictionaries"
    )

    private val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)

    private val scel2org5 by lazy { File(nativeDir, scel2org5Name) }

    private val bundledPinyinDictDir = File(
        DataManager.dataDir, "usr/share/fcitx5/pinyin/dictionaries"
    )

    private val pinyinSymbolsFile = File(
        DataManager.dataDir, "usr/share/fcitx5/pinyin/symbols"
    )

    private val managedDictionarySpecs = listOf(
        ManagedDictionarySpec(
            sourceName = "fcitx5-android-cantonese-compat.txt",
            dictionaryName = "fcitx5-android-cantonese-compat",
            markerName = "fcitx5-android-cantonese-compat.version"
        ),
        ManagedDictionarySpec(
            sourceName = "fcitx5-android-emoji.txt",
            dictionaryName = "fcitx5-android-emoji",
            markerName = "fcitx5-android-emoji.version",
            transformLine = ::applyPreferredEmojiTone
        )
    )

    fun listDictionaries(): List<PinyinDictionary> {
        val builtin = mutableListOf<PinyinDictionary>()
        builtinPinyinDictDir.listFiles()?.forEach {
            if (it.extension == PinyinDictionary.Type.LibIME.ext) {
                builtin.add(BuiltinDictionary(it))
            }
        }
        builtin.sortBy { it.name }
        val user = mutableListOf<PinyinDictionary>()
        pinyinDicDir.listFiles()?.forEach {
            PinyinDictionary.new(it)?.let { dict ->
                if (dict is LibIMEDictionary) {
                    user.add(dict)
                }
            }
        }
        user.sortBy { it.name }
        return builtin + user
    }

    fun importFromFile(file: File): Result<LibIMEDictionary> = runCatching {
        val raw =
            PinyinDictionary.new(file) ?: errorArg(R.string.exception_dict_filename, file.path)
        // convert to libime format in dictionaries dir
        // preserve original file name
        val new = raw.toLibIMEDictionary(
            File(
                pinyinDicDir,
                file.nameWithoutExtension + ".${PinyinDictionary.Type.LibIME.ext}"
            )
        )
        Timber.d("Converted $raw to $new")
        new
    }

    fun importFromInputStream(stream: InputStream, name: String): Result<LibIMEDictionary> {
        val tempFile = File(appContext.cacheDir, name)
        tempFile.outputStream().use {
            stream.copyTo(it)
        }
        val new = importFromFile(tempFile)
        tempFile.delete()
        return new
    }

    fun syncManagedData(enableCantonese: Boolean): SyncResult {
        val enabled = mapOf(
            "fcitx5-android-cantonese-compat" to enableCantonese,
            "fcitx5-android-emoji" to true
        )
        val dictionaryChanged = managedDictionarySpecs.map { spec ->
            syncManagedDictionary(spec, enabled.getValue(spec.dictionaryName))
        }.any { it }
        return SyncResult(
            dictionaryChanged = dictionaryChanged,
            symbolsChanged = syncManagedSymbols()
        )
    }

    fun syncManagedDictionaries(enableCantonese: Boolean): Boolean {
        return syncManagedData(enableCantonese).anyChanged
    }

    private fun syncManagedDictionary(spec: ManagedDictionarySpec, enabled: Boolean): Boolean {
        val active = File(pinyinDicDir, "${spec.dictionaryName}.${PinyinDictionary.Type.LibIME.ext}")
        val disabled = File(active.path + ".${LibIMEDictionary.DISABLE}")
        if (!enabled) {
            return active.exists() && active.renameTo(disabled)
        }

        var changed = false
        if (disabled.exists() && !active.exists()) {
            disabled.renameTo(active)
            changed = true
        }

        val source = File(bundledPinyinDictDir, spec.sourceName)
        if (!source.exists()) {
            Timber.w("Managed dictionary source does not exist: $source")
            return false
        }
        val marker = File(pinyinDicDir, spec.markerName)
        val version = listOf(
            source.length(),
            source.lastModified(),
            spec.transformVersion()
        ).joinToString(":")
        if (active.exists() && marker.takeIf { it.exists() }?.readText() == version) {
            return changed
        }

        val textSource = if (spec.transformLine == null) {
            source
        } else {
            File(appContext.cacheDir, spec.sourceName).also { temp ->
                temp.bufferedWriter().use { writer ->
                    source.forEachLine { line ->
                        writer.appendLine(spec.transformLine.invoke(line))
                    }
                }
            }
        }

        return try {
            pinyinDictConv(
                textSource.absolutePath,
                active.absolutePath,
                MODE_TXT_TO_BIN
            )
            marker.writeText(version)
            true
        } finally {
            if (textSource != source) {
                textSource.delete()
            }
        }
    }

    private data class ManagedDictionarySpec(
        val sourceName: String,
        val dictionaryName: String,
        val markerName: String,
        val transformLine: ((String) -> String)? = null
    ) {
        fun transformVersion(): String =
            if (transformLine == null) "" else EmojiModifier.defaultSkinToneVersion()
    }

    private fun applyPreferredEmojiTone(line: String): String {
        if (line.isBlank() || line.startsWith("#")) return line
        val fields = line.split('\t', limit = 2)
        if (fields.size < 2) return line
        return EmojiModifier.getPreferredTone(fields[0]) + "\t" + fields[1]
    }

    private fun syncManagedSymbols(): Boolean {
        if (!pinyinSymbolsFile.exists()) {
            Timber.w("Pinyin symbols source does not exist: $pinyinSymbolsFile")
            return false
        }
        val original = pinyinSymbolsFile.readText()
        val transformed = original.lineSequence()
            .joinToString(System.lineSeparator()) { line ->
                applyPreferredToneToSymbolLine(line)
            }
        if (original == transformed) {
            return false
        }
        pinyinSymbolsFile.writeText(transformed)
        return true
    }

    private fun applyPreferredToneToSymbolLine(line: String): String {
        if (line.isBlank() || line.startsWith("#")) return line
        val separatorStart = line.indexOfFirst { it.isWhitespace() }
        if (separatorStart < 0) return line
        val separatorEnd = line.indexOfFirst(separatorStart) { !it.isWhitespace() }
        if (separatorEnd < 0) return line
        val prefix = line.substring(0, separatorEnd)
        val value = line.substring(separatorEnd)
        return prefix + EmojiModifier.getPreferredTone(value)
    }

    private inline fun String.indexOfFirst(startIndex: Int, predicate: (Char) -> Boolean): Int {
        for (i in startIndex until length) {
            if (predicate(this[i])) return i
        }
        return -1
    }

    data class SyncResult(
        val dictionaryChanged: Boolean,
        val symbolsChanged: Boolean
    ) {
        val anyChanged: Boolean
            get() = dictionaryChanged || symbolsChanged
    }

    fun sougouDictConv(src: String, dest: String) {
        val process = Runtime.getRuntime()
            .exec(
                arrayOf(scel2org5.absolutePath, "-o", dest, src),
                arrayOf("LD_LIBRARY_PATH=${nativeDir.absolutePath}")
            )
        process.waitFor()
        if (process.exitValue() != 0) {
            throw IOException(process.errorStream.bufferedReader().readText())
        }
    }

    @JvmStatic
    external fun pinyinDictConv(src: String, dest: String, mode: Boolean)

    const val MODE_BIN_TO_TXT = true
    const val MODE_TXT_TO_BIN = false
    private const val scel2org5Name = "libscel2org5.so"

}
