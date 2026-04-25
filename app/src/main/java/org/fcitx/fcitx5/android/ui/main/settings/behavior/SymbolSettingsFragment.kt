/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.reloadPinyinDict
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider

class SymbolSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().symbols) {

    private val symbols = AppPrefs.getInstance().symbols

    private var managedDictionaryDirty = false

    private val managedDictionaryListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (key == symbols.defaultEmojiSkinTone.key) {
            managedDictionaryDirty = true
        }
    }

    override fun onStart() {
        super.onStart()
        symbols.registerOnChangeListener(managedDictionaryListener)
    }

    override fun onStop() {
        if (managedDictionaryDirty) {
            managedDictionaryDirty = false
            lifecycleScope.launch(NonCancellable + Dispatchers.IO) {
                val result = PinyinDictManager.syncManagedData(
                    AppPrefs.getInstance().internal.enableCantonesePinyinDictionary.getValue()
                )
                if (result.anyChanged) {
                    if (result.symbolsChanged) {
                        FcitxDaemon.restartFcitx()
                    } else if (result.dictionaryChanged) {
                        FcitxDaemon.getFirstConnectionOrNull()?.runIfReady {
                            reloadPinyinDict()
                        }
                    }
                }
            }
        }
        symbols.unregisterOnChangeListener(managedDictionaryListener)
        super.onStop()
    }
}
