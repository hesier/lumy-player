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
import com.hesier.lumy.jellyfin.JellyfinConnector
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ActivityDirectLoginBinding
import com.hesier.lumy.ui.home.HomeActivity
import com.hesier.lumy.ui.label
import com.hesier.lumy.ui.widget.attempt
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDirectLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val kind = runCatching { ServerKind.valueOf(intent.getStringExtra(EXTRA_KIND)!!) }.getOrDefault(ServerKind.JELLYFIN)
        binding.title.text = getString(R.string.direct_login_title, kind.label(this))
        binding.name.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) connect()
            false
        }
        binding.connect.setOnClickListener { connect() }
        binding.phoneInput.setOnClickListener {
            if (busy || phoneInput?.isShowing == true) return@setOnClickListener
            phoneInput = PhoneInputPanel(this) { input ->
                binding.address.setText(input.address)
                binding.username.setText(input.username)
                binding.password.setText(input.password)
                binding.name.setText(input.name)
                showStatus(getString(R.string.phone_input_received), binding.connect)
            }.also { it.show() }
        }
        binding.address.requestFocus()
    }

    override fun onStop() {
        phoneInput?.dismiss()
        phoneInput = null
        super.onStop()
    }

    private fun connect() {
        if (busy) return
        val address = binding.address.text.toString()
        val username = binding.username.text.toString().trim()
        val password = binding.password.text.toString()
        if (JellyfinConnector.normalizeAddress(address) == null) return showStatus(getString(R.string.direct_login_bad_address), binding.address)
        if (username.isEmpty()) return showStatus(getString(R.string.direct_login_need_username), binding.username)

        busy = true
        binding.progress.visibility = View.VISIBLE
        showStatus(getString(R.string.direct_login_probing), null)
        lifecycleScope.launch {
            val result = run {
                val server = attempt { app.jellyfinConnector.probe(address) }.getOrElse {
                    return@run getString(R.string.direct_login_unreachable, it.readableMessage()) to binding.address
                }
                // 用户可能在地址栏里只写了 IP，显示补全后的地址，方便确认
                binding.address.setText(server.baseUrl)
                showStatus(getString(R.string.direct_login_signing_in, server.info.serverName.ifBlank { server.baseUrl }), null)
                val session = attempt { app.jellyfinConnector.signIn(server, username, password) }.getOrElse {
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
            result?.let { (message, focus) -> showStatus(message, focus) }
        }
    }

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
