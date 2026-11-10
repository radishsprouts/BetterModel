/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.paper

import kr.toxicity.model.api.BetterModelPlatform
import kr.toxicity.model.api.nms.NMS
import kr.toxicity.model.api.version.MinecraftVersion
import kr.toxicity.model.api.version.MinecraftVersion.*
import kr.toxicity.model.bukkit.BetterModelPlugin
import kr.toxicity.model.util.toComponent
import kr.toxicity.model.util.warn

private typealias Latest = kr.toxicity.model.bukkit.nms.v26_R3.NMSImpl

@Suppress("UNUSED")
class BetterModelPaper : BetterModelPlugin() {

    override fun jarType(): BetterModelPlatform.JarType {
        return BetterModelPlatform.JarType.PAPER
    }

    override fun createNms(version: MinecraftVersion): NMS = when (version) {
        V26_3 -> Latest()
        V26_2 -> kr.toxicity.model.bukkit.nms.v26_R2.NMSImpl()
        V26_1, V26_1_1, V26_1_2 -> kr.toxicity.model.bukkit.nms.v26_R1.NMSImpl()
        else -> {
            if (version < V26_1) throw UnsupportedOperationException(
                "Minecraft $version is not supported. This fork supports Paper/Folia 26.1, 26.2 and 26.3 only. " +
                    "(対応外のバージョンです。対応しているのは Paper/Folia の 26.1・26.2・26.3 だけです)"
            )
            warn(
                "Note: this version is officially untested.".toComponent(),
                "So be careful to use!".toComponent()
            )
            Latest()
        }
    }
}
