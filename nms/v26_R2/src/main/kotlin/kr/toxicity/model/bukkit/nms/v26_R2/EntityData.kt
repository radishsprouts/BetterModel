/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.nms.v26_R2

import kr.toxicity.model.api.nms.AnimationBundler
import kr.toxicity.model.nms.TransformationState
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Display.ItemDisplay
import net.minecraft.world.entity.Entity
import org.joml.Quaternionf
import org.joml.Vector3f
import java.lang.reflect.Field

internal fun Field.toEntityDataAccessor() = run {
    isAccessible = true
    get(null) as EntityDataAccessor<*>
}

internal fun Class<*>.accessors() = declaredFields.filter { f ->
    EntityDataAccessor::class.java.isAssignableFrom(f.type)
}.map {
    it.toEntityDataAccessor()
}.sortedBy { it.id }

internal val DISPLAY_SET = Display::class.java.accessors()
internal val SHARED_FLAG = Entity::class.java.accessors().first().id
internal val ITEM_DISPLAY_ID = ItemDisplay::class.java.accessors().map {
    it.id
}
internal val ITEM_SERIALIZER = ItemDisplay::class.java.accessors().first()
internal val ITEM_ENTITY_DATA = buildList {
    add(SHARED_FLAG)
    addAll(ITEM_DISPLAY_ID)
    add(Display.DATA_POS_ROT_INTERPOLATION_DURATION_ID.id)
    DISPLAY_SET.subList(7, DISPLAY_SET.size).mapTo(this) { it.id }
}.toIntSet()

@Suppress("UNCHECKED_CAST")
private val DISPLAY_INTERPOLATION_DELAY = (DISPLAY_SET.first() as EntityDataAccessor<Int>).run {
    SynchedEntityData.DataValue(id, serializer, 0)
}
@Suppress("UNCHECKED_CAST")
internal val DISPLAY_INTERPOLATION_DURATION = DISPLAY_SET[1] as EntityDataAccessor<Int>
@Suppress("UNCHECKED_CAST")
internal val DISPLAY_TRANSLATION = DISPLAY_SET[3] as EntityDataAccessor<Vector3f>
@Suppress("UNCHECKED_CAST")
internal val DISPLAY_SCALE = DISPLAY_SET[4] as EntityDataAccessor<Vector3f>
@Suppress("UNCHECKED_CAST")
internal val DISPLAY_ROTATION = DISPLAY_SET[5] as EntityDataAccessor<Quaternionf>


internal class TransformationData {

    private var _duration = 0
    private val duration get() = SynchedEntityData.DataValue(DISPLAY_INTERPOLATION_DURATION.id, DISPLAY_INTERPOLATION_DURATION.serializer, _duration)
    // 項目ごとの変更の版は core の TransformationState が持つ。骨 1 本に 1 つで、全員の受け手が共有する。
    private val state = TransformationState()

    /**
     * 最後に送った版 [since] より後に変わった項目だけを詰める。変わった項目が無ければ何も詰めない。
     * 判定は版で行い、詰めても印は消えないので、同じフレームで別の版の受け手に何度詰んでもよい。
     * 値は複製を渡す（パケットは送信時に別のスレッドで読まれる）。
     */
    fun packChanges(entityId: Int, since: Long, dest: AnimationBundler) {
        val changes = state.changesAfter(since) ?: return
        (dest.mod as ModAnimationBundlerImpl).append(entityId) {
            dest.standard += ClientboundSetEntityDataPacket(entityId, buildList(changes.count + 2) {
                add(DISPLAY_INTERPOLATION_DELAY)
                changes.translation?.let { appendPosition(it); add(dataValue(DISPLAY_TRANSLATION, it)) }
                changes.rotation?.let { appendRotation(it); add(dataValue(DISPLAY_ROTATION, it)) }
                changes.scale?.let { appendScale(it); add(dataValue(DISPLAY_SCALE, it)) }
                appendDuration(_duration); add(duration)
            })
        }
    }

    fun transform(
        duration: Int,
        translation: Vector3f,
        scale: Vector3f,
        rotation: Quaternionf,
        version: Long
    ) {
        _duration = duration
        state.update(version, translation, scale, rotation)
    }

    fun pack(): List<SynchedEntityData.DataValue<*>> {
        val all = state.snapshot()
        return listOf(
            DISPLAY_INTERPOLATION_DELAY,
            duration,
            dataValue(DISPLAY_TRANSLATION, all.translation!!),
            dataValue(DISPLAY_SCALE, all.scale!!),
            dataValue(DISPLAY_ROTATION, all.rotation!!)
        )
    }

    private fun <T : Any> dataValue(accessor: EntityDataAccessor<T>, value: T) = SynchedEntityData.DataValue(accessor.id, accessor.serializer, value)
}
