package com.hesier.lumy.ui.backup

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor

/** Disabled outside this test so it cannot intercept user document requests. */
class BackupPickerFixture : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        finish()
    }
}

class BackupDocumentsTest(private val instrumentation: Instrumentation) {
    fun runAll() {
        val context = instrumentation.targetContext
        val fixture = ComponentName(instrumentation.context, BackupPickerFixture::class.java)
        val inputs = listOf(
            Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream"),
            Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),
        )
        for (intent in inputs) {
            try {
                val selected = BackupDocuments.resolve(context, intent)
                check(selected.component?.className?.endsWith(".Stubs\$DocumentsStub") != true)
            } catch (_: ActivityNotFoundException) {
                // An absent picker is expected on stock TV emulator images.
            }
        }
        shell("pm enable ${fixture.flattenToString()}")
        try {
            for (intent in inputs) {
                val resolved = BackupDocuments.resolve(context, intent)
                check(fixture == resolved.component) { "TV stub displaced the real document handler" }
            }
            check(fixture == BackupCreateDocument().createIntent(context, "fixture.lumy").component)
            check(fixture == BackupOpenDocument().createIntent(context, arrayOf("*/*")).component)
        } finally { shell("pm default-state ${fixture.flattenToString()}") }
    }

    private fun shell(command: String) {
        val ownPackage = instrumentation.context.packageName
        val asOwner = "run-as $ownPackage /system/bin/$command"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(asOwner)).use {
            check(it.bufferedReader().readText().contains("new state:"))
        }
    }
}
