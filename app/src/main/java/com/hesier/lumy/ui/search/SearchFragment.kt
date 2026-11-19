package com.hesier.lumy.ui.search

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.doOnNextLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.R
import com.hesier.lumy.databinding.FragmentSearchBinding
import com.hesier.lumy.ui.home.HomeHost
import com.hesier.lumy.ui.home.ItemChangeListener
import com.hesier.lumy.ui.widget.HubRowAdapter
import com.hesier.lumy.ui.widget.displayTitle
import com.hesier.lumy.ui.widget.StartAlignedLayoutManager
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.enableDpadNavigation
import com.hesier.lumy.ui.widget.readableMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 边输入边搜索，结果按电影 / 剧集 / 单集分行 */
class SearchFragment : Fragment(), ItemChangeListener {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val host get() = requireActivity() as HomeHost
    private var searchJob: Job? = null
    private var adapter: HubRowAdapter? = null
    private var requestVersion = 0
    private var resultQuery = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.rows.layoutManager = StartAlignedLayoutManager(requireContext(), RecyclerView.VERTICAL)
        binding.query.enableDpadNavigation { direction ->
            when (direction) {
                View.FOCUS_DOWN -> {
                    focusResultRow(0, direction)
                    null
                }
                View.FOCUS_LEFT -> binding.query.focusSearch(direction)
                else -> null
            }
        }
        binding.query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = schedule(s?.toString().orEmpty(), DEBOUNCE_MS)
        })
        binding.query.setOnEditorActionListener { _, actionId, event ->
            val submit = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (submit) {
                schedule(binding.query.text.toString(), 0)
                hideKeyboard()
                // 搜完把焦点移到结果上，遥控器可以直接选
                val rows = binding.rows
                rows.post { if (_binding?.rows === rows) focusResultRow(0, View.FOCUS_DOWN) }
            }
            submit
        }
        // 等搜索框挂载后再接收焦点，让侧边栏随焦点离开自动收起。
        binding.query.post {
            binding.query.requestFocus()
            showKeyboard()
        }
    }

    override fun onDestroyView() {
        requestVersion++
        searchJob?.cancel()
        adapter = null
        _binding = null
        super.onDestroyView()
    }

    override fun onItemChanged(item: MediaItem) = schedule(binding.query.text.toString(), 0)

    internal fun moveResultFocus(focused: View, direction: Int, onMoved: () -> Unit): Boolean {
        val current = _binding ?: return false
        val row = current.rows.findContainingViewHolder(focused) as? HubRowAdapter.Holder ?: return false
        val position = row.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return false
        val onViewAll = row.isViewAllFocused()
        val moved = when (direction) {
            View.FOCUS_UP -> if (position == 0) current.query.requestFocus(direction) else {
                focusResultRow(position - 1, direction, onMoved)
                return true
            }
            View.FOCUS_DOWN -> if (onViewAll) row.focusCards(direction) else {
                focusResultRow(position + 1, direction, onMoved)
                return true
            }
            View.FOCUS_LEFT -> if (onViewAll) row.focusCards(direction) else return false
            View.FOCUS_RIGHT -> if (onViewAll) return true else row.focusViewAllAfterLastCard(focused)
            else -> return false
        }
        if (moved) onMoved()
        return moved
    }

    private fun focusResultRow(position: Int, direction: Int, onMoved: () -> Unit = {}) {
        val rows = _binding?.rows ?: return
        val currentAdapter = rows.adapter ?: return
        if (position !in 0 until currentAdapter.itemCount) return
        val before = activity?.currentFocus
        fun focus() {
            val row = rows.findViewHolderForAdapterPosition(position) as? HubRowAdapter.Holder ?: return
            if (row.focusCards(direction) && activity?.currentFocus !== before) onMoved()
        }
        if (rows.findViewHolderForAdapterPosition(position) != null) {
            focus()
        } else {
            rows.scrollToPosition(position)
            rows.doOnNextLayout {
                if (_binding?.rows === rows && rows.adapter === currentAdapter && activity?.currentFocus === before) focus()
            }
        }
    }

    private fun schedule(query: String, delayMs: Long) {
        searchJob?.cancel()
        val version = ++requestVersion
        val q = query.trim()
        if (q.isEmpty()) {
            binding.progress.visibility = View.GONE
            binding.rows.adapter = null
            binding.empty.visibility = View.GONE
            return
        }
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(delayMs)
            binding.progress.visibility = View.VISIBLE
            val result = attempt { host.server.search(q) }
            if (version != requestVersion || _binding == null) return@launch
            binding.progress.visibility = View.GONE
            val hubs = result.getOrElse {
                binding.empty.text = getString(R.string.load_failed, it.readableMessage())
                binding.empty.visibility = View.VISIBLE
                return@launch
            }
            resultQuery = q
            binding.empty.text = getString(R.string.search_empty, q)
            binding.empty.visibility = if (hubs.isEmpty()) View.VISIBLE else View.GONE
            val existing = adapter
            if (existing != null && binding.rows.adapter === existing) {
                existing.update(hubs)
            } else {
                adapter = HubRowAdapter(host.server, hubs, host::showBackdrop, host::openItem, host::showItemMenu,
                    onViewAll = { hub ->
                        hub.searchScope?.let { scope ->
                            hideKeyboard()
                            startActivity(SearchResultsActivity.intent(requireContext(), resultQuery, scope, hub.displayTitle(requireContext())))
                        }
                    })
                binding.rows.adapter = adapter
            }
        }
    }

    private fun showKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.query, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.query.windowToken, 0)
    }

    private companion object {
        const val DEBOUNCE_MS = 500L
    }
}
