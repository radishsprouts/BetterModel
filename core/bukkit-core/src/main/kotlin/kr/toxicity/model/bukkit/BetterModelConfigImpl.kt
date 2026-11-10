/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit

import kr.toxicity.model.api.BetterModelConfig
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter
import kr.toxicity.model.api.config.BedrockConfig
import kr.toxicity.model.api.config.DebugConfig
import kr.toxicity.model.api.config.IndicatorConfig
import kr.toxicity.model.api.config.ModuleConfig
import kr.toxicity.model.api.config.PackConfig
import kr.toxicity.model.api.mount.MountController
import kr.toxicity.model.api.mount.MountControllers
import kr.toxicity.model.api.platform.PlatformItemStack
import kr.toxicity.model.api.util.EntityUtil
import kr.toxicity.model.util.ifNull
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.function.Supplier

class BetterModelConfigImpl(yaml: ConfigurationSection) : BetterModelConfig {

    private val debug = yaml.getConfigurationSection("debug")?.let {
        DebugConfig.from(it::getBoolean)
    } ?: DebugConfig.DEFAULT
    private val indicator = yaml.getConfigurationSection("indicator")?.let {
        IndicatorConfig.from(it::getBoolean)
    } ?: IndicatorConfig.DEFAULT
    private val module = yaml.getConfigurationSection("module")?.let {
        ModuleConfig.from(it::getBoolean)
    } ?: ModuleConfig.DEFAULT
    private val pack = yaml.getConfigurationSection("pack")?.let {
        PackConfig.from(it::getBoolean)
    } ?: PackConfig.DEFAULT
    private val bedrock = yaml.getConfigurationSection("bedrock")?.let {
        BedrockConfig(
            it.getBoolean("enabled", true),
            it.getString("output-folder")?.trim()?.takeIf(String::isNotEmpty) ?: BedrockConfig.DEFAULT_OUTPUT_FOLDER
        )
    } ?: BedrockConfig.DEFAULT
    private val metrics = yaml.getBoolean("metrics", true)
    private val sightTrace = yaml.getBoolean("sight-trace", true)
    private val mergeWithExternalResources = yaml.getBoolean("merge-with-external-resources", true)
    private val itemModel = yaml.getString("item")?.let {
        runCatching {
            Material.getMaterial(it.uppercase()).ifNull { "This item doesn't exist: $it" }
        }.getOrDefault(Material.LEATHER_HORSE_ARMOR)
    } ?: Material.LEATHER_HORSE_ARMOR
    private val item = Supplier { BukkitAdapter.adapt(ItemStack(itemModel)) }
    private val maxSight = yaml.getDouble("max-sight", -1.0).run {
        if (this <= 0.0) EntityUtil.renderDistance() else this
    }
    private val minSight = yaml.getDouble("min-sight", 5.0)
    private val namespace = yaml.getString("namespace") ?: "bettermodel"
    private val packType = yaml.getString("pack-type")?.let {
        runCatching {
            BetterModelConfig.PackType.valueOf(it.uppercase())
        }.getOrNull()
    } ?: BetterModelConfig.PackType.ZIP
    private val buildFolderLocation = (yaml.getString("build-folder-location") ?: "BetterModel/build").replace('/', File.separatorChar)
    private val followMobInvisibility = yaml.getBoolean("follow-mob-invisibility", true)
    private val usePurpurAfk = yaml.getBoolean("use-purpur-afk", true)
    private val versionCheck = yaml.getBoolean("version-check", true)
    private val defaultMountController = when (yaml.getString("default-mount-controller")?.lowercase()) {
        "invalid" -> MountControllers.INVALID
        "none" -> MountControllers.NONE
        "fly" -> MountControllers.FLY
        else -> MountControllers.WALK
    }
    private val lerpFrameTime = yaml.getInt("lerp-frame-time", 5)
    private val cancelPlayerModelInventory = yaml.getBoolean("cancel-player-model-inventory")
    private val playerHideDelay = yaml.getLong("player-hide-delay", 3L).coerceAtLeast(1L)
    private val packetBundlingSize = yaml.getInt("packet-bundling-size", 16)
    private val animationLod = yaml.getBoolean("animation-lod", true)
    private val animationLodNearDistance = yaml.getDouble("animation-lod-near-distance", 16.0).coerceAtLeast(0.0)
    private val animationLodFarDistance = yaml.getDouble("animation-lod-far-distance", 32.0).coerceAtLeast(animationLodNearDistance)
    private val animationLodNearStep = yaml.getInt("animation-lod-near-step", 2).coerceAtLeast(1)
    private val animationLodFarStep = yaml.getInt("animation-lod-far-step", 4).coerceAtLeast(1)
    private val enableStrictLoading = yaml.getBoolean("enable-strict-loading")

    override fun debug(): DebugConfig = debug
    override fun indicator(): IndicatorConfig = indicator
    override fun module(): ModuleConfig = module
    override fun pack(): PackConfig = pack
    override fun item(): Supplier<PlatformItemStack> = item
    override fun bedrock(): BedrockConfig = bedrock
    override fun metrics(): Boolean = metrics
    override fun sightTrace(): Boolean = sightTrace
    override fun mergeWithExternalResources(): Boolean = mergeWithExternalResources
    override fun maxSight(): Double = maxSight
    override fun minSight(): Double = minSight
    override fun namespace(): String = namespace
    override fun packType(): BetterModelConfig.PackType = packType
    override fun buildFolderLocation(): String = buildFolderLocation
    override fun followMobInvisibility(): Boolean = followMobInvisibility
    override fun usePurpurAfk(): Boolean = usePurpurAfk
    override fun versionCheck(): Boolean = versionCheck
    override fun defaultMountController(): MountController = defaultMountController
    override fun lerpFrameTime(): Int = lerpFrameTime
    override fun cancelPlayerModelInventory(): Boolean = cancelPlayerModelInventory
    override fun playerHideDelay(): Long = playerHideDelay
    override fun packetBundlingSize(): Int = packetBundlingSize
    override fun animationLod(): Boolean = animationLod
    override fun animationLodNearDistance(): Double = animationLodNearDistance
    override fun animationLodFarDistance(): Double = animationLodFarDistance
    override fun animationLodNearStep(): Int = animationLodNearStep
    override fun animationLodFarStep(): Int = animationLodFarStep
    override fun enableStrictLoading(): Boolean = enableStrictLoading
}
