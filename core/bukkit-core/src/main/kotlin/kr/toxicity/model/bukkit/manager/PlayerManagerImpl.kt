/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.manager

import kr.toxicity.model.api.manager.PlayerManager
import kr.toxicity.model.api.nms.PlayerChannelHandler
import kr.toxicity.model.api.pack.PackZipper
import kr.toxicity.model.api.platform.PlatformPlayer
import kr.toxicity.model.bukkit.util.registerListener
import kr.toxicity.model.bukkit.util.wrap
import kr.toxicity.model.manager.GlobalManager
import kr.toxicity.model.manager.ReloadPipeline
import kr.toxicity.model.manager.SkinManagerImpl
import kr.toxicity.model.util.PLATFORM
import kr.toxicity.model.util.handleFailure
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object PlayerManagerImpl : PlayerManager, GlobalManager {

    private val playerMap = ConcurrentHashMap<UUID, PlayerChannelHandler>()

    override fun start() {
        registerListener(object : Listener {
            @EventHandler(priority = EventPriority.HIGHEST)
            fun PlayerJoinEvent.join() {
                if (player.isOnline) runCatching { //偽のプレイヤーは除く
                    player.wrap().register()
                }.handleFailure {
                    "Unable to load ${player.name}'s data."
                }
            }
            @EventHandler(priority = EventPriority.MONITOR)
            fun PlayerQuitEvent.quit() {
                playerMap.remove(player.uniqueId)?.use {
                    SkinManagerImpl.removeCache(it.base().profile())
                }
            }
        })
    }

    private fun PlatformPlayer.register(): PlayerChannelHandler {
        playerMap[uuid()]?.let { return it }
        var created = false
        val handler = playerMap.computeIfAbsent(uuid()) {
            // オフラインのプレイヤー（退出後など）には注入しない。例外ならマップにも残らない。
            check(online()) { "${name()} is offline." }
            created = true
            PLATFORM.nms().inject(this)
        }
        // 皮膚の取得は、登録した時の1回だけ起動する。
        if (created) SkinManagerImpl.complete(handler.base().profile().asUncompleted())
        return handler
    }

    override fun reload(pipeline: ReloadPipeline, zipper: PackZipper) {
    }

    override fun end() {
        playerMap.values.removeIf {
            it.use { used -> SkinManagerImpl.removeCache(used.base().profile()) }
            true
        }
    }


    override fun player(uuid: UUID): PlayerChannelHandler? = playerMap[uuid]
    override fun player(player: PlatformPlayer): PlayerChannelHandler = player.register()
}
