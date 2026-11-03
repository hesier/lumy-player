package com.hesier.lumy.ui.backup

import android.app.Dialog
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.data.backup.BackupData
import com.hesier.lumy.data.backup.BackupArchive
import com.hesier.lumy.data.backup.BackupStore
import com.hesier.lumy.data.backup.RestoreOptions
import com.hesier.lumy.net.BackupCipher
import com.hesier.lumy.ui.home.HomeActivity
import com.hesier.lumy.ui.widget.Option
import com.hesier.lumy.ui.widget.OptionsPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Create during Activity.onCreate so document launchers register before STARTED. */
internal class BackupController(private val activity: FragmentActivity) : DefaultLifecycleObserver {
    private val store = BackupStore(activity.applicationContext)
    private val archive = BackupArchive(activity.applicationContext)
    private var dialog: Dialog? = null
    private var transfer: BackupTransferPanel? = null
    private var pendingExport: ByteArray? = null
    private var busy = false
    private var progress: Dialog? = null
    private val saveFile = activity.registerForActivityResult(BackupCreateDocument()) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) save(uri, bytes)
    }
    private val openFile = activity.registerForActivityResult(BackupOpenDocument()) { uri ->
        if (uri != null) work(R.string.backup_invalid, {
            activity.contentResolver.openInputStream(uri)?.use {
                val bytes = it.readBytesLimited()
                require(bytes.isNotEmpty())
                bytes
            } ?: error("Cannot read document")
        }, ::open)
    }

    init { activity.lifecycle.addObserver(this) }

    fun show() = options(R.string.backup_title, listOf(
        Option(text(R.string.backup_create), text(R.string.backup_create_detail)),
        Option(text(R.string.backup_restore), text(R.string.backup_restore_detail)),
    )) { if (it == 0) create() else restore() }

    fun restore() = chooseTransport { phone ->
        if (phone) showTransfer(null, ::open)
        else try { openFile.launch(arrayOf("*/*")) } catch (_: ActivityNotFoundException) { message(R.string.backup_no_picker) }
    }

    private fun create() = showPassword(true, ::export)

    private fun export(password: CharArray) {
        work(R.string.backup_failed, {
            try {
                archive.export(password)
            } finally { password.fill('\u0000') }
        }) { bytes ->
            chooseTransport { phone ->
                if (phone) showTransfer(bytes) {}
                else {
                    pendingExport = bytes
                    val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
                    try { saveFile.launch("Lumy-backup-$stamp.lumy") } catch (_: ActivityNotFoundException) {
                        pendingExport = null
                        message(R.string.backup_no_picker)
                    }
                }
            }
        }
    }

    private fun open(bytes: ByteArray) {
        if (BackupCipher.isEncrypted(bytes)) {
            showPassword(false) { password ->
                work(R.string.backup_decrypt_failed, {
                    try {
                        archive.inspect(bytes, password)
                    } finally { password.fill('\u0000') }
                }, ::preview)
            }
        } else work(R.string.backup_invalid, { archive.inspect(bytes) }, ::preview)
    }

    private fun preview(data: BackupData) {
        dialog = BackupRestorePanel(activity, data) { confirm(data, it) }.also { it.show() }
    }

    private fun confirm(data: BackupData, scope: RestoreOptions) = options(R.string.backup_overwrite_title, listOf(
        Option(text(R.string.cancel), selected = true),
        Option(text(R.string.backup_restore_confirm), text(R.string.backup_overwrite_detail)),
    )) { if (it == 1) work(R.string.backup_restore_failed, { store.restore(data, scope) }) {
        val app = LumyApp.instance
        app.setLanguage(app.settings.language)
        app.switchTo(app.store.current?.id.orEmpty())
        message(R.string.backup_restored)
        HomeActivity.restart(activity)
    } }

    private fun chooseTransport(onPick: (Boolean) -> Unit) = options(R.string.backup_transport, listOf(
        Option(text(R.string.backup_phone), text(R.string.backup_phone_detail)),
        Option(text(R.string.backup_file), text(R.string.backup_file_detail)),
    )) { onPick(it == 0) }

    private fun save(uri: Uri, bytes: ByteArray) = work(R.string.backup_failed, {
        activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Cannot write document")
    }) { message(R.string.backup_saved) }

    private fun showPassword(creating: Boolean, onPassword: (CharArray) -> Unit) {
        dialog = BackupPasswordPanel(activity, creating, onPassword).also { it.show() }
    }

    private fun showTransfer(bytes: ByteArray?, received: (ByteArray) -> Unit) {
        transfer?.dismiss()
        transfer = BackupTransferPanel(activity, bytes, received).also { it.show() }
    }

    private fun options(title: Int, rows: List<Option>, onPick: (Int) -> Unit) {
        if (busy || activity.isFinishing || activity.isDestroyed) return
        dialog?.dismiss()
        dialog = OptionsPanel(activity, text(title), rows, onPick = onPick).also { it.show() }
    }

    private fun <T> work(error: Int, block: () -> T, complete: (T) -> Unit) {
        if (busy) return
        busy = true
        @Suppress("DEPRECATION")
        val waiting = ProgressDialog(activity).apply {
            setMessage(text(R.string.backup_working))
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            show()
        }
        progress = waiting
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                busy = false
                waiting.dismiss()
                if (!activity.isFinishing && !activity.isDestroyed) complete(result)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { message(error)
            } finally { busy = false; waiting.dismiss(); progress = null }
        }
    }

    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= BackupCipher.MAX_ENCODED_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun text(id: Int) = activity.getString(id)
    private fun message(id: Int) = Toast.makeText(activity, id, Toast.LENGTH_LONG).show()
    override fun onStop(owner: LifecycleOwner) { transfer?.dismiss(); transfer = null }
    override fun onDestroy(owner: LifecycleOwner) { dialog?.dismiss(); progress?.dismiss(); pendingExport = null }
}
