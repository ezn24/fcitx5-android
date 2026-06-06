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
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
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

class EnglishCustomPhrasePredictionFragment : ProgressFragment(), OnItemChangedListener<String> {

    private lateinit var ui: BaseDynamicListUi<String>
    private val dustman = NaiveDustman<String>()

    override suspend fun initialize(): View {
        val initialPhrases = withContext(Dispatchers.IO) {
            EnglishWordManager.loadCustomPhrasePredictions()
        }
        ui = object : BaseDynamicListUi<String>(
            requireContext(),
            Mode.FreeAdd("", converter = { it.trim() }),
            initialPhrases,
        ) {
            init {
                shouldShowFab = true
                fab.setOnClickListener {
                    showEditDialog(ctx.getString(R.string.add)) { addItem(item = it) }
                }
            }

            override fun showEntry(x: String): String = x

            override fun showEditDialog(title: String, entry: String?, block: (String) -> Unit) {
                val (phraseLayout, phraseField) = materialTextInput {
                    setHint(R.string.english_custom_prediction_phrase)
                }
                phraseField.apply {
                    isSingleLine = false
                    maxLines = 4
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setText(entry.orEmpty())
                }
                val layout = verticalLayout {
                    setPaddingDp(20, 10, 20, 0)
                    add(phraseLayout, lParams(matchParent))
                }
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(layout)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                    .onPositiveButtonClick onClick@{
                        val phrase = EnglishWordManager.normalizePredictionPhrase(phraseField.str)
                        if (phrase.isEmpty()) {
                            phraseField.error = getString(R.string.english_custom_prediction_invalid)
                            phraseField.requestFocus()
                            return@onClick false
                        }
                        block(phrase)
                        true
                    }
                    .setCanceledOnTouchOutside(false)
            }
        }
        ui.addOnItemChangedListener(this)
        ui.addTouchCallback()
        ui.setViewModel(viewModel)
        resetDustman()
        viewModel.enableToolbarEditButton(initialPhrases.isNotEmpty()) {
            ui.enterMultiSelect(requireActivity().onBackPressedDispatcher)
        }
        return ui.root
    }

    private fun saveConfig() {
        if (!dustman.dirty) return
        resetDustman()
        lifecycleScope.launch(NonCancellable + Dispatchers.IO) {
            EnglishWordManager.saveCustomPhrasePredictions(ui.entries)
            FcitxDaemon.restartFcitx()
        }
    }

    private fun resetDustman() {
        dustman.reset(ui.entries.associateBy { it })
    }

    override fun onItemAdded(idx: Int, item: String) {
        dustman.addOrUpdate(item, item)
        saveConfig()
    }

    override fun onItemRemoved(idx: Int, item: String) {
        dustman.remove(item)
        saveConfig()
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, String>>) {
        batchRemove(indexed)
    }

    override fun onItemUpdated(idx: Int, old: String, new: String) {
        dustman.remove(old)
        dustman.addOrUpdate(new, new)
        saveConfig()
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getString(R.string.english_manage_custom_predictions))
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
