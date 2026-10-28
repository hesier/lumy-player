package com.hesier.lumy.update

import android.app.Dialog
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.hesier.lumy.BuildConfig
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ViewUpdatePanelBinding
import com.hesier.lumy.net.UpdateChecksumException
import com.hesier.lumy.net.UpdateInfo
import com.hesier.lumy.ui.widget.attempt
import com.hesier.lumy.ui.widget.readableMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 右侧弹出的新版本面板：确认键下载，下载完打开系统安装界面。
 * [auto] 为启动时自动检查弹出的，多一个“忽略此版本”。返回键关闭并取消下载
 */
class UpdatePanel(
    private val activity: ComponentActivity,
    private val info: UpdateInfo,
    private val auto: Boolean,
) : Dialog(activity, R.style.Theme_Lumy_Panel) {

    private lateinit var b: ViewUpdatePanelBinding
    private var job: Job? = null
    private var apk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ViewUpdatePanelBinding.inflate(LayoutInflater.from(context))
        setContentView(b.root)
        window?.apply {
            setLayout((PANEL_WIDTH_DP * context.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
        }
        b.title.text = context.getString(R.string.update_title, info.versionName)
        b.summary.text = context.getString(R.string.update_summary, BuildConfig.VERSION_NAME, Formatter.formatShortFileSize(context, info.size))
        b.notes.text = AppUpdater.notes(info)
        b.primary.setText(R.string.update_now)
        b.primary.setOnClickListener { onPrimary() }
        if (auto) {
            b.skip.visibility = View.VISIBLE
            b.skip.setOnClickListener {
                LumyApp.instance.settings.skippedUpdate = info.versionCode
                dismiss()
            }
        }
        b.primary.requestFocus()
    }

    override fun onStop() {
        super.onStop()
        job?.cancel()
    }

    private fun onPrimary() {
        if (job?.isActive == true) return
        apk?.let { return install(it) }
        b.skip.visibility = View.GONE
        showStatus(null)
        var lastPercent = -1
        // 协程回主线程用的是异步消息，可能比之前 post 的进度先执行；结果出来后丢掉迟到的进度
        var finished = false
        b.primary.text = context.getString(R.string.update_downloading, 0)
        job = activity.lifecycleScope.launch {
            val result = attempt {
                AppUpdater.download(info) { done, total ->
                    val percent = (done * 100 / total.coerceAtLeast(1)).toInt()
                    if (percent == lastPercent) return@download
                    lastPercent = percent
                    b.root.post {
                        if (finished) return@post
                        b.primary.text = context.getString(R.string.update_downloading, percent)
                        showStatus(context.getString(
                            R.string.update_progress,
                            Formatter.formatShortFileSize(context, done),
                            Formatter.formatShortFileSize(context, total),
                        ))
                    }
                }
            }
            finished = true
            result.onSuccess {
                apk = it
                b.primary.setText(R.string.update_install)
                showStatus(null)
                install(it)
            }.onFailure { e ->
                b.primary.setText(R.string.update_retry)
                val message = if (e is UpdateChecksumException) context.getString(R.string.update_checksum_failed)
                else context.getString(R.string.update_download_failed, e.readableMessage())
                // 直连失败时提示可以换镜像
                val hint = if (LumyApp.instance.settings.useUpdateMirror) "" else "\n" + context.getString(R.string.update_mirror_hint)
                showStatus(message + hint)
            }
        }
    }

    private fun install(file: File) {
        when (AppUpdater.install(activity, file)) {
            InstallResult.STARTED -> showStatus(null)
            InstallResult.NEEDS_PERMISSION -> showStatus(context.getString(R.string.update_allow_install))
            InstallResult.UNAVAILABLE -> showStatus(context.getString(R.string.update_install_unavailable, AppUpdater.RELEASES_URL))
        }
    }

    private fun showStatus(text: String?) {
        b.status.text = text
        b.status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private companion object {
        const val PANEL_WIDTH_DP = 640
    }
}
