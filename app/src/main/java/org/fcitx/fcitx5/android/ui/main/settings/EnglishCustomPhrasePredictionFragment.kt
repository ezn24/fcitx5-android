/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.keyboard.EnglishCustomPhrasePrediction
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

class EnglishCustomPhrasePredictionFragment : ProgressFragment(),
    OnItemChangedListener<EnglishCustomPhrasePrediction> {

    private lateinit var ui: BaseDynamicListUi<EnglishCustomPhrasePrediction>
    private val dustman = NaiveDustman<EnglishCustomPhrasePrediction>()

    override suspend fun initialize(): View {
        val initialPhrases = withContext(Dispatchers.IO) {
            EnglishWordManager.loadCustomPhrasePredictions()
        }
        ui = object : BaseDynamicListUi<EnglishCustomPhrasePrediction>(
            requireContext(),
            Mode.FreeAdd("", converter = { EnglishCustomPhrasePrediction(it.trim()) }),
            initialPhrases,
        ) {
            init {
                shouldShowFab = true
                fab.setOnClickListener {
                    showEditDialog(ctx.getString(R.string.add)) { addItem(item = it) }
                }
            }

            override fun showEntry(x: EnglishCustomPhrasePrediction): String {
                return "${x.phrase}\t${x.score}"
            }

            override fun showEditDialog(
                title: String,
                entry: EnglishCustomPhrasePrediction?,
                block: (EnglishCustomPhrasePrediction) -> Unit
            ) {
                val (phraseLayout, phraseField) = materialTextInput {
                    setHint(R.string.english_custom_prediction_phrase)
                }
                phraseField.apply {
                    isSingleLine = false
                    maxLines = 4
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setText(entry?.phrase.orEmpty())
                }
                val (scoreLayout, scoreField) = materialTextInput {
                    setHint(R.string.english_custom_prediction_score)
                    helperText = getString(R.string.english_custom_prediction_score_hint)
                }
                scoreField.apply {
                    isSingleLine = true
                    inputType = InputType.TYPE_CLASS_NUMBER
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setText((entry?.score ?: EnglishCustomPhrasePrediction.DefaultScore).toString())
                }
                val scoreInfo = TextView(context).apply {
                    alpha = 0.75f
                    textSize = 12f
                    setPaddingDp(0, 4, 0, 0)
                }
                var scoreInfoJob: Job? = null
                fun updateScoreInfo() {
                    scoreInfoJob?.cancel()
                    val rawPhrase = phraseField.str
                    scoreInfoJob = lifecycleScope.launch {
                        delay(250)
                        val info = withContext(Dispatchers.IO) {
                            EnglishWordManager.phrasePredictionWeightInfo(rawPhrase)
                        }
                        scoreInfo.text = if (info == null) {
                            getString(R.string.english_custom_prediction_score_hint)
                        } else if (info.candidates.isEmpty()) {
                            getString(
                                R.string.english_custom_prediction_score_no_competition,
                                info.prefix
                            )
                        } else {
                            val candidates = info.candidates.joinToString(", ") {
                                "${it.first} ${it.second}"
                            }
                            getString(
                                R.string.english_custom_prediction_score_competition,
                                info.prefix,
                                EnglishWordManager.DefaultPhrasePredictionSize,
                                candidates,
                                info.visibleThresholdScore + 1
                            )
                        }
                    }
                }
                phraseField.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        updateScoreInfo()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
                updateScoreInfo()
                val layout = verticalLayout {
                    setPaddingDp(20, 10, 20, 0)
                    add(phraseLayout, lParams(matchParent))
                    add(scoreLayout, lParams(matchParent))
                    add(scoreInfo, lParams(matchParent))
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
                        val score = scoreField.str.toIntOrNull()?.takeIf { it > 0 }
                        if (score == null) {
                            scoreField.error = getString(R.string.english_custom_prediction_invalid_score)
                            scoreField.requestFocus()
                            return@onClick false
                        }
                        block(EnglishCustomPhrasePrediction(phrase, score))
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
        dustman.reset(ui.entries.associateBy { it.serialize() })
    }

    override fun onItemAdded(idx: Int, item: EnglishCustomPhrasePrediction) {
        dustman.addOrUpdate(item.serialize(), item)
        saveConfig()
    }

    override fun onItemRemoved(idx: Int, item: EnglishCustomPhrasePrediction) {
        dustman.remove(item.serialize())
        saveConfig()
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, EnglishCustomPhrasePrediction>>) {
        batchRemove(indexed)
    }

    override fun onItemUpdated(
        idx: Int,
        old: EnglishCustomPhrasePrediction,
        new: EnglishCustomPhrasePrediction
    ) {
        dustman.remove(old.serialize())
        dustman.addOrUpdate(new.serialize(), new)
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
