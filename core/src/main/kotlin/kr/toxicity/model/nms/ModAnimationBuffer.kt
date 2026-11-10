/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.nms

import io.netty.buffer.ByteBuf
import kr.toxicity.model.api.util.MathUtil
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * `modelengine:bulk_data` のペイロード（MOD 用のアニメーション一括データ）を組み立てる。
 *
 * 版に依存しない部分だけを持ち、[ByteBuf] に書き込むことしかしない。
 * パケットへの包み込みと送信は、各 NMS 版の実装が行う。
 *
 * @param initialCapacity 書き込み処理の一覧の初期容量
 */
class ModAnimationBuffer(initialCapacity: Int) {

    companion object {

        /** カスタムペイロードのチャンネル名。 */
        const val KEY = "modelengine:bulk_data"

        const val PACKET_TYPE_BULK_DATA = 0x00

        const val FIELD_TRANSLATION = 1 shl 0
        const val FIELD_LEFT_ROTATION = 1 shl 1
        const val FIELD_SCALE = 1 shl 2
        const val FIELD_TRANSFORM_DURATION = 1 shl 4

        private val EMPTY_BUILD_TASK: (ByteBuf) -> Unit = {}

        /** Minecraft の VarInt と同じ形式（7 ビットずつ、続きがあれば最上位ビットを立てる）で書く。 */
        internal fun ByteBuf.writeVarInt(value: Int) {
            var v = value
            while ((v and -128) != 0) {
                writeByte((v and 127) or 128)
                v = v ushr 7
            }
            writeByte(v)
        }
    }

    private val builderList = ArrayList<(ByteBuf) -> Unit>(initialCapacity)
    private val appender = Appender()

    /**
     * 1 つのエンティティ分のデータを追加する。何も追加されなかった場合は何も書かない。
     *
     * @param id エンティティの ID
     * @param scope [Appender] に変更したい項目を追加する処理
     */
    fun append(id: Int, scope: Appender.() -> Unit) {
        val build = appender.init().apply(scope).build(id)
        if (build !== EMPTY_BUILD_TASK) builderList += build
    }

    /**
     * ペイロードの本体（種別・エンティティ数・各エンティティのデータ）を書き込む。
     * チャンネル名（[KEY]）は含まない。
     *
     * @param buf 書き込み先
     */
    fun write(buf: ByteBuf) {
        buf.writeByte(PACKET_TYPE_BULK_DATA)
        buf.writeVarInt(builderList.size)
        builderList.forEach { builder -> builder(buf) }
    }

    /**
     * 1 つのエンティティ分の差分を、フィールドのマスクと書き込み処理に変換する。
     * インスタンスは使い回され、[init] で初期化してから使う。
     */
    class Appender {
        private var mask = 0
        private val isEmpty get() = mask == 0

        private var buildTask = EMPTY_BUILD_TASK

        fun init() = apply {
            mask = 0
            buildTask = EMPTY_BUILD_TASK
        }

        fun appendPosition(vector: Vector3f) {
            mask = mask or FIELD_TRANSLATION
            task {
                writeFloat(it, vector.x)
                writeFloat(it, vector.y)
                writeFloat(it, vector.z)
            }
        }

        fun appendScale(vector: Vector3f) {
            mask = mask or FIELD_SCALE
            task {
                writeFloat(it, vector.x)
                writeFloat(it, vector.y)
                writeFloat(it, vector.z)
            }
        }

        fun appendRotation(quaternion: Quaternionf) {
            mask = mask or FIELD_LEFT_ROTATION
            task {
                writeFloat(it, quaternion.x)
                writeFloat(it, quaternion.y)
                writeFloat(it, quaternion.z)
                writeFloat(it, quaternion.w)
            }
        }

        fun appendDuration(duration: Int) {
            mask = mask or FIELD_TRANSFORM_DURATION
            task {
                it.writeVarInt(duration)
            }
        }

        fun build(entityId: Int): (ByteBuf) -> Unit {
            if (isEmpty) return EMPTY_BUILD_TASK
            val m = mask
            val t = buildTask
            return {
                it.writeVarInt(entityId)
                it.writeByte(m)
                t(it)
            }
        }

        private fun task(task: (ByteBuf) -> Unit) {
            if (isEmpty) {
                buildTask = task
                return
            }
            val last = buildTask
            buildTask = {
                last(it)
                task(it)
            }
        }

        private fun writeFloat(buf: ByteBuf, float: Float) {
            buf.writeShort(MathUtil.floatToHalf(float).toInt())
        }
    }
}
