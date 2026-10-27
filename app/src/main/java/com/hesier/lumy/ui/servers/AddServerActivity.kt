package com.hesier.lumy.ui.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.hesier.lumy.ui.ThemedActivity
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ActivityAddServerBinding
import com.hesier.lumy.databinding.ItemServerBinding
import com.hesier.lumy.ui.icon
import com.hesier.lumy.ui.label
import com.hesier.lumy.ui.login.DirectLoginActivity
import com.hesier.lumy.ui.login.LoginActivity

/** 选择要添加的服务器类型。首次启动时也从这里开始 */
class AddServerActivity : ThemedActivity() {

    private lateinit var binding: ActivityAddServerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddServerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.plex.bind(ServerKind.PLEX, R.string.kind_plex_detail) {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        binding.jellyfin.bind(ServerKind.JELLYFIN, R.string.kind_jellyfin_detail) {
            startActivity(DirectLoginActivity.intent(this, ServerKind.JELLYFIN))
        }
        binding.emby.bind(ServerKind.EMBY, R.string.kind_emby_detail) {
            startActivity(DirectLoginActivity.intent(this, ServerKind.EMBY))
        }
        binding.plex.root.requestFocus()
    }

    private fun ItemServerBinding.bind(kind: ServerKind, detail: Int, onClick: () -> Unit) {
        name.text = kind.label(root.context)
        this.detail.setText(detail)
        // 品牌图标保留原色，不用列表项的着色
        icon.imageTintList = null
        icon.setImageResource(kind.icon)
        presence.visibility = View.GONE
        root.setOnClickListener { onClick() }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, AddServerActivity::class.java)
    }
}
