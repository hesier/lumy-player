package com.hesier.lumy.ui.login

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hesier.lumy.LumyApp
import com.hesier.lumy.R
import com.hesier.lumy.core.ServerKind
import com.hesier.lumy.data.SavedServer
import com.hesier.lumy.ui.home.HomeActivity

/** Live account UI checks. PINs are supplied through instrumentation arguments only. */
class PlexHomeUiInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    private val app get() = LumyApp.instance
    private var lastResumed: Activity? = null
    private val resumed: Activity?
        get() = activities.lastOrNull { !it.isDestroyed && !it.isFinishing && it.hasWindowFocus() } ?: lastResumed
    private val activities = mutableListOf<Activity>()
    private var stage = "setup"
        set(value) {
            field = value
            sendStatus(1, Bundle().apply { putString("stream", "Plex Home: $value\n") })
        }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) { activities.add(activity) }
        override fun onActivityResumed(activity: Activity) { lastResumed = activity }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val session = targetContext.getSharedPreferences("session", Context.MODE_PRIVATE)
        val settings = targetContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val originalSession = session.all.toMap()
        val originalSettings = settings.all.toMap()
        var success = false
        try {
            main { app.registerActivityLifecycleCallbacks(lifecycle) }
            verifyFlow()
            success = true
        } catch (error: Throwable) {
            // Stack locations identify assertions without exposing account response messages.
            main {
                val status = (resumed as? PlexHomeActivity)?.let { panel(it)?.findViewById<TextView>(R.id.status)?.text }
                sendStatus(1, Bundle().apply {
                    putString("stream", "State: member=${app.currentEntry?.plexHome?.userName}, activity=${resumed?.javaClass?.simpleName}, windowFocus=${resumed?.hasWindowFocus()}, panelStatus=$status\n")
                })
            }
            sendStatus(1, Bundle().apply {
                putString("stream", "Failure type: ${error.javaClass.simpleName} at ${error.stackTrace.firstOrNull()}\n")
            })
        } finally {
            main {
                activities.filterNot { it.isDestroyed }.forEach { it.finishAndRemoveTask() }
                app.unregisterActivityLifecycleCallbacks(lifecycle)
                restore(session, originalSession)
                restore(settings, originalSettings)
                app.store.currentId?.let { app.switchTo(it, forceReset = true) }
            }
            waitForIdleSync()
        }
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", if (success) "Plex Home live UI selection, PIN retry/cancel, admin and startup gate passed\n"
                else "Plex Home UI check failed during $stage; preferences restored\n")
        })
    }

    private fun verifyFlow() {
        val adminPin = requiredPin("admin_pin")
        val memberPin = requiredPin("member_pin")
        check(memberPin != "0000") { "The retry fixture requires a different valid PIN" }
        val entry = app.store.servers.firstOrNull { it.kind == ServerKind.PLEX && it.accountToken != null }
            ?: error("A saved Plex account is required")
        val rootToken = checkNotNull(entry.plexHome?.accountToken ?: entry.accountToken)
        val memberName = arguments.getString("member_name", "test")
        val protectedName = arguments.getString("protected_name", "PIN")
        stage = "unprotected member selection"
        setInTouchMode(false)
        main {
            app.store.currentId = entry.id
            app.settings.plexHomeAutoSignIn = true
            app.unlockPlexHome(entry.id)
        }
        startActivitySync(Intent(targetContext, HomeActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        await("initial Home") { resumed is HomeActivity && resumed?.hasWindowFocus() == true }
        val picker = openPicker(entry, rootToken)
        choose(picker, memberName)
        awaitIdentity(entry.id, memberName, rootToken)
        stage = "invalid protected PIN"
        val protectedPicker = openPicker(entry, rootToken)
        val beforeProtected = app.store.servers
        choose(protectedPicker, protectedName)
        await("PIN panel") { panel(protectedPicker)?.window?.decorView?.hasWindowFocus() == true }
        repeat(4) { key(KeyEvent.KEYCODE_0) }
        await("invalid PIN message") {
            panel(protectedPicker)?.findViewById<TextView>(R.id.status)?.text ==
                protectedPicker.getString(R.string.plex_home_pin_invalid)
        }
        check(beforeProtected == app.store.servers)
        stage = "protected member D-pad PIN"
        enterPin(protectedPicker, memberPin)
        awaitIdentity(entry.id, protectedName, rootToken)
        stage = "admin PIN cancellation"
        val adminPicker = openPicker(entry, rootToken)
        val adminName = arguments.getString("admin_name") ?: adminName(adminPicker)
        val beforeCancel = app.store.servers
        choose(adminPicker, adminName)
        await("admin PIN panel") { panel(adminPicker)?.window?.decorView?.hasWindowFocus() == true }
        key(KeyEvent.KEYCODE_BACK)
        await("PIN cancellation") { panel(adminPicker) == null && adminPicker.hasWindowFocus() }
        check(beforeCancel == app.store.servers)
        stage = "admin D-pad PIN"
        choose(adminPicker, adminName)
        await("admin PIN panel") { panel(adminPicker)?.window?.decorView?.hasWindowFocus() == true }
        enterPin(adminPicker, adminPin)
        awaitIdentity(entry.id, adminName, rootToken, requireSeparateToken = false)
        verifyRuntimeLocks()
        verifyStartupGate()
    }

    private fun verifyRuntimeLocks() {
        stage = "runtime selection locks"
        main {
            val entry = checkNotNull(app.currentEntry)
            app.settings.plexHomeAutoSignIn = false
            app.lockPlexHome()
            check(app.needsPlexHomeSelection(entry))
            app.unlockPlexHome(entry.id)
            check(!app.needsPlexHomeSelection(entry))
            app.switchTo(entry.id, forceReset = true)
            check(app.needsPlexHomeSelection(entry))
            app.unlockPlexHome(entry.id)
            app.store.servers.firstOrNull { it.id != entry.id }?.let { other ->
                app.switchTo(other.id)
                app.switchTo(entry.id)
                check(app.needsPlexHomeSelection(entry))
                app.unlockPlexHome(entry.id)
            }
        }
    }

    private fun verifyStartupGate() {
        stage = "automatic sign-in"
        val previousHome = main { resumed }
        main { app.settings.plexHomeAutoSignIn = true; app.lockPlexHome(); HomeActivity.restart(app) }
        await("automatic Home") {
            resumed is HomeActivity && resumed !== previousHome && resumed?.hasWindowFocus() == true
        }
        check(main { !app.needsPlexHomeSelection(checkNotNull(app.currentEntry)) })
        stage = "startup selection gate"
        main { app.settings.plexHomeAutoSignIn = false; app.lockPlexHome(); HomeActivity.restart(app) }
        await("startup picker") { resumed is PlexHomeActivity && resumed?.hasWindowFocus() == true }
        val gate = main { resumed as PlexHomeActivity }
        key(KeyEvent.KEYCODE_BACK)
        await("startup task exit") { gate.isFinishing || gate.isDestroyed }
        SystemClock.sleep(300)
        check(main { resumed !is HomeActivity })
    }

    private fun openPicker(entry: SavedServer, token: String): PlexHomeActivity {
        val previous = main { resumed }
        val intent = PlexHomeActivity.intent(targetContext, token,
            preferredId = entry.session.serverId, replaceId = entry.id)
        if (previous != null) main { previous.startActivity(intent) }
        else startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        await("picker resumed") { resumed is PlexHomeActivity && resumed !== previous }
        val picker = main { resumed as PlexHomeActivity }
        await("member list") {
            picker.hasWindowFocus() && picker.findViewById<RecyclerView>(R.id.users).childCount >= 2
        }
        return picker
    }

    private fun choose(picker: PlexHomeActivity, name: String) {
        val list = main { picker.findViewById<RecyclerView>(R.id.users) }
        val target = main {
            (0 until checkNotNull(list.adapter).itemCount).first { index ->
                list.findViewHolderForAdapterPosition(index)?.itemView?.findViewById<TextView>(R.id.name)?.text == name
            }
        }
        main { check(list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true) }
        repeat(target / COLUMNS) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(target % COLUMNS) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        main { check(list.findViewHolderForAdapterPosition(target)?.itemView?.hasFocus() == true) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun enterPin(picker: PlexHomeActivity, pin: String) {
        pin.forEach { digit ->
            val index = if (digit == '0') 10 else digit.digitToInt() - 1
            val keypad = main { checkNotNull(panel(picker)).findViewById<GridLayout>(R.id.keypad) }
            main { check(keypad.getChildAt(0).requestFocus()) }
            repeat(index / 3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            repeat(index % 3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            main { check(keypad.getChildAt(index).hasFocus()) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
        }
    }

    private fun awaitIdentity(id: String, name: String, root: String, requireSeparateToken: Boolean = true) {
        await("member connection and Home") {
            val saved = app.store.servers.firstOrNull { it.id == id }
            saved?.plexHome?.userName == name && resumed is HomeActivity && resumed?.hasWindowFocus() == true
        }
        val saved = app.store.servers.first { it.id == id }
        check(saved.plexHome?.accountToken == root)
        if (requireSeparateToken) check(saved.accountToken != root)
    }

    private fun adminName(picker: PlexHomeActivity): String = main {
        val field = PlexHomeActivity::class.java.getDeclaredField("users").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(picker) as List<com.hesier.lumy.plex.PlexHomeUser>).first { it.admin }.title
    }

    private fun panel(picker: PlexHomeActivity): PlexHomePinPanel? =
        PlexHomeActivity::class.java.getDeclaredField("pinPanel").run {
            isAccessible = true
            get(picker) as? PlexHomePinPanel
        }

    private fun requiredPin(key: String): String = checkNotNull(arguments.getString(key)).also {
        require(it.length == 4 && it.all(Char::isDigit)) { "Missing four-digit PIN argument" }
    }

    private fun key(code: Int) { sendKeyDownUpSync(code); waitForIdleSync() }

    private fun restore(preferences: SharedPreferences, snapshot: Map<String, *>) {
        preferences.edit().clear().apply {
            snapshot.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }.commit()
    }

    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (main(condition)) return
            SystemClock.sleep(50)
        }
        error("Timed out waiting for $label")
    }

    private companion object {
        const val COLUMNS = 4
        const val TIMEOUT_MS = 60_000L
    }
}
