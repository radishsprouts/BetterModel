/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bedrock

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * プレイヤーの皮膚の部位1つ分の寸法と UV。Java 版の `PlayerLimbModels` の定義から控える。
 *
 * @param name 部位のモデル名（例: `head`、`left_slim_arm`）
 * @param spaceX 内側のキューブの幅（皮膚の画素）
 * @param spaceY 内側のキューブの高さ（皮膚の画素）
 * @param spaceZ 内側のキューブの奥行き（皮膚の画素）
 * @param offsetX 部位の中心の横のずれ（Java 版の `offset`）
 * @param offsetY 部位の中心の縦のずれ
 * @param offsetZ 部位の中心の奥行きのずれ
 * @param faces 面の名前（`north` など） → 皮膚の画像の中の UV の起点（u, v）
 * @param outer 外側の層（スキンの2層目）。無ければ null
 */
internal class BedrockLimbSpec(
    val name: String,
    val spaceX: Int,
    val spaceY: Int,
    val spaceZ: Int,
    val offsetX: Float,
    val offsetY: Float,
    val offsetZ: Float,
    val faces: Map<String, Pair<Int, Int>>,
    val outer: Outer?
) {
    /**
     * 外側の層。
     *
     * @param inflate 形を膨らませる量
     * @param x UV の起点の横のずれ
     * @param z UV の起点の縦のずれ
     */
    class Outer(val inflate: Float, val x: Int, val z: Int)
}

/**
 * 手足（プレイヤーの皮膚の部位）の、統合版向けの皮膚ジオメトリを作る。
 *
 * 寸法は Java 版の item model と同じ単位で書く（皮膚の1画素 = 0.9375、中心を原点とする）。
 * 偽のプレイヤーの骨 `head` の1本だけに、内側のキューブと外側のキューブを入れる。
 * 座標は 2.5 のキューブと同じく Blockbench の Bedrock 書き出しの規則（X を反転）に従う。
 */
internal object BedrockLimbs {

    /** 皮膚の1画素の、Java 版の item model での長さ（`PlayerLimbModels.DIV_FACTOR` の逆数 × 16）。 */
    const val PIXEL = 0.9375F

    /** 皮膚の画像の大きさ（64×64）。 */
    const val SKIN_SIZE = 64

    /**
     * 部位のモデル名から、種類（`head` `left_arm` など。細い腕でも通常の腕と同じ名前）を求める。
     */
    fun kind(name: String): String = name.replace("_slim", "")

    /** 細い腕か。 */
    fun slim(name: String): Boolean = name.contains("_slim")

    /**
     * その部位に重なる防具の部位。
     *
     * @return `helmet` `chestplate` `leggings` `boots` `none`
     */
    fun armor(kind: String): String = when (kind) {
        "head" -> "helmet"
        "chest", "waist", "left_arm", "right_arm" -> "chestplate"
        "hip", "left_leg", "right_leg" -> "leggings"
        "left_foreleg", "right_foreleg" -> "boots"
        else -> "none"
    }

    /**
     * 皮膚ジオメトリを作る。
     *
     * @param spec 部位の寸法と UV
     * @param namespace BetterModel の `namespace`
     * @param useObfuscation 難読化が有効か
     * @return 手足の出力
     */
    fun build(spec: BedrockLimbSpec, namespace: String, useObfuscation: Boolean): BedrockLimb {
        val kind = kind(spec.name)
        val slim = slim(spec.name)
        val suffix = if (slim) "${kind}_slim" else kind
        val geometryId = "geometry.bm.limb.$suffix"
        val cubes = JsonArray()
        cubes.add(cube(spec, 0F, 0, 0))
        spec.outer?.let { cubes.add(cube(spec, it.inflate, it.x, it.z)) }
        val bones = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("name", "head")
                add("pivot", BedrockGeometry.array(0F, 0F, 0F))
                add("cubes", cubes)
            })
        }
        return BedrockLimb(
            BedrockIds.limbItemModel(namespace, useObfuscation, spec.name),
            kind,
            slim,
            geometryId,
            "limbs/$suffix.geo.json",
            BedrockGeometry.geometryJson(geometryId, SKIN_SIZE, SKIN_SIZE, bones),
            armor(kind)
        )
    }

    private fun cube(spec: BedrockLimbSpec, inflate: Float, shiftX: Int, shiftZ: Int): JsonObject {
        val sx = spec.spaceX * PIXEL
        val sy = spec.spaceY * PIXEL
        val sz = spec.spaceZ * PIXEL
        val cx = spec.offsetX * PIXEL
        val cy = spec.offsetY * PIXEL
        val cz = spec.offsetZ * PIXEL
        val toX = cx + sx / 2
        val faces = JsonObject()
        for ((face, pos) in spec.faces) {
            val (w, h) = when (face) {
                "north", "south" -> spec.spaceX to spec.spaceY
                "east", "west" -> spec.spaceZ to spec.spaceY
                else -> spec.spaceX to spec.spaceZ
            }
            val u1 = (pos.first + shiftX).toFloat()
            val v1 = (pos.second + shiftZ).toFloat()
            faces.add(face, JsonObject().also { BedrockGeometry.faceUv(it, face, u1, v1, u1 + w, v1 + h) })
        }
        return JsonObject().apply {
            add("origin", BedrockGeometry.array(-toX, cy - sy / 2, cz - sz / 2))
            add("size", BedrockGeometry.array(sx, sy, sz))
            if (inflate != 0F) addProperty("inflate", BedrockGeometry.num(inflate))
            add("uv", faces)
        }
    }
}
