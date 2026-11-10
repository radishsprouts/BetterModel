/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.nms

import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import kr.toxicity.model.api.tracker.EntityTrackerRegistry
import java.util.UUID

/**
 * 1 人のプレイヤーに送るパケットを書き換える時の、版に依存しない判断をまとめる。
 * パケットの種類の判別と組み立て（NMS の型）は、各版の実装が行う。
 *
 * - クライアントがヒットボックスとして扱っているエンティティ ID の追跡
 * - 装備を隠すかどうかの判定
 * - ディメンションが変わった時の追跡のリセット
 *
 * 呼び出しは 1 本のチャンネルのスレッドに限る（スレッドセーフではない）。
 *
 * @param D ディメンションを表す型
 * @param uuid 送信先のプレイヤーの UUID
 * @param initialDimension 現在のディメンション
 */
class PlayerPacketState<D : Any>(
    private val uuid: UUID,
    initialDimension: D
) {
    // クライアント側のエンティティの種類は、削除パケットを書くまで追う（ワールドの検索で見つからなくなった後も）。
    private val hitBoxIds = IntOpenHashSet()
    private var dimension = initialDimension

    /**
     * ヒットボックスとして追跡中の ID かどうかを返す。
     *
     * @param id エンティティの ID
     * @return 追跡中なら true
     */
    fun isHitBox(id: Int): Boolean = hitBoxIds.contains(id)

    /**
     * ヒットボックスの追跡を始める。
     *
     * @param id エンティティの ID
     */
    fun trackHitBox(id: Int) {
        hitBoxIds.add(id)
    }

    /**
     * ヒットボックスの追跡をやめる。
     *
     * @param id エンティティの ID
     * @return 追跡中だったなら true
     */
    fun forgetHitBox(id: Int): Boolean = hitBoxIds.remove(id)

    /** 追跡中のヒットボックスを全て忘れる。 */
    fun clearHitBoxes() {
        hitBoxIds.clear()
    }

    /**
     * 再スポーン時のディメンションを記録する。前と違う場合は、追跡中のヒットボックスを全て忘れる。
     *
     * @param next 再スポーン後のディメンション
     */
    fun changeDimension(next: D) {
        if (dimension != next) hitBoxIds.clear()
        dimension = next
    }

    /**
     * このプレイヤーに対してスポーン済みのモデルの登録情報を返す。
     * ID の表と UUID の表は同時に登録・削除されるため、ID の表だけを引く（UUID の表での二度目の検索をしない）。
     *
     * @param entityId エンティティの ID
     * @return 登録情報。無い場合、またはこのプレイヤーに対してスポーンしていない場合は null
     */
    fun registryOf(entityId: Int): EntityTrackerRegistry? = EntityTrackerRegistry.registry(entityId)?.takeIf {
        it.isSpawned(uuid)
    }

    /**
     * このプレイヤーに対して、装備を隠すかどうかを返す。
     *
     * @param registry モデルの登録情報
     * @return 装備を隠すなら true。登録情報が無ければ false
     */
    fun hidesEquipment(registry: EntityTrackerRegistry?): Boolean = registry?.hideOption(uuid)?.equipment() == true
}
