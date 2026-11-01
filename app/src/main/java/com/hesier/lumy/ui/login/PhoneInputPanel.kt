package com.hesier.lumy.ui.login

import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.hesier.lumy.R
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.net.PhoneInputServer
import com.hesier.lumy.databinding.ViewPhoneInputPanelBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

internal class PhoneInputPanel(
    private val activity: FragmentActivity,
    private val kind: ServerKind,
    private val onReceived: (PhoneInputServer.ServerInput) -> Unit,
) {
    private var server: PhoneInputServer? = null
    private var expiry: Job? = null

    fun start(b: ViewPhoneInputPanelBinding) {
        close()
        b.status.visibility = View.GONE
        b.qr.visibility = View.VISIBLE
        try {
            lateinit var receiver: PhoneInputServer
            receiver = PhoneInputServer(lanAddress(), phoneInputPage(activity, kind), { input ->
                activity.runOnUiThread {
                    if (server === receiver && !activity.isFinishing && !activity.isDestroyed) {
                        expiry?.cancel()
                        showStatus(b, R.string.phone_input_received)
                        onReceived(input)
                    }
                }
            })
            server = receiver
            b.qr.setImageBitmap(qrBitmap(receiver.url))
            expiry = activity.lifecycleScope.launch {
                while (receiver.isRunning) delay(500)
                showStatus(b, R.string.phone_input_expired)
            }
        } catch (_: Exception) {
            close()
            showStatus(b, R.string.phone_input_unavailable)
        }
    }

    fun close() {
        expiry?.cancel()
        expiry = null
        server?.close()
        server = null
    }

    private fun showStatus(b: ViewPhoneInputPanelBinding, message: Int) {
        b.qr.setImageDrawable(null)
        b.qr.visibility = View.INVISIBLE
        b.status.visibility = View.VISIBLE
        b.status.setText(message)
    }

    private fun lanAddress(): Inet4Address = NetworkInterface.getNetworkInterfaces().toList()
        // Bind to one LAN interface, never a VPN/public/wildcard address.
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        ?: error("No LAN address")
}
