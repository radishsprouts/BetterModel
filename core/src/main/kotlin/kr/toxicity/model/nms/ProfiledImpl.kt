/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.nms

import kr.toxicity.model.api.armor.PlayerArmor
import kr.toxicity.model.api.nms.Profiled
import kr.toxicity.model.api.player.PlayerSkinParts
import kr.toxicity.model.api.profile.ModelProfile

/**
 * プレイヤーのプロフィール・防具・スキンのパーツを返す [Profiled] の実装。
 *
 * @param playerArmor 防具
 * @param modelProfile プロフィールを返す処理
 * @param playerSkinParts スキンのパーツを返す処理
 */
class ProfiledImpl(
    private val playerArmor: PlayerArmor,
    private val modelProfile: () -> ModelProfile,
    private val playerSkinParts: () -> PlayerSkinParts
) : Profiled {

    override fun profile(): ModelProfile = modelProfile()
    override fun armors(): PlayerArmor = playerArmor
    override fun skinParts(): PlayerSkinParts = playerSkinParts()
}
