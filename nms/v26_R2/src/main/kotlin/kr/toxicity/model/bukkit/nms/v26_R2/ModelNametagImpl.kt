/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.nms.v26_R2

import com.mojang.math.Transformation
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.bone.BoneMovement
import kr.toxicity.model.api.bone.BonePosition
import kr.toxicity.model.api.bone.RenderedBone
import kr.toxicity.model.api.nms.ModelNametag
import kr.toxicity.model.api.nms.PacketBundler
import kr.toxicity.model.api.platform.PlatformLocation
import kr.toxicity.model.api.platform.PlatformPlayer
import kr.toxicity.model.api.util.EntityUtil
import net.kyori.adventure.text.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import java.util.*
import java.util.concurrent.ConcurrentHashMap

internal class ModelNametagImpl(
    private val bone: RenderedBone
) : ModelNametag {
    private companion object {
        private val emptyVector = Vector3f()
        private val emptyTransformation = Transformation(
            Vector3f(-1F / 40F, -0.2F - 1F / 40F, 0F),
            null,
            null,
            null
        )
    }

    // 見えているプレイヤーと、そのプレイヤーへ送った本文の版を持つ。
    private val viewedPlayer = ConcurrentHashMap<UUID, Long>()
    private val display = Display.TextDisplay(
        EntityTypes.TEXT_DISPLAY,
        MinecraftServer.getServer().overworld()
    ).apply {
        entityData[Display.DATA_POS_ROT_INTERPOLATION_DURATION_ID] = 3
        setTransformation(emptyTransformation)
        billboardConstraints = Display.BillboardConstraints.CENTER
    }
    private val posCache = BoneMovement()
    private var alwaysVisible = false
    private var location = BetterModel.platform().adapter().zero()

    override fun component(component: Component?) {
        display.text = component?.asVanilla() ?: VanillaComponent.empty()
    }

    override fun teleport(location: PlatformLocation) {
        this.location = location
    }

    override fun alwaysVisible(alwaysVisible: Boolean) {
        this.alwaysVisible = alwaysVisible
    }

    // 本文が変わるたびに増やす版。プレイヤーごとに、どの版まで送ったかを覚える。
    private var dataVersion = 0L
    private var active = false
    private var sharedPosition = Vec3.ZERO
    private var sharedPositionPacket: ClientboundEntityPositionSyncPacket? = null
    private var sharedAddPacket: ClientboundAddEntityPacket? = null
    private var dirtyPacket: ClientboundSetEntityDataPacket? = null
    private val removePacket = ClientboundRemoveEntitiesPacket(display.id)

    /**
     * tick に1回だけ、移動と変更分の取り出しを行う。
     * 変更分（packDirty）は取り出すと消えるので、ここで1回だけ取り、全員へ同じものを送る。
     */
    override fun update() {
        active = display.text != VanillaComponent.empty()
        if (!active) {
            sharedPositionPacket = null
            sharedAddPacket = null
            dirtyPacket = null
            return
        }
        sharedPosition = positionOf(null)
        display.moveTo(sharedPosition)
        sharedPositionPacket = positionPacket(sharedPosition)
        sharedAddPacket = null
        dirtyPacket = display.entityData.packDirty()?.let {
            dataVersion++
            ClientboundSetEntityDataPacket(display.id, it)
        }
    }

    override fun send(player: PlatformPlayer) {
        if (!active) return
        val uuid = player.uuid()
        val inPoint = alwaysVisible || EntityUtil.isCustomNameVisible(player.location(), location)
        // プレイヤー個別のアニメーション状態を持つ時だけ、そのプレイヤー用の位置を計算し直す。
        val personal = if (inPoint && bone.hasPlayerState(uuid)) positionOf(uuid) else null
        val viewed = viewedPlayer[uuid]
        when {
            inPoint && viewed == null -> {
                viewedPlayer[uuid] = dataVersion
                bundlerOfNotNull(
                    if (personal != null) addPacket(personal) else sharedAddPacket(),
                    display.entityData.pack()?.let {
                        ClientboundSetEntityDataPacket(display.id, it)
                    }
                )
            }
            inPoint -> {
                // 隠されていた間などに本文の更新を逃したプレイヤーには、全体を送り直す。
                val held = viewed ?: dataVersion
                viewedPlayer[uuid] = dataVersion
                bundlerOfNotNull(
                    if (personal != null) positionPacket(personal) else sharedPositionPacket,
                    when {
                        held == dataVersion -> null
                        held == dataVersion - 1 && dirtyPacket != null -> dirtyPacket
                        else -> display.entityData.pack()?.let { ClientboundSetEntityDataPacket(display.id, it) }
                    }
                )
            }
            viewedPlayer.remove(uuid) != null -> bundlerOf(removePacket)
            else -> null
        }?.send(player)
    }

    override fun remove(bundler: PacketBundler) {
        viewedPlayer.clear()
        bundler += removePacket
    }

    override fun remove(player: PlatformPlayer, bundler: PacketBundler) {
        if (viewedPlayer.remove(player.uuid()) != null) bundler += removePacket
    }

    override fun forget(uuid: UUID) {
        viewedPlayer.remove(uuid)
    }

    private fun positionOf(uuid: UUID?): Vec3 {
        val hb = bone.group.hitboxPosition.localOffset
        val pos = bone.worldPosition(BonePosition(emptyVector, hb, uuid), posCache)
        return Vec3(
            location.x() + pos.x,
            location.y() + pos.y,
            location.z() + pos.z
        )
    }

    private fun positionPacket(position: Vec3) = ClientboundEntityPositionSyncPacket(
        display.id,
        PositionMoveRotation(position, display.deltaMovement, display.yRot, display.xRot),
        false
    )

    private fun sharedAddPacket() = sharedAddPacket ?: addPacket(sharedPosition).also { sharedAddPacket = it }

    private fun addPacket(position: Vec3) = ClientboundAddEntityPacket(
        display.id,
        display.uuid,
        position.x,
        position.y,
        position.z,
        display.xRot,
        display.yRot,
        display.type,
        0,
        display.deltaMovement,
        display.yHeadRot.toDouble()
    )
}
