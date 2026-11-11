/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.nms

import kr.toxicity.model.api.util.MathUtil
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * 送る値と、それが最後に変わった版を覚えておく。
 * 値の型に依存しないので、各 NMS 版が自分のデータ型（DataValue 等）に包んで使う。
 *
 * 「変わったか」を取り出すと消える印ではなく版で持つので、受け手ごとに最後に送った版が違っても、
 * 同じ値を何人にでも、何回でも判定できる。
 *
 * @param T 値の型
 * @param current 現在の値。[set] のたびに書き換わる
 * @param similar 2 つの値が（許容誤差内で）同じかどうか
 * @param setter 現在の値を書き換える処理（第 1 引数が書き換え先）
 * @param copier 値の複製を作る処理
 */
class StampedValue<T : Any>(
    private val current: T,
    private val similar: (T, T) -> Boolean,
    private val setter: (T, T) -> Unit,
    private val copier: (T) -> T
) {
    private var stamp = Long.MIN_VALUE

    /**
     * この値が、指定した版より後に変わったか。
     *
     * @param since 受け手がすでに持っている最後の版
     * @return 後に変わっていれば true
     */
    fun changedAfter(since: Long) = stamp > since

    /**
     * 変更が指定した版より後なら、値の複製を返す。無ければ null。
     * パケットは送信時（別スレッド）に読まれるため、書き換わり続ける現在の値そのものではなく複製を渡す。
     *
     * @param since 受け手がすでに持っている最後の版
     * @return 現在の値の複製。変更が無ければ null
     */
    fun copyIfChangedAfter(since: Long): T? = if (changedAfter(since)) copy() else null

    /**
     * 変更の有無にかかわらず、現在の値の複製を返す。
     *
     * @return 現在の値の複製
     */
    fun copy(): T = copier(current)

    /**
     * 値を設定する。直前の値と（許容誤差内で）同じなら何もしない。
     *
     * @param other 新しい値
     * @param version 値を設定する版。変わった時だけ記録する
     */
    fun set(other: T, version: Long) {
        if (similar(current, other)) return
        setter(current, other)
        stamp = version
    }
}

/**
 * 表示の位置・回転・拡大の、項目ごとの変更の版。
 * 骨 1 本に 1 つ持ち、全員の受け手が共有する。
 *
 * 版は呼び出し側が単調増加で渡す。受け手が「最後に送った版」より後に変わった項目だけを詰めれば、
 * 距離の違う受け手が何人いても、各自が自分の番に最新の値を受け取れる。
 */
class TransformationState {
    private val translation = StampedValue(Vector3f(), { a, b -> MathUtil.isSimilar(a, b) }, Vector3f::set) { Vector3f(it) }
    private val scale = StampedValue(Vector3f(), { a, b -> MathUtil.isSimilar(a, b) }, Vector3f::set) { Vector3f(it) }
    private val rotation = StampedValue(Quaternionf(), { a, b -> MathUtil.isSimilar(a, b) }, Quaternionf::set) { Quaternionf(it) }

    /**
     * 最新の姿勢を反映する。前と（許容誤差内で）同じ項目は版を進めない。
     *
     * @param version この姿勢の版
     * @param translation 位置
     * @param scale 拡大
     * @param rotation 回転
     */
    fun update(version: Long, translation: Vector3f, scale: Vector3f, rotation: Quaternionf) {
        this.translation.set(translation, version)
        this.scale.set(scale, version)
        this.rotation.set(rotation, version)
    }

    /**
     * 指定した版より後に変わった項目だけの複製を返す。
     *
     * @param since 受け手がすでに持っている最後の版
     * @return 変わった項目の複製。どれも変わっていなければ null
     */
    fun changesAfter(since: Long): Changes? {
        val t = translation.copyIfChangedAfter(since)
        val r = rotation.copyIfChangedAfter(since)
        val s = scale.copyIfChangedAfter(since)
        return if (t == null && r == null && s == null) null else Changes(t, r, s)
    }

    /**
     * 全項目の今の値の複製を返す（スポーンや強制の同期用）。
     *
     * @return 全項目の複製
     */
    fun snapshot() = Changes(translation.copy(), rotation.copy(), scale.copy())

    /**
     * 変わった項目の複製。変わっていない項目は null。
     *
     * @property translation 位置
     * @property rotation 回転
     * @property scale 拡大
     */
    class Changes(val translation: Vector3f?, val rotation: Quaternionf?, val scale: Vector3f?) {
        /** 変わった項目の数 */
        val count get() = (if (translation != null) 1 else 0) + (if (rotation != null) 1 else 0) + (if (scale != null) 1 else 0)
    }
}
