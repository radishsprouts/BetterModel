/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.tracker

import kr.toxicity.model.api.tracker.NameChangeDetector
import net.kyori.adventure.text.Component
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * エンティティの名前の変更を、モデルのネームタグへ追随させる判定を確かめる。
 */
class NameChangeDetectorTest {

    @Test
    fun `作成時と同じ参照なら変更とみなさない`() {
        val name = Component.text("ゴブリン")
        val detector = NameChangeDetector(name)
        assertFalse(detector.changed(name))
        assertSame(name, detector.last())
    }

    @Test
    fun `参照が違っても内容が同じなら変更とみなさず、新しい参照を覚える`() {
        val detector = NameChangeDetector(Component.text("ゴブリン"))
        val same = Component.text("ゴブリン")
        assertFalse(detector.changed(same))
        assertSame(same, detector.last())
        // 覚えた参照と同じなら、次からは参照の比較だけで済む。
        assertFalse(detector.changed(same))
    }

    @Test
    fun `名前が変わったら一度だけ変更とみなす`() {
        val detector = NameChangeDetector(Component.text("ゴブリン"))
        val renamed = Component.text("ボス")
        assertTrue(detector.changed(renamed))
        assertEquals(renamed, detector.last())
        assertFalse(detector.changed(renamed))
        assertFalse(detector.changed(Component.text("ボス")))
    }

    @Test
    fun `名前が無い状態から付いた時と、消された時は変更とみなす`() {
        val detector = NameChangeDetector(null)
        assertFalse(detector.changed(null))
        assertTrue(detector.changed(Component.text("ボス")))
        assertTrue(detector.changed(null))
        assertNull(detector.last())
        assertFalse(detector.changed(null))
    }

    @Test
    fun `装飾だけが変わっても変更とみなす`() {
        val detector = NameChangeDetector(Component.text("ボス"))
        assertTrue(detector.changed(Component.text("ボス").decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)))
    }
}
