package com.hesier.lumy.ui.backup

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ViewBackupTransferBinding
import com.hesier.lumy.net.BackupTransferServer
import com.hesier.lumy.ui.login.qrBitmap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

internal class BackupTransferPanel(
    private val activity: FragmentActivity,
    private val download: ByteArray?,
    private val onUploaded: (ByteArray) -> Unit,
) : Dialog(activity, R.style.Theme_Lumy_Panel) {
    private var server: BackupTransferServer? = null
    private var expiry: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ViewBackupTransferBinding.inflate(LayoutInflater.from(context))
        setContentView(b.root)
        window?.apply {
            setLayout((480 * context.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        b.title.setText(if (download == null) R.string.backup_restore else R.string.backup_create)
        b.close.setOnClickListener { dismiss() }
        b.close.requestFocus()
        setOnDismissListener { expiry?.cancel(); server?.close(); server = null }
        try {
            val receiver = BackupTransferServer(lanAddress(), backupPage(context, download != null), download, { bytes ->
                activity.runOnUiThread {
                    if (isShowing && !activity.isFinishing && !activity.isDestroyed) {
                        dismiss()
                        onUploaded(bytes)
                    }
                }
            })
            server = receiver
            b.qr.setImageBitmap(qrBitmap(receiver.url))
            expiry = activity.lifecycleScope.launch {
                while (receiver.isRunning) delay(500)
                b.qr.visibility = View.GONE
                b.status.setText(R.string.backup_transfer_expired)
            }
        } catch (_: Exception) {
            server?.close()
            b.qr.visibility = View.GONE
            b.status.setText(R.string.phone_input_unavailable)
        }
    }

    private fun lanAddress(): Inet4Address = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        ?: error("No LAN address")
}
