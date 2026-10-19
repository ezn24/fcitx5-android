/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.picker.EmojiSearchIndex
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EmojiSearchIndexTest {
    @Test
    fun bundledDataIncludesEnglishAndBothChineseScripts() {
        val data = File("src/main/assets/emoji/search.tsv")
        val bundled = data.bufferedReader().use { EmojiSearchIndex.fromLines(it.lineSequence()) }
        for (query in listOf("cat", "smile", "firefighter", "woman firefighter", "消防員", "消防员")) {
            assertTrue(query, bundled.search(query).isNotEmpty())
        }
    }

    private fun index() = EmojiSearchIndex.fromLines(sequenceOf(
        "😺\t笑臉貓|笑脸猫|grinning cat\t笑|貓|猫|cat|smile\txiao'lian'mao\tmao'mi",
        "🐱\t貓臉|猫脸|cat face\t貓|猫|cat\tmao'lian\tmao",
        "👩‍🚒\t女消防員|女消防员|woman firefighter\t消防|消防員|消防员|firefighter|woman\tnv'xiao'fang'yuan\txiao'fang'yuan",
        "😀\t笑臉|笑脸|grinning face\t笑|開心|开心|happy|smile\txiao'lian\tkai'xin",
    ))

    @Test
    fun bundledSearchIncludesImportedDictionaryAliases() {
        val bundled = File("src/main/assets/emoji/search.tsv").bufferedReader().use {
            EmojiSearchIndex.fromLines(it.lineSequence())
        }
        assertTrue(bundled.search("gong'xi", 100).contains("🎉"))
        assertTrue(bundled.search("shou'dao", 100).contains("🫡"))
    }

    @Test
    fun searchesBothChineseScriptsEnglishAndPinyin() {
        for (query in listOf("消防員", "消防员", "FIREFIGHTER", "ｆｉｒｅｆｉｇｈｔｅｒ", "xiao fang yuan", "xiao'fang'yuan", "xiaofangyuan")) {
            assertEquals(query, listOf("👩‍🚒"), index().search(query))
        }
        assertEquals(listOf("👩‍🚒"), index().search("fire woman"))
        assertEquals(listOf("😀"), index().search("happy"))
        assertEquals(listOf("😺", "🐱"), index().search("cat"))
    }

    @Test
    fun ranksExactNameAheadOfPartialMatchesAndKeepsStableTies() {
        assertEquals("😀", index().search("笑臉").first())
        assertEquals(listOf("😺", "😀"), index().search("smile"))
        assertEquals(listOf("😺"), index().search("smile", 1))
        assertTrue(index().search("  ").isEmpty())
        assertTrue(index().search("unknown word").isEmpty())
    }

    @Test
    fun customAliasesReplaceLegacyButDoNotModifyTheBundledIndex() {
        val original = index()
        val customized = original.withCustomAliases(listOf("🐱" to "miao", "🐱" to "miao", "🥳" to "partytime"))
        assertEquals(listOf("🐱"), customized.search("miao"))
        assertEquals(listOf("🥳"), customized.search("partytime"))
        assertTrue(customized.search("maomi").isEmpty())
        assertEquals(listOf("😺"), original.search("maomi"))
        assertEquals(listOf("😺", "🐱"), customized.search("cat"))
        assertEquals(listOf("👩‍🚒"), customized.search("nvxiaofangyuan"))
    }

    @Test
    fun joinsCustomToneAliasesToTheCanonicalEmojiWithoutDuplicates() {
        val customized = index().withCustomAliases(listOf("👩🏽‍🚒" to "hero", "👩‍🚒" to "hero"))
        assertEquals(listOf("👩‍🚒"), customized.search("hero"))
        assertEquals(listOf("👩‍🚒"), customized.search("👩🏽‍🚒"))
    }
}
