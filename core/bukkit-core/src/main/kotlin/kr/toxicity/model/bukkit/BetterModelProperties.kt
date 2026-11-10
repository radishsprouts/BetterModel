/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit

import kr.toxicity.model.BetterModelEvaluatorImpl
import kr.toxicity.model.api.BetterModelConfig
import kr.toxicity.model.api.BetterModelPlatform.ReloadResult
import kr.toxicity.model.api.bukkit.BetterModelBukkit
import kr.toxicity.model.api.event.PluginEndReloadEvent
import kr.toxicity.model.api.event.PluginStartReloadEvent
import kr.toxicity.model.api.manager.*
import kr.toxicity.model.api.pack.PackZipper
import kr.toxicity.model.api.nms.NMS
import kr.toxicity.model.api.version.MinecraftVersion
import kr.toxicity.model.api.version.MinecraftVersion.parse
import kr.toxicity.model.bukkit.configuration.PluginConfiguration
import kr.toxicity.model.bukkit.manager.CompatibilityManager
import kr.toxicity.model.bukkit.manager.EntityManager
import kr.toxicity.model.bukkit.manager.PlayerManagerImpl
import kr.toxicity.model.bukkit.scheduler.BukkitScheduler
import kr.toxicity.model.bukkit.scheduler.PaperScheduler
import kr.toxicity.model.manager.*
import kr.toxicity.model.util.*
import org.bstats.bukkit.Metrics
import org.bukkit.Bukkit
import org.semver4j.Semver

/**
 * @param plugin プラグイン本体
 * @param nmsFactory サーバーの版に合った NMS の実装を作る処理。版ごとの実装は platform 側が持つ（core は nms の各版を知らない）
 */
internal class BetterModelProperties(
    private val plugin: AbstractBetterModelPlugin,
    nmsFactory: (MinecraftVersion) -> NMS
) {
    private lateinit var _config: BetterModelConfig
    private var _metrics: Metrics? = null

    val version = parse(Bukkit.getBukkitVersion().substringBefore('-'))
    val nms = nmsFactory(version)
    val scheduler = if (BetterModelBukkit.IS_FOLIA) PaperScheduler() else BukkitScheduler()
    val evaluator = BetterModelEvaluatorImpl()
    val eventbus = BukkitModelEventBusImpl()
    @Suppress("DEPRECATION") //To support Spigot :(
    val semver = Semver.coerce(plugin.description.version).ifNull { "Unable to load BetterModel's sermver." }
    val snapshot = runCatching {
        plugin.attributes().getValue("Dev-Build").toInt()
    }.getOrElse {
        it.handleException("Unable to parse manifest's build data")
        -1
    }
    var config
        get() = _config
        set(value) {
            _config = value.apply {
                if (metrics()) {
                    if (_metrics == null) _metrics = Metrics(plugin, 24237)
                } else {
                    _metrics?.shutdown()
                    _metrics = null
                }
            }
        }
    val managers by lazy {
        mapOf(
            CompatibilityManager::class.java to CompatibilityManager,
            ArmorManager::class.java to ArmorManager,
            ProfileManager::class.java to ProfileManagerImpl,
            SkinManager::class.java to SkinManagerImpl,
            ModelManager::class.java to ModelManagerImpl,
            PlayerManager::class.java to PlayerManagerImpl,
            EntityManager::class.java to EntityManager,
            ScriptManager::class.java to ScriptManagerImpl
        )
    }

    var reloadStartTask: (PackZipper) -> Unit = { callEvent { PluginStartReloadEvent(it) } }
    var reloadEndTask: (ReloadResult) -> Unit = { callEvent { PluginEndReloadEvent(it) } }

    init {
        config = BetterModelConfigImpl(PluginConfiguration.CONFIG.create())
    }
}
