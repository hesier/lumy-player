package com.hesier.lumy.ui.login

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import com.hesier.lumy.ui.ThemedActivity
import androidx.lifecycle.lifecycleScope
import com.hesier.lumy.plex.PlexAccount
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ActivityLoginBinding
import com.hesier.lumy.ui.servers.ServersActivity
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.readableMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PIN 登录：TV 上不输密码，用户用手机扫码直接授权，或打开 plex.tv/link 输入屏幕上的代码。
 * 只在页面可见时轮询，代码过期后自动换新的。
 */
class LoginActivity : ThemedActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val app get() = LumyApp.instance
    private var pinJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.link.text = PlexAccount.LINK_URL
        binding.refresh.setOnClickListener { startPin() }
        binding.refresh.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        startPin()
    }

    override fun onStop() {
        pinJob?.cancel()
        super.onStop()
    }

    private fun startPin() {
        pinJob?.cancel()
        pinJob = lifecycleScope.launch {
            showCode(null)
            binding.status.setText(R.string.login_waiting)
            val pin = attempt { app.plexAccount.createPin() }.getOrElse {
                binding.codeProgress.visibility = View.GONE
                binding.status.text = getString(R.string.login_error, it.readableMessage())
                return@launch
            }
            showCode(pin.code)
            binding.qr.setImageBitmap(qrBitmap(app.plexAccount.linkUrl(pin)))
            val deadline = SystemClock.elapsedRealtime() + (pin.expiresIn ?: DEFAULT_EXPIRY_SEC) * 1000L
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(POLL_MS)
                // 单次轮询失败（网络抖动）不中断，下一轮再试
                val token = attempt { app.plexAccount.checkPin(pin.id) }.getOrNull()?.authToken
                if (token != null) {
                    onAuthorized(token)
                    return@launch
                }
            }
            startPin()
        }
    }

    private fun showCode(code: String?) {
        binding.code.text = code.orEmpty()
        binding.code.visibility = if (code == null) View.INVISIBLE else View.VISIBLE
        binding.codeProgress.visibility = if (code == null) View.VISIBLE else View.GONE
        // 二维码和代码绑定，换码时先清掉旧的；没码时隐藏，免得显示一块空白
        if (code == null) binding.qr.setImageDrawable(null)
        binding.qr.visibility = if (code == null) View.INVISIBLE else View.VISIBLE
    }

    private fun onAuthorized(token: String) {
        // 修复已有服务器时重新登录：登录后仍然替换那一项，而不是新增一台
        val replaceId = intent.getStringExtra(EXTRA_REPLACE_ID)
        val preferredId = intent.getStringExtra(EXTRA_PREFERRED_ID)
        startActivity(ServersActivity.intent(this, token, autoConnect = true, preferredId = preferredId, replaceId = replaceId))
        finish()
    }

    companion object {
        private const val POLL_MS = 2_000L
        private const val DEFAULT_EXPIRY_SEC = 900
        private const val EXTRA_REPLACE_ID = "replace_id"
        private const val EXTRA_PREFERRED_ID = "preferred_id"

        /** [replaceId] / [preferredId] 见 [ServersActivity.intent] */
        fun intent(context: Context, preferredId: String? = null, replaceId: String? = null): Intent =
            Intent(context, LoginActivity::class.java)
                .putExtra(EXTRA_PREFERRED_ID, preferredId)
                .putExtra(EXTRA_REPLACE_ID, replaceId)
    }
}
