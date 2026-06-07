/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.media.AudioManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.audioManager
import org.fcitx.fcitx5.android.utils.getSystemSettings
import org.fcitx.fcitx5.android.utils.vibrator
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object InputFeedbacks {

    enum class InputFeedbackMode(override val stringRes: Int) : ManagedPreferenceEnum {
        FollowingSystem(R.string.following_system_settings),
        Enabled(R.string.enabled),
        Disabled(R.string.disabled);
    }

    private var systemSoundEffects = false
    private var systemHapticFeedback = false

    fun syncSystemPrefs() {
        systemSoundEffects = getSystemSettings<Int>(Settings.System.SOUND_EFFECTS_ENABLED) == 1
        // it says "Replaced by using android.os.VibrationAttributes.USAGE_TOUCH"
        // but gives no clue about how to use it, and this one still works
        @Suppress("DEPRECATION")
        systemHapticFeedback = getSystemSettings<Int>(Settings.System.HAPTIC_FEEDBACK_ENABLED) == 1
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val soundOnKeyPress by keyboardPrefs.soundOnKeyPress
    private val soundOnKeyPressVolume by keyboardPrefs.soundOnKeyPressVolume
    private val systemSoundFileMode by keyboardPrefs.systemSoundFileMode
    private val hapticOnKeyPress by keyboardPrefs.hapticOnKeyPress
    private val hapticOnKeyUp by keyboardPrefs.hapticOnKeyUp
    private val buttonPressVibrationMilliseconds by keyboardPrefs.buttonPressVibrationMilliseconds
    private val buttonLongPressVibrationMilliseconds by keyboardPrefs.buttonLongPressVibrationMilliseconds
    private val buttonPressVibrationAmplitude by keyboardPrefs.buttonPressVibrationAmplitude
    private val buttonLongPressVibrationAmplitude by keyboardPrefs.buttonLongPressVibrationAmplitude

    private val vibrator = appContext.vibrator
    private var cachedPressDuration: Long = -1L
    private var cachedPressAmplitude: Int = Int.MIN_VALUE
    private var cachedPressEffect: VibrationEffect? = null
    private var cachedLongPressDuration: Long = -1L
    private var cachedLongPressAmplitude: Int = Int.MIN_VALUE
    private var cachedLongPressEffect: VibrationEffect? = null

    private val hasAmplitudeControl =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) && vibrator.hasAmplitudeControl()

    private fun vibrationEffect(duration: Long, amplitude: Int, longPress: Boolean): VibrationEffect {
        if (longPress) {
            if (
                cachedLongPressEffect == null ||
                cachedLongPressDuration != duration ||
                cachedLongPressAmplitude != amplitude
            ) {
                cachedLongPressEffect = VibrationEffect.createOneShot(duration, amplitude)
                cachedLongPressDuration = duration
                cachedLongPressAmplitude = amplitude
            }
            return cachedLongPressEffect!!
        }
        if (
            cachedPressEffect == null ||
            cachedPressDuration != duration ||
            cachedPressAmplitude != amplitude
        ) {
            cachedPressEffect = VibrationEffect.createOneShot(duration, amplitude)
            cachedPressDuration = duration
            cachedPressAmplitude = amplitude
        }
        return cachedPressEffect!!
    }

    fun hapticFeedback(view: View, longPress: Boolean = false, keyUp: Boolean = false) {
        when (hapticOnKeyPress) {
            InputFeedbackMode.Enabled -> {}
            InputFeedbackMode.Disabled -> return
            InputFeedbackMode.FollowingSystem -> if (!systemHapticFeedback) return
        }
        if (keyUp && !hapticOnKeyUp) return
        val duration: Long
        val amplitude: Int
        val hfc: Int
        if (longPress) {
            duration = buttonLongPressVibrationMilliseconds.toLong()
            amplitude = buttonLongPressVibrationAmplitude
            hfc = HapticFeedbackConstants.LONG_PRESS
        } else {
            duration = buttonPressVibrationMilliseconds.toLong()
            amplitude = buttonPressVibrationAmplitude
            hfc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && keyUp) {
                HapticFeedbackConstants.KEYBOARD_RELEASE
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        }

        // there is `VibrationEffect.DEFAULT_AMPLITUDE` but no default duration;
        // also `VibrationEffect.createOneShot()` only accepts positive duration.
        // so changing amplitude without changing duration makes no sense
        if (duration != 0L) {
            // on Android 13, if system haptic feedback was disabled, `vibrator.vibrate()` won't work
            // but `view.performHapticFeedback()` with `FLAG_IGNORE_GLOBAL_SETTING` still works
            if (hasAmplitudeControl && amplitude != 0) {
                vibrator.vibrate(vibrationEffect(duration, amplitude, longPress))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ve = vibrationEffect(duration, VibrationEffect.DEFAULT_AMPLITUDE, longPress)
                vibrator.vibrate(ve)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } else {
            var flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            if (hapticOnKeyPress == InputFeedbackMode.Enabled) {
                // it says "Starting TIRAMISU only privileged apps can ignore user settings for touch feedback"
                // but we still seem to be able to use `FLAG_IGNORE_GLOBAL_SETTING`
                @Suppress("DEPRECATION")
                flags = flags or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            }
            view.performHapticFeedback(hfc, flags)
        }
    }

    enum class SoundEffect {
        Standard, Modifier, SpaceBar, Delete, Return
    }

    private val audioManager = appContext.audioManager

    private val systemSoundPool by lazy {
        SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
            .apply {
                setOnLoadCompleteListener { _, sampleId, status ->
                    systemSoundLoaded[sampleId] = status == 0
                }
            }
    }

    private val systemSoundLoaded = ConcurrentHashMap<Int, Boolean>()
    private val systemSoundIds = mutableMapOf<SoundEffect, Int>()

    private val systemSoundPaths = mapOf(
        SoundEffect.Standard to listOf(
            "/system/media/audio/ui/TW_SIP.ogg",
            "/system/media/audio/ui/KeypressStandard.ogg"
        ),
        SoundEffect.Modifier to listOf(
            "/system/media/audio/ui/SIP_Modifier.ogg",
            "/system/media/audio/ui/TW_SIP.ogg",
            "/system/media/audio/ui/KeypressStandard.ogg"
        ),
        SoundEffect.SpaceBar to listOf(
            "/system/media/audio/ui/SIP_Modifier.ogg",
            "/system/media/audio/ui/KeypressSpacebar.ogg",
            "/system/media/audio/ui/TW_SIP.ogg",
            "/system/media/audio/ui/KeypressStandard.ogg"
        ),
        SoundEffect.Delete to listOf(
            "/system/media/audio/ui/S_SIP_Backspace.ogg",
            "/system/media/audio/ui/KeypressDelete.ogg"
        ),
        SoundEffect.Return to listOf(
            "/system/media/audio/ui/SIP_Modifier.ogg",
            "/system/media/audio/ui/KeypressReturn.ogg"
        )
    )

    private fun audioManagerFx(effect: SoundEffect) = when (effect) {
        SoundEffect.Standard -> AudioManager.FX_KEYPRESS_STANDARD
        SoundEffect.Modifier -> AudioManager.FX_KEYPRESS_STANDARD
        SoundEffect.SpaceBar -> AudioManager.FX_KEYPRESS_SPACEBAR
        SoundEffect.Delete -> AudioManager.FX_KEYPRESS_DELETE
        SoundEffect.Return -> AudioManager.FX_KEYPRESS_RETURN
    }

    private fun volume() = soundOnKeyPressVolume.let {
        if (it == 0) -1f else it / 100f
    }

    private fun playAudioManagerSound(effect: SoundEffect) {
        val fx = audioManagerFx(effect)
        val volume = volume()
        if (volume < 0f) {
            audioManager.playSoundEffect(fx, -1f)
        } else {
            audioManager.playSoundEffect(fx, volume)
        }
    }

    private fun systemSoundId(effect: SoundEffect): Int? {
        systemSoundIds[effect]?.let { return it }
        val path = systemSoundPaths[effect]
            ?.firstOrNull { File(it).canRead() }
            ?: return null
        return systemSoundPool.load(path, 1).takeIf { it != 0 }?.also {
            systemSoundIds[effect] = it
            systemSoundLoaded[it] = false
        }
    }

    private fun playSystemSoundFile(effect: SoundEffect): Boolean {
        val soundId = systemSoundId(effect) ?: return false
        if (systemSoundLoaded[soundId] != true) return false
        val volume = volume().takeIf { it >= 0f } ?: 1f
        systemSoundPool.play(soundId, volume, volume, 1, 0, 1f)
        return true
    }

    fun soundEffect(effect: SoundEffect) {
        when (soundOnKeyPress) {
            InputFeedbackMode.Enabled -> {}
            InputFeedbackMode.Disabled -> return
            InputFeedbackMode.FollowingSystem -> if (!systemSoundEffects) return
        }
        if (systemSoundFileMode && playSystemSoundFile(effect)) {
            return
        }
        playAudioManagerSound(effect)
    }

}
