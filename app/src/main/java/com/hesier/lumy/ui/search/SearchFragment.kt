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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.R
import com.hesier.lumy.databinding.FragmentSearchBinding
import com.hesier.lumy.ui.home.HomeHost
import com.hesier.lumy.ui.home.ItemChangeListener
import com.hesier.lumy.ui.widget.HubRowAdapter
import com.hesier.lumy.ui.widget.StartAlignedLayoutManager
import com.hesier.lumy.ui.widget.attempt
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.rows.layoutManager = StartAlignedLayoutManager(requireContext(), RecyclerView.VERTICAL)
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
                rows.post { rows.requestFocus() }
            }
            submit
        }
        binding.query.requestFocus()
        binding.query.post { showKeyboard() }
    }

    override fun onDestroyView() {
        adapter = null
        _binding = null
        super.onDestroyView()
    }

    override fun onItemChanged(item: MediaItem) = schedule(binding.query.text.toString(), 0)

    private fun schedule(query: String, delayMs: Long) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            binding.rows.adapter = null
            binding.empty.visibility = View.GONE
            return
        }
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(delayMs)
            binding.progress.visibility = View.VISIBLE
            val result = attempt { host.server.search(q) }
            binding.progress.visibility = View.GONE
            val hubs = result.getOrElse {
                binding.empty.text = getString(R.string.load_failed, it.readableMessage())
                binding.empty.visibility = View.VISIBLE
                return@launch
            }
            binding.empty.text = getString(R.string.search_empty, q)
            binding.empty.visibility = if (hubs.isEmpty()) View.VISIBLE else View.GONE
            val existing = adapter
            if (existing != null && binding.rows.adapter === existing) {
                existing.update(hubs)
            } else {
                adapter = HubRowAdapter(host.server, hubs, host::showBackdrop, host::openItem, host::showItemMenu)
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
