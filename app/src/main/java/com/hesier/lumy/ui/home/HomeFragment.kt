package com.hesier.lumy.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.core.MediaItem
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.databinding.FragmentHomeBinding
import com.hesier.lumy.ui.heroLine
import com.hesier.lumy.ui.widget.HubRowAdapter
import com.hesier.lumy.ui.widget.StartAlignedLayoutManager
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.hide
import com.hesier.lumy.ui.widget.readableMessage
import com.hesier.lumy.ui.widget.showLoading
import com.hesier.lumy.ui.widget.showMessage
import kotlinx.coroutines.launch

class HomeFragment : Fragment(), ItemChangeListener {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val host get() = requireActivity() as HomeHost
    private var rowsAdapter: HubRowAdapter? = null
    private var stale = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.rows.layoutManager = StartAlignedLayoutManager(requireContext(), RecyclerView.VERTICAL)
        binding.rows.setItemViewCacheSize(4)
        // 刷新数据时不做“替换”动画：动画会用新的 View 替换整行，之前记住的焦点卡片就失效了
        (binding.rows.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        load()
    }

    override fun onStop() {
        super.onStop()
        // 去看了详情或播放，回来时“继续观看”可能变了
        stale = true
    }

    override fun onStart() {
        super.onStart()
        if (stale) refresh()
    }

    override fun onItemChanged(item: MediaItem) = refresh()

    /** 静默刷新：保留现有行和焦点，只更新数据 */
    private fun refresh() {
        stale = false
        // 还没有行（之前加载失败或首页为空）：完整加载一次，有内容了才能显示出来
        val adapter = rowsAdapter ?: return load()
        viewLifecycleOwner.lifecycleScope.launch {
            LumyApp.instance.awaitPendingReports()
            val hubs = attempt { host.server.homeHubs() }.getOrNull() ?: return@launch
            if (hubs.isNotEmpty()) {
                adapter.update(hubs)
            } else {
                // 服务端首页清空了，去掉旧卡片
                rowsAdapter = null
                binding.rows.adapter = null
                binding.heroTitle.text = ""
                binding.state.showMessage(getString(R.string.home_empty))
            }
        }
    }

    override fun onDestroyView() {
        rowsAdapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun load() {
        binding.state.showLoading()
        viewLifecycleOwner.lifecycleScope.launch {
            val hubs = attempt { host.server.homeHubs() }.getOrElse {
                binding.state.showMessage(getString(R.string.load_failed, it.readableMessage()), getString(R.string.reconnect)) {
                    host.reconnect()
                }
                return@launch
            }
            if (hubs.isEmpty()) {
                binding.state.showMessage(getString(R.string.home_empty))
                return@launch
            }
            binding.state.hide()
            rowsAdapter = HubRowAdapter(host.server, hubs, ::onItemFocused, host::openItem, host::showItemMenu)
            binding.rows.adapter = rowsAdapter
            // 进入首页时焦点放在第一行第一张卡片上
            binding.rows.post {
                val firstRow = binding.rows.getChildAt(0)?.findViewById<RecyclerView>(R.id.items)
                firstRow?.post { firstRow.getChildAt(0)?.requestFocus() }
            }
        }
    }

    private fun onItemFocused(item: MediaItem) {
        val b = _binding ?: return
        b.heroTitle.text = item.heroLine(requireContext())
        host.showBackdrop(item)
    }
}
