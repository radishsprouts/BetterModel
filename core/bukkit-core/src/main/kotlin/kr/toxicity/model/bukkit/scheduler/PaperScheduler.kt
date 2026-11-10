/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.scheduler

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kr.toxicity.model.api.bukkit.scheduler.BukkitModelScheduler
import kr.toxicity.model.api.scheduler.ModelTask
import kr.toxicity.model.bukkit.util.PLUGIN
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import java.util.concurrent.TimeUnit

class PaperScheduler : BukkitModelScheduler {

    private fun ScheduledTask.wrap() = object : ModelTask {
        override fun isCancelled(): Boolean = this@wrap.isCancelled
        override fun cancel() {
            this@wrap.cancel()
        }
    }

    private fun ifEnabled(block: () -> ModelTask?): ModelTask? {
        return if (PLUGIN.isEnabled) block() else null
    }

    override fun task(location: Location, runnable: Runnable): ModelTask? = ifEnabled {
        Bukkit.getRegionScheduler().run(PLUGIN, location) {
            runnable.run()
        }.wrap()
    }

    override fun taskLater(location: Location, delay: Long, runnable: Runnable): ModelTask? = ifEnabled {
        Bukkit.getRegionScheduler().runDelayed(PLUGIN, location, {
            runnable.run()
        }, delay).wrap()
    }

    // Entity scheduler: the task follows the entity across regions, and retired runs when the entity is gone.
    override fun task(entity: Entity, runnable: Runnable, retired: Runnable): ModelTask? = ifEnabled {
        entity.scheduler.run(PLUGIN, {
            runnable.run()
        }, retired)?.wrap()
    }

    override fun taskLater(entity: Entity, delay: Long, runnable: Runnable, retired: Runnable): ModelTask? = ifEnabled {
        entity.scheduler.runDelayed(PLUGIN, {
            runnable.run()
        }, retired, delay.coerceAtLeast(1))?.wrap()
    }

    override fun asyncTask(runnable: Runnable) = Bukkit.getAsyncScheduler().runNow(PLUGIN) {
        runnable.run()
    }.wrap()

    override fun asyncTaskLater(delay: Long, runnable: Runnable) = Bukkit.getAsyncScheduler().runDelayed(PLUGIN, {
        runnable.run()
    }, (delay * 50).coerceAtLeast(1), TimeUnit.MILLISECONDS).wrap()

    override fun asyncTaskTimer(delay: Long, period: Long, runnable: Runnable) = Bukkit.getAsyncScheduler().runAtFixedRate(PLUGIN, {
        runnable.run()
    }, (delay * 50).coerceAtLeast(1), (period * 50).coerceAtLeast(1), TimeUnit.MILLISECONDS).wrap()
}
