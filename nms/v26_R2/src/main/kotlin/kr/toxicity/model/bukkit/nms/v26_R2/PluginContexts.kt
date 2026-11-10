/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.nms.v26_R2

import kr.toxicity.model.api.bukkit.BetterModelBukkit
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

internal val PLUGIN get() = BetterModelBukkit.platform() as Plugin

/**
 * コンテナの全データの再送を、プレイヤーの EntityScheduler 上で行う。
 * どのスレッドから呼んでもよい。プレイヤーが既に取り除かれている場合は何もしない。
 */
internal fun CraftPlayer.updateInventoryInRegion() {
    scheduler.run(PLUGIN, { handle.containerMenu.sendAllDataToRemote() }, null)
}

context(plugin: Plugin)
internal fun Entity.runInRegion(task: () -> Unit) {
    if (BetterModelBukkit.IS_FOLIA && !Bukkit.isOwnedByCurrentRegion(this)) {
        scheduler.run(plugin, { task() }) {}
    } else task()
}

context(plugin: Plugin)
internal fun Entity.hide(to: Player) {
    to.hideEntity(plugin, this)
}

context(plugin: Plugin)
internal fun Entity.show(to: Player) {
    to.showEntity(plugin, this)
}

