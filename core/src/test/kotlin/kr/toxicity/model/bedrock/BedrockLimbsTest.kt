/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bedrock

import com.google.gson.JsonParser
import kr.toxicity.library.dynamicuv.UVNamespace
import kr.toxicity.model.manager.PlayerLimbModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 手足の皮膚ジオメトリ（設計書 08-2 の 7.2）を確かめる。
 */
class BedrockLimbsTest {

    private val models = PlayerLimbModels { UVNamespace("test", "player_limb") }
    private val limbs = models.specs.map { BedrockLimbs.build(it, "bettermodel", true) }

    private fun limb(kind: String, slim: Boolean = false) = limbs.first { it.kind == kind && it.slim == slim }

    @Test
    fun `部位は全部で17個 細い腕を含む`() {
        assertEquals(17, limbs.size)
        assertEquals(setOf("head", "chest", "waist", "hip", "left_arm", "right_arm", "left_forearm", "right_forearm",
            "left_leg", "right_leg", "left_foreleg", "right_foreleg", "cape"), limbs.map { it.kind }.toSet())
        assertEquals(4, limbs.count { it.slim })
        // ファイル名と geometry の識別子は細い腕でも重ならない
        assertEquals(limbs.size, limbs.map { it.file }.toSet().size)
        assertEquals(limbs.size, limbs.map { it.geometryId }.toSet().size)
        assertEquals(limbs.size, limbs.map { it.key }.toSet().size)
    }

    @Test
    fun `頭の内側と外側のキューブ`() {
        val head = limb("head")
        assertEquals("bettermodel:l/095ce4a56e1fa564", head.key)
        assertEquals("geometry.bm.limb.head", head.geometryId)
        assertEquals("limbs/head.geo.json", head.file)
        assertEquals("helmet", head.armor)
        val expected = JsonParser.parseString("""
            {
              "format_version": "1.21.0",
              "minecraft:geometry": [{
                "description": { "identifier": "geometry.bm.limb.head", "texture_width": 64, "texture_height": 64,
                                 "visible_bounds_width": 256, "visible_bounds_height": 256, "visible_bounds_offset": [0, 0, 0] },
                "bones": [{ "name": "head", "pivot": [0, 0, 0], "cubes": [
                  { "origin": [-3.75, 0, -3.75], "size": [7.5, 7.5, 7.5],
                    "uv": {
                      "north": { "uv": [8, 8], "uv_size": [8, 8] },
                      "south": { "uv": [24, 8], "uv_size": [8, 8] },
                      "east": { "uv": [0, 8], "uv_size": [8, 8] },
                      "west": { "uv": [16, 8], "uv_size": [8, 8] },
                      "up": { "uv": [16, 8], "uv_size": [-8, -8] },
                      "down": { "uv": [24, 8], "uv_size": [-8, -8] }
                    } },
                  { "origin": [-3.75, 0, -3.75], "size": [7.5, 7.5, 7.5], "inflate": 0.5,
                    "uv": {
                      "north": { "uv": [40, 8], "uv_size": [8, 8] },
                      "south": { "uv": [56, 8], "uv_size": [8, 8] },
                      "east": { "uv": [32, 8], "uv_size": [8, 8] },
                      "west": { "uv": [48, 8], "uv_size": [8, 8] },
                      "up": { "uv": [48, 8], "uv_size": [-8, -8] },
                      "down": { "uv": [56, 8], "uv_size": [-8, -8] }
                    } }
                ] }]
              }]
            }
        """)
        assertEquals(expected, head.geometry)
    }

    @Test
    fun `細い腕は幅が3画素で通常の腕と別のジオメトリ`() {
        val slim = limb("left_arm", slim = true)
        val normal = limb("left_arm")
        assertEquals("geometry.bm.limb.left_arm_slim", slim.geometryId)
        assertEquals("limbs/left_arm_slim.geo.json", slim.file)
        assertEquals("bettermodel:l/b483f8400f319015", slim.key)
        fun size(l: BedrockLimb) = l.geometry.getAsJsonArray("minecraft:geometry")[0].asJsonObject
            .getAsJsonArray("bones")[0].asJsonObject.getAsJsonArray("cubes")[0].asJsonObject
            .getAsJsonArray("size").map { it.asFloat }
        assertEquals(listOf(4 * 0.9375F, 6 * 0.9375F, 4 * 0.9375F), size(normal))
        assertEquals(listOf(3 * 0.9375F, 6 * 0.9375F, 4 * 0.9375F), size(slim))
    }

    @Test
    fun `防具の部位は着せ替えの対応と同じ`() {
        assertEquals("helmet", limb("head").armor)
        listOf("chest", "waist", "left_arm", "right_arm").forEach { assertEquals("chestplate", limb(it).armor, it) }
        listOf("hip", "left_leg", "right_leg").forEach { assertEquals("leggings", limb(it).armor, it) }
        listOf("left_foreleg", "right_foreleg").forEach { assertEquals("boots", limb(it).armor, it) }
        listOf("left_forearm", "right_forearm", "cape").forEach { assertEquals("none", limb(it).armor, it) }
    }

    @Test
    fun `マントには外側の層が無い`() {
        val cubes = limb("cape").geometry.getAsJsonArray("minecraft:geometry")[0].asJsonObject
            .getAsJsonArray("bones")[0].asJsonObject.getAsJsonArray("cubes")
        assertEquals(1, cubes.size())
        assertTrue(cubes[0].asJsonObject.get("inflate") == null)
    }

    @Test
    fun `難読化なしの item_model は部位の名前のまま`() {
        val plain = BedrockLimbs.build(models.specs.first { it.name == "head" }, "bettermodel", false)
        assertEquals("bettermodel:player_limb/head", plain.key)
    }
}
