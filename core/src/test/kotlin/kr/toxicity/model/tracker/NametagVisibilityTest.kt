/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.tracker

import kr.toxicity.model.api.tracker.NametagVisibility
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * モデルのネームタグを常に表示するかの決め方を、バニラの見え方に合わせて確かめる。
 */
class NametagVisibilityTest {

    // Java のメソッドは名前付き引数で呼べないので、読みやすいように Kotlin の関数で包む。
    private fun alwaysVisible(playerTagged: Boolean, player: Boolean, customNameVisible: Boolean) =
        NametagVisibility.alwaysVisible(playerTagged, player, customNameVisible)

    @Test
    fun `骨のタグが PLAYER_TAG なら、印に関係なく常に表示する`() {
        assertTrue(alwaysVisible(playerTagged = true, player = false, customNameVisible = false))
        assertTrue(alwaysVisible(playerTagged = true, player = true, customNameVisible = false))
    }

    @Test
    fun `プレイヤーなら、印に関係なく常に表示する`() {
        assertTrue(alwaysVisible(playerTagged = false, player = true, customNameVisible = false))
    }

    @Test
    fun `名札で名前を付けただけの Mob は、常には表示せず見た時に出す`() {
        assertFalse(alwaysVisible(playerTagged = false, player = false, customNameVisible = false))
    }

    @Test
    fun `名前を常に表示する印が立った Mob は、常に表示する`() {
        assertTrue(alwaysVisible(playerTagged = false, player = false, customNameVisible = true))
    }
}
