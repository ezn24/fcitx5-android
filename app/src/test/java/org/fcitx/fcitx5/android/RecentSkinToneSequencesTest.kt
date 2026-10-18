/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.popup.RecentSkinToneSequences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentSkinToneSequencesTest {
    @Test
    fun recognizesAllUniformGroupSkinTones() {
        for (base in intArrayOf(0x1F46F, 0x1F93C)) {
            for (tone in 0x1F3FB..0x1F3FF) {
                val neutral = String(intArrayOf(base, tone), 0, 2)
                assertTrue(RecentSkinToneSequences.isStandardSequence(neutral))
                for (gender in intArrayOf(0x2640, 0x2642)) {
                    val gendered = String(intArrayOf(base, tone, 0x200D, gender, 0xFE0F), 0, 5)
                    assertTrue(RecentSkinToneSequences.isStandardSequence(gendered))
                }
            }
        }
    }

    @Test
    fun recognizesBalletDancerAndDefaultGroupVariants() {
        val dancer = intArrayOf(0x1F9D1, 0x200D, 0x1FA70)
        assertTrue(RecentSkinToneSequences.isStandardSequence(String(dancer, 0, dancer.size)))
        for (tone in 0x1F3FB..0x1F3FF) {
            val points = intArrayOf(0x1F9D1, tone, 0x200D, 0x1FA70)
            assertTrue(RecentSkinToneSequences.isStandardSequence(String(points, 0, points.size)))
        }
        for (base in intArrayOf(0x1F46F, 0x1F93C)) {
            assertTrue(RecentSkinToneSequences.isStandardSequence(String(intArrayOf(base), 0, 1)))
            for (gender in intArrayOf(0x2640, 0x2642)) {
                val points = intArrayOf(base, 0x200D, gender, 0xFE0F)
                assertTrue(RecentSkinToneSequences.isStandardSequence(String(points, 0, points.size)))
            }
        }
    }

    @Test
    fun doesNotBypassValidationForOtherOrMalformedSequences() {
        val invalid = listOf(
            intArrayOf(),
            intArrayOf(0x1F46F, 0x1F3FA),
            intArrayOf(0x1F46F, 0x1F400),
            intArrayOf(0x1F46F, 0x1F3FB, 0x1F3FC),
            intArrayOf(0x1F46F, 0x1F3FB, 0x200D, 0x2640),
            intArrayOf(0x1F46F, 0x1F3FB, 0x200D, 0x1F525, 0xFE0F),
            intArrayOf(0x1F469, 0x1F3FB),
            intArrayOf(0x1F9D1, 0x1F3FB, 0x200D, 0x1F525),
            intArrayOf(0x1F9D1, 0x1F3FB, 0x200D, 0x1FA70, 0xFE0F),
            intArrayOf(0x1F93C, 0x1F3FB, 0x200D, 0x2642, 0xFE0F, 0x200D),
        )
        invalid.forEach { points ->
            assertFalse(RecentSkinToneSequences.isStandardSequence(String(points, 0, points.size)))
        }
    }
}
