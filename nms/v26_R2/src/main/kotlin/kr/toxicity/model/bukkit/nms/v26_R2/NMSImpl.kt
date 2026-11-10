/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.nms.v26_R2

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup
import com.mojang.authlib.GameProfile
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.bone.RenderedBone
import kr.toxicity.model.api.bukkit.BetterModelBukkit
import kr.toxicity.model.api.bukkit.entity.BaseBukkitEntity
import kr.toxicity.model.api.data.blueprint.ModelBoundingBox
import kr.toxicity.model.api.entity.BaseEntity
import kr.toxicity.model.api.entity.BasePlayer
import kr.toxicity.model.api.mount.MountController
import kr.toxicity.model.api.nms.*
import kr.toxicity.model.api.platform.PlatformEntity
import kr.toxicity.model.api.platform.PlatformItemStack
import kr.toxicity.model.api.platform.PlatformLocation
import kr.toxicity.model.api.platform.PlatformPlayer
import kr.toxicity.model.api.player.PlayerSkinParts
import kr.toxicity.model.api.profile.ModelProfile
import kr.toxicity.model.api.tracker.EntityTrackerRegistry
import kr.toxicity.model.api.tracker.TrackerUpdateAction
import kr.toxicity.model.api.util.TransformedItemStack
import kr.toxicity.model.bukkit.nms.PlayerPacketState
import kr.toxicity.model.bukkit.util.unwarp
import kr.toxicity.model.bukkit.util.wrap
import net.kyori.adventure.key.Keyed
import net.minecraft.core.component.DataComponents
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.*
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.network.ServerCommonPacketListenerImpl
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Display.ItemDisplay
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.level.entity.LevelEntityGetter
import net.minecraft.world.level.entity.LevelEntityGetterAdapter
import net.minecraft.world.level.entity.PersistentEntitySectionManager
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.joml.Vector3d
import java.util.*
import java.util.function.Consumer
import java.util.function.IntConsumer

class NMSImpl : NMS {

    companion object {
        private const val INJECT_NAME = "bettermodel_channel_handler"

        //Spigot
        private val getGameProfile: (net.minecraft.world.entity.player.Player) -> GameProfile = createAdaptedFieldGetter { it.gameProfile }
        private val getConnection: (ServerCommonPacketListenerImpl) -> Connection = createAdaptedFieldGetter { it.connection }
        private val spigotChunkAccess = ServerLevel::class.java.fields.firstOrNull {
            it.type == PersistentEntitySectionManager::class.java
        }?.apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        private val ServerLevel.levelGetter
            get(): LevelEntityGetter<Entity> {
                return if (BetterModelBukkit.IS_PAPER) {
                    `moonrise$getEntityLookup`()
                } else {
                    spigotChunkAccess?.get(this)?.let {
                        (it as PersistentEntitySectionManager<*>).entityGetter as LevelEntityGetter<Entity>
                    } ?: throw RuntimeException("LevelEntityGetter")
                }
            }
        private val getEntityById: (LevelEntityGetter<Entity>, Int) -> Entity? = if (BetterModelBukkit.IS_PAPER) { g, i ->
            (g as EntityLookup)[i]
        } else LevelEntityGetterAdapter::class.java.declaredFields.first {
            net.minecraft.world.level.entity.EntityLookup::class.java.isAssignableFrom(it.type)
        }.let {
            it.isAccessible = true
            { e, i ->
                (it[e] as net.minecraft.world.level.entity.EntityLookup<*>).getEntity(i) as? Entity
            }
        }
        private fun Int.toEntity(level: ServerLevel) = getEntityById(level.levelGetter, this)
        //Spigot
        private val hitBoxData by lazy {
            ItemDisplay(EntityTypes.ITEM_DISPLAY, MinecraftServer.getServer().overworld()).run {
                entityData[Display.DATA_POS_ROT_INTERPOLATION_DURATION_ID] = 3
                entityData.nonDefaultValues!!
            }
        }
    }

    override fun hide(channel: PlayerChannelHandler, registry: EntityTrackerRegistry) {
        val target = registry.entity().handle() as? Entity ?: return
        val list = bundlerOf()
        target.entityData.pack(
            valueFilter = { it.id == SHARED_FLAG }
        )?.let {
            list += ClientboundSetEntityDataPacket(target.id, it).toRegistryDataPacket(channel.uuid(), registry)
        }
        if (target is LivingEntity) {
            val packet = if (registry.hideOption(channel.uuid()).equipment) target.toEmptyEquipmentPacket() else target.toEquipmentPacket()
            packet?.let { list += it }
        }
        list.send(channel)
    }

    private fun ClientboundSetEntityDataPacket.toRegistryDataPacket(uuid: UUID, registry: EntityTrackerRegistry) = ClientboundSetEntityDataPacket(id, packedItems().map {
        if (it.id == SHARED_FLAG) SynchedEntityData.DataValue(
            it.id,
            EntityDataSerializers.BYTE,
            registry.entityFlag(uuid, it.value() as Byte)
        ) else it
    })

    inner class PlayerChannelHandlerImpl(
        private val player: CraftPlayer
    ) : PlayerChannelHandler, ChannelDuplexHandler() {
        private val connection = player.handle.connection
        private val uuid = player.uniqueId
        private val base = adapt(player.wrap())
        // ヒットボックスの追跡・装備を隠すかの判定・ディメンション変更時のリセットは、core の共通部分。
        private val state = PlayerPacketState(uuid, connection.player.level().dimension())

        init {
            val pipeline = getConnection(connection).channel.pipeline()
            // 前回の注入が残っていると名前が重複して例外になるため、先に外す。
            pipeline.get(INJECT_NAME)?.let { pipeline.remove(it) }
            pipeline.addBefore(pipeline.first { it.value is Connection }.key, INJECT_NAME, this)
        }

        override fun close() {
            val channel = getConnection(connection).channel
            channel.eventLoop().execute {
                state.clearHitBoxes()
                // 名前だけで外すと、他の注入に差し替わっていた場合や既に外れている場合に誤る。自分自身の時だけ外す。
                val pipeline = channel.pipeline()
                if (pipeline.get(INJECT_NAME) === this) pipeline.remove(this)
            }
        }

        override fun base(): BasePlayer = base
        override fun isModEnabled(): Boolean = (if (BetterModelBukkit.IS_PAPER) player.channels() else player.listeningPluginChannels).contains(ModAnimationBundlerImpl.KEY)

        private val playerModel get() = connection.player.id.toRegistry()

        private fun Int.toPlayerEntity() = toEntity(connection.player.level())
        private fun Int.toRegistry() = state.registryOf(this)

        override fun sendEntityData(registry: EntityTrackerRegistry) {
            val handle = registry.entity().handle() as? Entity ?: return
            val list = bundlerOf(
                ClientboundSetPassengersPacket(handle)
            )
            handle.entityData.pack(
                valueFilter = { it.id == SHARED_FLAG }
            )?.let {
                list += ClientboundSetEntityDataPacket(handle.id, it)
            }
            if (handle is LivingEntity) handle.toEquipmentPacket()?.let {
                list += it
            }
            list.send(player.wrap())
        }

        private fun <T : ClientGamePacketListener> Packet<in T>.handle(): Packet<in T>? {
            when (this) {
                is ClientboundBundlePacket -> {
                    val sub = subPackets()
                    if (sub is Keyed) return this
                    // 中身が1つも変わらなかった時は、束ねパケットを作り直さずそのまま送る。
                    var changed = false
                    val handled = ArrayList<Packet<in ClientGamePacketListener>>()
                    for (packet in sub) {
                        val result = packet.handle()
                        if (result !== packet) changed = true
                        if (result != null) handled += result
                    }
                    return if (changed) ClientboundBundlePacket(handled) else this
                }
                is ClientboundAddEntityPacket -> {
                    state.forgetHitBox(id)
                    val entity = id.toPlayerEntity() ?: return this
                    if (entity is HitBox) {
                        state.trackHitBox(id)
                        return entity.toFakeAddPacket()
                    }
                    val wrap = entity.bukkitEntity.wrap()
                    BetterModel.registry(wrap).ifPresent {
                        wrap.taskLater(1) {
                            it.spawn(player.wrap())
                        }
                    }
                }
                is ClientboundRemoveEntitiesPacket -> {
                    entityIds
                        .asSequence()
                        .mapNotNull {
                            if (state.forgetHitBox(it)) null else it.toRegistry()
                        }
                        .forEach {
                            it.remove()
                        }
                }
                is ClientboundSetPassengersPacket -> {
                    vehicle.toRegistry()?.let {
                        return it.mountPacket(it.entity().handle() as? Entity ?: return this, array = passengers)
                    }
                }
                is ClientboundUpdateAttributesPacket if state.isHitBox(entityId) -> return null
                is ClientboundSetEntityDataPacket -> {
                    if (state.isHitBox(id)) return ClientboundSetEntityDataPacket(id, hitBoxData)
                    id.toRegistry()?.let { registry ->
                        return toRegistryDataPacket(uuid, registry)
                    }
                }
                is ClientboundSetEquipmentPacket -> {
                    if (state.isHitBox(entity)) return null
                    entity.toRegistry()?.let {
                        if (state.hidesEquipment(it)) (it.entity().handle() as? LivingEntity)?.toEmptyEquipmentPacket()?.let { packet ->
                            return packet
                        }
                    }
                }
                is ClientboundRespawnPacket -> {
                    state.changeDimension(commonPlayerSpawnInfo().dimension())
                    playerModel?.let {
                        bundlerOf(it.mountPacket(connection.player)).send(player.wrap())
                    }
                }
                is ClientboundContainerSetSlotPacket if isEquipment(connection.player) && state.hidesEquipment(playerModel) -> {
                    return ClientboundContainerSetSlotPacket(containerId, stateId, slot, EMPTY_ITEM)
                }
                is ClientboundContainerSetContentPacket if containerId == 0 && state.hidesEquipment(playerModel) -> {
                    return ClientboundContainerSetContentPacket(
                        containerId,
                        stateId,
                        // 元のリストは同じパケットを受け取る他の送信先でも使われるため、複製してから書き換える。
                        ArrayList(items).apply {
                            PLAYER_EQUIPMENT_SLOT.forEach(IntConsumer { set(it, EMPTY_ITEM) })
                            set(connection.player.hotbarSlot, EMPTY_ITEM)
                        },
                        carriedItem
                    )
                }
            }
            return this
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            val packet = if (msg is Packet<*>) msg.handle() else msg
            if (packet == null) promise.trySuccess()
            else super.write(ctx, packet, promise)
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            fun EntityTrackerRegistry.updatePlayerLimb() = BetterModel.platform().scheduler().asyncTaskLater(1) {
                if (isClosed) return@asyncTaskLater
                // 非同期スレッドからメニューを触らず、プレイヤーの所有リージョンで再送する。
                player.updateInventoryInRegion()
                trackers().forEach { tracker ->
                    tracker.update(TrackerUpdateAction.itemMapping()) { bone ->
                        !bone.itemMapper.fixed()
                    }
                }
            }
            when (msg) {
                is ServerboundSetCarriedItemPacket -> {
                    playerModel?.let { registry ->
                        if (!state.hidesEquipment(registry)) return super.channelRead(ctx, msg)
                        if (CONFIG.cancelPlayerModelInventory()) {
                            connection.send(ClientboundSetHeldSlotPacket(player.inventory.heldItemSlot))
                            return
                        }
                        registry.updatePlayerLimb()
                    }
                }
                is ServerboundPlayerActionPacket -> {
                    playerModel?.let { registry ->
                        if (!state.hidesEquipment(registry)) return super.channelRead(ctx, msg)
                        if (CONFIG.cancelPlayerModelInventory()) return
                        registry.updatePlayerLimb()
                    }
                }
            }
            super.channelRead(ctx, msg)
        }

        private fun EntityTrackerRegistry.remove() {
            remove(player.wrap())
        }
    }

    override fun mount(registry: EntityTrackerRegistry, bundler: PacketBundler) {
        val entity = registry.entity().handle()
        if (entity is Entity) bundler += registry.mountPacket(entity)
    }

    private fun EntityTrackerRegistry.mountPacket(entity: Entity, array: IntArray = entity.passengers.filter {
        EntityTrackerRegistry.registry(it.uuid) == null
    }.map {
        it.id
    }.toIntArray()): ClientboundSetPassengersPacket {
        return useByteBuf { buffer ->
            buffer.writeVarInt(entity.id)
            buffer.writeVarIntArray(displays()
                .mapToInt {
                    (it as ModelDisplayImpl).display.id
                }.toArray() + array)
            ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer)
        }
    }

    override fun inject(player: PlatformPlayer): PlayerChannelHandlerImpl = PlayerChannelHandlerImpl(player.unwarp() as CraftPlayer)

    override fun createBundler(initialCapacity: Int): PacketBundler = bundlerOf(initialCapacity)
    override fun createParallelBundler(threshold: Int): PacketBundler = parallelBundlerOf(threshold)
    override fun createModAnimationBuilder(initialCapacity: Int): ModAnimationBundler = ModAnimationBundlerImpl(initialCapacity)

    override fun create(location: PlatformLocation, yOffset: Double, initialConsumer: Consumer<ModelDisplay>): ModelDisplay = ModelDisplayImpl(
        Vector3d(location.x(), location.y(), location.z()),
        ItemDisplay(EntityTypes.ITEM_DISPLAY, (location.world().unwarp() as CraftWorld).handle).apply {
            entityData[Display.DATA_POS_ROT_INTERPOLATION_DURATION_ID] = 3
            billboardConstraints = Display.BillboardConstraints.FIXED
            valid = true
            yRot = location.yaw()
            itemTransform = ItemDisplayContext.FIXED
        },
        yOffset
    ).apply {
        initialConsumer.accept(this)
        display.entityData.packDirty()
    }

    override fun createNametag(bone: RenderedBone): ModelNametag = ModelNametagImpl(bone)

    override fun tint(itemStack: PlatformItemStack, rgb: Int): PlatformItemStack {
        return itemStack.unwarp().asVanilla().apply {
            set(DataComponents.CUSTOM_MODEL_DATA, get(DataComponents.CUSTOM_MODEL_DATA)?.let {
                CustomModelData(it.floats, it.flags, it.strings, it.colors
                    .run {
                        if (rgb == 0xFFFFFF) this else map { color ->
                            ARGB.multiply(color, rgb) and 0xFFFFFF
                        }
                    }
                    .ifEmpty { listOf(rgb) })
            } ?: CustomModelData(emptyList(), emptyList(), emptyList(), listOf(rgb)))
        }.asBukkit().wrap()
    }

    override fun withBedrockSeat(itemStack: PlatformItemStack, seat: String): PlatformItemStack {
        val tag = "${NMS.BEDROCK_SEAT_PREFIX}$seat"
        return itemStack.unwarp().asVanilla().apply {
            set(DataComponents.CUSTOM_MODEL_DATA, get(DataComponents.CUSTOM_MODEL_DATA)?.let {
                // 手足の item は防具の strings を先頭に持つので、座席は末尾に置く。
                CustomModelData(it.floats, it.flags, it.strings.filterNot { s -> s.startsWith(NMS.BEDROCK_SEAT_PREFIX) } + tag, it.colors)
            } ?: CustomModelData(emptyList(), emptyList(), listOf(tag), emptyList()))
        }.asBukkit().wrap()
    }

    override fun createHitBox(entity: BaseEntity, bone: RenderedBone, boundingBox: ModelBoundingBox, mountController: MountController, listener: HitBoxListener): HitBox? {
        val handle = entity.handle() as? Entity ?: return null
        return HitBoxImpl(
            boundingBox.center(),
            bone,
            listener,
            handle,
            mountController
        ).craftEntity
    }
    override fun version(): NMSVersion = NMSVersion.V26_R2

    override fun adapt(entity: PlatformEntity): BaseBukkitEntity {
        val craft = entity.unwarp() as CraftEntity
        return BaseEntityImpl(craft)
    }

    override fun adapt(player: PlatformPlayer): BasePlayer {
        val craft = player.unwarp() as CraftPlayer
        return BasePlayerImpl(
            craft,
            dirtyChecked({ getGameProfile(craft.handle) }, { ModelGameProfile(it) }),
            dirtyChecked({ craft.handle.toCustomisation() }, { PlayerSkinParts(it) })
        )
    }

    override fun profile(player: PlatformPlayer): ModelProfile = ModelGameProfile(getGameProfile((player.unwarp() as CraftPlayer).handle))

    override fun createSkinItem(model: String, floats: List<Float>, flags: List<Boolean>, strings: List<String>, colors: List<Int>): TransformedItemStack {
        return VanillaItemStack(Items.PLAYER_HEAD).run {
            set(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(floats, flags, strings, colors))
            set(DataComponents.ITEM_MODEL, Identifier.parse(model))
            TransformedItemStack.of(asBukkit().wrap())
        }
    }

    override fun isProxyOnlineMode(): Boolean = ONLINE_MODE
}
