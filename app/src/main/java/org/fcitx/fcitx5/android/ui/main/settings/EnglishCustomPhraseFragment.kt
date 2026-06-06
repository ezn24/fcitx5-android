/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.keyboard.EnglishCustomPhrase
import org.fcitx.fcitx5.android.data.keyboard.EnglishWordManager
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
import kotlin.math.absoluteValue

class EnglishCustomPhraseFragment : ProgressFragment(),
    OnItemChangedListener<EnglishCustomPhrase> {

    private lateinit var ui: BaseDynamicListUi<EnglishCustomPhrase>
    private val dustman = NaiveDustman<EnglishCustomPhrase>()

    override suspend fun initialize(): View {
        val initialEntries = withContext(Dispatchers.IO) {
            EnglishWordManager.loadCustomPhrases()
        }
        ui = object : BaseDynamicListUi<EnglishCustomPhrase>(
            requireContext(),
            Mode.FreeAdd("", converter = { EnglishCustomPhrase("", 1, "") }),
            initialEntries,
            enableOrder = true,
            initCheckBox = { entry ->
                isChecked = entry.enabled
                setOnCheckedChangeListener { _, checked ->
                    ui.updateItem(ui.indexItem(entry), entry.copyEnabled(checked))
                }
            }
        ) {
            override fun showEntry(x: EnglishCustomPhrase): String {
                return "${x.key} -> ${x.phrase}"
            }

            override fun showEditDialog(
                title: String,
                entry: EnglishCustomPhrase?,
                block: (EnglishCustomPhrase) -> Unit
            ) {
                val (keyLayout, keyField) = materialTextInput {
                    setHint(R.string.english_custom_phrase_key)
                }
                keyField.apply {
                    isSingleLine = true
                    filters = arrayOf(
                        InputFilter { source, _, _, _, _, _ ->
                            source.filter { it.isLetter() || it == '\'' || it == '-' }
                        }
                    )
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                    inputType =
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL or
                            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                val (orderLayout, orderField) = materialTextInput {
                    setHint(R.string.english_custom_phrase_order)
                }
                orderField.apply {
                    isSingleLine = true
                    inputType =
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL or
                            InputType.TYPE_NUMBER_FLAG_SIGNED
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                }
                val (phraseLayout, phraseField) = materialTextInput {
                    setHint(R.string.english_custom_phrase_phrase)
                }
                phraseField.apply {
                    isSingleLine = false
                    maxLines = 4
                }
                entry?.apply {
                    keyField.setText(key)
                    orderField.setText(order.absoluteValue.toString(10))
                    phraseField.setText(phrase)
                }
                val layout = verticalLayout {
                    setPaddingDp(20, 10, 20, 0)
                    add(keyLayout, lParams(matchParent))
                    add(orderLayout, lParams(matchParent))
                    add(phraseLayout, lParams(matchParent))
                }
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(layout)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                    .onPositiveButtonClick onClick@{
                        val key = EnglishWordManager.normalizeKey(keyField.str)
                        if (key.isEmpty()) {
                            keyField.error = getString(
                                R.string._cannot_be_empty,
                                getString(R.string.english_custom_phrase_key)
                            )
                            keyField.requestFocus()
                            return@onClick false
                        }
                        val phrase = EnglishWordManager.normalizePhrase(phraseField.str)
                        if (phrase.isEmpty()) {
                            phraseField.error = getString(
                                R.string._cannot_be_empty,
                                getString(R.string.english_custom_phrase_phrase)
                            )
                            phraseField.requestFocus()
                            return@onClick false
                        }
                        block(
                            EnglishCustomPhrase(
                                key = key,
                                order = orderField.str.toIntOrNull() ?: 1,
                                phrase = phrase,
                                enabled = entry?.enabled ?: true
                            )
                        )
                        true
                    }
                    .setCanceledOnTouchOutside(false)
            }
        }
        ui.addOnItemChangedListener(this)
        ui.addTouchCallback()
        ui.setViewModel(viewModel)
        resetDustman()
        viewModel.enableToolbarEditButton(initialEntries.isNotEmpty()) {
            ui.enterMultiSelect(requireActivity().onBackPressedDispatcher)
        }
        return ui.root
    }

    private fun saveConfig() {
        if (!dustman.dirty) return
        resetDustman()
        lifecycleScope.launch(NonCancellable + Dispatchers.IO) {
            EnglishWordManager.saveCustomPhrases(ui.entries)
            FcitxDaemon.restartFcitx()
        }
    }

    private fun resetDustman() {
        dustman.reset(ui.entries.associateBy { it.serialize() })
    }

    override fun onItemAdded(idx: Int, item: EnglishCustomPhrase) {
        dustman.addOrUpdate(item.serialize(), item)
        saveConfig()
    }

    override fun onItemRemoved(idx: Int, item: EnglishCustomPhrase) {
        dustman.remove(item.serialize())
        saveConfig()
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, EnglishCustomPhrase>>) {
        batchRemove(indexed)
    }

    override fun onItemUpdated(
        idx: Int,
        old: EnglishCustomPhrase,
        new: EnglishCustomPhrase
    ) {
        dustman.remove(old.serialize())
        dustman.addOrUpdate(new.serialize(), new)
        saveConfig()
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getString(R.string.english_manage_custom_phrase))
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
