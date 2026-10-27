package com.hesier.lumy.ui.login

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hesier.lumy.R
import com.hesier.lumy.core.pairing.PhoneInputServer
import com.hesier.lumy.databinding.ViewPhoneInputPanelBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

internal class PhoneInputPanel(
    private val activity: FragmentActivity,
    private val onReceived: (PhoneInputServer.ServerInput) -> Unit,
) : Dialog(activity, R.style.Theme_Lumy_Panel) {
    private var server: PhoneInputServer? = null
    private var expiry: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ViewPhoneInputPanelBinding.inflate(LayoutInflater.from(context))
        setContentView(b.root)
        window?.apply {
            setLayout((800 * context.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        b.close.setOnClickListener { dismiss() }
        b.close.requestFocus()
        setOnDismissListener {
            expiry?.cancel()
            server?.close()
            server = null
        }
        try {
            // Bind to one LAN interface, never a VPN/public/wildcard address.
            val address = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
                ?: error("No LAN address")
            val receiver = PhoneInputServer(address, phoneInputPage(context), { input ->
                activity.runOnUiThread {
                    if (isShowing && !activity.isFinishing && !activity.isDestroyed) {
                        onReceived(input)
                        dismiss()
                    }
                }
            })
            server = receiver
            val size = 660
            val matrix = QRCodeWriter().encode(receiver.url, BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
            b.qr.setImageBitmap(Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888))
            b.status.text = receiver.url
            expiry = activity.lifecycleScope.launch {
                while (receiver.isRunning) delay(500)
                if (isShowing) {
                    b.qr.setImageDrawable(null)
                    b.status.setText(R.string.phone_input_expired)
                }
            }
        } catch (_: Exception) {
            server?.close()
            b.status.setText(R.string.phone_input_unavailable)
        }
    }
}
