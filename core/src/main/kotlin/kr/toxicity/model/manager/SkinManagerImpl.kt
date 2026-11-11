/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import it.unimi.dsi.fastutil.ints.IntList
import kr.toxicity.library.armormodel.ArmorResource
import kr.toxicity.library.dynamicuv.*
import kr.toxicity.model.api.armor.ArmorItem
import kr.toxicity.model.api.armor.PlayerArmor
import kr.toxicity.model.api.event.CreatePlayerSkinEvent
import kr.toxicity.model.api.event.RemovePlayerSkinEvent
import kr.toxicity.model.api.manager.SkinManager
import kr.toxicity.model.api.pack.PackObfuscator
import kr.toxicity.model.api.pack.PackZipper
import kr.toxicity.model.api.profile.ModelProfile
import kr.toxicity.model.api.profile.ModelProfileInfo
import kr.toxicity.model.api.skin.SkinData
import kr.toxicity.model.bedrock.BedrockIds
import kr.toxicity.model.bedrock.BedrockLimb
import kr.toxicity.model.bedrock.BedrockLimbs
import kr.toxicity.model.api.util.TransformedItemStack
import kr.toxicity.model.util.*
import org.joml.Vector3f
import java.awt.image.BufferedImage
import java.net.URI
import java.net.http.HttpResponse
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

object SkinManagerImpl : SkinManager, GlobalManager {

    @Volatile
    private var uvNamespace = UVNamespace(
        CONFIG.namespace(),
        BedrockIds.limbDirectory(CONFIG.pack().useObfuscation())
    )

    private val limbs = PlayerLimbModels { uvNamespace }

    /**
     * 統合版用の手足の皮膚ジオメトリ。部位の定義から毎回同じ値になり、皮膚やリロードの順番に依存しない。
     */
    internal fun bedrockLimbs(): List<BedrockLimb> = limbs.specs.map {
        BedrockLimbs.build(it, CONFIG.namespace(), CONFIG.pack().useObfuscation())
    }

    private data class SkinModelData(
        private val model: UVModel,
        val data: UVModelData,
        val profile: ModelProfile
    ) {
        var namespace = model.itemModelNamespace()
            private set

        fun refresh() {
            namespace = model.itemModelNamespace()
        }
    }

    private fun UVModel.asModelData(image: BufferedImage, profile: ModelProfile): SkinModelData {
        val data = write(image)
        return SkinModelData(
            this,
            data,
            profile
        )
    }

    private val whiteList = IntList.of(*(0..8).map { 0xFFFFFF }.toIntArray())

    private fun SkinModelData.asItem(colors: IntList = IntList.of()): TransformedItemStack = PLATFORM.nms().createSkinItem(
        namespace,
        data.floats,
        data.flags,
        emptyList(),
        colors.takeIf { it.isNotEmpty() }?.let { it + data.colors } ?: data.colors,
        profile
    )

    private fun SkinModelData.asItem(resource: ArmorResource, item: ArmorItem? = null): TransformedItemStack {
        if (!CONFIG.module().playerArmor) return asItem()
        if (item == null) return asItem(whiteList)
        val armorData = ArmorManager.armor.resource(resource).run {
            item.trim()?.let {
                customModelData(item.type, item.tint, it, item.palette?.let { p -> ArmorManager.armor.colors()[p] })
            } ?: customModelData(item.type, item.tint)
        }
        // 頭だけ、統合版で兜を被せるために、被せる兜のアイテムの id を strings の末尾に足す。
        // 防具の select は strings の 0 番・1 番しか見ないので、Java 版の見た目は変わらない。
        val strings = if (resource == ArmorResource.HELMET && item.itemId() != null) {
            armorData.strings + "${BedrockIds.ARMOR_PREFIX}${item.itemId()}"
        } else armorData.strings
        return PLATFORM.nms().createSkinItem(
            namespace,
            data.floats,
            data.flags,
            strings,
            armorData.colors + data.colors,
            profile
        )
    }

    fun write(block: (UVByteBuilder) -> Unit) {
        val set = mutableSetOf<UVByteBuilder>()

        val useObfuscation = CONFIG.pack().useObfuscation()
        val modelObf = PackObfuscator.order()
        val textureName = PackObfuscator.order().let {
            UVTextureName(
                it.obfuscate(UVTextureName.DEFAULT.normalPixel),
                it.obfuscate(UVTextureName.DEFAULT.translucentPixel)
            )
        }

        fun UVModel.parseAsJson(armorResource: ArmorResource? = null): Collection<UVByteBuilder> {
            val model = modelName()
            // item_model の名前は部位の名前だけから決まる（難読化の出現順に依存しない）
            packName(BedrockIds.limbPackName(useObfuscation, model))
            return asJson(UVLoadContext(
                textureName,
                modelObf::obfuscate
            ) { indexer, _, array ->
                armorResource
                    ?.takeIf { CONFIG.module().playerArmor }
                    ?.let { ArmorManager.armor.resource(it) }
                    ?.let {
                        array += it.toJson()
                        indexer.shiftColor(9)
                    }
            })
        }
        set += limbs.head.parseAsJson(ArmorResource.HELMET)
        set += limbs.chest.parseAsJson(ArmorResource.CHEST)
        set += limbs.waist.parseAsJson(ArmorResource.WAIST)
        set += limbs.hip.parseAsJson(ArmorResource.HIP)
        set += limbs.leftLeg.parseAsJson(ArmorResource.LEFT_LEG)
        set += limbs.leftForeLeg.parseAsJson(ArmorResource.LEFT_FORELEG)
        set += limbs.rightLeg.parseAsJson(ArmorResource.RIGHT_LEG)
        set += limbs.rightForeLeg.parseAsJson(ArmorResource.RIGHT_FORELEG)
        set += limbs.leftArm.parseAsJson(ArmorResource.LEFT_ARM)
        set += limbs.leftForeArm.parseAsJson()
        set += limbs.rightArm.parseAsJson(ArmorResource.RIGHT_ARM)
        set += limbs.rightForeArm.parseAsJson()
        set += limbs.slimLeftArm.parseAsJson(ArmorResource.LEFT_ARM)
        set += limbs.slimLeftForeArm.parseAsJson()
        set += limbs.slimRightArm.parseAsJson(ArmorResource.RIGHT_ARM)
        set += limbs.slimRightForeArm.parseAsJson()
        set += limbs.cape.parseAsJson()

        set += textureName.normalPixel(uvNamespace)
        set += textureName.translucentPixel(uvNamespace)

        set.forEach(block)
    }

    private val profileCache = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .removalListener<UUID, SkinDataImpl> { key, value, cause ->
            if (cause == RemovalCause.EXPIRED && key != null && value != null) {
                handleExpiration(key, value)
            }
        }
        .buildAsync<UUID, SkinDataImpl>()

    private val fallback by lazy {
        PLATFORM.getResource("fallback_skin.png")!!.use {
            SkinDataImpl(ModelProfile.UNKNOWN, ImageIO.read(it), null)
        }
    }

    private fun handleExpiration(key: UUID, skin: SkinDataImpl) {
        skin.profile().let {
            if (!callEvent { RemovePlayerSkinEvent(it) } || it.playerEquals()) profileCache.put(key, CompletableFuture.completedFuture(skin))
        }
    }

    private fun ModelProfile.playerEquals() = player()?.let { player ->
        ModelProfile.of(player).info()
    } == info()

    override fun fallback(): SkinData = fallback

    override fun complete(profile: ModelProfile.Uncompleted): CompletableFuture<out SkinData> {
        if (profile.info() == ModelProfileInfo.UNKNOWN) return CompletableFuture.completedFuture(fallback)
        // 同時の要求は1つの Future を共有する。失敗または null で完了した Future はキャッシュが自分で外す。
        return profileCache.get(profile.info().id) { _, _ -> load(profile) }
            .handle<SkinData> { skin, _ -> skin ?: fallback }
    }

    /**
     * このプロフィールの皮膚を読み込む。
     * 皮膚を読めなければ失敗で、プロフィールに皮膚が無ければ null で完了する。
     * どちらもキャッシュには残らず、[complete] が既定の皮膚へ戻す。
     */
    @Suppress("UNCHECKED_CAST")
    private fun load(profile: ModelProfile.Uncompleted): CompletableFuture<SkinDataImpl> {
        return profile.complete().thenApply { provided ->
            CreatePlayerSkinEvent(provided).run {
                call()
                modelProfile
            }
        }.thenComposeAsync compose@ { selected ->
            val skin = selected.skin().skin ?: return@compose CompletableFuture.completedFuture<SkinDataImpl?>(null)
            val cape = selected.skin().cape
            // PNG は URL をキーにしたキャッシュ（メモリとディスク）から取る。UUID ごとには落とさない。
            skinImages.get(skin).thenCombine(cape?.let { skinImages.get(it) } ?: CompletableFuture.completedFuture(null)) { skin, cape ->
                SkinDataImpl(
                    selected,
                    skin.convertLegacy(),
                    cape
                )
            }
        }.whenComplete { _, error ->
            error?.handleException("Unable to read this skin: ${profile.info().name}")
        } as CompletableFuture<SkinDataImpl>
    }

    /**
     * 皮膚・マントの PNG のキャッシュ。保存先は `.cache/skins`。
     * 皮膚を変えると URL が変わるので、UUID のキャッシュを消せば新しい皮膚を取る（同じ URL の PNG は取り直さない）。
     */
    private val skinImages by lazy {
        SkinImageCache(DATA_FOLDER.getOrCreateDirectory(".cache").getOrCreateDirectory("skins"), ::download)
    }

    private fun download(uri: URI): CompletableFuture<ByteArray> = httpClient {
        sendAsync(
            buildHttpRequest {
                uri(uri)
                GET()
            },
            HttpResponse.BodyHandlers.ofByteArray()
        ).thenApply { response ->
            check(response.statusCode() == 200) { "Unexpected status code ${response.statusCode()}: $uri" }
            response.body()
        }
    }.orElse {
        CompletableFuture.failedFuture(it)
    }

    override fun removeCache(profile: ModelProfile) = profileCache.synchronous().invalidate(profile.info().id)

    private fun BufferedImage.convertLegacy() = if (height == 64) this else BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).also { newImage ->
        fun drawTo(from: UVPos, to: UVPos, xr: IntRange, zr: IntRange) {
            val maxX = xr.last + xr.first
            for (x in xr) {
                for (z in zr) {
                    newImage.setRGB(
                        to.x + maxX - x,
                        to.z + z,
                        getRGB(from.x + x, from.z + z)
                    )
                }
            }
        }
        fun drawTo(from: UVPos, to: UVPos) {
            drawTo(from, to, 0..<4, 4..<16)
            drawTo(from, to, 4..<8, 0..<16)
            drawTo(from, to, 8..<12, 0..<16)
            drawTo(from, to, 12..<16, 4..<16)
        }
        newImage.createGraphics().let {
            it.drawImage(this, 0, 0, null)
            it.dispose()
        }
        drawTo(UVPos(0, 16), UVPos(16, 48))
        drawTo(UVPos(40, 16), UVPos(32, 48))
    }

    private class SkinDataImpl(
        private val profile: ModelProfile,
        private val head: SkinModelData,
        private val hip: SkinModelData,
        private val waist: SkinModelData,
        private val chest: SkinModelData,
        private val leftArm: SkinModelData,
        private val rightArm: SkinModelData,
        private val leftLeg: SkinModelData,
        private val leftForeLeg: SkinModelData,
        private val rightLeg: SkinModelData,
        private val rightForeLeg: SkinModelData,
        private val leftForeArm: TransformedItemStack,
        private val rightForeArm: TransformedItemStack,
        private val cape: TransformedItemStack?
    ) : SkinData {

        constructor(
            profile: ModelProfile,
            skinImage: BufferedImage,
            capeImage: BufferedImage?
        ) : this(
            profile,
            limbs.head.asModelData(skinImage, profile),
            limbs.hip.asModelData(skinImage, profile),
            limbs.waist.asModelData(skinImage, profile),
            limbs.chest.asModelData(skinImage, profile),
            (if (profile.skin().slim) limbs.slimLeftArm else limbs.leftArm).asModelData(skinImage, profile),
            (if (profile.skin().slim) limbs.slimRightArm else limbs.rightArm).asModelData(skinImage, profile),
            limbs.leftLeg.asModelData(skinImage, profile),
            limbs.leftForeLeg.asModelData(skinImage, profile),
            limbs.rightLeg.asModelData(skinImage, profile),
            limbs.rightForeLeg.asModelData(skinImage, profile),
            (if (profile.skin().slim) limbs.slimLeftForeArm else limbs.leftForeArm).asModelData(skinImage, profile).asItem(),
            (if (profile.skin().slim) limbs.slimRightForeArm else limbs.rightForeArm).asModelData(skinImage, profile).asItem(),
            capeImage?.let { limbs.cape.asModelData(it, profile).asItem() }
        )
        override fun profile(): ModelProfile = profile
        override fun head(armor: PlayerArmor): TransformedItemStack = head.asItem(ArmorResource.HELMET, armor.helmet())
        override fun hip(armor: PlayerArmor): TransformedItemStack = hip.asItem(ArmorResource.HIP, armor.leggings())
        override fun waist(armor: PlayerArmor): TransformedItemStack = waist.asItem(ArmorResource.WAIST, armor.chestplate())
        override fun chest(armor: PlayerArmor): TransformedItemStack = chest.asItem(ArmorResource.CHEST, armor.chestplate())
        override fun leftArm(armor: PlayerArmor): TransformedItemStack = leftArm.asItem(ArmorResource.LEFT_ARM, armor.chestplate())
        override fun rightArm(armor: PlayerArmor): TransformedItemStack = rightArm.asItem(ArmorResource.RIGHT_ARM, armor.chestplate())
        override fun leftLeg(armor: PlayerArmor): TransformedItemStack = leftLeg.asItem(ArmorResource.LEFT_LEG, armor.leggings())
        override fun rightLeg(armor: PlayerArmor): TransformedItemStack = rightLeg.asItem(ArmorResource.RIGHT_LEG, armor.leggings())
        override fun leftForeLeg(armor: PlayerArmor): TransformedItemStack = leftForeLeg.asItem(ArmorResource.LEFT_FORELEG, armor.boots())
        override fun rightForeLeg(armor: PlayerArmor): TransformedItemStack = rightForeLeg.asItem(ArmorResource.RIGHT_FORELEG, armor.boots())
        override fun leftForeArm(): TransformedItemStack = leftForeArm
        override fun rightForeArm(): TransformedItemStack = rightForeArm
        override fun cape(armor: PlayerArmor): TransformedItemStack? = if (armor.chestplate() != null) cape?.offset(Vector3f(0F, 0F, -1F / 16F)) else cape

        fun refresh() {
            head.refresh()
            hip.refresh()
            waist.refresh()
            chest.refresh()
            leftArm.refresh()
            rightArm.refresh()
            leftLeg.refresh()
            leftForeLeg.refresh()
            rightLeg.refresh()
            rightForeLeg.refresh()
        }
    }

    override fun reload(pipeline: ReloadPipeline, zipper: PackZipper) {
        uvNamespace = UVNamespace(
            CONFIG.namespace(),
            BedrockIds.limbDirectory(CONFIG.pack().useObfuscation())
        )
        // 起動時と再読み込みの時に、使われていない皮膚の PNG を消す。ディスクを触るので、reload を止めない場所で行う。
        CompletableFuture.runAsync {
            runCatching { skinImages.cleanUp() }.handleFailure { "Unable to clean up the skin cache." }
        }
        if (!CONFIG.module().playerAnimation) return
        write { resource ->
            zipper.assets().add(resource.path(), resource.estimatedSize()) {
                resource.build()
            }
        }
        profileCache.synchronous().asMap().values.forEach {
            it.refresh()
        }
    }

    override fun end() {
        profileCache.synchronous().cleanUp()
    }
}
