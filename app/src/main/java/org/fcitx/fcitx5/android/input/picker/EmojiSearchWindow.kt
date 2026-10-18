/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.doOnAttach
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

class EmojiSearchWindow : InputWindow.SimpleInputWindow<EmojiSearchWindow>() {
    companion object {
        private const val QUERY_HEIGHT_DP = 40
        private const val RESULTS_HEIGHT_DP = 48
    }

    val extraHeightPx: Int
        get() = context.dp(QUERY_HEIGHT_DP + RESULTS_HEIGHT_DP)

    private val service by manager.inputMethodService()
    private val theme by manager.theme()
    private val windows: InputWindowManager by manager.must()
    private val keyboardWindow: KeyboardWindow by manager.must()
    private val popup: PopupComponent by manager.must()
    private val policy = EmojiPickerPolicy()
    private lateinit var root: LinearLayout
    private lateinit var editor: EditText
    private lateinit var results: RecyclerView
    private lateinit var empty: TextView
    private lateinit var keyboardHost: FrameLayout
    private var index: EmojiSearchIndex? = null
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var beginJob: Job? = null
    private var tonePopup: PopupWindow? = null
    private var attached = false
    private var closing = false
    private var inputReady = false
    private var clearJob: Job? = null
    private val supported = hashMapOf<String, Boolean>()
    private val resultAdapter = ResultsAdapter()

    private fun icon(resource: Int, label: Int, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(theme.keyTextColor)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = context.getString(label)
        TooltipCompat.setTooltipText(this, contentDescription)
        setOnClickListener { action() }
    }

    override fun onCreateView(): View {
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.keyboardColor)
        }
        val queryRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        queryRow.addView(icon(R.drawable.ic_baseline_arrow_back_24, R.string.emoji_search_back) {
            finishSearch()
        }, LinearLayout.LayoutParams(context.dp(40), ViewGroup.LayoutParams.MATCH_PARENT))
        editor = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            showSoftInputOnFocus = false
            setTextColor(theme.keyTextColor)
            setHintTextColor(ColorStateList.valueOf(theme.keyTextColor).withAlpha(150))
            hint = context.getString(R.string.emoji_search)
            textSize = 16f
            setPadding(context.dp(4), 0, context.dp(4), 0)
            setOnEditorActionListener { _, _, _ -> true }
            doAfterTextChanged { scheduleSearch() }
        }
        queryRow.addView(editor, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        queryRow.addView(icon(R.drawable.ic_baseline_close_24, R.string.emoji_search_clear) {
            if (closing || !inputReady) return@icon
            inputReady = false
            clearJob = service.lifecycleScope.launch {
                service.postFcitxJob { reset() }.join()
                kotlinx.coroutines.yield()
                if (!attached || closing) return@launch
                editor.text.clear()
                editor.requestFocus()
                inputReady = true
            }
        }, LinearLayout.LayoutParams(context.dp(40), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(queryRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(QUERY_HEIGHT_DP)))
        results = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = resultAdapter
            itemAnimator = null
        }
        empty = TextView(context).apply {
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
            textSize = 14f
            setText(R.string.emoji_search)
        }
        val resultArea = android.widget.FrameLayout(context).apply {
            addView(results, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(empty, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(resultArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(RESULTS_HEIGHT_DP)))
        // Keep keys visible while preventing input from reaching the app before local routing is ready.
        keyboardHost = object : FrameLayout(context) {
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
                !inputReady || closing || super.onInterceptTouchEvent(event)

            override fun onTouchEvent(event: MotionEvent): Boolean =
                !inputReady || closing || super.onTouchEvent(event)
        }
        root.addView(keyboardHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onAttached() {
        attached = true
        closing = false
        inputReady = false
        keyboardWindow.attachForEmojiSearch(keyboardHost)
        editor.doOnAttach {
            if (!attached || closing) return@doOnAttach
            editor.requestFocus()
            beginJob = service.beginEmojiSearchInput(editor) {
                inputReady = true
            }
        }
        loadJob = service.lifecycleScope.launch {
            val loaded = runCatching { withContext(Dispatchers.IO) { EmojiSearchRepository.load(context) } }
            if (!attached) return@launch
            loaded.onSuccess {
                index = it
                scheduleSearch()
            }.onFailure { empty.setText(R.string.emoji_search_unavailable) }
        }
    }

    private fun scheduleSearch() {
        if (!attached || closing) return
        searchJob?.cancel()
        tonePopup?.dismiss()
        val query = editor.text.toString()
        if (query.isBlank()) {
            resultAdapter.submit(emptyList())
            results.visibility = View.INVISIBLE
            empty.visibility = View.VISIBLE
            empty.setText(R.string.emoji_search)
            return
        }
        val loaded = index ?: return
        searchJob = service.lifecycleScope.launch {
            delay(100)
            val matches = withContext(Dispatchers.Default) { loaded.search(query) }
            val visible = matches.filter { supported.getOrPut(it) { policy.filter(it) } }
            if (!attached || editor.text.toString() != query) return@launch
            resultAdapter.submit(visible)
            results.scrollToPosition(0)
            results.visibility = if (visible.isEmpty()) View.INVISIBLE else View.VISIBLE
            empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
            empty.setText(R.string.emoji_search_no_results)
        }
    }

    private fun finishSearch(emoji: String? = null) {
        if (closing) return
        closing = true
        inputReady = false
        beginJob?.cancel()
        clearJob?.cancel()
        tonePopup?.dismiss()
        popup.dismissAll()
        service.endEmojiSearchInput(editor) {
            if (!attached) return@endEmojiSearchInput
            if (emoji != null) {
                (windows.getEssentialWindow(PickerWindow.Key.Emoji) as PickerWindow).insertRecent(emoji)
                service.commitText(emoji)
            }
            windows.attachWindow(PickerWindow.Key.Emoji)
        }
    }

    private fun showTones(anchor: View, emoji: String): Boolean {
        val choices = (policy.popup(emoji) as? KeyDef.Popup.Keyboard.Explicit)?.items ?: return false
        tonePopup?.dismiss()
        val row = LinearLayout(context)
        choices.forEach { choice ->
            val item = TextView(context).apply {
                text = choice
                textSize = 26f
                gravity = Gravity.CENTER
                setOnClickListener { finishSearch(choice) }
            }
            row.addView(item, LinearLayout.LayoutParams(context.dp(44), context.dp(48)))
        }
        tonePopup = PopupWindow(row, ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(48), true).apply {
            setBackgroundDrawable(ColorDrawable(theme.keyboardColor))
            isOutsideTouchable = true
            elevation = context.dp(4).toFloat()
            showAsDropDown(anchor)
        }
        return true
    }

    override fun onDetached() {
        attached = false
        inputReady = false
        clearJob?.cancel()
        beginJob?.cancel()
        loadJob?.cancel()
        searchJob?.cancel()
        tonePopup?.dismiss()
        popup.dismissAll()
        keyboardWindow.detachFromEmojiSearch(keyboardHost)
        service.endEmojiSearchInput(editor)
    }

    private inner class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.Holder>() {
        inner class Holder(val key: TextKeyView) : RecyclerView.ViewHolder(key)
        private var items = emptyList<String>()
        fun submit(values: List<String>) {
            items = values
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val key = TextKeyView(context, theme, KeyDef.Appearance.Text(
                displayText = "", textSize = 26f, border = KeyDef.Appearance.Border.Off
            ))
            key.layoutParams = RecyclerView.LayoutParams(context.dp(48), ViewGroup.LayoutParams.MATCH_PARENT)
            key.mainText.setFontTypeFace("key_main_font")
            return Holder(key)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val emoji = items[position]
            holder.key.mainText.text = policy.transform(emoji)
            holder.key.contentDescription = emoji
            holder.key.setOnClickListener { finishSearch(policy.transform(emoji)) }
            holder.key.setOnLongClickListener { showTones(holder.key, emoji) }
        }
    }
}
