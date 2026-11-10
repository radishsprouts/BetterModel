/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.nms.v26_R3

import kr.toxicity.model.api.nms.AnimationBundler
import kr.toxicity.model.api.util.MathUtil
import kr.toxicity.model.nms.DirtyValue
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
    private val translation = Item(Vector3f(), DISPLAY_TRANSLATION, MathUtil::isSimilar, Vector3f::set) { Vector3f(it) }
    private val scale = Item(Vector3f(), DISPLAY_SCALE, MathUtil::isSimilar, Vector3f::set) { Vector3f(it) }
    private val rotation = Item(Quaternionf(), DISPLAY_ROTATION, MathUtil::isSimilar, Quaternionf::set) { Quaternionf(it) }

    fun packDirty(entityId: Int, dest: AnimationBundler) {
        val i = translation.cleanIndex + scale.cleanIndex + rotation.cleanIndex
        if (i == 0) return
        (dest.mod as ModAnimationBundlerImpl).append(entityId) {
            dest.standard += ClientboundSetEntityDataPacket(entityId, buildList(i + 2) {
                add(DISPLAY_INTERPOLATION_DELAY)
                translation.value?.let { appendPosition(it.value); add(it) }
                rotation.value?.let { appendRotation(it.value); add(it) }
                scale.value?.let { appendScale(it.value); add(it) }
                appendDuration(_duration); add(duration)
            })
        }
    }

    fun transform(
        duration: Int,
        translation: Vector3f,
        scale: Vector3f,
        rotation: Quaternionf
    ) {
        _duration = duration
        this.translation.set(translation)
        this.scale.set(scale)
        this.rotation.set(rotation)
    }

    fun pack() = listOf(
        DISPLAY_INTERPOLATION_DELAY,
        duration,
        translation.forceValue,
        scale.forceValue,
        rotation.forceValue
    )

    /** 変更の記録（core の共通部分）を、この版の DataValue に包む。 */
    private class Item<T : Any>(
        initialValue: T,
        private val accessor: EntityDataAccessor<T>,
        dirtyChecker: (T, T) -> Boolean,
        setter: (T, T) -> Unit,
        copier: (T) -> T
    ) {
        private val state = DirtyValue(initialValue, dirtyChecker, setter, copier)

        val cleanIndex get() = state.cleanIndex
        val value get() = state.takeIfDirty()?.let { dataValue(it) }
        // パケットは送信時（別スレッド）に読まれるため、書き換わり続ける現在の値ではなく複製を持たせる（複製は DirtyValue が作る）。
        val forceValue get() = dataValue(state.copy())

        fun set(other: T) {
            state.set(other)
        }

        private fun dataValue(value: T) = SynchedEntityData.DataValue(accessor.id, accessor.serializer, value)
    }
}
