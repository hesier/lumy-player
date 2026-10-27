package com.hesier.lumy.ui.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.hesier.lumy.ui.ThemedActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.plex.PlexHttpException
import com.hesier.lumy.plex.PlexResource
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ActivityServersBinding
import com.hesier.lumy.databinding.ItemServerBinding
import com.hesier.lumy.ui.home.HomeActivity
import com.hesier.lumy.ui.login.LoginActivity
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.readableMessage
import kotlinx.coroutines.launch

class ServersActivity : ThemedActivity() {

    private lateinit var binding: ActivityServersBinding
    private val app get() = LumyApp.instance
    private val adapter = ServerAdapter(::connect)
    private var connecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.retry.setOnClickListener { load() }
        binding.otherAccount.setOnClickListener { otherAccount() }
        load()
    }

    private val token: String? get() = intent.getStringExtra(EXTRA_TOKEN)

    private fun load() {
        val token = token ?: return otherAccount()
        binding.progress.visibility = View.VISIBLE
        binding.status.visibility = View.GONE
        binding.retry.visibility = View.GONE
        lifecycleScope.launch {
            val result = attempt { app.plexAccount.servers(token) }
            binding.progress.visibility = View.GONE
            val servers = result.getOrElse {
                // 账号 token 失效（在 plex.tv 上退出了这台设备），重新登录
                if (it is PlexHttpException && it.code == 401) return@launch otherAccount()
                showStatus(getString(R.string.load_failed, it.readableMessage()), retry = true)
                return@launch
            }
            if (servers.isEmpty()) {
                showStatus(getString(R.string.servers_empty), retry = true)
                return@launch
            }
            adapter.submit(servers)
            val preferred = intent.getStringExtra(EXTRA_PREFERRED_ID)?.let { id -> servers.firstOrNull { it.clientIdentifier == id } }
            val auto = preferred ?: servers.singleOrNull()?.takeIf { intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false) }
            if (auto != null) connect(auto) else binding.list.post { binding.list.getChildAt(0)?.requestFocus() }
        }
    }

    private fun connect(resource: PlexResource) {
        if (connecting) return
        connecting = true
        showStatus(getString(R.string.servers_connecting, resource.name), retry = false)
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val session = attempt { app.plexResolver.resolve(resource) }.getOrNull()
            connecting = false
            binding.progress.visibility = View.GONE
            if (session == null) {
                showStatus(getString(R.string.servers_unreachable, resource.name), retry = false)
                binding.list.getChildAt(adapter.indexOf(resource).coerceAtLeast(0))?.requestFocus()
                return@launch
            }
            val replaceId = intent.getStringExtra(EXTRA_REPLACE_ID)
            if (replaceId != null) app.replaceServer(replaceId, session, token) else app.addServer(session, accountToken = token)
            HomeActivity.restart(this@ServersActivity)
            finish()
        }
    }

    private fun showStatus(text: String, retry: Boolean) {
        binding.status.visibility = View.VISIBLE
        binding.status.text = text
        binding.retry.visibility = if (retry) View.VISIBLE else View.GONE
        if (retry) binding.retry.requestFocus()
    }

    /** 换账号 / 账号失效重新登录：带上要替换的那一项，登录后不会多出一台重复的服务器 */
    private fun otherAccount() {
        startActivity(LoginActivity.intent(this, intent.getStringExtra(EXTRA_PREFERRED_ID), intent.getStringExtra(EXTRA_REPLACE_ID)))
        finish()
    }

    companion object {
        /** plex.tv 账号 token */
        private const val EXTRA_TOKEN = "token"
        /** 只有一台服务器时直接连接 */
        private const val EXTRA_AUTO_CONNECT = "auto_connect"
        /** 重新连接指定服务器（例如地址变了） */
        private const val EXTRA_PREFERRED_ID = "preferred_id"
        /** 选中的服务器替换这一项（本地记录 id），而不是新增 */
        private const val EXTRA_REPLACE_ID = "replace_id"

        fun intent(
            context: Context,
            token: String,
            autoConnect: Boolean = false,
            preferredId: String? = null,
            replaceId: String? = null,
        ): Intent =
            Intent(context, ServersActivity::class.java)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_AUTO_CONNECT, autoConnect)
                .putExtra(EXTRA_PREFERRED_ID, preferredId)
                .putExtra(EXTRA_REPLACE_ID, replaceId)
    }
}

private class ServerAdapter(private val onClick: (PlexResource) -> Unit) : RecyclerView.Adapter<ServerAdapter.Holder>() {
    private var items: List<PlexResource> = emptyList()

    fun submit(list: List<PlexResource>) {
        items = list
        notifyDataSetChanged()
    }

    fun indexOf(resource: PlexResource) = items.indexOf(resource)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val b: ItemServerBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(r: PlexResource) {
            val ctx = b.root.context
            b.name.text = r.name
            b.detail.text = listOf(
                ctx.getString(if (r.owned) R.string.servers_owned else R.string.servers_shared),
                ctx.getString(if (r.presence) R.string.servers_online else R.string.servers_offline),
            ).joinToString("  ·  ")
            b.presence.alpha = if (r.presence) 1f else 0.2f
            b.root.setOnClickListener { onClick(r) }
        }
    }
}
