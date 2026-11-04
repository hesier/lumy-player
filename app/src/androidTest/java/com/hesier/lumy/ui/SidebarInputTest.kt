package com.hesier.lumy.ui

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import java.util.concurrent.CopyOnWriteArrayList
import com.hesier.lumy.R
import com.hesier.lumy.ui.detail.DetailActivity
import com.hesier.lumy.ui.home.HomeActivity
import com.hesier.lumy.ui.player.PlayerActivity
import com.hesier.lumy.ui.widget.SidebarLayout

/** Real pointer events must reach cards under the clipped part of the sidebar. */
internal class SidebarInputTest(private val instrumentation: Instrumentation) {
    fun runAll(): String {
        val manager = instrumentation.targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        check(manager.appTasks.none { it.taskInfo.topActivity?.className == PlayerActivity::class.java.name })
        checkActivation(InputDevice.SOURCE_TOUCHSCREEN)
        val mouseActivated = checkActivation(InputDevice.SOURCE_MOUSE)
        val mouseControlActivated = checkActivation(InputDevice.SOURCE_MOUSE, hideSidebar = true)
        check(mouseActivated || !mouseControlActivated) {
            "Mouse activated a card only after removing the sidebar; clipped-sidebar activation regressed"
        }
        for (source in listOf(InputDevice.SOURCE_TOUCHSCREEN, InputDevice.SOURCE_MOUSE)) {
            checkDismiss(source)
        }
        val mouseResult = if (mouseActivated) "mouse card activation passed" else
            "mouse DOWN/UP dispatch passed; mouse activation unverified (also absent with sidebar GONE)"
        return "Home sidebar touch activation and expanded-panel dismiss gestures passed; $mouseResult"
    }

    private fun checkActivation(source: Int, hideSidebar: Boolean = false): Boolean = withHome { home, monitor ->
        val events = CopyOnWriteArrayList<String>()
        val rect = cardRect(home, events)
        val sidebar = main { home.findViewById<SidebarLayout>(R.id.sidebar) }
        await("collapsed sidebar before pointer source $source") {
            sidebar.revealedWidth == home.resources.getDimensionPixelSize(R.dimen.sidebar_collapsed)
        }
        check(rect.centerX() > sidebar.revealedWidth && rect.centerX() < sidebar.width) {
            "Card must overlap the clipped sidebar region"
        }
        if (hideSidebar) main { sidebar.visibility = android.view.View.GONE }
        instrumentation.waitForIdleSync()
        tap(source, rect.centerX().toFloat(), rect.centerY().toFloat())
        val detail = instrumentation.waitForMonitorWithTimeout(monitor, 5_000)
        check(events.any { it.startsWith("card: 0") } && events.any { it.startsWith("card: 1") }) {
            "Pointer source $source did not reach the card (sidebar GONE=$hideSidebar); events=$events"
        }
        check(source == InputDevice.SOURCE_MOUSE || detail != null) { "Touch did not activate the card; events=$events" }
        // Report mouse dispatch separately when this device also fails the unobstructed click control.
        detail != null
    }

    private fun checkDismiss(source: Int) = withHome { home, monitor ->
        val rect = cardRect(home)
        val sidebar = main { home.findViewById<SidebarLayout>(R.id.sidebar) }
        main { home.javaClass.getDeclaredMethod("setSidebarExpanded", Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
        }.invoke(home, true) }
        await("expanded sidebar for source $source") { sidebar.revealedWidth == sidebar.width }
        tap(source, sidebar.width + 100f, rect.centerY().toFloat())
        check(instrumentation.waitForMonitorWithTimeout(monitor, 500) == null) {
            "Dismiss tap opened a card under the expanded sidebar for source $source"
        }
        await("sidebar dismissed for source $source") {
            home.hasWindowFocus() && sidebar.revealedWidth == home.resources.getDimensionPixelSize(R.dimen.sidebar_collapsed)
        }
    }

    private fun cardRect(home: HomeActivity, events: MutableList<String>? = null): Rect = main {
        val card = home.findViewById<android.view.View>(R.id.content)
            .findViewById<android.view.View>(R.id.frame).parent as android.view.View
        check(card.isAttachedToWindow && card.requestFocusFromTouch())
        val rect = Rect()
        check(card.getGlobalVisibleRect(rect))
        if (events != null) card.setOnTouchListener { view, event ->
            events.add("card: ${event.actionMasked} pressed=${view.isPressed} focused=${view.isFocused}")
            false
        }
        rect
    }

    private fun <T> withHome(action: (HomeActivity, Instrumentation.ActivityMonitor) -> T): T {
        instrumentation.setInTouchMode(false)
        val home = instrumentation.startActivitySync(Intent(instrumentation.targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as HomeActivity
        val taskId = home.taskId
        val monitor = instrumentation.addMonitor(DetailActivity::class.java.name, null, false)
        try {
            await("loaded home card in task $taskId") {
                val frame = home.findViewById<android.view.View>(R.id.content)
                    .findViewById<android.view.View>(R.id.frame)
                val card = frame?.parent as? android.view.View
                home.hasWindowFocus() && !card?.findViewById<android.widget.TextView>(R.id.title)?.text.isNullOrEmpty()
            }
            return action(home, monitor)
        } finally {
            instrumentation.removeMonitor(monitor)
            // Detail may recreate Home on return. Remove our task without depending on the old Activity instance.
            val manager = instrumentation.targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            main { manager.appTasks.firstOrNull { it.taskInfo.id == taskId }?.finishAndRemoveTask() }
        }
    }

    private fun tap(source: Int, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val mouse = source == InputDevice.SOURCE_MOUSE
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if (mouse) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            pressure = 1f; size = 1f
        }
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val buttons = if (mouse && action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, 1,
                arrayOf(properties), arrayOf(coordinates), 0, buttons, 1f, 1f, 0, 0, source, 0)
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
    }

    private fun <T> main(action: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(action) }
        return checkNotNull(value).getOrThrow()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (main(condition)) return
            Thread.sleep(50)
        }
        error("Timed out: $label")
    }
}
