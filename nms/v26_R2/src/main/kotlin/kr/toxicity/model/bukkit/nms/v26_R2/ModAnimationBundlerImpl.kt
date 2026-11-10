/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.nms.v26_R2

import kr.toxicity.model.api.nms.ModAnimationBundler
import kr.toxicity.model.api.platform.PlatformPlayer
import kr.toxicity.model.bukkit.util.unwarp
import kr.toxicity.model.nms.ModAnimationBuffer
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.server.MinecraftServer
import org.bukkit.craftbukkit.entity.CraftPlayer

internal class ModAnimationBundlerImpl(initialCapacity: Int) : ModAnimationBundler {

    companion object {
        const val KEY = ModAnimationBuffer.KEY
    }

    // ペイロードの組み立ては core の共通部分。ここでは版ごとのパケットに包むだけ。
    private val animationBuffer = ModAnimationBuffer(initialCapacity)

    private val packet by lazy {
        useByteBuf { packetBuffer ->
            ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(
                RegistryFriendlyByteBuf(
                    packetBuffer,
                    MinecraftServer.getServer().registryAccess()
                ).apply {
                    writeUtf(KEY)
                    useByteBuf {
                        animationBuffer.write(it)
                        writeBytes(it)
                    }
                }
            )
        }
    }

    override fun send(player: PlatformPlayer) {
        (player.unwarp() as CraftPlayer).handle.connection.send(packet)
    }

    fun append(id: Int, scope: ModAnimationBuffer.Appender.() -> Unit) {
        animationBuffer.append(id, scope)
    }
}
