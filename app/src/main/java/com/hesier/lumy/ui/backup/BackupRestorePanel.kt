package com.hesier.lumy.ui.backup

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import com.hesier.lumy.R
import com.hesier.lumy.data.backup.BackupData
import com.hesier.lumy.data.backup.RestoreOptions
import com.hesier.lumy.databinding.ViewBackupRestoreBinding
import java.text.DateFormat
import java.util.Date

internal class BackupRestorePanel(
    context: Context,
    private val data: BackupData,
    private val onRestore: (RestoreOptions) -> Unit,
) : Dialog(context, R.style.Theme_Lumy_Panel) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ViewBackupRestoreBinding.inflate(LayoutInflater.from(context))
        setContentView(b.root)
        window?.apply {
            setLayout((540 * context.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
        }
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(data.createdAt))
        b.summary.text = context.getString(R.string.backup_summary, date, data.appVersion, data.serverCount,
            context.getString(R.string.backup_credentials_present))
        b.settings.setOnCheckedChangeListener { _, checked ->
            b.devices.isEnabled = checked
            if (!checked) b.devices.isChecked = false
            b.servers.nextFocusDownId = if (checked) b.devices.id else b.confirm.id
            b.confirm.nextFocusUpId = if (checked) b.devices.id else b.servers.id
        }
        b.confirm.setOnClickListener {
            if (!b.settings.isChecked && !b.servers.isChecked) {
                Toast.makeText(context, R.string.backup_scope_empty, Toast.LENGTH_SHORT).show()
            } else {
                dismiss()
                onRestore(RestoreOptions(b.settings.isChecked, b.servers.isChecked, b.devices.isChecked))
            }
        }
        b.cancel.setOnClickListener { dismiss() }
        b.settings.nextFocusUpId = b.settings.id
        b.settings.nextFocusDownId = b.servers.id
        b.servers.nextFocusUpId = b.settings.id
        b.servers.nextFocusDownId = b.devices.id
        b.devices.nextFocusUpId = b.servers.id
        b.devices.nextFocusDownId = b.confirm.id
        b.confirm.nextFocusUpId = b.devices.id
        b.confirm.nextFocusDownId = b.cancel.id
        b.cancel.nextFocusUpId = b.confirm.id
        b.cancel.nextFocusDownId = b.cancel.id
        b.cancel.requestFocus()
    }
}
