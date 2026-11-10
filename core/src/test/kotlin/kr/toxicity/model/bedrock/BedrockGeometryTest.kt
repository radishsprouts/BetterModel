/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bedrock

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kr.toxicity.model.api.data.Float2
import kr.toxicity.model.api.data.Float3
import kr.toxicity.model.api.data.Float4
import kr.toxicity.model.api.data.blueprint.BlueprintElement
import kr.toxicity.model.api.data.blueprint.BlueprintFace
import kr.toxicity.model.api.data.blueprint.BlueprintTexture
import kr.toxicity.model.api.data.blueprint.BlueprintUV
import kr.toxicity.model.api.data.raw.ModelResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * キューブ・メッシュ・組（slot）を統合版の geometry に写した結果の JSON を確かめる。
 * 期待値は設計書（08-1 の 2.5〜2.7）の規則を手で計算したもの。
 */
class BedrockGeometryTest {

    private val resolution = ModelResolution(16, 16)

    private fun texture(name: String = "skin", glowing: Boolean = false) =
        BedrockTexture(name, resolution, glowing, ByteArray(0))

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    private fun source(
        cubes: List<BlueprintElement.Cube> = emptyList(),
        meshes: List<BlueprintElement.Mesh> = emptyList(),
        forceGlow: Boolean = false,
        scale: Float = 1F,
        origin: Float3 = Float3(0F, 0F, 0F),
        textures: (Int) -> BedrockTexture = { texture() }
    ) = BedrockBoneSource("model", "bone", origin, scale, forceGlow, cubes, meshes, textures)

    private fun uv(face: String, u1: Float, v1: Float, u2: Float, v2: Float, rotation: Int = 0, texture: Int = 0) =
        BlueprintUV(face, Float4(u1, v1, u2, v2), rotation, texture)

    private fun cube(
        from: Float3, to: Float3,
        faces: BlueprintFace,
        inflate: Float = 0F,
        rotation: Float3 = Float3(0F, 0F, 0F),
        origin: Float3 = Float3(0F, 0F, 0F),
        lightEmission: Int? = null
    ) = BlueprintElement.Cube("cube", from, to, inflate, rotation, origin, faces, lightEmission, true)

    private fun BedrockBone.slotBone(name: String): JsonObject = geometry
        .getAsJsonArray("minecraft:geometry")[0].asJsonObject
        .getAsJsonArray("bones")
        .map { it.asJsonObject }
        .first { it.get("name").asString == name }

    @Test
    fun `設計書の例の1キューブがそのままの JSON になる`() {
        val bone = assertNotNull(BedrockGeometry.build(source(
            cubes = listOf(cube(
                from = Float3(-4F, -8F, -4F),
                to = Float3(4F, 0F, 4F),
                inflate = 0.25F,
                rotation = Float3(22.5F, 0F, 45F),
                origin = Float3(0F, -8F, 0F),
                faces = BlueprintFace(
                    uv("north", 0F, 0F, 4F, 4F), null, null, null,
                    uv("up", 4F, 0F, 8F, 4F), null
                )
            ))
        )))
        val expected = json("""
            {
              "origin": [-4, -8, -4], "size": [8, 8, 8], "inflate": 0.25,
              "pivot": [0, -8, 0], "rotation": [-22.5, 0, 45],
              "uv": {
                "north": { "uv": [0, 0], "uv_size": [4, 4] },
                "up": { "uv": [8, 4], "uv_size": [-4, -4] }
              }
            }
        """)
        assertEquals(expected, bone.slotBone("bm_s0").getAsJsonArray("cubes")[0])
    }

    @Test
    fun `骨の鎖と bm_body と組の骨が並ぶ`() {
        val bone = assertNotNull(BedrockGeometry.build(source(cubes = listOf(simpleCube()))))
        val geometry = bone.geometry.getAsJsonArray("minecraft:geometry")[0].asJsonObject
        val names = geometry.getAsJsonArray("bones").map { it.asJsonObject.get("name").asString }
        assertEquals(BedrockGeometry.CHAIN + listOf("bm_body", "bm_s0"), names)
        assertEquals(BedrockIds.geometryId(bone.key), geometry.getAsJsonObject("description").get("identifier").asString)
        assertEquals("1.21.0", bone.geometry.get("format_version").asString)
        assertEquals(json("""{ "name": "bm_body", "parent": "kado_ctx_scale", "pivot": [0, 0, 0], "rotation": [0, 180, 0] }"""), bone.slotBone("bm_body"))
        assertEquals("bm_body", bone.slotBone("bm_s0").get("parent").asString)
        assertEquals("kado_root", bone.slotBone("kado_billboard").get("parent").asString)
        assertNull(bone.slotBone("kado_root").get("parent"))
    }

    @Test
    fun `原点と倍率で相対座標になる`() {
        // 骨の origin (8, 0, 0)、倍率 2 のとき p(v) = (v - origin) / 2
        val bone = assertNotNull(BedrockGeometry.build(source(
            origin = Float3(8F, 0F, 0F),
            scale = 2F,
            cubes = listOf(cube(
                from = Float3(8F, 0F, 0F), to = Float3(16F, 4F, 6F),
                faces = BlueprintFace(uv("north", 0F, 0F, 4F, 4F), null, null, null, null, null)
            ))
        )))
        val cube = bone.slotBone("bm_s0").getAsJsonArray("cubes")[0].asJsonObject
        // p(from) = (0, 0, 0)、p(to) = (4, 2, 3) → origin = (-4, 0, 0)、size = (4, 2, 3)
        assertEquals(JsonParser.parseString("[-4, 0, 0]"), cube.get("origin"))
        assertEquals(JsonParser.parseString("[4, 2, 3]"), cube.get("size"))
        assertNull(cube.get("rotation"))
        assertNull(cube.get("inflate"))
    }

    @Test
    fun `面の回転は uv_rotation になる`() {
        val bone = assertNotNull(BedrockGeometry.build(source(cubes = listOf(cube(
            from = Float3(0F, 0F, 0F), to = Float3(2F, 2F, 2F),
            faces = BlueprintFace(uv("north", 0F, 0F, 2F, 2F, rotation = 90), null, null, null, null, null)
        )))))
        val north = bone.slotBone("bm_s0").getAsJsonArray("cubes")[0].asJsonObject.getAsJsonObject("uv").getAsJsonObject("north")
        assertEquals(90, north.get("uv_rotation").asInt)
    }

    @Test
    fun `テクスチャと全光の組ごとに骨が分かれ番号は並びになる`() {
        val textures = listOf(texture("a"), texture("b_e", glowing = true))
        val bone = assertNotNull(BedrockGeometry.build(source(
            textures = { textures[it] },
            cubes = listOf(
                cube(Float3(0F, 0F, 0F), Float3(1F, 1F, 1F), BlueprintFace(uv("north", 0F, 0F, 1F, 1F, texture = 1), null, null, null, null, null)),
                cube(Float3(0F, 0F, 0F), Float3(1F, 1F, 1F), BlueprintFace(uv("north", 0F, 0F, 1F, 1F, texture = 0), null, null, null, null, null)),
                // 光 15 は通常のテクスチャでも全光の組になる
                cube(Float3(0F, 0F, 0F), Float3(1F, 1F, 1F), BlueprintFace(uv("north", 0F, 0F, 1F, 1F, texture = 0), null, null, null, null, null), lightEmission = 15),
                // 光 1〜14 は通常の光（近似）
                cube(Float3(0F, 0F, 0F), Float3(1F, 1F, 1F), BlueprintFace(uv("north", 0F, 0F, 1F, 1F, texture = 0), null, null, null, null, null), lightEmission = 7)
            )
        )))
        assertEquals(
            listOf(BedrockSlot(0, false), BedrockSlot(0, true), BedrockSlot(1, true)),
            bone.slots
        )
        assertEquals(listOf("a", "a", "b_e"), bone.textures.map { it.name })
        assertEquals(2, bone.slotBone("bm_s0").getAsJsonArray("cubes").size())
        assertEquals(1, bone.slotBone("bm_g1").getAsJsonArray("cubes").size())
        assertEquals(1, bone.slotBone("bm_g2").getAsJsonArray("cubes").size())
    }

    @Test
    fun `GLOW タグの骨は全面が全光の組になる`() {
        val bone = assertNotNull(BedrockGeometry.build(source(forceGlow = true, cubes = listOf(simpleCube()))))
        assertEquals(listOf(BedrockSlot(0, true)), bone.slots)
        assertEquals("bm_g0", BedrockGeometry.boneName(bone.slots[0], 0))
    }

    @Test
    fun `範囲外の UV の面だけが落ち 全面が落ちれば骨ごと無い`() {
        // 0〜16 を超える UV は Java 版でも面ごと捨てられる
        val invalid = cube(Float3(0F, 0F, 0F), Float3(1F, 1F, 1F), BlueprintFace(uv("north", 0F, 0F, 40F, 4F), null, null, null, null, null))
        assertNull(BedrockGeometry.build(source(cubes = listOf(invalid))))
        assertNull(BedrockGeometry.build(source()))
    }

    @Test
    fun `メッシュは X を反転し頂点の順を逆にして三角形になる`() {
        val mesh = BlueprintElement.Mesh(
            Float3(0F, 0F, 0F), Float3(0F, 0F, 0F),
            listOf(BlueprintElement.Mesh.Face(
                listOf(
                    BlueprintElement.Mesh.Point(Float3(0F, 0F, 0F), Float2(0F, 0F)),
                    BlueprintElement.Mesh.Point(Float3(16F, 0F, 0F), Float2(16F, 0F)),
                    BlueprintElement.Mesh.Point(Float3(0F, 16F, 0F), Float2(0F, 16F))
                ), 0
            )),
            true
        )
        val bone = assertNotNull(BedrockGeometry.build(source(meshes = listOf(mesh))))
        val expected = json("""
            {
              "normalized_uvs": true,
              "positions": [[0, 16, 0], [-16, 0, 0], [0, 0, 0]],
              "normals": [[0, 0, 1]],
              "uvs": [[0, 1], [1, 0], [0, 0]],
              "polys": [[[0, 0, 0], [1, 0, 1], [2, 0, 2]]]
            }
        """)
        assertEquals(expected, bone.slotBone("bm_s0").getAsJsonObject("poly_mesh"))
        assertFalse(bone.slotBone("bm_s0").has("cubes"))
    }

    @Test
    fun `四角形のメッシュは2つの三角形に分かれ 同じ頂点は共有される`() {
        val mesh = BlueprintElement.Mesh(
            Float3(0F, 0F, 0F), Float3(0F, 0F, 0F),
            listOf(BlueprintElement.Mesh.Face(
                listOf(
                    BlueprintElement.Mesh.Point(Float3(0F, 0F, 0F), Float2(0F, 0F)),
                    BlueprintElement.Mesh.Point(Float3(16F, 0F, 0F), Float2(16F, 0F)),
                    BlueprintElement.Mesh.Point(Float3(16F, 16F, 0F), Float2(16F, 16F)),
                    BlueprintElement.Mesh.Point(Float3(0F, 16F, 0F), Float2(0F, 16F))
                ), 0
            )),
            true
        )
        val poly = assertNotNull(BedrockGeometry.build(source(meshes = listOf(mesh)))).slotBone("bm_s0").getAsJsonObject("poly_mesh")
        assertEquals(2, poly.getAsJsonArray("polys").size())
        assertEquals(4, poly.getAsJsonArray("positions").size())
        assertEquals(1, poly.getAsJsonArray("normals").size())
        assertTrue(poly.get("normalized_uvs").asBoolean)
    }

    @Test
    fun `動くテクスチャのコマ数は画像の高さを幅で割った値`() {
        val animated = BlueprintTexture("fire", BlueprintTexture.RenderMode.DEFAULT, ByteArray(0), 16, 64, 0, 0, true, 3, false)
        val still = BlueprintTexture("stone", BlueprintTexture.RenderMode.DEFAULT, ByteArray(0), 16, 16, 0, 0, true, 3, false)
        val glowByName = BlueprintTexture("lamp_e", BlueprintTexture.RenderMode.DEFAULT, ByteArray(0), 16, 16, 0, 0, true, 1, false)
        val glowByMode = BlueprintTexture("plain", BlueprintTexture.RenderMode.EMISSIVE, ByteArray(0), 16, 16, 0, 0, true, 1, false)
        BedrockTexture.of(animated, resolution).also {
            assertEquals(4, it.frames)
            assertEquals(3, it.frameTime)
        }
        assertEquals(1, BedrockTexture.of(still, resolution).frames)
        assertTrue(BedrockTexture.of(glowByName, resolution).glowing)
        assertTrue(BedrockTexture.of(glowByMode, resolution).glowing)
        assertFalse(BedrockTexture.of(still, resolution).glowing)
    }

    private fun simpleCube() = cube(
        Float3(0F, 0F, 0F), Float3(1F, 1F, 1F),
        BlueprintFace(uv("north", 0F, 0F, 1F, 1F), null, null, null, null, null)
    )
}
