/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.core.reloadPinyinDict
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.ui.main.settings.FcitxPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.lazyRoute

class InputMethodConfigFragment : FcitxPreferenceFragment() {
    val args by lazyRoute<SettingsRoute.InputMethodConfig>()

    override fun getPageTitle(): String = args.name

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig {
        return fcitx.getImConfig(args.uniqueName)
    }

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        fcitx.setImConfig(args.uniqueName, newConfig)
    }

    override fun onPreferenceScreenCreated(screen: PreferenceScreen) {
        if (args.uniqueName != "pinyin") return

        val cantoneseDictionary =
            AppPrefs.getInstance().internal.enableCantonesePinyinDictionary
        screen.addPreference(
            MySwitchPreference(screen.context).apply {
                key = cantoneseDictionary.key
                title = getString(R.string.enable_cantonese_pinyin_dictionary)
                summary = getString(R.string.enable_cantonese_pinyin_dictionary_summary)
                isIconSpaceReserved = false
                isSingleLineTitle = false
                isChecked = cantoneseDictionary.getValue()
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    cantoneseDictionary.setValue(enabled)
                    lifecycleScope.launch(NonCancellable + Dispatchers.IO) {
                        if (PinyinDictManager.syncManagedDictionaries(enabled)) {
                            FcitxDaemon.getFirstConnectionOrNull()?.runIfReady {
                                reloadPinyinDict()
                            }
                        }
                    }
                    true
                }
            }
        )
    }
}
