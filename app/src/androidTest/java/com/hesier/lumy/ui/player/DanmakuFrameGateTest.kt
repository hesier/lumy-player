package com.hesier.lumy.ui.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Deterministic producer/consumer orders exercise the production gate without Android timing. */
internal object DanmakuFrameGateTest {
    fun runAll() {
        orderedConcurrentRequest(consumeFirst = true)
        orderedConcurrentRequest(consumeFirst = false)
        pendingRequestsCoalesce()
        pausedDrawDoesNotSchedule()
    }

    private fun pendingRequestsCoalesce() {
        val gate = DanmakuFrameGate()
        check(gate.acquire())
        repeat(10) { check(!gate.acquire()) }
        check(gate.consume())
        check(gate.acquire())
        check(!gate.consume()) { "Repeated pending requests must produce only one wakeup" }
    }

    private fun pausedDrawDoesNotSchedule() {
        val gate = DanmakuFrameGate()
        check(gate.acquire())
        repeat(10) { check(!gate.consume()) { "A paused draw must not continuously schedule work" } }
        check(gate.acquire()) { "A later explicit paused seek/cache request must still run" }
        check(!gate.consume())
    }

    private fun orderedConcurrentRequest(consumeFirst: Boolean) {
        val gate = DanmakuFrameGate()
        check(gate.acquire())
        val firstFinished = CountDownLatch(1)
        val finished = CountDownLatch(2)
        var acquired = false
        var wake = false
        val producer = Thread {
            if (consumeFirst) check(firstFinished.await(5, TimeUnit.SECONDS))
            acquired = gate.acquire()
            if (!consumeFirst) firstFinished.countDown()
            finished.countDown()
        }
        val consumer = Thread {
            if (!consumeFirst) check(firstFinished.await(5, TimeUnit.SECONDS))
            wake = gate.consume()
            if (consumeFirst) firstFinished.countDown()
            finished.countDown()
        }
        producer.start(); consumer.start()
        check(finished.await(5, TimeUnit.SECONDS)) { "Producer/consumer gate stalled" }
        producer.join(); consumer.join()
        check(acquired == consumeFirst && wake != consumeFirst) {
            "Request must acquire available credit or receive one deferred wakeup"
        }
        if (wake) check(gate.acquire())
        check(!gate.consume())
    }
}
