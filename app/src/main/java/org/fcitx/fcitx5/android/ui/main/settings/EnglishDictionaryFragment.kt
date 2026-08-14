/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
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
import org.fcitx.fcitx5.android.ui.main.EditDeleteMenuProvider
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.toast
import java.io.File

class EnglishDictionaryFragment : ProgressFragment(), OnItemChangedListener<File> {

    private lateinit var launcher: ActivityResultLauncher<String>
    private lateinit var ui: BaseDynamicListUi<File>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importFromUri(uri)
        }
    }

    override suspend fun initialize(): View {
        val dictionaries = withContext(Dispatchers.IO) {
            EnglishWordManager.listDictionaries()
        }
        ui = object : BaseDynamicListUi<File>(
            requireContext(),
            Mode.Custom(),
            dictionaries
        ) {
            init {
                enableUndo = false
                shouldShowFab = true
                fab.setOnClickListener {
                    launcher.launch("text/*")
                }
            }

            override fun showEntry(x: File): String = x.name

            override fun updateFAB() {
            }
        }
        ui.addOnItemChangedListener(this)
        ui.addTouchCallback()
        ui.setViewModel(viewModel)
        viewModel.toolbarButton.value =
            if (ui.entries.isNotEmpty()) ButtonMode.EDIT else ButtonMode.NONE
        requireActivity().addMenuProvider(
            EditDeleteMenuProvider(
                buttonMode = viewModel.toolbarButton,
                editButtonAction = { ui.enterMultiSelect(requireActivity().onBackPressedDispatcher) },
                deleteButtonAction = { ui.deleteSelected(); ui.exitMultiSelect() },
                menuHost = requireActivity(),
                lifecycleOwner = viewLifecycleOwner,
            ),
            viewLifecycleOwner,
            Lifecycle.State.STARTED
        )
        return ui.root
    }

    private fun importFromUri(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch(NonCancellable + Dispatchers.IO) {
            try {
                val fileName = ctx.contentResolver.queryFileName(uri) ?: "dictionary.txt"
                val stream = ctx.contentResolver.openInputStream(uri) ?: return@launch
                val count = EnglishWordManager.importWords(stream, fileName)
                val importedName = EnglishWordManager.dictionaryFileName(fileName)
                val imported = EnglishWordManager.listDictionaries()
                    .firstOrNull { it.name == importedName }
                withContext(Dispatchers.Main) {
                    ctx.toast(getString(R.string.english_imported_words, count))
                    imported?.let { ui.addItem(item = it) }
                }
                FcitxDaemon.restartFcitx()
            } catch (e: Exception) {
                ctx.importErrorDialog(e)
            }
        }
    }

    override fun onItemAdded(idx: Int, item: File) {
    }

    override fun onItemRemoved(idx: Int, item: File) {
        lifecycleScope.launch(NonCancellable + Dispatchers.IO) {
            EnglishWordManager.deleteDictionary(item)
            FcitxDaemon.restartFcitx()
        }
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, File>>) {
        batchRemove(indexed)
    }

    override fun onItemUpdated(idx: Int, old: File, new: File) {
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getString(R.string.english_manage_dictionaries))
    }

    override fun onStop() {
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
