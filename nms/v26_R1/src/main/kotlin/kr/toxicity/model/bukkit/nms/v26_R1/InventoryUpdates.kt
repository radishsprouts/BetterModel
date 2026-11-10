/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.nms.v26_R1

import kr.toxicity.model.bukkit.util.runOnOwnScheduler
import org.bukkit.craftbukkit.entity.CraftPlayer

/**
 * コンテナの全データの再送を、プレイヤーの EntityScheduler 上で行う。
 * どのスレッドから呼んでもよい。プレイヤーが既に取り除かれている場合は何もしない。
 */
internal fun CraftPlayer.updateInventoryInRegion() {
    runOnOwnScheduler { handle.containerMenu.sendAllDataToRemote() }
}
