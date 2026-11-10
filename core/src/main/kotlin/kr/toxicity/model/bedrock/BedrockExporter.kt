/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bedrock

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kr.toxicity.model.util.*
import net.kyori.adventure.text.format.NamedTextColor
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.*
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 手足（プレイヤーのモデルの部位）1つ分の、統合版向けの皮膚ジオメトリ。
 *
 * @param key Java 版の item_model（`名前空間:パス`）
 * @param kind 部位の種類（`head` `right_arm` など）
 * @param slim 細い腕か
 * @param geometryId geometry の識別子
 * @param file `index.json` からの相対パス
 * @param geometry geometry のファイルの中身
 * @param armor その手足に重なる防具の部位（`helmet` `chestplate` `leggings` `boots` `none`）
 */
internal class BedrockLimb(
    val key: String,
    val kind: String,
    val slim: Boolean,
    val geometryId: String,
    val file: String,
    val geometry: JsonObject,
    val armor: String
)

/**
 * 1回のリロードで集めた、骨1つ分の出力。
 *
 * @param modelName モデルの名前
 * @param boneName 骨の生の名前
 * @param itemModel Java 版の item_model（`名前空間:パス`）
 * @param bone 作った geometry
 */
internal class BedrockBoneEntry(
    val modelName: String,
    val boneName: String,
    val itemModel: String,
    val bone: BedrockBone
)

/**
 * リロード中に骨の出力を集める入れ物。モデルの読み込みは複数のスレッドから呼ばれうるので、追加は排他にする。
 */
internal class BedrockCollector {
    private val entries = ArrayList<BedrockBoneEntry>()

    /**
     * 骨の出力を追加する。
     *
     * @param entry 骨の出力
     */
    fun add(entry: BedrockBoneEntry) {
        synchronized(entries) { entries += entry }
    }

    /**
     * 集めた骨の出力。
     *
     * @return 追加順の写し
     */
    fun entries(): List<BedrockBoneEntry> = synchronized(entries) { ArrayList(entries) }
}

/**
 * 統合版（Bedrock）向けの出力（geometry・テクスチャ・目録・mcpack）を組み立てて書き出す。
 *
 * 組み立て（[assemble]）は設定やファイルに触れない純粋な処理で、書き出し（[write]）だけが入出力を行う。
 * 出力の形は、設計書の `index.json` の書式（format 1）に従う。
 */
internal object BedrockExporter {

    /** mcpack のファイル名。 */
    const val PACK_FILE = "BetterModelBedrock.mcpack"

    /** `index.json` の書式の版。 */
    const val INDEX_FORMAT = 1

    /** 一時フォルダの名前（出力フォルダの直下）。 */
    private const val TEMP_FOLDER = ".tmp"

    private val COMPACT = GsonBuilder().disableHtmlEscaping().create()
    private val PRETTY = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    /** mcpack の中のファイルの更新時刻（内容が同じなら同じバイト列になるように固定する）。 */
    private val FIXED_TIME = LocalDateTime.of(1980, 1, 1, 0, 0)

    /**
     * [assemble] への入力。
     *
     * @param namespace BetterModel の `namespace`
     * @param generator ログ用の生成元の表記
     * @param packUuid パックの UUID
     * @param bones 集めた骨の出力
     * @param limbs 手足の皮膚ジオメトリ
     */
    class Input(
        val namespace: String,
        val generator: String,
        val packUuid: UUID,
        val bones: List<BedrockBoneEntry>,
        val limbs: List<BedrockLimb> = emptyList()
    )

    /**
     * [assemble] の結果。
     *
     * @param index `index.json` のバイト列
     * @param mcpack mcpack のバイト列
     * @param limbFiles `index.json` からの相対パス → 手足の geometry のバイト列
     * @param warnings 出力を飛ばしたモデルなど、ログに出す警告
     * @param boneCount 目録に載せた骨の数
     * @param version パックの版
     */
    class Output(
        val index: ByteArray,
        val mcpack: ByteArray,
        val limbFiles: Map<String, ByteArray>,
        val warnings: List<String>,
        val boneCount: Int,
        val version: List<Int>
    )

    /**
     * 目録・manifest・mcpack を組み立てる。
     *
     * 骨のキー・テクスチャのキーが衝突したモデルは、衝突に関わったものをすべて出力から外し、警告を返す。
     *
     * @param input 入力
     * @return 出力
     */
    fun assemble(input: Input): Output {
        val warnings = ArrayList<String>()
        val skipped = findConflicts(input.bones, warnings)

        // 同じ骨が2回来た場合（同名のモデルが一般と手足の両方にある等）は1つにまとめる
        val bones = LinkedHashMap<String, BedrockBoneEntry>()
        for (entry in input.bones) {
            if (entry.modelName in skipped) continue
            bones.putIfAbsent(entry.bone.id, entry)
        }
        val sorted = bones.values.sortedBy { it.bone.id }

        // mcpack の中身（manifest 以外）
        val files = TreeMap<String, ByteArray>()
        for (entry in sorted) {
            files["models/entity/bettermodel/${entry.bone.id}.geo.json"] = json(entry.bone.geometry)
            entry.bone.textures.forEach { texture ->
                files["${BedrockIds.texturePath(BedrockIds.textureKey(entry.modelName, texture.name))}.png"] = texture.image
            }
        }
        val version = versionOf(files)
        files["manifest.json"] = json(manifest(input.packUuid, version))
        val mcpack = zip(files)

        val limbFiles = TreeMap<String, ByteArray>()
        input.limbs.forEach { limbFiles[it.file] = json(it.geometry) }

        val index = JsonObject().apply {
            addProperty("format", INDEX_FORMAT)
            addProperty("generator", input.generator)
            addProperty("namespace", input.namespace)
            add("pack", JsonObject().apply {
                addProperty("file", PACK_FILE)
                addProperty("uuid", input.packUuid.toString())
                add("version", intArray(version))
            })
            add("chain", JsonArray().apply { BedrockGeometry.CHAIN.forEach(::add) })
            add("bones", JsonArray().apply {
                sorted.forEach { add(boneJson(it)) }
            })
            add("limbs", JsonArray().apply {
                input.limbs.forEach { limb ->
                    add(JsonObject().apply {
                        addProperty("key", limb.key)
                        addProperty("kind", limb.kind)
                        addProperty("slim", limb.slim)
                        addProperty("geometry", limb.geometryId)
                        addProperty("file", limb.file)
                        addProperty("armor", limb.armor)
                    })
                }
            })
        }
        return Output(prettyJson(index), mcpack, limbFiles, warnings, sorted.size, version)
    }

    private fun boneJson(entry: BedrockBoneEntry): JsonObject = JsonObject().apply {
        val bone = entry.bone
        addProperty("key", entry.itemModel)
        addProperty("id", bone.id)
        addProperty("model", entry.modelName)
        addProperty("bone", entry.boneName)
        addProperty("geometry", bone.geometryId)
        add("slots", JsonArray().apply {
            bone.slots.forEachIndexed { k, slot ->
                val texture = bone.textures[k]
                add(JsonObject().apply {
                    addProperty("bone", BedrockGeometry.boneName(slot, k))
                    addProperty("texture", BedrockIds.texturePath(BedrockIds.textureKey(entry.modelName, texture.name)))
                    addProperty("glow", slot.glow)
                    addProperty("translucent", texture.translucent)
                    addProperty("frames", texture.frames)
                    addProperty("frametime", texture.frameTime)
                })
            }
        })
    }

    /**
     * 骨のキー・テクスチャのキーの衝突を探し、衝突に関わったモデルの名前を返す。
     */
    private fun findConflicts(entries: List<BedrockBoneEntry>, warnings: MutableList<String>): Set<String> {
        val skipped = HashSet<String>()
        // 骨のキー: 同じキーで、モデル名・骨の名前が違う（ハッシュの衝突）か、同じ名前なのに中身が違う
        entries.groupBy { it.bone.key }.forEach { (key, list) ->
            val first = list.first()
            val conflict = list.any {
                it.modelName != first.modelName || it.boneName != first.boneName ||
                    it.bone.geometry != first.bone.geometry
            }
            if (conflict) {
                list.forEach { skipped += it.modelName }
                warnings += "統合版の骨のキー $key が衝突したため、次のモデルの統合版の出力を飛ばします: " +
                    list.map { it.modelName }.distinct().joinToString()
            }
        }
        // テクスチャのキー: 同じキーで画像が違う（同じモデルに同名のテクスチャが2つある等）
        val images = HashMap<String, Pair<String, ByteArray>>()
        for (entry in entries) {
            if (entry.modelName in skipped) continue
            for (texture in entry.bone.textures) {
                val key = BedrockIds.textureKey(entry.modelName, texture.name)
                val known = images.putIfAbsent(key, entry.modelName to texture.image) ?: continue
                if (!known.second.contentEquals(texture.image)) {
                    skipped += entry.modelName
                    skipped += known.first
                    warnings += "統合版のテクスチャのキー $key が衝突したため、モデル ${entry.modelName} の統合版の出力を飛ばします" +
                        "（同じ名前のテクスチャが複数あります: ${texture.name}）"
                }
            }
        }
        return skipped
    }

    /**
     * 内容（manifest 以外）の SHA-256 から、manifest の版（`[1, 上位16ビット, 次の16ビット]`）を決める。
     */
    fun versionOf(files: SortedMap<String, ByteArray>): List<Int> {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { (name, bytes) ->
            digest.update(name.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(bytes)
        }
        val hash = digest.digest()
        return listOf(
            1,
            ((hash[0].toInt() and 0xFF) shl 8) or (hash[1].toInt() and 0xFF),
            ((hash[2].toInt() and 0xFF) shl 8) or (hash[3].toInt() and 0xFF)
        )
    }

    /**
     * mcpack の `manifest.json` を作る。
     */
    fun manifest(packUuid: UUID, version: List<Int>): JsonObject = JsonObject().apply {
        addProperty("format_version", 2)
        add("header", JsonObject().apply {
            addProperty("description", "BetterModel models for Bedrock (generated)")
            addProperty("name", "BetterModelBedrock")
            addProperty("uuid", packUuid.toString())
            add("version", intArray(version))
            add("min_engine_version", intArray(listOf(1, 21, 0)))
        })
        add("modules", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "resources")
                addProperty("uuid", UUID.nameUUIDFromBytes("$packUuid:resources".toByteArray(StandardCharsets.UTF_8)).toString())
                add("version", intArray(version))
            })
        })
    }

    private fun intArray(values: List<Int>) = JsonArray(values.size).apply { values.forEach(::add) }

    private fun json(element: JsonElement): ByteArray = COMPACT.toJson(element).toByteArray(StandardCharsets.UTF_8)

    private fun prettyJson(element: JsonElement): ByteArray = (PRETTY.toJson(element) + "\n").toByteArray(StandardCharsets.UTF_8)

    /**
     * 更新時刻を固定した zip を作る（内容が同じなら同じバイト列になる）。PNG は圧縮済みなので無圧縮で格納する。
     */
    fun zip(files: SortedMap<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            files.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                entry.timeLocal = FIXED_TIME
                if (name.endsWith(".png", ignoreCase = true)) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    /**
     * 出力先のフォルダへ書く。一時フォルダに全部作ってから、`index.json` を最後に置き換える。
     *
     * @param folder 出力先のフォルダ
     * @param output [assemble] の結果
     */
    fun write(folder: File, output: Output) {
        val root = folder.toPath()
        Files.createDirectories(root)
        val temp = root.resolve(TEMP_FOLDER)
        temp.toFile().deleteRecursively()
        try {
            Files.createDirectories(temp.resolve("limbs"))
            Files.write(temp.resolve(PACK_FILE), output.mcpack)
            output.limbFiles.forEach { (path, bytes) -> Files.write(temp.resolve(path), bytes) }
            Files.write(temp.resolve("index.json"), output.index)

            moveReplacing(temp.resolve(PACK_FILE), root.resolve(PACK_FILE))
            Files.createDirectories(root.resolve("limbs"))
            output.limbFiles.keys.forEach { moveReplacing(temp.resolve(it), root.resolve(it)) }
            // 目録を最後に置き換える（読む側は目録が新しくなるまで古い状態を見る）
            moveReplacing(temp.resolve("index.json"), root.resolve("index.json"))
            // 今回の出力に無い古い手足のファイルは、目録を置き換えた後に消す
            root.resolve("limbs").toFile().listFiles()
                ?.filter { it.isFile && it.name.endsWith(".geo.json") && "limbs/${it.name}" !in output.limbFiles }
                ?.forEach(File::delete)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    /**
     * パックの UUID を読む。無い・壊れている時は乱数で作って保存する（以後は変えない）。
     *
     * @param folder 出力先のフォルダ
     * @return パックの UUID
     */
    fun loadOrCreatePackUuid(folder: File): UUID {
        val file = File(folder, "pack.uuid")
        runCatching { UUID.fromString(file.readText(StandardCharsets.UTF_8).trim()) }.getOrNull()?.let { return it }
        folder.mkdirs()
        return UUID.randomUUID().also { file.writeText(it.toString(), StandardCharsets.UTF_8) }
    }

    /**
     * 出力先のフォルダを決める。絶対パスならそのまま、相対パスならプラグインのデータフォルダからの相対とする。
     */
    fun resolveFolder(outputFolder: String): File {
        val file = File(outputFolder)
        return if (file.isAbsolute) file else File(DATA_FOLDER, outputFolder)
    }

    /**
     * リロードの最後に1回だけ呼ぶ。設定が無効なら何もしない。失敗してもリロードは止めず、ログに記録する。
     *
     * @param collector 集めた骨の出力
     * @param limbs 手足の皮膚ジオメトリ
     */
    fun export(collector: BedrockCollector, limbs: List<BedrockLimb>) {
        val config = CONFIG.bedrock()
        if (!config.enabled()) return
        runCatching {
            val folder = resolveFolder(config.outputFolder())
            val output = assemble(Input(
                CONFIG.namespace(),
                "BetterModel ${BedrockExporter::class.java.`package`?.implementationVersion ?: "unknown"} (radishsprouts/BetterModel v3)",
                loadOrCreatePackUuid(folder),
                collector.entries(),
                limbs
            ))
            output.warnings.forEach { warn(it.toComponent(NamedTextColor.RED)) }
            write(folder, output)
            info("統合版用の出力を書き出しました: ${folder.path} (骨 ${output.boneCount} 件、手足 ${limbs.size} 件、版 ${output.version.joinToString(".")})".toComponent(NamedTextColor.GREEN))
        }.handleFailure { "統合版用の出力の書き出しに失敗しました。" }
    }
}
