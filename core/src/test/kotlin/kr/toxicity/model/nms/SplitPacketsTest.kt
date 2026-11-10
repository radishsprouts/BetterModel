/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.nms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SplitPackets] と [DirtyValue] が、nms の各版にあった時と同じ振る舞いをすることを確かめる。
 */
class SplitPacketsTest {

    private class Chunk : PacketChunk<Int> {
        val list = ArrayList<Int>()
        override fun add(packet: Int) {
            list += packet
        }
        override fun isEmpty() = list.isEmpty()
        override fun size() = list.size
    }

    @Test
    fun `最初は入れ物が 1 つあり空である`() {
        val split = SplitPackets<Int, Chunk>(10, { 1 }) { Chunk() }
        assertEquals(1, split.chunks.size)
        assertTrue(split.isEmpty())
        assertEquals(0, split.size())
    }

    @Test
    fun `見積もりがしきい値を超えた次のパケットから新しい入れ物に入れる`() {
        // しきい値 2・各パケットの見積もり 1: 3 つ入れて見積もりが 3（>2）になったら、次の 1 つから新しい入れ物
        val split = SplitPackets<Int, Chunk>(2, { 1 }) { Chunk() }
        (1..7).forEach(split::add)
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7)), split.chunks.map { it.list })
        assertEquals(7, split.size())
        assertFalse(split.isEmpty())
    }

    @Test
    fun `見積もりはパケットごとの大きさで数える`() {
        val split = SplitPackets<Int, Chunk>(10, { it }) { Chunk() }
        listOf(6, 6, 1, 20, 1, 1).forEach(split::add)
        // 6,6 -> 12 > 10 なので 3 つ目から次の入れ物。1 -> 1, 20 -> 21 > 10 なので 5 つ目から次の入れ物
        assertEquals(listOf(listOf(6, 6), listOf(1, 20), listOf(1, 1)), split.chunks.map { it.list })
    }

    @Test
    fun `変更の記録は同じ値では立たず、取り出すと消える`() {
        val current = floatArrayOf(0f)
        val value = DirtyValue(current, { a, b -> a[0] == b[0] }, { dest, src -> dest[0] = src[0] }, { it.copyOf() })
        assertEquals(0, value.cleanIndex)
        value.set(floatArrayOf(0f))
        assertEquals(0, value.cleanIndex)
        value.set(floatArrayOf(2f))
        assertEquals(1, value.cleanIndex)
        val taken = value.takeIfDirty()
        assertEquals(2f, taken!![0])
        assertEquals(0, value.cleanIndex)
        assertEquals(null, value.takeIfDirty())
        // 取り出した値は複製で、その後の変更に影響されない
        value.set(floatArrayOf(5f))
        assertEquals(2f, taken[0])
        assertEquals(5f, value.copy()[0])
    }
}
