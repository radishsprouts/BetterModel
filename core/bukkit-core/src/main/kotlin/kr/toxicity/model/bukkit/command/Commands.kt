/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.command

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.BetterModelPlatform.ReloadResult.*
import kr.toxicity.model.api.animation.AnimationIterator
import kr.toxicity.model.api.animation.AnimationModifier
import kr.toxicity.model.api.tracker.EntityHideOption
import kr.toxicity.model.api.tracker.ModelScaler
import kr.toxicity.model.api.tracker.Tracker
import kr.toxicity.model.api.tracker.TrackerModifier
import kr.toxicity.model.api.bukkit.BetterModelBukkit
import kr.toxicity.model.bukkit.audience.AudiencePlayer
import kr.toxicity.model.bukkit.audience.AudienceSender
import kr.toxicity.model.bukkit.audience.BukkitAudience
import kr.toxicity.model.bukkit.util.PLUGIN
import kr.toxicity.model.bukkit.util.toRegistry
import kr.toxicity.model.bukkit.util.toTracker
import kr.toxicity.model.bukkit.util.wrap
import kr.toxicity.model.command.*
import kr.toxicity.model.util.*
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.format.NamedTextColor.*
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Enemy
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import org.incendo.cloud.SenderMapper
import org.incendo.cloud.bukkit.BukkitCommandMeta
import org.incendo.cloud.bukkit.CloudBukkitCapabilities
import org.incendo.cloud.bukkit.data.MultipleEntitySelector
import org.incendo.cloud.bukkit.parser.PlayerParser.playerParser
import org.incendo.cloud.bukkit.parser.location.LocationParser.locationParser
import org.incendo.cloud.bukkit.parser.selector.MultipleEntitySelectorParser.multipleEntitySelectorParser
import org.incendo.cloud.context.CommandContext
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.paper.LegacyPaperCommandManager
import org.incendo.cloud.parser.standard.BooleanParser.booleanParser
import org.incendo.cloud.parser.standard.DoubleParser.doubleParser
import org.incendo.cloud.parser.standard.EnumParser.enumParser
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider.blockingStrings

private val MODEL_SUGGESTION = blockingStrings<Audience> { _, _ -> BetterModel.modelKeys() }
private val LIMB_SUGGESTION = blockingStrings<Audience> { _, _ -> BetterModel.limbKeys() }

fun startBukkitCommand() {
    LegacyPaperCommandManager(
        PLUGIN,
        ExecutionCoordinator.simpleCoordinator(),
        SenderMapper.create<CommandSender, Audience>(
            { sender -> if (sender is Player) AudiencePlayer(sender) else AudienceSender(sender) },
            { audience -> (audience as BukkitAudience).sender }
        )
    ).apply {
        if (hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER)) {
            registerBrigadier()
            brigadierManager().setNativeNumberSuggestions(true)
        } else if (hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) registerAsynchronousCompletions()
    }.register(
        "bettermodel",
        "All-related command.",
        { it.meta(BukkitCommandMeta.BUKKIT_DESCRIPTION, info.description.textDescription()) },
        "bm", "model"
    ) {
        create(
            "reload",
            "Reloads BetterModel.",
            "re", "rl"
        ) {
            handler(::reload)
        }
        create(
            "spawn",
            "Summons some model to given type",
            "s"
        ) {
            required("model", stringParser(), MODEL_SUGGESTION)
                .optional("type", enumParser(EntityType::class.java))
                .optional("scale", doubleParser(0.0625, 16.0))
                .optional("location", locationParser())
                .senderType(AudiencePlayer::class.java)
                .handler(::spawn)
        }
        create(
            "test",
            "Tests some model's animation to specific player",
            "t"
        ) {
            required("model", stringParser(), MODEL_SUGGESTION)
                .required(
                    "animation",
                    stringParser(),
                    blockingStrings { ctx, _ -> ctx.nullableString("model") { BetterModel.modelOrNull(it)?.animations()?.keys } ?: emptySet()  }
                )
                .optional("player", playerParser())
                .optional("location", locationParser())
                .handler(::test)
        }
        create(
            "disguise",
            "Disguises self.",
            "d"
        ) {
            required("model", stringParser(), MODEL_SUGGESTION)
                .optional("scaling", booleanParser())
                .senderType(AudiencePlayer::class.java)
                .handler(::disguise)
        }
        create(
            "undisguise",
            "Undisguises self.",
            "ud"
        ) {
            senderType(AudiencePlayer::class.java)
                .optional("model", stringParser(), blockingStrings { ctx, _ -> ctx.sender().sender.toRegistry()?.trackers()?.map(Tracker::name) ?: emptyList() })
                .handler(::undisguise)
        }
        create(
            "play",
            "Plays player animation",
            "p"
        ) {
            required("limb", stringParser(), LIMB_SUGGESTION)
                .required(
                    "animation",
                    stringParser(),
                    blockingStrings { ctx, _ -> ctx.nullableString("limb") { BetterModel.limbOrNull(it)?.animations()?.keys } ?: emptySet()  }
                )
                .optional("loop_type", enumParser(AnimationIterator.Type::class.java))
                .optional("hide", booleanParser())
                .senderType(AudiencePlayer::class.java)
                .handler(::play)
        }
        create(
            "hide",
            "Hides some entities from target player."
        ) {
            required("model", stringParser(), MODEL_SUGGESTION)
                .required("player", playerParser())
                .required("entities", multipleEntitySelectorParser())
                .handler(::hide)
        }
        create(
            "show",
            "Shows some entities to target player."
        ) {
            required("model", stringParser(), MODEL_SUGGESTION)
                .required("player", playerParser())
                .required("entities", multipleEntitySelectorParser())
                .handler(::show)
        }
        create(
            "version",
            "Checks BetterModel's version",
            "v"
        ) {
            handler(::version)
        }
    }
}

private fun hide(context: CommandContext<Audience>) {
    val sender = context.sender()
    val model = context.get<String>("model")
    val player = context.get<Player>("player").wrap()
    var success = false
    context.get<MultipleEntitySelector>("entities").values().forEach {
        if (it.toRegistry()?.tracker(model)?.hide(player) == true) success = true
    }
    if (!success) sender.warn("Failed to hide any of provided entities.")
}

private fun show(context: CommandContext<Audience>) {
    val sender = context.sender()
    val model = context.get<String>("model")
    val player = context.get<Player>("player").wrap()
    var success = false
    context.get<MultipleEntitySelector>("entities").values().forEach {
        if (it.toRegistry()?.tracker(model)?.show(player) == true) success = true
    }
    if (!success) sender.warn("Failed to show any of provided entities.")
}

private fun disguise(context: CommandContext<AudiencePlayer>) {
    val audience = context.sender()
    val player = audience.sender
    val scaling = if (context.getOrDefault("scaling", true)) ModelScaler.entity() else ModelScaler.defaultScaler()
    context.model("model") { return audience.warn("Unable to find this model: $it") }.getOrCreate(player.wrap(), TrackerModifier.DEFAULT) {
        it.scaler(scaling)
    }
}

private fun undisguise(context: CommandContext<AudiencePlayer>) {
    val audience = context.sender()
    val player = audience.sender
    val model = context.nullable<String>("model")
    if (model != null) {
        player.toTracker(model)?.close() ?: audience.warn("Cannot find this model to undisguise: $model")
    } else player.toRegistry()?.close() ?: audience.warn("Cannot find any model to undisguise")
}

private fun spawn(context: CommandContext<AudiencePlayer>) {
    val audience = context.sender()
    val player = audience.sender
    val model = context.model("model") { return audience.warn("モデル $it が見つかりません。名前を確かめてください（入力中に出る候補から選べます）") }
    val modelName = context.get<String>("model")
    val type = context.nullable("type", EntityType.HUSK)
    val scale = context.nullable("scale", 1.0)
    val loc = context.nullable("location") { player.location }.clone().apply {
        if (world == null) world = player.world
    }
    val world = loc.world
    val typeName = type.name.lowercase()
    // ピースフルではバニラが敵対モブを次の tick に DESPAWN で消すので、出す前に止める。
    if (refusesInPeaceful(world.difficulty == Difficulty.PEACEFUL, isHostileClass(type.entityClass, Enemy::class.java))) {
        return audience.warn("${world.name} は難易度がピースフルなので、敵対モブの $typeName は出せません。難易度を上げるか、敵対しない type を指定してください（例: /bm spawn $modelName villager）")
    }
    // 指定座標は送信者と別のリージョンにある場合がある（Folia）。そのリージョンのスレッドで探索・spawn・モデル作成を行う。
    loc.wrap().task {
        val position = if (world.isChunkLoaded(loc.blockX shr 4, loc.blockZ shr 4)) {
            val size = type.spawnSize(world, loc)
            findSpawnPosition(
                loc.x, loc.y, loc.z, size.first, size.second, type.isFlying()
            ) { minX, minY, minZ, maxX, maxY, maxZ -> world.overlapsBlocks(minX, minY, minZ, maxX, maxY, maxZ) }
        } else SpawnPosition(loc.x, loc.y, loc.z) // 読み込まれていない場所は同期で読み込まず、指定位置のまま出す
        if (position == null) {
            return@task audience.warn("${loc.x.pos()}, ${loc.y.pos()}, ${loc.z.pos()} の周りに、モブが埋まらずに立てる場所がありません。出していません。開けた場所の座標を指定してください")
        }
        val spawnLoc = Location(world, position.x, position.y, position.z, loc.yaw, loc.pitch)
        val entity = try {
            world.spawnEntity(spawnLoc, type)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (entity == null || !entity.isValid) {
            return@task audience.warn("$typeName を出せませんでした。保護区域やモブの出現を制限するプラグインに止められた可能性があります")
        }
        model.create(entity.wrap(), TrackerModifier.DEFAULT) { tracker -> tracker.scaler(ModelScaler.entity().multiply(scale.toFloat())) }
        val moved = position.movedFrom(loc.x, loc.y, loc.z)
        val gone = { audience.warn("$typeName を出しましたが、すぐにサーバーか別のプラグインに消されました。モデルも一緒に消えています") }
        // 1 tick 後にまだ生きているか見直す。エンティティのスケジューラが retired を返した時は、既に消えている。
        entity.wrap().taskLater(
            1,
            {
                if (entity.isValid) {
                    audience.info(
                        "$typeName にモデル $modelName を付けて出しました（${world.name} の ${position.x.pos()}, ${position.y.pos()}, ${position.z.pos()}）" +
                            if (moved) "。指定の位置では埋まるため、近くの空いた場所へずらしました" else ""
                    )
                } else gone()
            },
            gone
        )
    }
}

private fun Double.pos() = String.format(Locale.ROOT, "%.1f", this)

private val FLYING_TYPES = setOf("BAT", "BLAZE", "VEX", "ALLAY", "BEE", "PARROT", "GHAST", "HAPPY_GHAST", "PHANTOM", "ENDER_DRAGON", "WITHER")

/** 床が無くても出してよい（飛ぶ）種類か。 */
private fun EntityType.isFlying() = name in FLYING_TYPES

/** 出す前の種類の当たり判定（幅, 高さ）。世界に追加しないエンティティを作って測り、作れなければ人型の大きさにする。 */
private fun EntityType.spawnSize(world: World, loc: Location): Pair<Double, Double> = try {
    val box = world.createEntity(loc, entityClass ?: error("no class")).boundingBox
    max(box.widthX, box.widthZ) to box.height
} catch (_: Exception) {
    0.6 to 1.8
}

/**
 * 箱がブロックの当たり判定と重なるか。読み込まれていないチャンクや、今のリージョンが持たない場所に触れる箱は、
 * 同期で読み込んだり別リージョンへ触ったりせず「重なる（使えない）」として返す。
 */
private fun World.overlapsBlocks(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double): Boolean {
    val box = BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)
    for (bx in floor(minX).toInt()..floor(maxX).toInt()) for (bz in floor(minZ).toInt()..floor(maxZ).toInt()) {
        if (!isChunkLoaded(bx shr 4, bz shr 4)) return true
        // 所有の判定はチャンク単位（ブロック座標を渡さない）。Folia 以外にはこの API が無い。
        if (BetterModelBukkit.IS_FOLIA && !Bukkit.isOwnedByCurrentRegion(this, bx shr 4, bz shr 4)) return true
        for (by in floor(minY).toInt()..floor(maxY).toInt()) {
            if (by < minHeight || by >= maxHeight) continue
            val block = getBlockAt(bx, by, bz)
            if (block.isPassable) continue
            if (block.collisionShape.boundingBoxes.any { it.clone().shift(bx.toDouble(), by.toDouble(), bz.toDouble()).overlaps(box) }) return true
        }
    }
    return false
}

private fun version(context: CommandContext<Audience>) {
    val sender = context.sender()
    sender.info("Searching version, please wait...")
    PLATFORM.scheduler().asyncTask {
        val version = LATEST_VERSION
        sender.infoNotNull(
            emptyComponentOf(),
            "Current: ${PLATFORM.semver()}".toComponent(),
            version.release?.let { version -> componentOf("Latest release: ") { append(version.toURLComponent()) } },
            version.snapshot?.let { version -> componentOf("Latest snapshot: ") { append(version.toURLComponent()) } }
        )
    }
}

private fun reload(context: CommandContext<Audience>) {
    val audience = context.sender()
    PLATFORM.scheduler().asyncTask {
        audience.info("Start reloading. please wait...")
        when (val result = PLATFORM.reload(audience)) {
            is OnReload -> audience.warn("BetterModel is still on reload!")
            is Success -> {
                audience.info(
                    emptyComponentOf(),
                    "Reload completed. (${result.totalTime().withComma()}ms)".toComponent(GREEN),
                    "Assets reload time - ${result.assetsTime().withComma()}ms".toComponent {
                        color(GRAY)
                        hoverEvent("Reading all config and model.".toComponent().toHoverEvent())
                    },
                    "Packing time - ${result.packingTime().withComma()}ms".toComponent {
                        color(GRAY)
                        hoverEvent("Packing all model to resource pack.".toComponent().toHoverEvent())
                    },
                    "${BetterModel.models().size.withComma()} of models are loaded successfully. (${result.length().toByteFormat()})".toComponent(YELLOW),
                    (if (result.packResult.changed()) "${result.packResult.size().withComma()} of files are zipped." else "Zipping is skipped due to the same result.").toComponent(YELLOW),
                    emptyComponentOf()
                )
            }
            is Failure -> {
                audience.warn(
                    emptyComponentOf(),
                    "Reload failed.".toComponent(),
                    "Please read the log to find the problem.".toComponent(),
                    emptyComponentOf()
                )
                audience.warn()
                result.throwable.handleException("Reload failed.")
            }
        }
    }
}

private fun play(context: CommandContext<AudiencePlayer>) {
    val audience = context.sender()
    val player = audience.sender
    val limb = context.limb("limb") { return audience.warn("Unable to find this limb: $it") }
    val animation = context.string("animation") { limb.animation(it).orElse(null) ?: return audience.warn("Unable to find this animation: $it") }
    val loopType = context.nullable("loop_type", AnimationIterator.Type.PLAY_ONCE)
    val hide = context.nullable<Boolean>("hide") != false
    limb.getOrCreate(player.wrap(), TrackerModifier.DEFAULT) {
        it.hideOption(if (hide) EntityHideOption.DEFAULT else EntityHideOption.FALSE)
    }.run {
        if (!animate(animation, AnimationModifier(0, 0, loopType), ::close)) close()
    }
}

private fun test(context: CommandContext<Audience>) {
    val audience = context.sender()
    val model = context.model("model") { return audience.warn("Unable to find this model: $it") }
    val animation = context.string("animation") { str -> model.animation(str).orElse(null) ?: return audience.warn("Unable to find this animation: $str") }
    val player = context.nullable("player") { (audience as? AudiencePlayer)?.sender ?: return audience.warn("Unable to find target player.") }
    val location = context.nullable("location") {
        player.location.apply {
            add(Vector(0, 0, 10).rotateAroundY(-Math.toRadians(yaw.toDouble())))
            yaw += 180
        }
    }
    model.create(location.wrap()).run {
        spawn(player.wrap())
        animate(animation, AnimationModifier(0, 0, AnimationIterator.Type.PLAY_ONCE), ::close)
    }
}
