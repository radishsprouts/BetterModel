/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.nms

/**
 * パケットを 1 つにまとめて送る入れ物（束ねパケット 1 個分）。
 *
 * @param P パケットの型
 */
interface PacketChunk<P> {
    /**
     * パケットを追加する。
     *
     * @param packet 追加するパケット
     */
    fun add(packet: P)

    /**
     * 空かどうかを返す。
     *
     * @return パケットが 1 つも無ければ true
     */
    fun isEmpty(): Boolean

    /**
     * 入っているパケットの数を返す。
     *
     * @return パケットの数
     */
    fun size(): Int
}

/**
 * パケットを、見積もりの大きさがしきい値を超えるたびに新しい入れ物へ振り分ける。
 * 1 つの束ねパケットが大きくなりすぎないようにするための、版に依存しない部分。
 *
 * @param P パケットの型
 * @param C 入れ物の型
 * @param threshold 入れ物 1 つあたりの見積もりの大きさのしきい値。これを超えた次のパケットから新しい入れ物に入れる
 * @param sizeOf パケット 1 つの見積もりの大きさ
 * @param chunkFactory 空の入れ物を作る処理
 */
class SplitPackets<P, C : PacketChunk<P>>(
    private val threshold: Int,
    private val sizeOf: (P) -> Int,
    private val chunkFactory: () -> C
) {
    private val _chunks = ArrayList<C>()
    private var sizeAssume = 0
    private var selected = newChunk()

    /** これまでに作った入れ物（作った順）。 */
    val chunks: List<C> get() = _chunks

    private fun newChunk(): C = chunkFactory().also {
        sizeAssume = 0
        _chunks += it
    }

    /**
     * 最後の入れ物が空かどうかを返す。
     *
     * @return 最後の入れ物が空なら true
     */
    fun isEmpty(): Boolean = selected.isEmpty()

    /**
     * 全ての入れ物のパケット数の合計を返す。
     *
     * @return パケットの数
     */
    fun size(): Int = _chunks.sumOf { it.size() }

    /**
     * パケットを追加する。直前までの見積もりがしきい値を超えていれば、新しい入れ物に入れる。
     *
     * @param packet 追加するパケット
     */
    fun add(packet: P) {
        selected = if (sizeAssume > threshold) newChunk() else selected
        selected.add(packet)
        sizeAssume += sizeOf(packet)
    }
}
