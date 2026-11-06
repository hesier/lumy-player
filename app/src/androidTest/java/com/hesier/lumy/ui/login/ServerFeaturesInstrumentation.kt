package com.hesier.lumy.ui.login

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.hesier.lumy.R
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.data.SessionStore

/** Exercises the live server pairing UI without authorizing or persisting an account. */
class ServerFeaturesInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            verifyQuickConnect()
            result.putString("stream", "Quick Connect live detection, D-pad entry, pairing dialog, Back cancellation, input restoration and session preservation passed\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun verifyQuickConnect() {
        val preferences = targetContext.getSharedPreferences("session", Context.MODE_PRIVATE)
        val savedPreferences = preferences.all.toMap()
        val address = SessionStore(targetContext).servers.firstOrNull { it.kind == ServerKind.JELLYFIN }
            ?.session?.baseUrl ?: error("A saved Jellyfin server is required for this live UI check")
        setInTouchMode(false)
        val activity = startActivitySync(DirectLoginActivity.intent(targetContext, ServerKind.JELLYFIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as DirectLoginActivity
        try {
            await("login activity window") { activity.hasWindowFocus() }
            main {
                activity.findViewById<EditText>(R.id.address).setText(address)
                check(activity.findViewById<View>(R.id.quick_connect).requestFocus())
            }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("server detection and enabled input") {
                activity.findViewById<TextView>(R.id.quick_connect).text == activity.getString(R.string.quick_connect_login) &&
                    activity.findViewById<EditText>(R.id.address).isEnabled
            }
            checkDpadPairing(activity)
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("pairing cancelled and inputs restored") {
                dialog(activity) == null && listOf(R.id.address, R.id.username, R.id.password, R.id.name)
                    .all { activity.findViewById<EditText>(it).isEnabled } && activity.hasWindowFocus()
            }
            main {
                check(activity.findViewById<View>(R.id.quick_connect).isFocused) { "Back did not restore Quick Connect focus" }
                check(activity.findViewById<EditText>(R.id.username).text.isEmpty())
                check(activity.findViewById<EditText>(R.id.password).text.isEmpty())
            }
            check(savedPreferences == preferences.all) { "Pairing UI changed saved server sessions" }
        } finally {
            main { activity.finishAndRemoveTask() }
            waitForIdleSync()
        }
    }

    private fun checkDpadPairing(activity: DirectLoginActivity) {
        main { check(activity.findViewById<View>(R.id.refresh).requestFocus()) }
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        waitForIdleSync()
        main {
            check(activity.findViewById<View>(R.id.quick_connect).isFocused) {
                "D-pad Up from phone refresh did not focus Quick Connect"
            }
        }
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await("Quick Connect pairing dialog") { dialog(activity)?.isShowing == true }
        main {
            val pairing = checkNotNull(dialog(activity))
            val message = pairing.findViewById<TextView>(android.R.id.message).text.toString()
            check(Regex("^[A-Za-z0-9]{6}\\s").containsMatchIn(message)) { "Pairing dialog does not show a six-character code" }
            check(pairing.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled) { "Cancel action is unavailable" }
            check(listOf(R.id.address, R.id.username, R.id.password, R.id.name)
                .none { activity.findViewById<EditText>(it).isEnabled }) { "Pairing inputs remain editable" }
        }
    }

    private fun dialog(activity: DirectLoginActivity): AlertDialog? =
        DirectLoginActivity::class.java.getDeclaredField("quickDialog").run {
            isAccessible = true
            get(activity) as? AlertDialog
        }

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (main(condition)) return
            Thread.sleep(50)
        }
        error("Timed out waiting for $label")
    }
}
