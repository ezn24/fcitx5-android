/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.popup

import android.annotation.SuppressLint
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.UnicodeSet
import android.os.Build
import android.text.TextPaint
import androidx.annotation.RequiresApi
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

object EmojiModifier {

    enum class SkinTone(val value: String, override val stringRes: Int) : ManagedPreferenceEnum {
        Default("", R.string.emoji_skin_tone_none),
        Type_1_2("🏻", R.string.emoji_skin_tone_type_1_2),
        Type_3("🏼", R.string.emoji_skin_tone_type_3),
        Type_4("🏽", R.string.emoji_skin_tone_type_4),
        Type_5("🏾", R.string.emoji_skin_tone_type_5),
        Type_6("🏿", R.string.emoji_skin_tone_type_6)
    }

    /**
     * Drop `U+FE0F` (Variation Selector-16) when combining with skin tone if
     * the base has no default emoji presentation.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun shouldSkipVariationSelector16(ch: Int): Boolean {
        return UCharacter.hasBinaryProperty(ch, UProperty.EMOJI_MODIFIER_BASE) &&
            !UCharacter.hasBinaryProperty(ch, UProperty.EMOJI_PRESENTATION)
    }

    private const val VariationSelector16 = 0xFE0F
    private const val ZeroWidthJoiner = 0x200D
    private const val MaleSign = 0x2642
    private const val FemaleSign = 0x2640
    private val SkinToneModifiers = 0x1F3FB..0x1F3FF

    private val PersonGenderGroups = listOf(
        intArrayOf(0x1F9D1, 0x1F468, 0x1F469),
        intArrayOf(0x1F9D2, 0x1F466, 0x1F467),
        intArrayOf(0x1F9D3, 0x1F474, 0x1F475),
    )

    private val GenderSignBases = intArrayOf(
        0x1F3C3, // runner
        0x1F6B6, // walking
        0x1F9CD, // standing
        0x1F9CE, // kneeling
        0x1F9D6, // person in steamy room
        0x1F9D7, // climbing
        0x1F3CA, // swimming
        0x1F3C4, // surfing
        0x1F6A3, // rowing boat
        0x1F6B4, // biking
        0x1F6B5, // mountain biking
        0x1F3CB, // lifting weights
        0x1F93C, // wrestling
        0x1F938, // cartwheel
        0x26F9,  // bouncing ball
        0x1F93E, // handball
        0x1F93D, // water polo
        0x1F574, // levitating
        0x1F575, // detective
        0x1F477, // construction worker
        0x1F482, // guard
        0x1F473, // turban
    )

    /**
     * Make `U+1F91D` (🤝 Handshake) in 🧑‍🤝‍🧑 not modifiable.
     */
    private val HandshakeBetweenPeople = intArrayOf(
        0x1F9D1, 0x200D, 0x1F91D, 0x200D, 0x1F9D1,
    )

    private val defaultSkinTone by AppPrefs.getInstance().symbols.defaultEmojiSkinTone

    fun isSupported(): Boolean {
        // UProperty.EMOJI_MODIFIER_BASE requires API 28
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    }

    private fun isModifiable(modifiable: BooleanArray): Boolean {
        val count = modifiable.count { it }
        return count == 1 || count == 2
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun getCodePoints(emoji: String): Pair<IntArray, BooleanArray> {
        val codePoints = emoji.codePoints().toArray()
        val modifiable = BooleanArray(codePoints.size) {
            UCharacter.hasBinaryProperty(codePoints[it], UProperty.EMOJI_MODIFIER_BASE)
        }
        if (codePoints contentEquals HandshakeBetweenPeople) {
            modifiable[2] = false
        }
        return codePoints to modifiable
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun buildEmoji(codePoints: IntArray, modifiable: BooleanArray, tone: SkinTone): String {
        return buildString {
            var i = 0
            while (i < codePoints.size) {
                appendCodePoint(codePoints[i])
                if (modifiable[i]) {
                    append(tone.value)
                    if (tone != SkinTone.Default &&
                        codePoints.getOrNull(i + 1) == VariationSelector16 &&
                        shouldSkipVariationSelector16(codePoints[i])
                    ) {
                        i++
                    }
                }
                i++
            }
        }
    }

    private val DefaultTextPaint by lazy {
        TextPaint()
    }

    private val RGIEmojiSet by lazy {
        @SuppressLint("NewApi")
        UnicodeSet("[:RGI_Emoji:]").freeze()
    }

    fun isValidEmoji(emoji: String): Boolean {
        // UProperty.RGI_EMOJI is available on 34+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (!RGIEmojiSet.contains(emoji)) return false
        }
        return DefaultTextPaint.hasGlyph(emoji)
    }

    private fun buildEmoji(codePoints: IntArray): String {
        return buildString {
            codePoints.forEach { appendCodePoint(it) }
        }
    }

    fun removeSkinToneModifiers(emoji: String): String {
        return buildString {
            emoji.codePoints().forEach { codePoint ->
                if (codePoint !in SkinToneModifiers) {
                    appendCodePoint(codePoint)
                }
            }
        }
    }

    fun getPreferredTone(emoji: String): String {
        return getPreferredTone(emoji, defaultSkinTone)
    }

    fun getPreferredTone(emoji: String, tone: SkinTone): String {
        if (!isSupported()) return emoji
        val baseEmoji = removeSkinToneModifiers(emoji)
        val (codePoints, modifiable) = getCodePoints(baseEmoji)
        if (tone == SkinTone.Default || !isModifiable(modifiable)) return baseEmoji
        val candidate = buildEmoji(codePoints, modifiable, tone)
        return if (isValidEmoji(candidate)) candidate else baseEmoji
    }

    fun defaultSkinToneVersion(): String = defaultSkinTone.name

    private fun skinToneCandidates(emoji: String): List<String>? {
        if (!isSupported()) return null
        val (codePoints, modifiable) = getCodePoints(removeSkinToneModifiers(emoji))
        if (!isModifiable(modifiable)) return null
        return SkinTone.entries
            .map { buildEmoji(codePoints, modifiable, it) }
            .filter { isValidEmoji(it) }
    }

    private fun personGenderVariants(emoji: String): List<String> {
        val codePoints = removeSkinToneModifiers(emoji).codePoints().toArray()
        val variants = mutableListOf(buildEmoji(codePoints))
        PersonGenderGroups.forEach { group ->
            val index = codePoints.indexOfFirst { it in group }
            if (index >= 0) {
                group.forEach { replacement ->
                    val copy = codePoints.copyOf()
                    copy[index] = replacement
                    variants += buildEmoji(copy)
                }
            }
        }
        val first = codePoints.firstOrNull()
        if (first != null && first in GenderSignBases) {
            variants += buildEmoji(codePoints + intArrayOf(ZeroWidthJoiner, MaleSign, VariationSelector16))
            variants += buildEmoji(codePoints + intArrayOf(ZeroWidthJoiner, FemaleSign, VariationSelector16))
        }
        return variants.distinct()
    }

    fun produceSkinTones(emoji: String, excludeTone: SkinTone): Array<String>? {
        if (!isSupported()) return null
        val current = getPreferredTone(emoji, excludeTone)
        val candidates = personGenderVariants(emoji)
            .flatMap { variant -> skinToneCandidates(variant) ?: listOf(variant) }
            .filter { it != current }
            .distinct()
        return if (candidates.isEmpty()) null else candidates.toTypedArray()
    }
}
