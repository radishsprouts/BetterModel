/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bedrock

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kr.toxicity.model.api.data.Float3
import kr.toxicity.model.api.data.Float4
import kr.toxicity.model.api.data.blueprint.BlueprintElement
import kr.toxicity.model.api.data.blueprint.BlueprintFace
import kr.toxicity.model.api.data.blueprint.BlueprintUV
import kr.toxicity.model.api.data.raw.ModelResolution
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 目録（`index.json`）・manifest・mcpack の組み立てと、書き出しの手順を確かめる。
 */
class BedrockExporterTest {

    private val uuid = UUID.fromString("0f8c2b7e-1d4a-4c39-9e55-6a7b8c9d0e1f")
    private val resolution = ModelResolution(16, 16)

    /** 1画素の PNG（試験用の入力データで、素材ではない）。 */
    private fun png(alpha: Int): ByteArray {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, (alpha shl 24) or 0x336699)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun bone(
        model: String,
        bone: String,
        textureName: String = "skin",
        image: ByteArray = png(255),
        glowing: Boolean = false,
        itemModel: String? = null
    ): BedrockBoneEntry {
        val texture = BedrockTexture(textureName, resolution, glowing, image, translucent = BedrockTexture.hasTranslucentPixel(image))
        val built = checkNotNull(BedrockGeometry.build(BedrockBoneSource(
            model, bone, Float3(0F, 0F, 0F), 1F, false,
            listOf(BlueprintElement.Cube(
                "cube", Float3(0F, 0F, 0F), Float3(1F, 1F, 1F), 0F, Float3(0F, 0F, 0F), Float3(0F, 0F, 0F),
                BlueprintFace(BlueprintUV("north", Float4(0F, 0F, 1F, 1F), 0, 0), null, null, null, null, null),
                null, true
            )),
            emptyList()
        ) { texture }))
        val key = BedrockIds.boneKey(model, bone)
        return BedrockBoneEntry(model, bone, itemModel ?: "bettermodel:b/$key", built)
    }

    private fun input(bones: List<BedrockBoneEntry>, limbs: List<BedrockLimb> = emptyList()) =
        BedrockExporter.Input("bettermodel", "test", uuid, bones, limbs)

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { put(it.name, zip.readBytes()) }
        }
    }

    private fun parse(bytes: ByteArray): JsonObject = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject

    @Test
    fun `目録は設計書の書式で 骨は id の順に並ぶ`() {
        val a = bone("demon_knight", "head")
        val b = bone("blue_wizard", "hat", textureName = "cloth", glowing = true)
        val output = BedrockExporter.assemble(input(listOf(a, b)))
        val index = parse(output.index)

        assertEquals(1, index.get("format").asInt)
        assertEquals("bettermodel", index.get("namespace").asString)
        assertEquals("BetterModelBedrock.mcpack", index.getAsJsonObject("pack").get("file").asString)
        assertEquals(uuid.toString(), index.getAsJsonObject("pack").get("uuid").asString)
        assertEquals(output.version, index.getAsJsonObject("pack").getAsJsonArray("version").map { it.asInt })
        assertEquals(BedrockGeometry.CHAIN, index.getAsJsonArray("chain").map { it.asString })

        val ids = index.getAsJsonArray("bones").map { it.asJsonObject.get("id").asString }
        assertEquals(ids.sorted(), ids)
        assertEquals(2, output.boneCount)

        val entry = index.getAsJsonArray("bones").map { it.asJsonObject }.first { it.get("model").asString == "demon_knight" }
        val key = BedrockIds.boneKey("demon_knight", "head")
        val textureKey = BedrockIds.textureKey("demon_knight", "skin")
        assertEquals(JsonParser.parseString("""
            {
              "key": "bettermodel:b/$key", "id": "bm_$key", "model": "demon_knight", "bone": "head",
              "geometry": "geometry.bm.$key",
              "slots": [{ "bone": "bm_s0", "texture": "textures/bettermodel/t_$textureKey",
                          "glow": false, "translucent": false, "frames": 1, "frametime": 1 }]
            }
        """), entry)

        val wizard = index.getAsJsonArray("bones").map { it.asJsonObject }.first { it.get("model").asString == "blue_wizard" }
        assertTrue(wizard.getAsJsonArray("slots")[0].asJsonObject.get("glow").asBoolean)
    }

    @Test
    fun `mcpack には manifest と geometry とテクスチャが入る`() {
        val a = bone("demon_knight", "head")
        val output = BedrockExporter.assemble(input(listOf(a)))
        val files = unzip(output.mcpack)
        val key = BedrockIds.boneKey("demon_knight", "head")
        val textureKey = BedrockIds.textureKey("demon_knight", "skin")
        assertEquals(
            setOf("manifest.json", "models/entity/bettermodel/bm_$key.geo.json", "textures/bettermodel/t_$textureKey.png"),
            files.keys
        )
        assertContentEquals(png(255), files.getValue("textures/bettermodel/t_$textureKey.png"))

        val manifest = parse(files.getValue("manifest.json"))
        assertEquals(2, manifest.get("format_version").asInt)
        val header = manifest.getAsJsonObject("header")
        assertEquals(uuid.toString(), header.get("uuid").asString)
        assertEquals(output.version, header.getAsJsonArray("version").map { it.asInt })
        assertEquals(listOf(1, 21, 0), header.getAsJsonArray("min_engine_version").map { it.asInt })
        val module = manifest.getAsJsonArray("modules")[0].asJsonObject
        assertEquals("resources", module.get("type").asString)
        assertEquals(UUID.nameUUIDFromBytes("$uuid:resources".toByteArray()).toString(), module.get("uuid").asString)
        assertEquals(parse(files.getValue("models/entity/bettermodel/bm_$key.geo.json")), a.bone.geometry)
    }

    @Test
    fun `版は内容から決まり 同じ入力なら mcpack も同じバイト列`() {
        val first = BedrockExporter.assemble(input(listOf(bone("m", "b"))))
        val again = BedrockExporter.assemble(input(listOf(bone("m", "b"))))
        assertEquals(first.version, again.version)
        assertContentEquals(first.mcpack, again.mcpack)
        assertEquals(1, first.version[0])

        val changed = BedrockExporter.assemble(input(listOf(bone("m", "b", image = png(100)))))
        assertNotEquals(first.version, changed.version)
        assertTrue(first.version.all { it in 0..0xFFFF })
    }

    @Test
    fun `半透明の画素があるテクスチャは translucent になる`() {
        val output = BedrockExporter.assemble(input(listOf(bone("m", "b", image = png(100)))))
        val slot = parse(output.index).getAsJsonArray("bones")[0].asJsonObject.getAsJsonArray("slots")[0].asJsonObject
        assertTrue(slot.get("translucent").asBoolean)
        assertFalse(BedrockTexture.hasTranslucentPixel(png(255)))
        assertFalse(BedrockTexture.hasTranslucentPixel(png(0)))
        assertTrue(BedrockTexture.hasTranslucentPixel(png(1)))
        assertTrue(BedrockTexture.hasTranslucentPixel(png(254)))
    }

    @Test
    fun `同じ骨が2回来ても1つにまとまる`() {
        val output = BedrockExporter.assemble(input(listOf(bone("m", "b"), bone("m", "b"))))
        assertEquals(1, output.boneCount)
        assertTrue(output.warnings.isEmpty())
    }

    @Test
    fun `同じモデルに同名で中身の違うテクスチャがあれば そのモデルだけ飛ばす`() {
        val broken = listOf(bone("m", "a", image = png(255)), bone("m", "b", image = png(10)))
        val fine = bone("other", "x")
        val output = BedrockExporter.assemble(input(broken + fine))
        assertEquals(1, output.boneCount)
        assertEquals(1, output.warnings.size)
        assertTrue(output.warnings[0].contains("m"))
        val models = parse(output.index).getAsJsonArray("bones").map { it.asJsonObject.get("model").asString }
        assertEquals(listOf("other"), models)
    }

    @Test
    fun `手足は目録と limbs の下の別ファイルに出る`() {
        val limb = BedrockLimb(
            "bettermodel:l/095ce4a56e1fa564", "head", false, "geometry.bm.limb.head", "limbs/head.geo.json",
            JsonObject().apply { addProperty("format_version", "1.21.0") }, "helmet"
        )
        val output = BedrockExporter.assemble(input(emptyList(), listOf(limb)))
        assertEquals(setOf("limbs/head.geo.json"), output.limbFiles.keys)
        assertEquals(JsonParser.parseString("""
            { "key": "bettermodel:l/095ce4a56e1fa564", "kind": "head", "slim": false,
              "geometry": "geometry.bm.limb.head", "file": "limbs/head.geo.json", "armor": "helmet" }
        """), parse(output.index).getAsJsonArray("limbs")[0])
        // 手足の geometry は mcpack には入れない
        assertEquals(setOf("manifest.json"), unzip(output.mcpack).keys)
    }

    @Test
    fun `書き出しは index json を最後に置き 一時フォルダを残さず 古い手足を消す`() {
        val folder = Files.createTempDirectory("bm-bedrock-test").toFile()
        try {
            val stale = folder.resolve("limbs/old.geo.json").also { it.parentFile.mkdirs(); it.writeText("{}") }
            val limb = BedrockLimb("k", "head", false, "geometry.bm.limb.head", "limbs/head.geo.json", JsonObject(), "helmet")
            val output = BedrockExporter.assemble(input(listOf(bone("m", "b")), listOf(limb)))
            BedrockExporter.write(folder, output)

            assertContentEquals(output.index, folder.resolve("index.json").readBytes())
            assertContentEquals(output.mcpack, folder.resolve("BetterModelBedrock.mcpack").readBytes())
            assertTrue(folder.resolve("limbs/head.geo.json").isFile)
            assertFalse(stale.exists())
            assertFalse(folder.resolve(".tmp").exists())

            // もう一度書いても同じ（置き換えで失敗しない）
            BedrockExporter.write(folder, output)
            assertContentEquals(output.index, folder.resolve("index.json").readBytes())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `パックの UUID は初回に作り 以後は変えない`() {
        val folder = Files.createTempDirectory("bm-bedrock-uuid").toFile()
        try {
            val first = BedrockExporter.loadOrCreatePackUuid(folder)
            assertEquals(first, BedrockExporter.loadOrCreatePackUuid(folder))
            assertEquals(first.toString(), folder.resolve("pack.uuid").readText().trim())
            // 壊れていたら作り直す
            folder.resolve("pack.uuid").writeText("broken")
            assertNotEquals(first, BedrockExporter.loadOrCreatePackUuid(folder))
        } finally {
            folder.deleteRecursively()
        }
    }
}
