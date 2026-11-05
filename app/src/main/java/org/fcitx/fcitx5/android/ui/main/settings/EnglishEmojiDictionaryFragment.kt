/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.keyboard.EnglishEmojiDictionary
import org.fcitx.fcitx5.android.data.keyboard.EnglishEmojiDictionary.Entry
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.main.EditDeleteMenuProvider
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.onPositiveButtonClick
import org.fcitx.fcitx5.android.utils.str
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.setPaddingDp
import timber.log.Timber

class EnglishEmojiDictionaryFragment : ProgressFragment() {
    private lateinit var ui: BaseDynamicListUi<Entry>
    private var savedEntries = emptyList<Entry>()
    private var resetting = false

    override suspend fun initialize(): View {
        savedEntries = withContext(Dispatchers.IO) { EnglishEmojiDictionary.load() }
        val list = object : BaseDynamicListUi<Entry>(
            requireContext(), Mode.FreeAdd("", converter = { Entry(it, "") }), savedEntries
        ) {
            override fun showEntry(x: Entry) = "${x.emoji}\t${x.keyword}"

            fun jumpToKeyword(query: String): Boolean {
                if (query.isBlank()) return false
                val exact = entries.indexOfFirst { it.keyword.equals(query, ignoreCase = true) }
                val position = if (exact >= 0) exact else entries.indexOfFirst {
                    it.keyword.startsWith(query, ignoreCase = true)
                }
                if (position < 0) return false
                recyclerView.scrollToPosition(position)
                return true
            }

            override fun showEditDialog(title: String, entry: Entry?, block: (Entry) -> Unit) {
                val (keywordLayout, keywordField) = materialTextInput {
                    setHint(R.string.english_emoji_keyword)
                }
                val (emojiLayout, emojiField) = materialTextInput {
                    setHint(R.string.pinyin_emoji_dict_emoji)
                }
                keywordField.isSingleLine = true
                emojiField.isSingleLine = true
                keywordField.setText(entry?.keyword)
                emojiField.setText(entry?.emoji)
                val content = verticalLayout {
                    setPaddingDp(20, 10, 20, 0)
                    add(keywordLayout, lParams(matchParent))
                    add(emojiLayout, lParams(matchParent))
                }
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(content)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                    .onPositiveButtonClick onClick@{
                        val keyword = EnglishEmojiDictionary.normalizeKeyword(keywordField.str)
                        if (keyword == null) {
                            keywordField.error = getString(R.string.english_emoji_invalid_keyword)
                            return@onClick false
                        }
                        val emoji = emojiField.str.trim()
                        if (emoji.isBlank() || emoji.any { it.isWhitespace() || it.isISOControl() }) {
                            emojiField.error = getString(R.string.english_emoji_invalid_value)
                            return@onClick false
                        }
                        block(Entry(keyword, emoji))
                        true
                    }
            }
        }
        ui = list
        ui.addTouchCallback()
        ui.setViewModel(viewModel)
        viewModel.toolbarButton.value = if (ui.entries.isEmpty()) ButtonMode.NONE else ButtonMode.EDIT
        val activity = requireActivity()
        activity.addMenuProvider(
            EditDeleteMenuProvider(
                buttonMode = viewModel.toolbarButton,
                editButtonAction = { ui.enterMultiSelect(activity.onBackPressedDispatcher) },
                deleteButtonAction = { ui.deleteSelected(); ui.exitMultiSelect() },
                menuHost = activity,
                lifecycleOwner = viewLifecycleOwner,
            ), viewLifecycleOwner, Lifecycle.State.STARTED
        )
        activity.addMenuProvider(object : MenuProvider {
            private var resetItem: MenuItem? = null
            private var searchItem: MenuItem? = null
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                searchItem = menu.add(R.string.search)
                resetItem = menu.add(R.string.reset)
            }
            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem === searchItem) {
                    val (layout, field) = requireContext().materialTextInput {
                        setHint(R.string.english_emoji_keyword)
                    }
                    field.isSingleLine = true
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.search)
                        .setView(layout)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                        .onPositiveButtonClick {
                            list.jumpToKeyword(field.str.trim()).also { found ->
                                if (!found) field.error = getString(R.string.emoji_search_no_results)
                            }
                        }
                    return true
                }
                if (menuItem !== resetItem) return false
                if (resetting) return true
                AlertDialog.Builder(requireContext())
                    .setMessage(R.string.english_emoji_reset_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.reset) { _, _ ->
                        resetting = true
                        viewLifecycleOwner.lifecycleScope.launch {
                            val restored = withContext(NonCancellable + Dispatchers.IO) {
                                persist { EnglishEmojiDictionary.reset() }
                            }
                            if (restored) findNavController().popBackStack()
                            else resetting = false
                        }
                    }
                    .show()
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.STARTED)
        return ui.root
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getString(R.string.english_emoji_dictionary))
    }

    override fun onStop() {
        if (isInitialized) {
            val snapshot = ui.entries.toList()
            if (!resetting && snapshot != savedEntries) {
                savedEntries = snapshot
                lifecycleScope.launch(NonCancellable + Dispatchers.IO) {
                    persist { EnglishEmojiDictionary.save(snapshot) }
                }
            }
            ui.exitMultiSelect()
        }
        viewModel.disableToolbarEditButton()
        super.onStop()
    }

    private suspend fun persist(write: () -> Unit): Boolean =
        try {
            write()
            FcitxDaemon.restartFcitx()
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to update English emoji dictionary")
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, R.string.save_failed, Toast.LENGTH_SHORT).show()
            }
            false
        }
}
