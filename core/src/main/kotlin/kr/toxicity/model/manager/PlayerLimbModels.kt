/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import kr.toxicity.library.dynamicuv.*

/**
 * プレイヤーの皮膚の部位（頭・胴・腕・脚・マント）の定義。
 * マント以外の部位は、内側の層と、同じ形を少し膨らませてテクスチャの領域を一定量ずらした外側の層（スキンの2層目）でできている。
 *
 * @param namespace 名前空間の供給元
 */
internal class PlayerLimbModels(private val namespace: () -> UVNamespace) {

    private companion object {
        const val DIV_FACTOR = 16F / 0.9375F

        val HEAD_OFFSET = ElementVector(0f, 4f, 0f)
        val BODY_OFFSET = ElementVector(0f, 2f, 0f)
        val LIMB_OFFSET = ElementVector(0f, -3f, 0f)
        val CAPE_OFFSET = ElementVector(0f, -8f, 0.5f)
    }

    /**
     * 部位の外側の層。
     *
     * @param inflate 形を膨らませる量
     * @param x テクスチャの領域の横方向のずれ
     * @param z テクスチャの領域の縦方向のずれ
     */
    private class Outer(val inflate: Float, val x: Int, val z: Int)

    private fun faces(
        north: UVPos,
        south: UVPos,
        east: UVPos,
        west: UVPos,
        up: UVPos? = null,
        down: UVPos? = null
    ): Map<UVFace, UVPos> = buildMap {
        put(UVFace.NORTH, north)
        put(UVFace.SOUTH, south)
        put(UVFace.EAST, east)
        put(UVFace.WEST, west)
        if (up != null) put(UVFace.UP, up)
        if (down != null) put(UVFace.DOWN, down)
    }

    private fun model(
        name: String,
        space: UVSpace,
        offset: ElementVector,
        faces: Map<UVFace, UVPos>,
        outer: Outer? = null
    ): UVModel {
        val size = ElementVector(space.x().toFloat(), space.y().toFloat(), space.z().toFloat())
        val position = offset.div(DIV_FACTOR)
        return UVModel(namespace, name).addElement(
            UVElement(
                size.div(DIV_FACTOR),
                position,
                space,
                UVElement.ColorType.RGB,
                faces
            )
        ).apply {
            if (outer != null) addElement(
                UVElement(
                    size.div(DIV_FACTOR).inflate(outer.inflate),
                    position,
                    space,
                    UVElement.ColorType.COMPLEX_ARGB,
                    faces.mapValues { (_, pos) -> UVPos(pos.x + outer.x, pos.z + outer.z) }
                )
            )
        }
    }

    val head = model(
        "head",
        UVSpace(8, 8, 8),
        HEAD_OFFSET,
        faces(UVPos(8, 8), UVPos(24, 8), UVPos(0, 8), UVPos(16, 8), up = UVPos(8, 0), down = UVPos(16, 0)),
        Outer(0.5f, 32, 0)
    )
    val chest = model(
        "chest",
        UVSpace(8, 4, 4),
        BODY_OFFSET,
        faces(UVPos(20, 20), UVPos(32, 20), UVPos(16, 20), UVPos(28, 20), up = UVPos(20, 16)),
        Outer(0.25f, 0, 16)
    )
    val waist = model(
        "waist",
        UVSpace(8, 4, 4),
        BODY_OFFSET,
        faces(UVPos(20, 24), UVPos(32, 24), UVPos(16, 24), UVPos(28, 24)),
        Outer(0.25f, 0, 16)
    )
    val hip = model(
        "hip",
        UVSpace(8, 4, 4),
        BODY_OFFSET,
        faces(UVPos(20, 28), UVPos(32, 28), UVPos(16, 28), UVPos(28, 28), down = UVPos(28, 16)),
        Outer(0.25f, 0, 16)
    )
    val leftLeg = model(
        "left_leg",
        UVSpace(4, 6, 4),
        LIMB_OFFSET,
        faces(UVPos(20, 52), UVPos(28, 52), UVPos(16, 52), UVPos(24, 52), up = UVPos(20, 48)),
        Outer(0.25f, -16, 0)
    )
    val leftForeLeg = model(
        "left_foreleg",
        UVSpace(4, 6, 4),
        LIMB_OFFSET,
        faces(UVPos(20, 58), UVPos(28, 58), UVPos(16, 58), UVPos(24, 58), down = UVPos(24, 48)),
        Outer(0.25f, -16, 0)
    )
    val rightLeg = model(
        "right_leg",
        UVSpace(4, 6, 4),
        LIMB_OFFSET,
        faces(UVPos(4, 20), UVPos(12, 20), UVPos(0, 20), UVPos(8, 20), up = UVPos(4, 16)),
        Outer(0.25f, 0, 16)
    )
    val rightForeLeg = model(
        "right_foreleg",
        UVSpace(4, 6, 4),
        LIMB_OFFSET,
        faces(UVPos(4, 26), UVPos(12, 26), UVPos(0, 26), UVPos(8, 26), down = UVPos(8, 16)),
        Outer(0.25f, 0, 16)
    )
    val leftArm = model(
        "left_arm",
        UVSpace(4, 6, 4),
        LIMB_OFFSET,
        faces(UVPos(36, 52), UVPos(44, 52), UVPos(32, 52), UVPos(40, 52), up = UVPos(36, 48)),
        Outer(0.25f, 16, 0)
    )
    val leftForeArm = model(
        "left_forearm",
        UVSpace(4, 6, 4),
        LIMB_OFFSET,
        faces(UVPos(36, 58), UVPos(44, 58), UVPos(32, 58), UVPos(40, 58), down = UVPos(40, 48)),
        Outer(0.25f, 16, 0)
    )
    val rightArm = model(
        "right_arm",
        UVSpace(4, 6, 4),
        LIMB_OFFSET,
        faces(UVPos(44, 20), UVPos(52, 20), UVPos(40, 20), UVPos(48, 20), up = UVPos(44, 16)),
        Outer(0.25f, 0, 16)
    )
    val rightForeArm = model(
        "right_forearm",
        UVSpace(4, 6, 4),
        LIMB_OFFSET,
        faces(UVPos(44, 26), UVPos(52, 26), UVPos(40, 26), UVPos(48, 26), down = UVPos(48, 16)),
        Outer(0.25f, 0, 16)
    )
    val slimLeftArm = model(
        "left_slim_arm",
        UVSpace(3, 6, 4),
        LIMB_OFFSET,
        faces(UVPos(36, 52), UVPos(43, 52), UVPos(32, 52), UVPos(39, 52), up = UVPos(36, 48)),
        Outer(0.25f, 16, 0)
    )
    val slimLeftForeArm = model(
        "left_slim_forearm",
        UVSpace(3, 6, 4),
        LIMB_OFFSET,
        faces(UVPos(36, 58), UVPos(43, 58), UVPos(32, 58), UVPos(39, 58), down = UVPos(39, 48)),
        Outer(0.25f, 16, 0)
    )
    val slimRightArm = model(
        "right_slim_arm",
        UVSpace(3, 6, 4),
        LIMB_OFFSET,
        faces(UVPos(44, 20), UVPos(51, 20), UVPos(40, 20), UVPos(47, 20), up = UVPos(44, 16)),
        Outer(0.25f, 0, 16)
    )
    val slimRightForeArm = model(
        "right_slim_forearm",
        UVSpace(3, 6, 4),
        LIMB_OFFSET,
        faces(UVPos(44, 26), UVPos(51, 26), UVPos(40, 26), UVPos(47, 26), down = UVPos(47, 16)),
        Outer(0.25f, 0, 16)
    )
    val cape = model(
        "cape",
        UVSpace(10, 16, 1),
        CAPE_OFFSET,
        faces(UVPos(12, 1), UVPos(1, 1), UVPos(11, 1), UVPos(0, 1), up = UVPos(1, 0), down = UVPos(11, 0))
    )
}
