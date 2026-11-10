/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.util

import kr.toxicity.model.api.bukkit.BetterModelBukkit
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * エンティティを所有するスケジューラー上で処理を行う。
 * どのスレッドから呼んでもよい。エンティティが既に取り除かれている場合は何もしない。
 *
 * @param task 行う処理
 */
fun Entity.runOnOwnScheduler(task: () -> Unit) {
    scheduler.run(PLUGIN, { task() }, null)
}

/**
 * Folia では、エンティティを所有するリージョンのスレッド上で処理を行う。
 * 既にそのスレッド上にいる場合や、Folia でない場合は、その場で処理を行う。
 *
 * @param task 行う処理
 */
context(plugin: Plugin)
fun Entity.runInRegion(task: () -> Unit) {
    if (BetterModelBukkit.IS_FOLIA && !Bukkit.isOwnedByCurrentRegion(this)) {
        scheduler.run(plugin, { task() }) {}
    } else task()
}

/**
 * このエンティティを、指定したプレイヤーから隠す。
 *
 * @param to 隠す相手
 */
context(plugin: Plugin)
fun Entity.hide(to: Player) {
    to.hideEntity(plugin, this)
}

/**
 * このエンティティを、指定したプレイヤーに再び見せる。
 *
 * @param to 見せる相手
 */
context(plugin: Plugin)
fun Entity.show(to: Player) {
    to.showEntity(plugin, this)
}
