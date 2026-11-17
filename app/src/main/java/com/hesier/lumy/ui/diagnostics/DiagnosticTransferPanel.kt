package com.hesier.lumy.ui.diagnostics

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hesier.lumy.R
import com.hesier.lumy.databinding.ViewBackupTransferBinding
import com.hesier.lumy.net.BackupTransferServer
import com.hesier.lumy.ui.login.qrBitmap
import com.hesier.lumy.util.diagnostics.PlaybackDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

internal class DiagnosticTransferPanel(private val activity: FragmentActivity) : Dialog(activity, R.style.Theme_Lumy_Panel) {
    private var server: BackupTransferServer? = null
    private var job: Job? = null
    private val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) { dismiss() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ViewBackupTransferBinding.inflate(LayoutInflater.from(context))
        setContentView(b.root)
        window?.apply {
            setLayout((480 * context.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        b.title.setText(R.string.diagnostics_export)
        b.status.setText(R.string.diagnostics_detail)
        b.close.setOnClickListener { dismiss() }
        b.close.requestFocus()
        activity.lifecycle.addObserver(observer)
        setOnDismissListener {
            job?.cancel()
            server?.close()
            server = null
            activity.lifecycle.removeObserver(observer)
        }
        job = activity.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { PlaybackDiagnostics.export() }
            if (!isShowing) return@launch
            try {
                val receiver = BackupTransferServer(lanAddress(), page(), bytes, downloadFilename = "Lumy-diagnostics.json")
                server = receiver
                b.qr.setImageBitmap(qrBitmap(receiver.url))
                while (receiver.isRunning) delay(500)
                b.qr.visibility = View.GONE
                b.status.setText(R.string.backup_transfer_expired)
            } catch (_: Exception) {
                server?.close()
                b.qr.visibility = View.GONE
                b.status.setText(R.string.phone_input_unavailable)
            }
        }
    }

    private fun page(): String {
        fun text(id: Int) = context.getString(id).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
        return """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${text(R.string.diagnostics_export)}</title>
            <style>body{font:18px system-ui;padding:24px;background:#1c1917;color:#fafaf9}main{max-width:480px;margin:auto}p{line-height:1.6}button{font:inherit;padding:16px;width:100%}</style>
            </head><body><main><h1>${text(R.string.diagnostics_export)}</h1><p>${text(R.string.diagnostics_detail)}</p>
            <button id="download">${text(R.string.backup_download)}</button><p id="status" role="status"></p>
            <template id="success">${text(R.string.backup_download_started)}</template><template id="failure">${text(R.string.backup_web_error)}</template>
            <script>
            const token=location.hash.slice(1),button=document.getElementById('download');
            if(!token)button.disabled=true;
            button.onclick=async()=>{button.disabled=true;try{
              const response=await fetch('/backup',{headers:{'X-Lumy-Token':token},credentials:'omit',cache:'no-store'});
              if(!response.ok)throw new Error();
              const url=URL.createObjectURL(await response.blob()),link=document.createElement('a');
              link.href=url;link.download='Lumy-diagnostics.json';document.body.appendChild(link);link.click();link.remove();
              setTimeout(()=>URL.revokeObjectURL(url),60000);
              document.getElementById('status').textContent=document.getElementById('success').content.textContent;
            }catch(error){document.getElementById('status').textContent=document.getElementById('failure').content.textContent;}button.disabled=false;};
            </script></main></body></html>
        """.trimIndent()
    }

    private fun lanAddress(): Inet4Address = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress } ?: error("No LAN address")
}
