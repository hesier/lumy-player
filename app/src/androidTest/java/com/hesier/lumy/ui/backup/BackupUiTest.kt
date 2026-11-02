package com.hesier.lumy.ui.backup

import android.app.Dialog
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.text.method.PasswordTransformationMethod
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hesier.lumy.R
import com.hesier.lumy.data.backup.BackupData
import com.hesier.lumy.net.BackupCipher
import com.hesier.lumy.net.BackupTransferServer
import com.hesier.lumy.ui.settings.SettingsActivity
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.GeneralSecurityException

/** Exercises remote-only navigation without exporting or restoring user preferences. */
class BackupUiTest(private val instrumentation: Instrumentation) {
    fun runAll() {
        checkDeviceCipher()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as SettingsActivity
        try {
            instrumentation.waitForIdleSync()
            await { onMain { activity.hasWindowFocus() } }
            navigateToBackup(activity)
            checkPasswordPanel(activity)
            BackupPasswordTest(instrumentation).runAll(activity)
            checkRestorePreview(activity)
            checkPhoneTransfer(activity)
        } finally {
            onMain { activity.finishAndRemoveTask() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun checkDeviceCipher() {
        val password = "备份密碼🔑123".toCharArray()
        val wrong = "incorrect".toCharArray()
        try {
            val plain = "isolated-device-backup-fixture".toByteArray()
            val encoded = BackupCipher.encrypt(plain, password)
            check(BackupCipher.isEncrypted(encoded))
            check(plain.contentEquals(BackupCipher.decrypt(encoded, password)))
            var rejected = false
            try { BackupCipher.decrypt(encoded, wrong) } catch (_: GeneralSecurityException) { rejected = true }
            check(rejected) { "Device cipher accepted an incorrect password" }
            check("independent backup fixture".toByteArray().contentEquals(BackupCipher.decrypt(encryptedFixture(), password)))
        } finally {
            password.fill('\u0000')
            wrong.fill('\u0000')
        }
    }

    private fun navigateToBackup(activity: SettingsActivity) {
        focusLabel(activity, R.string.settings_section_about, KeyEvent.KEYCODE_DPAD_DOWN)
        onMain { check(activity.findViewById<View>(R.id.categories)?.hasFocus() == true) }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        focusLabel(activity, R.string.settings_version, KeyEvent.KEYCODE_DPAD_UP)
        focusLabel(activity, R.string.backup_title, KeyEvent.KEYCODE_DPAD_DOWN)
        onMain { check(activity.findViewById<View>(R.id.list)?.hasFocus() == true) }
    }

    private fun checkPasswordPanel(activity: SettingsActivity) {
        val origin = onMain { checkNotNull(activity.currentFocus) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        val options = onMain { activeDialog(activity) }
        await { onMain { containsText(options.currentFocus, activity.getString(R.string.backup_create)) } }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        val panel = onMain { activeDialog(activity) as BackupPasswordPanel }
        val password = onMain { panel.view<EditText>(R.id.password) }
        val repeated = onMain { panel.view<EditText>(R.id.repeated) }
        onMain {
            check(panel.isShowing && password.hasFocus())
            check(password.transformationMethod is PasswordTransformationMethod)
            check(repeated.transformationMethod is PasswordTransformationMethod)
            password.setText("test-password-123")
            repeated.setText("test-password-123")
            val ime = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            ime.hideSoftInputFromWindow(password.windowToken, 0)
        }
        await { onMain { ViewCompat.getRootWindowInsets(password)?.isVisible(WindowInsetsCompat.Type.ime()) != true } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        onMain { check(repeated.hasFocus()) { "DOWN did not reach password confirmation" } }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        onMain { check(panel.view<View>(R.id.confirm).hasFocus()) { "DOWN did not reach Continue" } }
        key(KeyEvent.KEYCODE_BACK)
        onMain {
            check(!panel.isShowing)
            check(password.text.isEmpty() && repeated.text.isEmpty())
            check(origin.hasFocus()) { "Password dismissal lost the backup entry focus" }
        }
    }

    private fun checkRestorePreview(activity: SettingsActivity) {
        var restoreCalled = false
        val panel = onMain {
            BackupRestorePanel(activity, BackupData(System.currentTimeMillis(), "test", 0, "{}")) {
                restoreCalled = true
            }.also { it.show() }
        }
        try {
            instrumentation.waitForIdleSync()
            onMain {
                check(panel.view<View>(R.id.cancel).hasFocus()) { "Restore must default to Cancel" }
                check(panel.view<CheckBox>(R.id.settings).isChecked)
                check(panel.view<CheckBox>(R.id.servers).isChecked)
                check(!panel.view<CheckBox>(R.id.devices).isChecked)
            }
            SystemClock.sleep(200)
            val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: onMain {
                val decor = checkNotNull(panel.window).decorView
                Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also {
                    decor.draw(Canvas(it))
                }
            }
            try {
                File(instrumentation.targetContext.cacheDir, "backup-preview-test.png").outputStream().use {
                    check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
            for (id in listOf(R.id.confirm, R.id.devices, R.id.servers, R.id.settings)) {
                key(KeyEvent.KEYCODE_DPAD_UP)
                onMain {
                    check(panel.view<View>(id).hasFocus()) {
                        "UP did not reach restore control $id; actual focus ${panel.currentFocus?.id}"
                    }
                }
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            onMain {
                check(!panel.view<CheckBox>(R.id.settings).isChecked)
                check(!panel.view<CheckBox>(R.id.devices).isEnabled)
                check(!panel.view<CheckBox>(R.id.devices).isChecked)
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            onMain { check(panel.view<View>(R.id.servers).hasFocus()) }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            onMain { check(panel.view<View>(R.id.confirm).hasFocus()) { "Disabled device scope received focus" } }
            key(KeyEvent.KEYCODE_BACK)
            onMain {
                check(!panel.isShowing && !restoreCalled)
                check(containsText(activity.currentFocus, activity.getString(R.string.backup_title)))
            }
        } finally {
            onMain { panel.dismiss() }
        }
    }

    private fun checkPhoneTransfer(activity: SettingsActivity) {
        val fixture = encryptedFixture()
        val download = onMain { BackupTransferPanel(activity, fixture) { error("Download invoked upload callback") }.also { it.show() } }
        try {
            val server = onMain { transferServer(download) }
            val address = URL(server.url)
            val origin = "${address.protocol}://${address.host}:${address.port}"
            val page = request(URL("$origin/")).toString(Charsets.UTF_8)
            check(page.contains(onMain { activity.getString(R.string.backup_web_hint) }))
            check(!page.contains(address.ref)) { "Transfer page embedded the session token" }
            check(fixture.contentEquals(request(URL("$origin/backup"), address.ref)))
            key(KeyEvent.KEYCODE_BACK)
            onMain { check(!download.isShowing && !server.isRunning) }
            checkClosed(address)
        } finally { onMain { download.dismiss() } }

        var uploaded: ByteArray? = null
        val upload = onMain {
            BackupTransferPanel(activity, null) {
                check(Looper.myLooper() == Looper.getMainLooper()) { "Upload callback ran outside the main thread" }
                uploaded = it
            }.also { it.show() }
        }
        try {
            val server = onMain { transferServer(upload) }
            val address = URL(server.url)
            val origin = "${address.protocol}://${address.host}:${address.port}"
            request(URL("$origin/backup"), address.ref, fixture)
            await { onMain { uploaded != null && !upload.isShowing } }
            onMain { check(fixture.contentEquals(uploaded)) { "Upload changed encrypted fixture bytes" } }
            check(!server.isRunning)
            checkClosed(address)
        } finally { onMain { upload.dismiss() } }
    }

    private fun request(url: URL, token: String? = null, body: ByteArray? = null): ByteArray {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.useCaches = false
            if (token != null) connection.setRequestProperty("X-Lumy-Token", token)
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Origin", "${url.protocol}://${url.host}:${url.port}")
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            check(connection.responseCode == 200) { "Transfer HTTP status ${connection.responseCode}" }
            return connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }

    private fun checkClosed(address: URL) {
        var reachable = false
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address.host, address.port), 1_000)
                reachable = true
            }
        } catch (_: IOException) { /* A dismissed transfer must stop listening. */ }
        check(!reachable) { "Dismissed transfer still accepts connections" }
    }

    private fun transferServer(panel: BackupTransferPanel): BackupTransferServer =
        checkNotNull(BackupTransferPanel::class.java.getDeclaredField("server").apply { isAccessible = true }
            .get(panel) as? BackupTransferServer) { "Transfer panel did not start its LAN server" }

    private fun encryptedFixture(): ByteArray {
        // Independent Python hashlib + AESGCM fixture verifies UTF-8 portability on Android.
        val hex = "4c554d59454e430a01000927c0000102030405060708090a0b0c0d0e0f" +
            "101112131415161718191a1b968bc0175099b3f2494769eb33ca4ed71e1fd8c02e66007a" +
            "c63e3c810a0daf29cec2cf26597cb7d710fb"
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun focusLabel(activity: SettingsActivity, label: Int, direction: Int) {
        repeat(20) {
            if (onMain { containsText(activity.currentFocus, activity.getString(label)) }) return
            key(direction)
        }
        error("D-pad navigation did not reach ${activity.getString(label)}; focus=${onMain { activity.currentFocus?.findViewById<TextView>(R.id.label)?.text }}")
    }

    private fun containsText(view: View?, text: String): Boolean {
        if (view is TextView && view.text.toString() == text) return true
        return view is ViewGroup && (0 until view.childCount).any { containsText(view.getChildAt(it), text) }
    }

    private fun activeDialog(activity: SettingsActivity): Dialog {
        // Inspect the dialog opened by actual D-pad input without changing production visibility.
        val controller = SettingsActivity::class.java.getDeclaredField("backup").apply { isAccessible = true }.get(activity)
        return BackupController::class.java.getDeclaredField("dialog").apply { isAccessible = true }.get(controller) as Dialog
    }

    private fun <T : View> Dialog.view(id: Int): T = checkNotNull(findViewById<T>(id))

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        SystemClock.sleep(250)
        instrumentation.waitForIdleSync()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for backup UI" }
            SystemClock.sleep(40)
            instrumentation.waitForIdleSync()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
