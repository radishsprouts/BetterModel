/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.nms

/**
 * 送信する値の「前回送った値から変わったか」を覚えておく。
 * 値の型に依存しないので、各 NMS 版が自分のデータ型（DataValue 等）に包んで使う。
 *
 * @param T 値の型
 * @param current 現在の値。[set] のたびに書き換わる
 * @param dirtyChecker 2 つの値が（許容誤差内で）同じかどうか
 * @param setter 現在の値を書き換える処理（第 1 引数が書き換え先）
 * @param copier 値の複製を作る処理
 */
class DirtyValue<T : Any>(
    private val current: T,
    private val dirtyChecker: (T, T) -> Boolean,
    private val setter: (T, T) -> Unit,
    private val copier: (T) -> T
) {
    private var dirty = false

    /** 変更があれば 1、無ければ 0。変更のあった項目の数を数えるのに使う。 */
    val cleanIndex get() = if (dirty) 1 else 0

    /**
     * 変更があれば値の複製を返し、変更の記録を消す。無ければ null。
     *
     * @return 変更後の値の複製。変更が無ければ null
     */
    fun takeIfDirty(): T? = if (dirty) {
        dirty = false
        copy()
    } else null

    /**
     * 変更の有無にかかわらず、現在の値の複製を返す。
     * パケットは送信時（別スレッド）に読まれるため、書き換わり続ける現在の値そのものではなく複製を渡す。
     *
     * @return 現在の値の複製
     */
    fun copy(): T = copier(current)

    /**
     * 値を設定する。直前の値と（許容誤差内で）同じなら何もしない。
     *
     * @param other 新しい値
     */
    fun set(other: T) {
        if (dirtyChecker(current, other)) return
        dirty = true
        setter(current, other)
    }
}
