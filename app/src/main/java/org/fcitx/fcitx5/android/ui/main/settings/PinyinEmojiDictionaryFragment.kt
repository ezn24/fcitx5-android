/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.reloadPinyinDict
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.pinyin.PinyinEmojiDictionaryData
import org.fcitx.fcitx5.android.data.pinyin.PinyinEmojiDictionaryEntry
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.utils.NaiveDustman
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.onPositiveButtonClick
import org.fcitx.fcitx5.android.utils.str
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.setPaddingDp

class PinyinEmojiDictionaryFragment :
    ProgressFragment(),
    OnItemChangedListener<PinyinEmojiDictionaryEntry> {

    private lateinit var ui: BaseDynamicListUi<PinyinEmojiDictionaryEntry>

    private val dustman = NaiveDustman<PinyinEmojiDictionaryEntry>()

    override suspend fun initialize(): View {
        val initialEntries = withContext(Dispatchers.IO) {
            PinyinDictManager.loadEmojiDictionaryData()
        }
        ui = object : BaseDynamicListUi<PinyinEmojiDictionaryEntry>(
            requireContext(),
            Mode.FreeAdd("", converter = { PinyinEmojiDictionaryEntry("", "", "0.3") }),
            initialEntries,
        ) {
            override fun showEntry(x: PinyinEmojiDictionaryEntry): String = x.run {
                val suffix = if (weight.isBlank()) "" else "\t$weight"
                "$emoji\t$code$suffix"
            }

            override fun showEditDialog(
                title: String,
                entry: PinyinEmojiDictionaryEntry?,
                block: (PinyinEmojiDictionaryEntry) -> Unit
            ) {
                val (emojiLayout, emojiField) = materialTextInput {
                    setHint(R.string.pinyin_emoji_dict_emoji)
                }
                emojiField.apply {
                    isSingleLine = true
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                }
                val (codeLayout, codeField) = materialTextInput {
                    setHint(R.string.pinyin_emoji_dict_code)
                }
                codeField.apply {
                    isSingleLine = true
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                }
                val (weightLayout, weightField) = materialTextInput {
                    setHint(R.string.pinyin_emoji_dict_weight)
                }
                weightField.apply {
                    isSingleLine = true
                    imeOptions = EditorInfo.IME_ACTION_DONE
                }
                entry?.apply {
                    emojiField.setText(emoji)
                    codeField.setText(code)
                    weightField.setText(weight)
                }
                val layout = verticalLayout {
                    setPaddingDp(20, 10, 20, 0)
                    add(emojiLayout, lParams(matchParent))
                    add(codeLayout, lParams(matchParent))
                    add(weightLayout, lParams(matchParent))
                }
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(layout)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                    .onPositiveButtonClick onClick@{
                        val emoji = emojiField.str.trim()
                        if (emoji.isBlank()) {
                            emojiField.error = getString(
                                R.string._cannot_be_empty,
                                getString(R.string.pinyin_emoji_dict_emoji)
                            )
                            emojiField.requestFocus()
                            return@onClick false
                        }
                        emojiField.error = null

                        val code = codeField.str.trim()
                        if (code.isBlank()) {
                            codeField.error = getString(
                                R.string._cannot_be_empty,
                                getString(R.string.pinyin_emoji_dict_code)
                            )
                            codeField.requestFocus()
                            return@onClick false
                        }
                        codeField.error = null

                        val weight = weightField.str.trim()
                        if (weight.isNotBlank() && weight.toDoubleOrNull() == null) {
                            weightField.error = getString(R.string.pinyin_emoji_dict_invalid_weight)
                            weightField.requestFocus()
                            return@onClick false
                        }
                        weightField.error = null

                        block(PinyinEmojiDictionaryEntry(emoji, code, weight))
                        true
                    }
                    .setCanceledOnTouchOutside(false)
            }
        }
        ui.addOnItemChangedListener(this)
        ui.addTouchCallback()
        resetDustman()
        ui.setViewModel(viewModel)
        viewModel.enableToolbarEditButton(initialEntries.isNotEmpty()) {
            ui.enterMultiSelect(requireActivity().onBackPressedDispatcher)
        }
        return ui.root
    }

    override fun onItemAdded(idx: Int, item: PinyinEmojiDictionaryEntry) {
        dustman.addOrUpdate(item.serialize(), item)
    }

    override fun onItemRemoved(idx: Int, item: PinyinEmojiDictionaryEntry) {
        dustman.remove(item.serialize())
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, PinyinEmojiDictionaryEntry>>) {
        batchRemove(indexed)
    }

    override fun onItemUpdated(
        idx: Int,
        old: PinyinEmojiDictionaryEntry,
        new: PinyinEmojiDictionaryEntry
    ) {
        dustman.addOrUpdate(new.serialize(), new)
    }

    private fun saveConfig() {
        if (!dustman.dirty) return
        resetDustman()
        lifecycleScope.launch(NonCancellable + Dispatchers.IO) {
            PinyinDictManager.saveEmojiDictionaryData(PinyinEmojiDictionaryData(ui.entries))
            val result = PinyinDictManager.syncManagedData()
            if (result.symbolsChanged) {
                FcitxDaemon.restartFcitx()
            } else if (result.dictionaryChanged) {
                FcitxDaemon.getFirstConnectionOrNull()?.runIfReady {
                    reloadPinyinDict()
                }
            }
        }
    }

    private fun resetDustman() {
        dustman.reset(ui.entries.associateBy { it.serialize() })
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getString(R.string.pinyin_emoji_dict))
        if (isInitialized) {
            viewModel.enableToolbarEditButton(ui.entries.isNotEmpty()) {
                ui.enterMultiSelect(requireActivity().onBackPressedDispatcher)
            }
        }
    }

    override fun onStop() {
        saveConfig()
        if (isInitialized) {
            ui.exitMultiSelect()
        }
        viewModel.disableToolbarEditButton()
        super.onStop()
    }

    override fun onDestroy() {
        if (isInitialized) {
            ui.removeItemChangedListener()
        }
        super.onDestroy()
    }
}
