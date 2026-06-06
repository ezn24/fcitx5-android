/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.ui.main.settings.FcitxPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class InputMethodConfigFragment : FcitxPreferenceFragment() {
    val args by lazyRoute<SettingsRoute.InputMethodConfig>()

    override fun getPageTitle(): String = args.name

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig {
        return fcitx.getImConfig(args.uniqueName)
    }

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        fcitx.setImConfig(args.uniqueName, newConfig)
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        if (args.uniqueName != "keyboard-us") return
        screen.addPreference(
            R.string.english_word_list
        ) {
            navigateWithAnim(SettingsRoute.EnglishWordList)
        }
        screen.addPreference(
            R.string.english_manage_dictionaries
        ) {
            navigateWithAnim(SettingsRoute.EnglishDictionaries)
        }
        screen.addPreference(
            R.string.english_manage_phrase_books
        ) {
            navigateWithAnim(SettingsRoute.EnglishPhraseBooks)
        }
        screen.addPreference(
            R.string.english_manage_custom_predictions
        ) {
            navigateWithAnim(SettingsRoute.EnglishCustomPredictions)
        }
        screen.addPreference(
            R.string.english_manage_custom_phrase
        ) {
            navigateWithAnim(SettingsRoute.EnglishCustomPhrase)
        }
    }
}
