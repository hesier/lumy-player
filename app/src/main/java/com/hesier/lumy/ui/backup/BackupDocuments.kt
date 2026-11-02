package com.hesier.lumy.ui.backup

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts

/** TV framework stubs resolve document intents but only show a toast and finish. */
internal object BackupDocuments {
    fun resolve(context: Context, intent: Intent): Intent {
        val manager = context.packageManager
        val candidates = manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo }.filter { info ->
                info.exported && !info.name.endsWith(".Stubs\$DocumentsStub") &&
                    (info.permission == null || manager.checkPermission(info.permission, context.packageName) == PackageManager.PERMISSION_GRANTED)
            }
        if (candidates.isEmpty()) throw ActivityNotFoundException("No document picker")
        val preferred = manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        val chosen = candidates.firstOrNull { it.packageName == preferred?.packageName && it.name == preferred.name }
        val targets = candidates.map { Intent(intent).setComponent(ComponentName(it.packageName, it.name)) }
        return when {
            chosen != null -> Intent(intent).setComponent(ComponentName(chosen.packageName, chosen.name))
            targets.size == 1 -> targets.first()
            else -> Intent.createChooser(targets.first(), null)
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
        }
    }
}

internal class BackupCreateDocument : ActivityResultContracts.CreateDocument("application/octet-stream") {
    override fun createIntent(context: Context, input: String): Intent =
        BackupDocuments.resolve(context, super.createIntent(context, input))
}

internal class BackupOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        BackupDocuments.resolve(context, super.createIntent(context, input))
}
