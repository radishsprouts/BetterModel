/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.util

import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `/bm spawn` の位置探索と、ピースフルでの敵対モブ判定の単体試験。ブロックは整数座標のセル集合で表す。 */
class SpawnPlacementTest {

    private interface Enemy
    private class Zombie : Enemy
    private class Pig

    /** 与えられたセルを固体として、箱がどれかと重なるかを返す判定。 */
    private fun solid(vararg cells: Triple<Int, Int, Int>): (Double, Double, Double, Double, Double, Double) -> Boolean {
        val set = cells.toSet()
        return { minX, minY, minZ, maxX, maxY, maxZ ->
            var hit = false
            for (bx in floor(minX).toInt()..floor(maxX).toInt()) for (by in floor(minY).toInt()..floor(maxY).toInt()) for (bz in floor(minZ).toInt()..floor(maxZ).toInt()) {
                if (Triple(bx, by, bz) in set) hit = true
            }
            hit
        }
    }

    /** y=63 のセルを一面の床にし、さらに [extra] を固体にした世界。 */
    private fun floorWorld(vararg extra: Triple<Int, Int, Int>): (Double, Double, Double, Double, Double, Double) -> Boolean {
        val cells = extra.toSet()
        return { minX, minY, minZ, maxX, maxY, maxZ ->
            var hit = false
            for (bx in floor(minX).toInt()..floor(maxX).toInt()) for (by in floor(minY).toInt()..floor(maxY).toInt()) for (bz in floor(minZ).toInt()..floor(maxZ).toInt()) {
                if (by == 63 || Triple(bx, by, bz) in cells) hit = true
            }
            hit
        }
    }

    @Test
    fun `指定位置が空いていればそのまま使う`() {
        val pos = findSpawnPosition(10.5, 64.0, 10.5, 0.6, 1.8, false, floorWorld())
        assertEquals(SpawnPosition(10.5, 64.0, 10.5), pos)
        assertFalse(pos!!.movedFrom(10.5, 64.0, 10.5))
    }

    @Test
    fun `上へずらしても埋まるなら横へずらす`() {
        // 指定位置の列が高さ 10 まで柱で埋まっていて、上へずらしても入らない。隣の列は空いている。
        val pillar = (64..74).map { Triple(10, it, 10) }.toTypedArray()
        val pos = findSpawnPosition(10.5, 64.0, 10.5, 0.6, 1.8, false, floorWorld(*pillar))
        assertNotNull(pos)
        assertEquals(64.0, pos.y)
        assertTrue(pos.movedFrom(10.5, 64.0, 10.5))
        assertEquals(1, kotlin.math.abs(pos.x - 10.5).toInt() + kotlin.math.abs(pos.z - 10.5).toInt())
    }

    @Test
    fun `足元が埋まっていれば上へずらす`() {
        // y=64 の周囲 3 マス四方が固体。上の y=65 は空いていて、その下の y=64 が床になる。
        val cells = buildList {
            for (dx in -4..4) for (dz in -4..4) add(Triple(10 + dx, 64, 10 + dz))
        }.toTypedArray()
        val pos = findSpawnPosition(10.5, 64.0, 10.5, 0.6, 1.8, false, floorWorld(*cells))
        assertEquals(SpawnPosition(10.5, 65.0, 10.5), pos)
    }

    @Test
    fun `上方向を水平より先に試す`() {
        // 指定位置の y=64 だけが埋まっている。隣の列は空いているが、上の y=65 が先に選ばれる。
        val pos = findSpawnPosition(10.5, 64.0, 10.5, 0.6, 1.8, false, floorWorld(Triple(10, 64, 10)))
        assertEquals(SpawnPosition(10.5, 65.0, 10.5), pos)
    }

    @Test
    fun `幅の広いモブは隣のブロックに食い込むのでずらす`() {
        // 幅 1.4 のモブは x=10.5 で隣の x=9 と x=11 の列に食い込む。そこに壁を置く。
        val walls = arrayOf(Triple(9, 64, 10), Triple(9, 65, 10), Triple(11, 64, 10), Triple(11, 65, 10))
        val pos = findSpawnPosition(10.5, 64.0, 10.5, 1.4, 1.0, false, floorWorld(*walls))
        assertNotNull(pos)
        assertTrue(pos.movedFrom(10.5, 64.0, 10.5))
    }

    @Test
    fun `範囲内に空きが無ければ null`() {
        // 周囲の広い範囲を高さ 10 まで固体で埋める。
        val cells = buildList {
            for (dx in -10..10) for (dz in -10..10) for (y in 64..74) add(Triple(10 + dx, y, 10 + dz))
        }.toTypedArray()
        assertNull(findSpawnPosition(10.5, 64.0, 10.5, 0.6, 1.8, false, solid(*cells)))
    }

    @Test
    fun `床が無ければ宙には出さない`() {
        // 何も無い空間（床なし）。歩く種類は出せない。
        assertNull(findSpawnPosition(10.5, 200.0, 10.5, 0.6, 1.8, false, solid()))
    }

    @Test
    fun `飛ぶ種類は床が無くてもよい`() {
        assertEquals(SpawnPosition(10.5, 200.0, 10.5), findSpawnPosition(10.5, 200.0, 10.5, 0.9, 0.5, true, solid()))
    }

    @Test
    fun `探す範囲は定数の上限まで`() {
        // 水平の上限ちょうどまでを高さ 10 まで固体で埋める。その外側は空いているが、探す範囲の外なので見つからない。
        val r = SPAWN_SEARCH_HORIZONTAL
        val cells = buildList {
            for (dx in -r..r) for (dz in -r..r) for (y in 64..74) add(Triple(10 + dx, y, 10 + dz))
        }.toTypedArray()
        assertNull(findSpawnPosition(10.5, 64.0, 10.5, 0.6, 1.8, false, floorWorld(*cells)))
    }

    @Test
    fun `ピースフルでは敵対モブだけ止める`() {
        assertTrue(refusesInPeaceful(peaceful = true, hostile = true))
        assertFalse(refusesInPeaceful(peaceful = true, hostile = false))
        assertFalse(refusesInPeaceful(peaceful = false, hostile = true))
        assertFalse(refusesInPeaceful(peaceful = false, hostile = false))
    }

    @Test
    fun `Enemy を実装するクラスだけ敵対と判定する`() {
        assertTrue(isHostileClass(Zombie::class.java, Enemy::class.java))
        assertFalse(isHostileClass(Pig::class.java, Enemy::class.java))
        assertFalse(isHostileClass(null, Enemy::class.java))
    }
}
