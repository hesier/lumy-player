package com.hesier.lumy.ui.player

/** Atomically pairs draw credit with a pending update so consumption cannot lose a wakeup. */
internal class DanmakuFrameGate {
    private var credit = true
    private var pending = false

    @Synchronized fun acquire(): Boolean {
        if (!credit) {
            pending = true
            return false
        }
        credit = false
        return true
    }

    /** Returns whether one deferred update needs scheduling after this draw. */
    @Synchronized fun consume(): Boolean {
        credit = true
        val wake = pending
        pending = false
        return wake
    }
}
