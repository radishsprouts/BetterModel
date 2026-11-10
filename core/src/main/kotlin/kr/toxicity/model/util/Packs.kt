/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.util

import kr.toxicity.model.api.BetterModelConfig
import kr.toxicity.model.api.BetterModelConfig.PackType.*
import kr.toxicity.model.api.pack.*
import kr.toxicity.model.manager.ReloadPipeline
import net.kyori.adventure.text.format.NamedTextColor
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Collectors
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import kotlin.io.path.pathString

fun BetterModelConfig.PackType.toGenerator() = when (this) {
    FOLDER -> FolderGenerator()
    ZIP -> ZipGenerator()
    NONE -> NoneGenerator()
}

private fun hashFile() = File(DATA_FOLDER.getOrCreateDirectory(".cache"), "zip-hash.txt")

interface PackGenerator {
    val exists: Boolean
    fun create(zipper: PackZipper, pipeline: ReloadPipeline): PackResult

    /**
     * パックが前回完了したビルドと違うかを調べます。ハッシュは書き込みません。
     */
    fun hashChanged(result: PackResult): Boolean {
        if (!exists) return true
        val file = hashFile()
        return !file.exists() || file.readText() != result.hash().toString()
    }

    /**
     * ハッシュを記録します。パックをすべて書き終えた後にだけ呼ぶこと。
     * そうしないと、失敗したビルドが再試行されません。
     */
    fun writeHash(result: PackResult) {
        val file = hashFile()
        val temp = Files.createTempFile(file.parentFile.toPath(), "zip-hash", ".tmp")
        try {
            Files.writeString(temp, result.hash().toString())
            moveReplacing(temp, file.toPath())
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

class FolderGenerator : PackGenerator {
    private val file = File(DATA_FOLDER.parent, CONFIG.buildFolderLocation())
    override val exists: Boolean = file.exists()

    private val fileTree by lazy {
        val root = file.apply {
            mkdirs()
        }.toPath()
        Files.walk(root).use { stream ->
            stream
                .filter { it != root }
                .collect(Collectors.toConcurrentMap(
                    { path -> root.relativize(path).pathString },
                    { it }
                ))
        }
    }

    private fun PackPath.toFile(): File {
        val replaced = path.replace('/', File.separatorChar)
        return fileTree.remove(replaced)?.toFile() ?: File(file, replaced).apply {
            parentFile.mkdirs()
        }
    }

    override fun create(zipper: PackZipper, pipeline: ReloadPipeline): PackResult {
        val build = zipper.build()
        val pack = PackResult(build.meta(), file)
        val changed = AtomicBoolean()

        pipeline.forEachParallel(build.resources(), PackResource::estimatedSize) { pack[it.overlay()] = PackByte(it.path(), it.get()) }
        if (!hashChanged(pack) && file.exists()) return pack.apply { freeze() }

        pipeline.forEachParallel(pack.stream().toList(), PackByte::length) {
            val file = it.path().toFile()
            val index = pipeline.progress(it.path().name())
            if (file.exists() && file.length() == it.length() && file.readBytes().contentEquals(it.bytes)) return@forEachParallel
            file.writeBytes(it.bytes)
            changed.set(true)
            debugPack {
                componentOf(
                    "This file was successfully generated: ".toComponent(),
                    it.path().path.toComponent(NamedTextColor.GREEN),
                    " ($index/${pipeline.goal})".toComponent(NamedTextColor.DARK_GRAY)
                )
            }
        }
        fileTree.values
            .sortedByDescending { it }
            .forEach {
                if (it.toFile().delete()) changed.set(true)
            }
        pack.freeze(changed.get())
        writeHash(pack)
        return pack
    }
}

class ZipGenerator : PackGenerator {
    private val file = File(DATA_FOLDER.parent, "${CONFIG.buildFolderLocation()}.zip")
    override val exists: Boolean = file.exists()

    override fun create(zipper: PackZipper, pipeline: ReloadPipeline): PackResult {
        val result = zipper.writeToResult(pipeline, file)
        val changed = hashChanged(result)
        result.freeze(changed)
        if (!changed) return result
        // ZIP は一時ファイルへ書いてから移す。ビルドが途中で止まっても壊れた ZIP が残らない。
        val temp = Files.createTempFile(file.absoluteFile.parentFile.toPath(), file.nameWithoutExtension, ".tmp")
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                zip.setComment("BetterModel's generated resource pack.")
                result.stream().forEach { packByte ->
                    val name = packByte.path().path()
                    val bytes = packByte.bytes()
                    val entry = ZipEntry(name)
                    if (name.endsWith(".png", ignoreCase = true)) {
                        // PNG は圧縮済みなので、もう一度圧縮しても CPU の無駄になる。
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.compressedSize = bytes.size.toLong()
                        entry.crc = CRC32().apply { update(bytes) }.value
                    }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            moveReplacing(temp, file.toPath())
        } finally {
            Files.deleteIfExists(temp)
        }
        writeHash(result)
        return result
    }
}

class NoneGenerator : PackGenerator {
    override val exists: Boolean = false
    override fun create(zipper: PackZipper, pipeline: ReloadPipeline): PackResult {
        return zipper.writeToResult(pipeline).apply {
            freeze()
        }
    }
}

fun PackZipper.writeToResult(pipeline: ReloadPipeline, dir: File? = null): PackResult {
    val build = build()
    return PackResult(build.meta(), dir).apply {
        pipeline.forEachParallel(build.resources(), PackResource::estimatedSize) {
            set(it.overlay(), PackByte(it.path(), it.get()))
            val index = pipeline.progress(it.path().name())
            debugPack {
                componentOf(
                    "This file was successfully zipped: ".toComponent(),
                    it.path().path.toComponent(NamedTextColor.GREEN),
                    " ($index/${pipeline.goal})".toComponent(NamedTextColor.DARK_GRAY)
                )
            }
        }
    }
}
