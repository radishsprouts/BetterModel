/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import kr.toxicity.library.armormodel.ArmorImage
import kr.toxicity.library.armormodel.ArmorModel
import kr.toxicity.library.armormodel.ArmorNameMapper
import kr.toxicity.library.armormodel.ArmorPaletteImage
import kr.toxicity.model.api.pack.PackObfuscator
import kr.toxicity.model.api.pack.PackZipper
import kr.toxicity.model.util.*
import net.kyori.adventure.text.format.NamedTextColor
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists

object ArmorManager : GlobalManager {

    private val ARMOR_PATH = "assets/minecraft/textures/entity/equipment/humanoid" to "assets/minecraft/textures/entity/equipment/humanoid_leggings"
    private val ARMOR_TRIM_PATH = "assets/minecraft/textures/trims/entity/humanoid" to "assets/minecraft/textures/trims/entity/humanoid_leggings"
    private const val ARMOR_PALETTE_PATH = "assets/minecraft/textures/palettes/trim"

    /**
     * A file written after every armor asset has been extracted. It stores the Minecraft version the assets were taken from,
     * so an interrupted download is retried and a server update refreshes the assets.
     */
    private const val COMPLETE_MARKER = ".complete"
    private const val DOWNLOAD_TIMEOUT_MINUTES = 5L

    private val armors = setOf(
        "chainmail",
        "copper",
        "diamond",
        "gold",
        "iron",
        "leather",
        "netherite"
    )

    private val palettes = setOf(
        "amethyst",
        "copper",
        "copper_darker",
        "diamond",
        "diamond_darker",
        "emerald",
        "gold",
        "gold_darker",
        "iron",
        "iron_darker",
        "lapis",
        "netherite",
        "netherite_darker",
        "quartz",
        "redstone",
        "resin"
    )

    private val trims = setOf(
        "bolt",
        "coast",
        "dune",
        "eye",
        "flow",
        "host",
        "raiser",
        "rib",
        "sentry",
        "shaper",
        "silence",
        "snout",
        "spire",
        "tide",
        "vex",
        "ward",
        "wayfinder",
        "wild"
    )

    var armor: ArmorModel = ArmorModel.EMPTY
        private set

    private data class VersionManifest(
        val latest: ManifestLatest,
        val versions: List<ManifestVersion>
    ) {
        fun find(id: String) = versions.firstOrNull { it.id == id }
        val latestManifest get() = versions.first { it.id == latest.release }
    }

    private data class ManifestLatest(
        val release: String
    )

    private data class ManifestVersion(
        val id: String,
        val url: String
    ) {
        fun toURI(): URI = URI.create(url)
    }

    private data class VersionHash(
        val downloads: Map<String, HashDownload>
    ) {
        val client by downloads
    }

    private data class HashDownload(
        val url: String
    ) {
        fun toURI(): URI = URI.create(url)
    }

    private data class MinecraftClient(
        val version: String,
        val file: File
    )

    /**
     * The Minecraft version id used by the version manifest (a patch of 0 is not written, like "26.1").
     */
    private fun serverVersionId() = PLATFORM.version().run {
        if (patch == 0) "$major.$minor" else "$major.$minor.$patch"
    }

    private fun HttpResponse<InputStream>.requireOk(): HttpResponse<InputStream> {
        if (statusCode() != 200) {
            body().close()
            throw IllegalStateException("Unexpected status code ${statusCode()} from ${uri()}")
        }
        return this
    }

    private fun downloadMinecraftClient(versionId: String): CompletableFuture<MinecraftClient> = httpClient {
        val cacheFolder = DATA_FOLDER.getOrCreateDirectory(".cache")
        sendAsync(
            buildHttpRequest {
                GET()
                uri(URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
            },
            HttpResponse.BodyHandlers.ofInputStream()
        ).thenComposeAsync { response1 ->
            val versions = response1.requireOk().toJson(VersionManifest::class.java)
            val manifest = versions.find(versionId) ?: versions.latestManifest.also {
                warn("Unable to find the Minecraft $versionId in the version manifest. Use ${it.id} instead.".toComponent())
            }
            val cache = File(cacheFolder, "${manifest.id}.jar")
            if (cache.exists() && cache.length() > 0) CompletableFuture.completedFuture(MinecraftClient(manifest.id, cache))
            else sendAsync(
                buildHttpRequest {
                    GET()
                    uri(manifest.toURI())
                },
                HttpResponse.BodyHandlers.ofInputStream()
            ).thenComposeAsync { response2 ->
                sendAsync(
                    buildHttpRequest {
                        GET()
                        uri(response2.requireOk().toJson(VersionHash::class.java).client.toURI())
                    },
                    HttpResponse.BodyHandlers.ofInputStream()
                ).thenApplyAsync { response3 ->
                    val temp = createTempFile(cache.parentFile.toPath(), manifest.id, ".tmp")
                    try {
                        response3.requireOk().body().use { input ->
                            temp.toFile().outputStream().buffered().use(input::copyTo)
                        }
                        moveReplacing(temp, cache.toPath())
                    } finally {
                        temp.deleteIfExists()
                    }
                    MinecraftClient(manifest.id, cache)
                }
            }
        }.orTimeout(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    }.orElse {
        CompletableFuture.failedFuture(it)
    }

    private class ArmorImageCache(
        val name: String,
        val armor: ByteArray,
        val leggings: ByteArray
    ) {
        val size: Long get() = armor.size.toLong() + leggings.size.toLong()

        fun write(target: File) {
            val file = File(target, name).apply { mkdirs() }
            File(file, "armor.png").outputStream().buffered().use { it.write(armor) }
            File(file, "leggings.png").outputStream().buffered().use { it.write(leggings) }
        }
    }

    private fun JarFile.loadArmorImage(name: String, pathPair: Pair<String, String>): ArmorImageCache? {
        val armor = loadImage(pathPair.first, name) ?: return null
        val leggings = loadImage(pathPair.second, name) ?: return null
        return ArmorImageCache(name, armor, leggings)
    }

    private fun JarFile.loadImage(path: String, name: String): ByteArray? {
        val entry = getJarEntry("$path/$name.png")
        if (entry == null) {
            warn("This asset doesn't exist in the client jar so it will be skipped: $path/$name.png".toComponent(NamedTextColor.YELLOW))
            return null
        }
        return getInputStream(entry).use { it.readAllBytes() }
    }

    private fun File.isArmorAssetsReady(versionId: String): Boolean {
        val marker = File(this, COMPLETE_MARKER)
        return marker.isFile && runCatching { marker.readText() == versionId }.getOrDefault(false)
    }

    private fun downloadArmorAssets(folder: File, versionId: String, pipeline: ReloadPipeline) {
        info("Downloading client jar...".toComponent())
        runCatching {
            val client = downloadMinecraftClient(versionId).join()
            val armorsFile = File(folder, "armors").apply { mkdirs() }
            val trimsFile = File(folder, "armor_trims").apply { mkdirs() }
            val palettesFile = File(folder, "palettes").apply { mkdirs() }
            JarFile(client.file).use { jar ->
                pipeline.forEachParallel(
                    armors.mapNotNull { name -> jar.loadArmorImage(name, ARMOR_PATH) },
                    ArmorImageCache::size
                ) { image -> image.write(armorsFile) }
                pipeline.forEachParallel(
                    trims.mapNotNull { name -> jar.loadArmorImage(name, ARMOR_TRIM_PATH) },
                    ArmorImageCache::size
                ) { image -> image.write(trimsFile) }
                pipeline.forEachParallel(
                    palettes.mapNotNull { name -> jar.loadImage(ARMOR_PALETTE_PATH, name)?.let { name to it } },
                    { image -> image.second.size.toLong() },
                ) { image -> File(palettesFile, "${image.first}.png").writeBytes(image.second) }
            }
            // The marker is written last: an unfinished download has no marker and is retried on the next reload.
            File(folder, COMPLETE_MARKER).writeText(versionId)
            info("Download success!".toComponent(NamedTextColor.LIGHT_PURPLE))
        }.handleFailure {
            "Unable to download default armor assets. It will be retried on the next reload."
        }
    }

    override fun reload(
        pipeline: ReloadPipeline,
        zipper: PackZipper
    ) {
        if (!CONFIG.module().playerAnimation || !CONFIG.module().playerArmor) {
            armor = ArmorModel.EMPTY
            return
        }
        val folder = DATA_FOLDER.getOrCreateDirectory("armors")
        val versionId = serverVersionId()
        if (!folder.isArmorAssetsReady(versionId)) downloadArmorAssets(folder, versionId, pipeline)
        val textures = PackObfuscator.order()
        val models = PackObfuscator.order()
        armor = ArmorModel.builder()
            .namespace(CONFIG.namespace())
            .itemPath(zipper.assets().obfuscate("armor"))
            .streamLoader { path -> PLATFORM.getResource(path)!! }
            .armors(pipeline
                .mapParallel(File(folder, "armors").subFiles(), File::length) { it.toArmorImage() }
                .sortedBy { it.name }
            )
            .armorTrims(pipeline
                .mapParallel(File(folder, "armor_trims").subFiles(), File::length) { it.toArmorImage() }
                .sortedBy { it.name }
            )
            .palettes(pipeline
                .mapParallel(File(folder, "palettes").subFiles(), File::length) { it.toPaletteImage() }
                .sortedBy { it.name }
            )
            .nameMapper(ArmorNameMapper(
                { textures.obfuscate(it) },
                { models.obfuscate(it) }
            ))
            .flush(false)
            .build()
        armor.builders().forEach {
            zipper.assets().add(
                it.path(),
                256
            ) {
                it.get()
            }
        }
    }

    private fun File.toArmorImage() = runCatching {
        ArmorImage(
            nameWithoutExtension,
            File(this, "armor.png").toImage(),
            File(this, "leggings.png").toImage()
        )
    }.handleFailure {
        "Unable to load this armor image: $path"
    }.getOrNull()

    private fun File.toPaletteImage() = runCatching {
        ArmorPaletteImage(nameWithoutExtension, toImage())
    }.handleFailure {
        "Unable to load this palette image: $path"
    }.getOrNull()
}
