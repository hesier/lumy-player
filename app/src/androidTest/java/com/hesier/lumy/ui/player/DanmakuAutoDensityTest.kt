// Exercise the version-pinned Ak entity admission contract without rendering a View.
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.hesier.lumy.ui.player

import android.graphics.Color
import com.badlogic.ashley.core.PooledEngine
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.ecs.DanmakuContext
import com.kuaishou.akdanmaku.ecs.component.FilterResultComponent
import com.kuaishou.akdanmaku.ecs.component.ItemDataComponent
import com.kuaishou.akdanmaku.ecs.system.DataSystem
import com.kuaishou.akdanmaku.utils.DanmakuTimer

internal object DanmakuAutoDensityTest {
    fun runAll() {
        transientPressureDoesNotReduce()
        pressureAndCooldownAreBounded()
        recoveryIsGradual()
        idleAndResetClearHistory()
        stableFilterPreservesTypes()
        engineKeepsAdmissionUntilEntityExpires()
        sessionSamplingPreservesPreferences()
    }

    private fun transientPressureDoesNotReduce() {
        val policy = DanmakuAutoDensity()
        repeat(12) { index ->
            check(!policy.sample(index % 3 != 2, index * 1_000L))
            check(1 == policy.divisor)
        }
        policy.reset()
        check(!policy.sample(true, 0))
        check(!policy.sample(true, 1))
        check(!policy.sample(true, 999))
        check(!policy.sample(true, 1_000))
        check(policy.sample(true, 2_000))
        check(2 == policy.divisor)
    }

    private fun reduceToQuarter(policy: DanmakuAutoDensity) {
        check(!policy.sample(true, 0))
        check(!policy.sample(true, 1_000))
        check(policy.sample(true, 2_000))
        check(2 == policy.divisor)
        for (second in 3..6) {
            check(!policy.sample(true, second * 1_000L))
            check(2 == policy.divisor)
        }
        check(policy.sample(true, 7_000))
        check(4 == policy.divisor)
    }

    private fun pressureAndCooldownAreBounded() {
        val policy = DanmakuAutoDensity()
        reduceToQuarter(policy)
        for (second in 8..200) {
            check(!policy.sample(true, second * 1_000L))
            check(4 == policy.divisor)
        }
    }

    private fun recoveryIsGradual() {
        val policy = DanmakuAutoDensity()
        reduceToQuarter(policy)
        for (second in 8..26) check(!policy.sample(false, second * 1_000L))
        check(4 == policy.divisor)
        check(policy.sample(false, 27_000))
        check(2 == policy.divisor)
        for (second in 28..46) check(!policy.sample(false, second * 1_000L))
        check(2 == policy.divisor)
        check(policy.sample(false, 47_000))
        check(1 == policy.divisor)
        for (second in 48..100) check(!policy.sample(false, second * 1_000L))
        check(1 == policy.divisor)
    }

    private fun idleAndResetClearHistory() {
        val policy = DanmakuAutoDensity()
        check(!policy.sample(true, 0))
        check(!policy.sample(true, 1_000))
        policy.idle()
        check(!policy.sample(true, 2_000))
        check(!policy.sample(true, 3_000))
        check(policy.sample(true, 4_000))
        for (second in 5..23) check(!policy.sample(false, second * 1_000L))
        policy.idle()
        check(2 == policy.divisor)
        for (second in 24..42) check(!policy.sample(false, second * 1_000L))
        check(policy.sample(false, 43_000))
        check(1 == policy.divisor)
        policy.reset()
        reduceToQuarter(policy)
        policy.idle()
        check(4 == policy.divisor)
        policy.reset()
        check(1 == policy.divisor)
        check(!policy.sample(true, 0))
        check(!policy.sample(true, 1_000))
        check(policy.sample(true, 2_000))
    }

    private fun sessionSamplingPreservesPreferences() {
        val context = com.hesier.lumy.LumyApp.instance
        val preferences = context.getSharedPreferences("danmaku-auto-test", android.content.Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
        val saved = com.hesier.lumy.data.DanmakuSettings(preferences)
        saved.density = com.hesier.lumy.data.DanmakuDensity.HIGH
        val session = DanmakuTestSession(preferences, android.view.View(context)) {}
        try {
            val overloaded = DanmakuMetrics()
            repeat(3) { second ->
                // Even one completed frame per second must trigger protection under pressure.
                overloaded.draw.record(100_000_000)
                check((second == 2) == session.sample(overloaded, true, second * 1_000L))
            }
            check(2 == session.divisor)
            val healthy = DanmakuMetrics()
            repeat(3) { healthy.draw.record(100_000) }
            for (second in 3..22) {
                healthy.draw.record(100_000)
                check((second == 22) == session.sample(healthy, true, second * 1_000L))
            }
            check(1 == session.divisor)
            check(com.hesier.lumy.data.DanmakuDensity.HIGH == saved.density)
            check(preferences.all.keys == setOf("danmaku_density"))
        } finally {
            session.release()
            check(preferences.edit().clear().commit())
        }
    }

    private fun stableFilterPreservesTypes() {
        val modes = listOf(DanmakuItemData.DANMAKU_MODE_ROLLING,
            DanmakuItemData.DANMAKU_MODE_CENTER_TOP, DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM)
        val items = List(3_072) { index -> DanmakuItem(DanmakuItemData(index.toLong(), 0L,
            "sample", modes[index % modes.size], 25, Color.WHITE)) }
        val timer = DanmakuTimer()
        val config = DanmakuConfig()
        val filter = DanmakuDensityFilter()
        val retained = listOf(1, 2, 4).associateWith { divisor ->
            filter.divisor = divisor
            val kept = items.filterNot { filter.filter(it, timer, config) }
            val target = items.size / divisor
            check(kept.size in target * 85 / 100..target * 115 / 100)
            modes.forEach { mode ->
                val perType = target / modes.size
                check(kept.count { it.data.mode == mode } in perType * 85 / 100..perType * 115 / 100)
            }
            check(kept == items.filterNot { filter.filter(it, timer, config) })
            kept.map { it.data.danmakuId }.toSet()
        }
        check(items.size == retained.getValue(1).size)
        check(retained.getValue(2).containsAll(retained.getValue(4)))
        listOf(0, 3, 8, -1).forEach { invalid ->
            check(runCatching { filter.divisor = invalid }.exceptionOrNull() is IllegalArgumentException)
            check(4 == filter.divisor)
        }
    }

    private fun engineKeepsAdmissionUntilEntityExpires() {
        val filter = DanmakuDensityFilter()
        val context = DanmakuContext(DanmakuTextRenderer(0.5f))
        context.updateConfig(DanmakuConfig(dataFilter = listOf(filter)))
        val engine = PooledEngine()
        val system = DataSystem(context)
        engine.addSystem(system)
        val initial = densityItems(0, 1_000)
        val preadmitted = densityItems(300, 2_000)
        val reduced = densityItems(100, 1_000)
        val recovered = densityItems(200, 1_000)
        try {
            // Ak's slice end is exclusive; a later sentinel keeps every tested item inside it.
            val sentinel = densityItems(400, 60_000).take(1)
            system.addItems(initial + preadmitted + sentinel)
            densityFrame(context, engine, 1_000)
            check(initial.size + preadmitted.size == engine.entities.size())
            check(admissions(engine).values.none { it })
            filter.divisor = 4
            system.addItems(reduced)
            densityFrame(context, engine, 1_000)
            val limited = admissions(engine)
            check((initial + preadmitted).all { limited[it.data.danmakuId] == false }) {
                "Reduction changed existing admission"
            }
            check(reduced.any { limited[it.data.danmakuId] == true })
            reduced.forEach { check(filter.filter(it, context.timer, context.config) == limited[it.data.danmakuId]) }
            filter.divisor = 1
            system.addItems(recovered)
            densityFrame(context, engine, 1_000)
            val restored = admissions(engine)
            check(limited.all { (id, filtered) -> restored[id] == filtered }) { "Recovery revived rejected input" }
            check(recovered.all { restored[it.data.danmakuId] == false })
            densityFrame(context, engine, 20_000)
            check(0 == engine.entities.size())
            densityFrame(context, engine, 1_000)
            check(admissions(engine).isNotEmpty())
            check(admissions(engine).values.none { it }) { "Recreated entities did not use current density" }
        } finally {
            engine.removeAllEntities()
            context.cacheManager.release()
        }
    }

    private fun densityItems(firstId: Int, positionMs: Long) = List(32) { index ->
        DanmakuItem(DanmakuItemData((firstId + index).toLong(), positionMs,
            "sample", DanmakuItemData.DANMAKU_MODE_ROLLING, 25, Color.WHITE))
    }

    private fun densityFrame(context: DanmakuContext, engine: PooledEngine, positionMs: Long) {
        context.timer.start(positionMs)
        context.timer.paused = true
        engine.getSystem(DataSystem::class.java).updateEntities()
        // Engine.update defers timeout removals until system iteration completes.
        engine.update(0f)
    }

    private fun admissions(engine: PooledEngine): Map<Long, Boolean> = engine.entities.associate { entity ->
        entity.getComponent(ItemDataComponent::class.java).item!!.data.danmakuId to
            entity.getComponent(FilterResultComponent::class.java).filtered
    }
}
