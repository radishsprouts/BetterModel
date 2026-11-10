/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.nms.v26_R1

import kr.toxicity.model.api.player.PlayerSkinParts
import kr.toxicity.model.api.profile.ModelProfile
import kr.toxicity.model.bukkit.nms.AbstractBasePlayer
import kr.toxicity.model.nms.ProfiledImpl
import net.minecraft.util.Mth
import org.bukkit.craftbukkit.entity.CraftPlayer

internal data class BasePlayerImpl(
    private val delegate: CraftPlayer,
    private val profile: () -> ModelProfile,
    private val skinParts: () -> PlayerSkinParts
) : AbstractBasePlayer(delegate, BaseEntityImpl(delegate), ProfiledImpl(PlayerArmorImpl(delegate), profile, skinParts)) {

    override fun updateInventory() {
        delegate.updateInventoryInRegion()
    }

    override fun bodyYaw(): Float {
        val handle = delegate.handle
        var yaw = -45 * handle.xMovement()
        if (handle.zMovement() < 0) yaw *= -1
        return Mth.wrapDegrees(handle.yHeadRot + yaw)
    }
}
