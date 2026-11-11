/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.nms

import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [StampedValue] と [TransformationState] が、距離（最後に送った版）の違う受け手のそれぞれに、
 * 変わった項目の最新の値を渡せることを確かめる。
 */
class TransformationStateTest {

    @Test
    fun `項目は変わった版を覚え、同じ値では版を進めない`() {
        val value = StampedValue(floatArrayOf(0f), { a, b -> a[0] == b[0] }, { dest, src -> dest[0] = src[0] }, { it.copyOf() })
        assertFalse(value.changedAfter(0))
        value.set(floatArrayOf(0f), 3)
        assertFalse(value.changedAfter(0))
        value.set(floatArrayOf(2f), 4)
        assertTrue(value.changedAfter(3))
        assertFalse(value.changedAfter(4))
        // 取り出しても印は消えない: 何度でも同じ結果になる
        assertEquals(2f, value.copyIfChangedAfter(3)!![0])
        assertEquals(2f, value.copyIfChangedAfter(3)!![0])
        assertNull(value.copyIfChangedAfter(4))
        // 取り出した値は複製で、その後の変更に影響されない
        val taken = value.copy()
        value.set(floatArrayOf(5f), 5)
        assertEquals(2f, taken[0])
        assertEquals(5f, value.copy()[0])
    }

    @Test
    fun `同じフレームで、版の違う受け手に続けて詰めても、どちらにも変わった項目が届く`() {
        // 不具合 (a): 2 人目以降のバンドラーでは「変化なし」と判定され、何も詰められなかった
        val state = TransformationState()
        // 拡大は初期値（TransformationState の初期値は 0）と同じ値を渡し、変わっていない項目として扱わせる
        state.update(7, Vector3f(1f, 2f, 3f), Vector3f(), Quaternionf())
        val near = assertNotNull(state.changesAfter(6))
        val far = assertNotNull(state.changesAfter(2))
        assertEquals(Vector3f(1f, 2f, 3f), near.translation)
        assertEquals(Vector3f(1f, 2f, 3f), far.translation)
        // 回転・拡大は初期値のままなので、どちらにも詰めない
        assertNull(near.rotation)
        assertNull(near.scale)
        assertEquals(1, near.count)
        assertEquals(1, far.count)
    }

    @Test
    fun `その骨が変わらなくなった後でも、遠い受け手の番に最新の値が届く`() {
        // 不具合 (b): 近い人へ送って印が消えた後、遠い人の番では、骨がもう変わっていないと何も送られなかった
        val state = TransformationState()
        state.update(1, Vector3f(1f, 0f, 0f), Vector3f(1f, 1f, 1f), Quaternionf())
        // 近い人（版 0 まで受け取り済み）に送る
        assertNotNull(state.changesAfter(0))
        // 以後、骨は変わらない（同じ姿勢で更新が来ても版は進まない）
        state.update(1, Vector3f(1f, 0f, 0f), Vector3f(1f, 1f, 1f), Quaternionf())
        // 遠い人（版 0 まで受け取り済み、番が遅れた）にも届く
        val far = assertNotNull(state.changesAfter(0))
        assertEquals(Vector3f(1f, 0f, 0f), far.translation)
        // 受け取った後（版 1 まで）は届かない
        assertNull(state.changesAfter(1))
    }

    /** 視聴者 1 人。ティッカーと同じに「最後に送った版」と間引きの段で、自分の番だけ受け取る。 */
    private class Viewer(val step: Int, var sent: Long = 0L) {
        var translation = Vector3f()
        var rotation = Quaternionf()
        var scale = Vector3f()

        fun receive(changes: TransformationState.Changes) {
            changes.translation?.let { translation = it }
            changes.rotation?.let { rotation = it }
            changes.scale?.let { scale = it }
        }
    }

    @Test
    fun `step 1 と step 4 の 2 人に数フレーム回すと、両方が最新の値を受け取る`() {
        val state = TransformationState()
        val near = Viewer(1)
        val far = Viewer(4)
        val viewers = listOf(near, far)
        var version = 0L

        // フレーム 1〜6 の途中で骨が動き、その後は止まる。
        val poses = listOf(
            Triple(Vector3f(1f, 0f, 0f), Vector3f(1f, 1f, 1f), Quaternionf().rotateY(0.3f)),
            Triple(Vector3f(2f, 0f, 0f), Vector3f(1f, 1f, 1f), Quaternionf().rotateY(0.6f)),
            Triple(Vector3f(3f, 0f, 0f), Vector3f(2f, 2f, 2f), Quaternionf().rotateY(0.9f)),
            null,
            null,
            null,
            null,
            null
        )
        var latest = Triple(Vector3f(), Vector3f(), Quaternionf())
        poses.forEachIndexed { index, pose ->
            val frame = index + 1
            var changed = false
            if (pose != null) {
                version++
                changed = true
                latest = pose
            }
            // ティッカーと同じ順序: 版ごとに、番が来た視聴者へ「最後に送った版より後」を詰める
            if (changed || viewers.any { it.sent < version }) {
                for (viewer in viewers) {
                    if (viewer.sent >= version) continue
                    if (viewer.step > 1 && frame % viewer.step != 0) continue
                    // 1 つの骨の状態を全員で共有し、同じフレームに何度も（版の違う受け手ごとに）更新・取り出しをする
                    state.update(version, latest.first, latest.second, latest.third)
                    state.changesAfter(viewer.sent)?.let(viewer::receive)
                    viewer.sent = version
                }
            }
        }

        for (viewer in viewers) {
            assertEquals(version, viewer.sent, "step ${viewer.step} が最新の版を受け取っていない")
            assertEquals(latest.first, viewer.translation, "step ${viewer.step} の位置")
            assertEquals(latest.second, viewer.scale, "step ${viewer.step} の拡大")
            assertTrue(latest.third.equals(viewer.rotation, 1e-6f), "step ${viewer.step} の回転")
        }
    }

    @Test
    fun `途中のフレームを飛ばした遠い受け手も、その間に動いて止まった骨の最新の値を受け取る`() {
        val state = TransformationState()
        val far = Viewer(4)
        // 版 1〜3 で動き、版 3 の位置で止まる。far は版 3 の後（フレーム 4）で初めて番が来る
        for (v in 1L..3L) state.update(v, Vector3f(v.toFloat(), 0f, 0f), Vector3f(1f, 1f, 1f), Quaternionf())
        state.changesAfter(far.sent)?.let(far::receive)
        far.sent = 3
        assertEquals(Vector3f(3f, 0f, 0f), far.translation)
    }

    @Test
    fun `1 人だけの時は、変わった項目だけを詰め、送る量は増えない`() {
        val state = TransformationState()
        var sent = 0L
        // 位置と回転だけが毎フレーム変わる（拡大は固定）
        for (v in 1L..5L) {
            state.update(v, Vector3f(v.toFloat(), 0f, 0f), Vector3f(), Quaternionf().rotateY(v * 0.2f))
            val changes = assertNotNull(state.changesAfter(sent))
            assertEquals(2, changes.count)
            assertNull(changes.scale)
            sent = v
        }
        // 変わらないフレームは何も詰めない
        state.update(6, Vector3f(5f, 0f, 0f), Vector3f(), Quaternionf().rotateY(1.0f))
        assertNull(state.changesAfter(sent))
    }

    @Test
    fun `スナップショットは全項目の複製で、その後の更新に影響されない`() {
        val state = TransformationState()
        state.update(1, Vector3f(1f, 1f, 1f), Vector3f(2f, 2f, 2f), Quaternionf())
        val all = state.snapshot()
        state.update(2, Vector3f(9f, 9f, 9f), Vector3f(2f, 2f, 2f), Quaternionf())
        assertEquals(3, all.count)
        assertEquals(Vector3f(1f, 1f, 1f), all.translation)
    }

    @Test
    fun `無人の間に姿勢が変わっても、後から来た視聴者に最新の姿勢が全部届く`() {
        // 視聴者がいない間（または遠い視聴者が番を待つ間）は、骨が動いても状態へ取り込まれず、状態は古いまま。
        // 新しい視聴者へのスポーンのパケットは、その古い値で作られる。
        val state = TransformationState()
        state.update(1, Vector3f(1f, 0f, 0f), Vector3f(1f, 1f, 1f), Quaternionf())
        // 版 2〜4 は無人で、骨だけが動いた。版 5 で新しい視聴者が来る（何も受け取っていない版 -1 で登録）。
        val latestRotation = Quaternionf().rotateY(0.9f)
        state.update(5, Vector3f(7f, 0f, 0f), Vector3f(2f, 2f, 2f), latestRotation)
        val changes = assertNotNull(state.changesAfter(-1))
        assertEquals(Vector3f(7f, 0f, 0f), changes.translation)
        assertEquals(Vector3f(2f, 2f, 2f), changes.scale)
        assertTrue(latestRotation.equals(assertNotNull(changes.rotation), 1e-6f), "回転")
        // 受け取った後（版 5 まで）は、同じ値で届かない
        assertNull(state.changesAfter(5))
    }

    @Test
    fun `一度も変わっていない項目は、新しい視聴者にも詰めない（スポーンの初期値と同じ）`() {
        val state = TransformationState()
        state.update(3, Vector3f(1f, 0f, 0f), Vector3f(), Quaternionf())
        val changes = assertNotNull(state.changesAfter(-1))
        assertEquals(1, changes.count)
        assertNull(changes.rotation)
        assertNull(changes.scale)
    }

    @Test
    fun `視聴者の版がずれている時、骨が止まった後の拡大の変化が近い人にも遠い人にも届く`() {
        val state = TransformationState()
        // 骨の姿勢は版 5 で止まった。近い人は版 5 まで、遠い人は版 3 までを受け取っている。
        state.update(5, Vector3f(1f, 0f, 0f), Vector3f(1f, 1f, 1f), Quaternionf())
        assertNotNull(state.changesAfter(3))
        assertNull(state.changesAfter(5))

        // 版 6 で拡大だけが変わった（骨の姿勢は変わらない）。ティッカーの今の版 6 で記録する。
        state.update(6, Vector3f(1f, 0f, 0f), Vector3f(2f, 2f, 2f), Quaternionf())
        val near = assertNotNull(state.changesAfter(5), "近い人に拡大の変化が届かない")
        assertEquals(Vector3f(2f, 2f, 2f), near.scale)
        assertNull(near.translation)

        // 遠い人の番は版 8。その間にも骨は止まったまま。今の版 8 で詰め直しても、遠い人は版 3 より後の分を全部受け取る。
        state.update(8, Vector3f(1f, 0f, 0f), Vector3f(2f, 2f, 2f), Quaternionf())
        val far = assertNotNull(state.changesAfter(3), "遠い人に変化が届かない")
        assertEquals(Vector3f(2f, 2f, 2f), far.scale)
        assertEquals(Vector3f(1f, 0f, 0f), far.translation)
        // 詰め直しても版は進まないので、近い人（版 6 まで受け取り済み）には重ねて届かない
        assertNull(state.changesAfter(6))
    }

    @Test
    fun `項目の版に骨自身の変更の版を使うと、先に受け取った近い人は拡大の変化を取りこぼす（今の版を渡す理由）`() {
        val state = TransformationState()
        state.update(5, Vector3f(1f, 0f, 0f), Vector3f(1f, 1f, 1f), Quaternionf())
        // 近い人は版 5 まで受け取り済み。骨の変更の版は 5 のまま、拡大だけが変わって詰め直された。
        state.update(5, Vector3f(1f, 0f, 0f), Vector3f(2f, 2f, 2f), Quaternionf())
        assertNull(state.changesAfter(5), "骨の版 5 で記録すると、版 5 まで受け取った人には届かない")
        // ティッカーの今の版（6）で記録すれば届く
        state.update(6, Vector3f(1f, 0f, 0f), Vector3f(3f, 3f, 3f), Quaternionf())
        assertEquals(Vector3f(3f, 3f, 3f), assertNotNull(state.changesAfter(5)).scale)
    }
}
