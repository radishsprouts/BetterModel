/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.nms

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import kr.toxicity.model.api.util.MathUtil
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * nms の各版から core へ移した [ModAnimationBuffer] が、移す前と同じバイト列を出すことを確かめる。
 *
 * 移す前の実装は FriendlyByteBuf（Minecraft 本体）に書いていた。ここでは、その書き込み順と
 * Minecraft 本体の VarInt（桁数ごとに展開した書き方）を、本体に依存しない形で書き直して基準にする。
 */
class ModAnimationBufferTest {

    /** 1 つのエンティティに対する 1 回の追加操作。 */
    private sealed interface Op {
        data class Position(val v: Vector3f) : Op
        data class Scale(val v: Vector3f) : Op
        data class Rotation(val q: Quaternionf) : Op
        data class Duration(val d: Int) : Op
    }

    private data class Entity(val id: Int, val ops: List<Op>)

    // ---- 基準: 移す前の書き込み順を、Minecraft 本体の VarInt と同じ書き方で再現したもの ----

    /** Minecraft 本体の VarInt.write と同じ書き方（桁数ごとに展開）。 */
    private fun ByteBuf.refVarInt(value: Int) {
        if ((value and -128) == 0) {
            writeByte(value)
        } else if ((value and -16384) == 0) {
            writeShort(((value and 127) or 128) shl 8 or (value ushr 7))
        } else if ((value and -2097152) == 0) {
            writeMedium(((value and 127) or 128) shl 16 or ((((value ushr 7) and 127) or 128) shl 8) or (value ushr 14))
        } else if ((value and -268435456) == 0) {
            writeInt(
                ((value and 127) or 128) shl 24 or
                    ((((value ushr 7) and 127) or 128) shl 16) or
                    ((((value ushr 14) and 127) or 128) shl 8) or
                    (value ushr 21)
            )
        } else {
            writeInt(
                ((value and 127) or 128) shl 24 or
                    ((((value ushr 7) and 127) or 128) shl 16) or
                    ((((value ushr 14) and 127) or 128) shl 8) or
                    (((value ushr 21) and 127) or 128)
            )
            writeByte(value ushr 28)
        }
    }

    private fun ByteBuf.refFloat(f: Float) {
        writeShort(MathUtil.floatToHalf(f).toInt())
    }

    private fun reference(entities: List<Entity>): ByteArray {
        val buf = Unpooled.buffer()
        // 何も追加しなかったエンティティは書かれない（移す前も同じ）
        val written = entities.filter { it.ops.isNotEmpty() }
        buf.writeByte(0x00)
        buf.refVarInt(written.size)
        written.forEach { entity ->
            buf.refVarInt(entity.id)
            var mask = 0
            entity.ops.forEach {
                mask = mask or when (it) {
                    is Op.Position -> 1 shl 0
                    is Op.Rotation -> 1 shl 1
                    is Op.Scale -> 1 shl 2
                    is Op.Duration -> 1 shl 4
                }
            }
            buf.writeByte(mask)
            // 本体は、追加した順に書く（マスクの並び順ではない）
            entity.ops.forEach {
                when (it) {
                    is Op.Position -> {
                        buf.refFloat(it.v.x)
                        buf.refFloat(it.v.y)
                        buf.refFloat(it.v.z)
                    }
                    is Op.Scale -> {
                        buf.refFloat(it.v.x)
                        buf.refFloat(it.v.y)
                        buf.refFloat(it.v.z)
                    }
                    is Op.Rotation -> {
                        buf.refFloat(it.q.x)
                        buf.refFloat(it.q.y)
                        buf.refFloat(it.q.z)
                        buf.refFloat(it.q.w)
                    }
                    is Op.Duration -> buf.refVarInt(it.d)
                }
            }
        }
        return buf.toBytes()
    }

    // ---- 検査対象 ----

    private fun actual(entities: List<Entity>): ByteArray {
        val buffer = ModAnimationBuffer(entities.size)
        entities.forEach { entity ->
            buffer.append(entity.id) {
                entity.ops.forEach {
                    when (it) {
                        is Op.Position -> appendPosition(it.v)
                        is Op.Scale -> appendScale(it.v)
                        is Op.Rotation -> appendRotation(it.q)
                        is Op.Duration -> appendDuration(it.d)
                    }
                }
            }
        }
        val buf = Unpooled.buffer()
        buffer.write(buf)
        return buf.toBytes()
    }

    private fun ByteBuf.toBytes() = ByteArray(readableBytes()).also { readBytes(it) }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // ---- 検査 ----

    @Test
    fun `固定値のバイト列が期待どおりになる`() {
        // 種別 0、エンティティ数 1、ID 5、マスク 1（位置）、半精度で 1.0 / 2.0 / 3.0
        val bytes = actual(listOf(Entity(5, listOf(Op.Position(Vector3f(1f, 2f, 3f))))))
        assertContentEquals(bytes(0x00, 0x01, 0x05, 0x01, 0x3C, 0x00, 0x40, 0x00, 0x42, 0x00), bytes)
    }

    @Test
    fun `全項目を追加した順に書き、マスクはその論理和になる`() {
        val entity = Entity(
            1,
            listOf(
                Op.Duration(300),
                Op.Scale(Vector3f(1f, 1f, 1f)),
                Op.Rotation(Quaternionf(0f, 0f, 0f, 1f)),
                Op.Position(Vector3f(0f, 0f, 0f))
            )
        )
        val bytes = actual(listOf(entity))
        // マスク = 位置(1) | 回転(2) | 拡大(4) | 補間時間(16) = 23。補間時間 300 は VarInt で AC 02
        assertContentEquals(
            bytes(
                0x00, 0x01, 0x01, 23,
                0xAC, 0x02,
                0x3C, 0x00, 0x3C, 0x00, 0x3C, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x3C, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            ),
            bytes
        )
    }

    @Test
    fun `何も追加しないエンティティは書かれない`() {
        assertContentEquals(bytes(0x00, 0x00), actual(listOf(Entity(7, emptyList()))))
        assertContentEquals(bytes(0x00, 0x00), actual(emptyList()))
        // 空のものを挟んでも、その後ろのエンティティは正しく書かれる（Appender の使い回しで前の状態が残らない）
        val mixed = listOf(
            Entity(1, listOf(Op.Duration(2))),
            Entity(2, emptyList()),
            Entity(3, listOf(Op.Duration(4)))
        )
        assertContentEquals(bytes(0x00, 0x02, 0x01, 16, 0x02, 0x03, 16, 0x04), actual(mixed))
    }

    @Test
    fun `VarInt の桁の境目で本体と同じバイト列になる`() {
        val values = intArrayOf(
            0, 1, 127, 128, 255, 300, 16383, 16384, 2097151, 2097152,
            268435455, 268435456, Int.MAX_VALUE, -1, Int.MIN_VALUE
        )
        values.forEach { value ->
            val entities = listOf(Entity(value, listOf(Op.Duration(value))))
            assertContentEquals(reference(entities), actual(entities), "VarInt $value")
        }
    }

    @Test
    fun `ランダムな入力で本体と同じバイト列になる`() {
        val random = Random(20261111L)
        repeat(500) { round ->
            val entities = List(random.nextInt(6)) {
                Entity(random.nextInt(1 shl random.nextInt(31)), randomOps(random))
            }
            assertContentEquals(reference(entities), actual(entities), "round $round")
        }
    }

    @Test
    fun `半精度への変換は本体と同じ値を使う`() {
        // 範囲外・小数・負数・ゼロを含めて、floatToHalf の結果がそのまま 2 バイトで書かれる
        val values = floatArrayOf(0f, -0f, 0.5f, -1.5f, 65504f, 1e-8f, 123.456f, Float.MAX_VALUE)
        values.forEach { f ->
            val entities = listOf(Entity(1, listOf(Op.Scale(Vector3f(f, f, f)))))
            assertContentEquals(reference(entities), actual(entities), "half $f")
        }
    }

    @Test
    fun `使い回した Appender は前の内容を引きずらない`() {
        val buffer = ModAnimationBuffer(2)
        buffer.append(1) { appendPosition(Vector3f(1f, 1f, 1f)) }
        buffer.append(2) { appendDuration(3) }
        val buf = Unpooled.buffer()
        buffer.write(buf)
        // 2 件目は補間時間だけ（1 件目の位置が混ざらない）
        assertEquals(
            listOf(0x00, 0x02, 0x01, 0x01, 0x3C, 0x00, 0x3C, 0x00, 0x3C, 0x00, 0x02, 16, 0x03),
            buf.toBytes().map { it.toInt() and 0xFF }
        )
    }

    private fun randomOps(random: Random): List<Op> = List(random.nextInt(5)) {
        when (random.nextInt(4)) {
            0 -> Op.Position(Vector3f(randomFloat(random), randomFloat(random), randomFloat(random)))
            1 -> Op.Scale(Vector3f(randomFloat(random), randomFloat(random), randomFloat(random)))
            2 -> Op.Rotation(Quaternionf(randomFloat(random), randomFloat(random), randomFloat(random), randomFloat(random)))
            else -> Op.Duration(random.nextInt(1 shl random.nextInt(31)))
        }
    }

    private fun randomFloat(random: Random) = (random.nextFloat() - 0.5f) * 200f
}
