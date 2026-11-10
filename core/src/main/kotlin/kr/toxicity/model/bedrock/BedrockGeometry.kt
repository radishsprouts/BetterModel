/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bedrock

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kr.toxicity.model.api.data.Float3
import kr.toxicity.model.api.data.blueprint.BlueprintElement
import kr.toxicity.model.api.data.blueprint.BlueprintTexture
import kr.toxicity.model.api.data.blueprint.BlueprintUV
import kr.toxicity.model.api.data.raw.ModelResolution
import org.joml.Vector3f
import java.io.ByteArrayInputStream
import java.util.*
import javax.imageio.ImageIO

/**
 * 統合版の geometry に写すときに必要なテクスチャの情報。
 *
 * @param name テクスチャの名前
 * @param resolution UV の解像度（キューブの面の UV を 0〜16 に直すための値）
 * @param glowing 発光するテクスチャか（名前が `_e` で終わる、または発光の描画モード）
 * @param image 画像（PNG）のバイト列
 * @param frames 動くテクスチャのコマ数（動かなければ 1）
 * @param frameTime 動くテクスチャの1コマの tick 数
 * @param translucent アルファが 1〜254 の画素があるか
 */
internal class BedrockTexture(
    val name: String,
    val resolution: ModelResolution,
    val glowing: Boolean,
    val image: ByteArray,
    val frames: Int = 1,
    val frameTime: Int = 1,
    val translucent: Boolean = false
) {
    companion object {
        /**
         * Blueprint のテクスチャから作る。
         *
         * コマ数は Java 版の動くテクスチャと同じく「画像の高さ / 幅」（Java 版の `.mcmeta` は正方形のコマを前提にしている）。
         *
         * @param texture テクスチャ
         * @param modelResolution モデルの解像度
         * @return 統合版用のテクスチャ
         */
        fun of(texture: BlueprintTexture, modelResolution: ModelResolution): BedrockTexture {
            val animated = texture.isAnimatedTexture
            return BedrockTexture(
                texture.name(),
                texture.resolution(modelResolution),
                texture.isGlowingTexture,
                texture.image(),
                if (animated && texture.width() > 0) (texture.height() / texture.width()).coerceAtLeast(1) else 1,
                texture.frameTime().coerceAtLeast(1),
                hasTranslucentPixel(texture.image())
            )
        }

        /**
         * アルファが 1〜254 の画素があるか。画像が読めなければ false。
         */
        fun hasTranslucentPixel(png: ByteArray): Boolean {
            val image = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull() ?: return false
            if (!image.colorModel.hasAlpha()) return false
            for (y in 0 until image.height) for (x in 0 until image.width) {
                if ((image.getRGB(x, y) ushr 24) in 1..254) return true
            }
            return false
        }
    }
}

/**
 * 統合版の geometry を作る元になる、骨1つ分の情報。
 *
 * @param modelName モデルの名前
 * @param boneName 骨の生の名前
 * @param origin 骨の生の origin（Blockbench の座標）
 * @param scale Java 版でキューブを 0〜16 に収めるために割る倍率
 * @param forceGlow 骨に GLOW タグがあるか
 * @param cubes 骨のキューブ
 * @param meshes 骨のメッシュ
 * @param texture テクスチャの番号からテクスチャを引く関数
 */
internal class BedrockBoneSource(
    val modelName: String,
    val boneName: String,
    val origin: Float3,
    val scale: Float,
    val forceGlow: Boolean,
    val cubes: List<BlueprintElement.Cube>,
    val meshes: List<BlueprintElement.Mesh>,
    val texture: (Int) -> BedrockTexture
)

/**
 * geometry の中の「テクスチャ × 全光か」の組。
 *
 * @param textureIndex モデルの中のテクスチャの番号
 * @param glow 全光で描くか
 */
internal data class BedrockSlot(val textureIndex: Int, val glow: Boolean) : Comparable<BedrockSlot> {
    override fun compareTo(other: BedrockSlot): Int {
        val byTexture = textureIndex.compareTo(other.textureIndex)
        return if (byTexture != 0) byTexture else glow.compareTo(other.glow)
    }
}

/**
 * 作った geometry。
 *
 * @param key 骨のキー
 * @param id 統合版の骨の識別子
 * @param geometryId geometry の識別子
 * @param geometry geometry のファイルの中身
 * @param slots 組の並び（並びの番号が骨 `bm_s<k>` / `bm_g<k>` の k）
 * @param textures 組と同じ並びのテクスチャ
 */
internal class BedrockBone(
    val key: String,
    val id: String,
    val geometryId: String,
    val geometry: JsonObject,
    val slots: List<BedrockSlot>,
    val textures: List<BedrockTexture>
)

/**
 * BetterModel の骨（[kr.toxicity.model.api.data.blueprint.BlueprintElement.Group]）を、
 * 統合版の geometry（Blockbench の Bedrock 書き出しと同じ規則）に写す。
 *
 * 座標の決め方: Java 版が使うのと同じ相対座標 `p(v) = (v - 骨の origin) / scale` を作り、
 * Blockbench の Bedrock 書き出しの規則（X を反転、回転は X と Y を反転）に当てて、
 * 最後に Y 軸で 180 度だけ回した骨 `bm_body` に入れる（KadoDisplayEntity の座標の取り方との差を打ち消す）。
 */
internal object BedrockGeometry {

    /** geometry に写す骨の鎖（親から順）。KadoDisplayEntity の `display.geo.json` の鎖と一致していなければならない。 */
    val CHAIN = listOf(
        "kado_root", "kado_billboard", "kado_translation", "kado_lr_z", "kado_lr_y", "kado_lr_x",
        "kado_scale", "kado_rr_z", "kado_rr_y", "kado_rr_x", "kado_ctx_scale"
    )

    /** 鎖の末尾の骨の下に置く、180 度回した骨の名前。 */
    const val BODY = "bm_body"

    /** geometry の `format_version`。`uv_rotation` を使うため 1.21.0。 */
    const val FORMAT_VERSION = "1.21.0"

    private val SLOT_ORDER = Comparator<BedrockSlot> { a, b -> a.compareTo(b) }

    /**
     * 数値を小数 4 桁に丸める（浮動小数の誤差が JSON に出ないようにする）。-0.0 は 0.0 にする。
     *
     * @param value 値
     * @return 丸めた値
     */
    fun num(value: Float): Double = Math.round(value.toDouble() * 10000.0) / 10000.0 + 0.0

    fun array(vararg values: Float): JsonArray = JsonArray(values.size).apply { values.forEach { add(num(it)) } }

    private class SlotContent {
        val cubes = JsonArray()
        val mesh = MeshAccumulator()
    }

    /**
     * 骨1つ分の geometry を作る。
     *
     * @param source 元になる情報
     * @return geometry。描くものが無ければ null
     */
    fun build(source: BedrockBoneSource): BedrockBone? {
        val slots = TreeMap<BedrockSlot, SlotContent>(SLOT_ORDER)
        val scale = source.scale
        fun relative(value: Float3): Float3 = value.minus(source.origin).div(scale)

        for (cube in source.cubes) {
            val byTexture = LinkedHashMap<Int, MutableList<BlueprintUV>>()
            cube.faces().uv().forEach { byTexture.computeIfAbsent(it.texture()) { mutableListOf() }.add(it) }
            val from = relative(cube.from())
            val to = relative(cube.to())
            val size = to.minus(from)
            val pivot = relative(cube.origin())
            val rotation = cube.rotation()
            for ((textureIndex, uvs) in byTexture) {
                val texture = source.texture(textureIndex)
                val faces = JsonObject()
                for (uv in uvs) {
                    val normalized = uv.uv().div(texture.resolution)
                    if (!normalized.isValid) continue
                    faces.add(uv.faceId(), face(uv, normalized.dx(), normalized.dz(), normalized.tx(), normalized.tz()))
                }
                if (faces.isEmpty) continue
                val glow = source.forceGlow || texture.glowing || cube.lightEmission() == 15
                val json = JsonObject()
                json.add("origin", array(-to.x(), from.y(), from.z()))
                json.add("size", array(size.x(), size.y(), size.z()))
                val inflate = cube.inflate() / scale
                if (inflate != 0F) json.addProperty("inflate", num(inflate))
                if (Float3.ZERO != rotation) {
                    json.add("pivot", array(-pivot.x(), pivot.y(), pivot.z()))
                    json.add("rotation", array(-rotation.x(), -rotation.y(), rotation.z()))
                }
                json.add("uv", faces)
                slots.computeIfAbsent(BedrockSlot(textureIndex, glow)) { SlotContent() }.cubes.add(json)
            }
        }

        for (mesh in source.meshes) {
            val rotation = mesh.rotation().toQuaternionXYZ()
            val delta = mesh.origin().minus(source.origin).toVector()
            for (face in mesh.faces()) {
                val texture = source.texture(face.texture())
                val glow = source.forceGlow || texture.glowing
                val points = face.points().map { point ->
                    val v = point.vertices().toVector().rotate(rotation).add(delta)
                    MeshPoint(
                        Vector3f(-v.x / scale, v.y / scale, v.z / scale),
                        point.uv().x() / texture.resolution.width(),
                        // 設計の既定: V は反転しない（推測。試験モデルで向きを確かめる）
                        point.uv().y() / texture.resolution.height()
                    )
                }
                if (points.size < 3) continue
                slots.computeIfAbsent(BedrockSlot(face.texture(), glow)) { SlotContent() }.mesh.add(points)
            }
        }

        slots.entries.removeIf { it.value.cubes.isEmpty && it.value.mesh.isEmpty() }
        if (slots.isEmpty()) return null

        val key = BedrockIds.boneKey(source.modelName, source.boneName)
        val geometryId = BedrockIds.geometryId(key)
        val bones = JsonArray()
        CHAIN.forEachIndexed { index, name ->
            bones.add(JsonObject().apply {
                addProperty("name", name)
                if (index > 0) addProperty("parent", CHAIN[index - 1])
                add("pivot", array(0F, 0F, 0F))
            })
        }
        bones.add(JsonObject().apply {
            addProperty("name", BODY)
            addProperty("parent", CHAIN.last())
            add("pivot", array(0F, 0F, 0F))
            add("rotation", array(0F, 180F, 0F))
        })
        val slotList = slots.keys.toList()
        slots.entries.forEachIndexed { index, (slot, content) ->
            bones.add(JsonObject().apply {
                addProperty("name", boneName(slot, index))
                addProperty("parent", BODY)
                add("pivot", array(0F, 0F, 0F))
                if (!content.cubes.isEmpty) add("cubes", content.cubes)
                if (!content.mesh.isEmpty()) add("poly_mesh", content.mesh.toJson())
            })
        }
        return BedrockBone(
            key,
            BedrockIds.boneId(key),
            geometryId,
            geometryJson(geometryId, 16, 16, bones),
            slotList,
            slotList.map { source.texture(it.textureIndex) }
        )
    }

    /**
     * 組の骨の名前。
     *
     * @param slot 組
     * @param index 組の並びの番号
     * @return 通常の光なら `bm_s<k>`、全光なら `bm_g<k>`
     */
    fun boneName(slot: BedrockSlot, index: Int): String = (if (slot.glow) "bm_g" else "bm_s") + index

    /**
     * geometry のファイルの中身を作る。
     *
     * @param identifier geometry の識別子
     * @param textureWidth テクスチャの幅
     * @param textureHeight テクスチャの高さ
     * @param bones 骨
     * @return ファイルの中身
     */
    fun geometryJson(identifier: String, textureWidth: Int, textureHeight: Int, bones: JsonArray): JsonObject = JsonObject().apply {
        addProperty("format_version", FORMAT_VERSION)
        add("minecraft:geometry", JsonArray(1).apply {
            add(JsonObject().apply {
                add("description", JsonObject().apply {
                    addProperty("identifier", identifier)
                    addProperty("texture_width", textureWidth)
                    addProperty("texture_height", textureHeight)
                    // 骨の平行移動で本体から離れても、描画が切られないように大きく取る
                    addProperty("visible_bounds_width", 256)
                    addProperty("visible_bounds_height", 256)
                    add("visible_bounds_offset", array(0F, 0F, 0F))
                })
                add("bones", bones)
            })
        })
    }

    /**
     * 面1つの JSON。north / south / east / west は始点と大きさ、up / down は終点と負の大きさにする（Blockbench の書き出しと同じ）。
     */
    private fun face(uv: BlueprintUV, u1: Float, v1: Float, u2: Float, v2: Float): JsonObject = JsonObject().apply {
        faceUv(this, uv.faceId(), u1, v1, u2, v2)
        if (uv.rotation() != 0) addProperty("uv_rotation", uv.rotation())
    }

    /**
     * 面の UV を `uv` / `uv_size` として書く。
     *
     * @param target 書き込み先
     * @param faceId 面の名前
     * @param u1 左上の u
     * @param v1 左上の v
     * @param u2 右下の u
     * @param v2 右下の v
     */
    fun faceUv(target: JsonObject, faceId: String, u1: Float, v1: Float, u2: Float, v2: Float) {
        if (faceId == BlueprintUV.UP || faceId == BlueprintUV.DOWN) {
            target.add("uv", array(u2, v2))
            target.add("uv_size", array(u1 - u2, v1 - v2))
        } else {
            target.add("uv", array(u1, v1))
            target.add("uv_size", array(u2 - u1, v2 - v1))
        }
    }

    /** メッシュの頂点（位置は X 反転済み、UV は 0〜1）。 */
    private class MeshPoint(val position: Vector3f, val u: Float, val v: Float)

    /**
     * `poly_mesh` を作る。同じ値の位置・法線・UV は1つにまとめる。
     * X を反転すると面の向きが逆になるので、頂点の順を逆にして表を保つ。四角形以上は三角形に分ける。
     */
    private class MeshAccumulator {
        private val positions = LinkedHashMap<List<Double>, Int>()
        private val normals = LinkedHashMap<List<Double>, Int>()
        private val uvs = LinkedHashMap<List<Double>, Int>()
        private val polys = JsonArray()

        fun isEmpty() = polys.isEmpty

        fun add(points: List<MeshPoint>) {
            val ordered = points.asReversed()
            val origin = ordered[0]
            for (i in 1 until ordered.size - 1) {
                val b = ordered[i]
                val c = ordered[i + 1]
                val normal = Vector3f(b.position).sub(origin.position).cross(Vector3f(c.position).sub(origin.position))
                if (normal.lengthSquared() > 0F) normal.normalize()
                val normalIndex = index(normals, listOf(num(normal.x), num(normal.y), num(normal.z)))
                val poly = JsonArray(3)
                for (p in listOf(origin, b, c)) {
                    poly.add(JsonArray(3).apply {
                        add(index(positions, listOf(num(p.position.x), num(p.position.y), num(p.position.z))))
                        add(normalIndex)
                        add(index(uvs, listOf(num(p.u), num(p.v))))
                    })
                }
                polys.add(poly)
            }
        }

        private fun index(map: MutableMap<List<Double>, Int>, value: List<Double>): Int = map.computeIfAbsent(value) { map.size }

        fun toJson(): JsonObject = JsonObject().apply {
            addProperty("normalized_uvs", true)
            add("positions", positions.keys.toJsonArray())
            add("normals", normals.keys.toJsonArray())
            add("uvs", uvs.keys.toJsonArray())
            add("polys", polys)
        }

        private fun Collection<List<Double>>.toJsonArray() = JsonArray(size).also { array ->
            forEach { values -> array.add(JsonArray(values.size).apply { values.forEach(::add) }) }
        }
    }
}
