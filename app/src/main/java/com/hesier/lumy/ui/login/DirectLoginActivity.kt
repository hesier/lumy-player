package com.hesier.lumy.ui.login

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import com.hesier.lumy.ui.ThemedActivity
import androidx.lifecycle.lifecycleScope
import com.hesier.lumy.core.HttpStatusException
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.core.ServerSession
import com.hesier.lumy.fnos.FnOsConnector
import com.hesier.lumy.jellyfin.JellyfinConnector
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ActivityDirectLoginBinding
import com.hesier.lumy.ui.home.HomeActivity
import com.hesier.lumy.ui.label
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.enableDpadNavigation
import com.hesier.lumy.ui.widget.readableMessage
import kotlinx.coroutines.launch

/**
 * Jellyfin / Emby：输入地址、用户名、密码登录。
 * 先探测地址（顺便确认实际是哪种服务器），再用用户名密码换 token。
 */
class DirectLoginActivity : ThemedActivity() {

    private lateinit var binding: ActivityDirectLoginBinding
    private val app get() = LumyApp.instance
    private var busy = false
    private var phoneInput: PhoneInputPanel? = null
    private val kind by lazy {
        runCatching { ServerKind.valueOf(intent.getStringExtra(EXTRA_KIND)!!) }.getOrDefault(ServerKind.JELLYFIN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDirectLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.title.text = getString(R.string.direct_login_title, kind.label(this))
        if (kind == ServerKind.FNOS) {
            binding.title.maxLines = 2
            binding.hint.setText(R.string.direct_login_fnos_hint)
            binding.address.setHint(R.string.direct_login_fnos_address_hint)
        }
        setupInputNavigation()
        binding.name.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) connect()
            false
        }
        binding.connect.setOnClickListener { connect() }
        phoneInput = PhoneInputPanel(this, kind) { input ->
            if (busy) return@PhoneInputPanel
            binding.address.setText(input.address)
            binding.username.setText(input.username)
            binding.password.setText(input.password)
            binding.name.setText(input.name)
            showStatus(getString(R.string.phone_input_received), binding.connect)
        }
        binding.phoneInput.refresh.setOnClickListener {
            if (!busy) phoneInput?.start(binding.phoneInput)
        }
        binding.address.requestFocus()
    }

    private fun setupInputNavigation() {
        val fields = listOf(binding.address, binding.username, binding.password, binding.name)
        fields.forEachIndexed { index, field ->
            field.enableDpadNavigation { direction ->
                when (direction) {
                    View.FOCUS_UP -> fields.getOrNull(index - 1)
                    View.FOCUS_DOWN -> fields.getOrNull(index + 1) ?: binding.connect
                    View.FOCUS_LEFT -> binding.phoneInput.refresh
                    else -> null
                }
            }
        }
        binding.phoneInput.refresh.apply {
            nextFocusRightId = R.id.address
            nextFocusLeftId = R.id.refresh
            nextFocusUpId = R.id.refresh
            nextFocusDownId = R.id.refresh
        }
        binding.connect.nextFocusUpId = R.id.name
        binding.connect.nextFocusLeftId = R.id.refresh
        binding.connect.nextFocusRightId = R.id.connect
        binding.connect.nextFocusDownId = R.id.connect
    }

    override fun onStart() {
        super.onStart()
        phoneInput?.start(binding.phoneInput)
    }

    override fun onStop() {
        phoneInput?.close()
        super.onStop()
    }

    private fun connect() {
        if (busy) return
        val address = binding.address.text.toString()
        val username = binding.username.text.toString().trim()
        val password = binding.password.text.toString()
        val normalized = if (kind == ServerKind.FNOS) FnOsConnector.normalizeAddress(address)
            else JellyfinConnector.normalizeAddress(address)
        if (normalized == null) return showStatus(getString(R.string.direct_login_bad_address), binding.address)
        if (username.isEmpty()) return showStatus(getString(R.string.direct_login_need_username), binding.username)

        busy = true
        phoneInput?.close()
        binding.progress.visibility = View.VISIBLE
        showStatus(getString(R.string.direct_login_probing), null)
        lifecycleScope.launch {
            val result = run {
                val server = attempt { probe(address) }.getOrElse {
                    return@run getString(R.string.direct_login_unreachable, it.readableMessage()) to binding.address
                }
                // 用户可能在地址栏里只写了 IP，显示补全后的地址，方便确认
                binding.address.setText(server.baseUrl)
                showStatus(getString(R.string.direct_login_signing_in, server.name.ifBlank { server.baseUrl }), null)
                val session = attempt { server.signIn(username, password) }.getOrElse {
                    val wrong = it is HttpStatusException && (it.code == 401 || it.code == 403)
                    return@run getString(if (wrong) R.string.direct_login_wrong_password else R.string.direct_login_failed, it.readableMessage()) to
                        if (wrong) binding.password else binding.connect
                }
                app.addServer(session, userName = username, customName = binding.name.text.toString())
                HomeActivity.restart(this@DirectLoginActivity)
                finish()
                null
            }
            busy = false
            binding.progress.visibility = View.GONE
            result?.let { (message, focus) ->
                phoneInput?.start(binding.phoneInput)
                showStatus(message, focus)
            }
        }
    }

    private suspend fun probe(address: String): LoginServer {
        val fnosAddress = if (kind == ServerKind.FNOS) address else {
            val server = app.jellyfinConnector.probe(address)
            if (server.kind != ServerKind.FNOS) {
                return LoginServer(server.baseUrl, server.info.serverName) { username, password ->
                    app.jellyfinConnector.signIn(server, username, password)
                }
            }
            server.baseUrl
        }
        val server = app.fnosConnector.probe(fnosAddress)
        // Preserve automatic detection when a different server type was selected.
        if (!server.info.operatingSystem.equals("fnOS", ignoreCase = true)) {
            val detected = app.jellyfinConnector.probe(server.baseUrl)
            return LoginServer(detected.baseUrl, detected.info.serverName) { username, password ->
                app.jellyfinConnector.signIn(detected, username, password)
            }
        }
        return LoginServer(server.baseUrl, server.info.serverName) { username, password ->
            app.fnosConnector.signIn(server, username, password)
        }
    }

    private data class LoginServer(
        val baseUrl: String,
        val name: String,
        val signIn: suspend (String, String) -> ServerSession,
    )

    private fun showStatus(text: String, focus: View?) {
        binding.status.visibility = View.VISIBLE
        binding.status.text = text
        focus?.requestFocus()
    }

    companion object {
        private const val EXTRA_KIND = "kind"

        fun intent(context: Context, kind: ServerKind): Intent =
            Intent(context, DirectLoginActivity::class.java).putExtra(EXTRA_KIND, kind.name)
    }
}
