/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 皮膚の PNG のキャッシュ（キーの作り方、ディスクへの保存と読み出し、壊れたファイルの扱い、掃除）を確かめる。
 * HTTP は呼ばず、取得の処理を差し替えて呼ばれた回数を数える。
 */
class SkinImageCacheTest {

    private val hash = "a".repeat(64)
    private val mojangUri = URI.create("http://textures.minecraft.net/texture/$hash")
    private val baseTime = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private lateinit var folder: File

    @BeforeTest
    fun setUp() {
        folder = Files.createTempDirectory("skin-cache-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        folder.deleteRecursively()
    }

    /** テスト用の小さな PNG。 */
    private fun png(rgb: Int): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        for (x in 0..1) for (y in 0..1) image.setRGB(x, y, 0xFF000000.toInt() or rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun cache(
        fetch: (URI) -> CompletableFuture<ByteArray>,
        errors: MutableList<String> = mutableListOf()
    ) = SkinImageCache(
        folder = folder,
        fetch = fetch,
        executor = { it.run() },
        now = { baseTime },
        onError = { message, _ -> errors += message }
    )

    private fun File.age(days: Long) {
        assertTrue(setLastModified(baseTime - days * day))
    }

    private fun fileOf(key: String) = File(folder, "$key.png")

    @Test
    fun `textures_minecraft_net の URL は末尾のハッシュをキーにする`() {
        assertEquals(hash, SkinImageCache.keyOf(mojangUri))
        // https でも同じキーになる。
        assertEquals(hash, SkinImageCache.keyOf(URI.create("https://textures.minecraft.net/texture/$hash")))
    }

    @Test
    fun `それ以外の URL は URL 全体の SHA-256 をキーにし、URL が違えばキーも違う`() {
        val a = SkinImageCache.keyOf(URI.create("https://example.com/skin.png"))
        val b = SkinImageCache.keyOf(URI.create("https://example.com/skin.png?v=2"))
        assertTrue(Regex("[0-9a-f]{64}").matches(a))
        assertNotEquals(a, b)
        assertEquals(a, SkinImageCache.keyOf(URI.create("https://example.com/skin.png")))
    }

    @Test
    fun `末尾がハッシュの形でない URL や別のホストはハッシュを流用しない`() {
        val sha = Regex("[0-9a-f]{64}")
        // 大文字・記号・短すぎる文字列は、ファイル名にしてよい形ではないので SHA-256 にする。
        for (path in listOf("../evil", "ABCDEF0123456789ABCDEF", "abc", "")) {
            val key = SkinImageCache.keyOf(URI.create("http://textures.minecraft.net/texture/$path"))
            assertTrue(sha.matches(key), "path=$path key=$key")
        }
        assertNotEquals(hash, SkinImageCache.keyOf(URI.create("http://example.com/texture/$hash")))
    }

    @Test
    fun `取得した PNG をディスクへ保存し、新しいキャッシュ（再起動の後）は HTTP をしない`() {
        val calls = AtomicInteger()
        val bytes = png(0x112233)
        val first = cache({ calls.incrementAndGet(); CompletableFuture.completedFuture(bytes) })
        val image = first.get(mojangUri).get(5, TimeUnit.SECONDS)
        assertEquals(2, image.width)
        assertEquals(1, calls.get())
        assertContentEquals(bytes, fileOf(hash).readBytes())
        // 一時ファイルは残らない。
        assertEquals(listOf("$hash.png"), folder.list()!!.toList())

        // 同じキャッシュからは、メモリから返る。
        first.get(mojangUri).get(5, TimeUnit.SECONDS)
        assertEquals(1, calls.get())

        // 作り直したキャッシュ（再ログイン・サーバーの再起動に当たる）は、ディスクから読む。
        val second = cache({ calls.incrementAndGet(); CompletableFuture.failedFuture(IllegalStateException("呼ばれてはいけない")) })
        val restored = second.get(mojangUri).get(5, TimeUnit.SECONDS)
        assertEquals(1, calls.get())
        assertEquals(image.getRGB(0, 0), restored.getRGB(0, 0))
    }

    @Test
    fun `同じ URL の同時の要求は取得を1本にまとめる`() {
        val calls = AtomicInteger()
        val pending = CompletableFuture<ByteArray>()
        val cache = cache({ calls.incrementAndGet(); pending })
        val a = cache.get(mojangUri)
        val b = cache.get(mojangUri)
        assertEquals(1, calls.get())
        assertFalse(a.isDone)
        pending.complete(png(0x445566))
        assertEquals(a.get(5, TimeUnit.SECONDS).getRGB(0, 0), b.get(5, TimeUnit.SECONDS).getRGB(0, 0))
        assertEquals(1, calls.get())
    }

    @Test
    fun `壊れたファイルは消して HTTP へ戻り、正しいファイルに置き換わる`() {
        fileOf(hash).writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val calls = AtomicInteger()
        val bytes = png(0x778899)
        val image = cache({ calls.incrementAndGet(); CompletableFuture.completedFuture(bytes) })
            .get(mojangUri).get(5, TimeUnit.SECONDS)
        assertEquals(1, calls.get())
        assertEquals(2, image.width)
        assertContentEquals(bytes, fileOf(hash).readBytes())
    }

    @Test
    fun `空のファイルも壊れたものとして扱う`() {
        fileOf(hash).writeBytes(ByteArray(0))
        val calls = AtomicInteger()
        cache({ calls.incrementAndGet(); CompletableFuture.completedFuture(png(0x010203)) })
            .get(mojangUri).get(5, TimeUnit.SECONDS)
        assertEquals(1, calls.get())
        assertTrue(fileOf(hash).length() > 0)
    }

    @Test
    fun `画像として読めない応答は失敗にし、保存せず、失敗も残さない`() {
        val calls = AtomicInteger()
        val cache = cache({ calls.incrementAndGet(); CompletableFuture.completedFuture(byteArrayOf(9, 9, 9)) })
        assertFailsWith<ExecutionException> { cache.get(mojangUri).get(5, TimeUnit.SECONDS) }
        assertFalse(fileOf(hash).exists())
        assertEquals(0, folder.list()!!.size)
        // 失敗はキャッシュに残らず、次の要求でまた取りに行く。
        assertFailsWith<ExecutionException> { cache.get(mojangUri).get(5, TimeUnit.SECONDS) }
        assertEquals(2, calls.get())
    }

    @Test
    fun `取得が失敗したら失敗にし、保存しない`() {
        val cache = cache({ CompletableFuture.failedFuture(IllegalStateException("404")) })
        assertFailsWith<ExecutionException> { cache.get(mojangUri).get(5, TimeUnit.SECONDS) }
        assertFalse(fileOf(hash).exists())
    }

    @Test
    fun `保存先が作れなくても画像は返し、失敗を記録する`() {
        // フォルダの場所にファイルを置いて、フォルダを作れない状態にする。
        folder.deleteRecursively()
        folder.writeBytes(byteArrayOf(0))
        val errors = mutableListOf<String>()
        val image = cache({ CompletableFuture.completedFuture(png(0x0A0B0C)) }, errors)
            .get(mojangUri).get(5, TimeUnit.SECONDS)
        assertEquals(2, image.width)
        assertEquals(1, errors.size)
        folder.delete()
    }

    @Test
    fun `読んだ時に最終更新時刻を触る。ただし新しいファイルは触らない`() {
        val old = fileOf(hash).also { it.writeBytes(png(0x111111)); it.age(10) }
        val recentKey = "b".repeat(64)
        val recent = fileOf(recentKey).also { it.writeBytes(png(0x222222)); it.setLastModified(baseTime - 1000) }
        val noFetch = { _: URI -> CompletableFuture.failedFuture<ByteArray>(IllegalStateException("呼ばれてはいけない")) }
        val cache = cache(noFetch)
        cache.get(mojangUri).get(5, TimeUnit.SECONDS)
        cache.get(URI.create("http://textures.minecraft.net/texture/$recentKey")).get(5, TimeUnit.SECONDS)
        assertEquals(baseTime, old.lastModified())
        assertEquals(baseTime - 1000, recent.lastModified())
    }

    @Test
    fun `掃除は期限を過ぎたファイルと中断された一時ファイルだけを消す`() {
        val fresh = fileOf("1".repeat(64)).also { it.writeBytes(png(1)); it.age(1) }
        val stale = fileOf("2".repeat(64)).also { it.writeBytes(png(2)); it.age(31) }
        val borderline = fileOf("3".repeat(64)).also { it.writeBytes(png(3)); it.age(29) }
        val oldTemp = File(folder, "skin-old.tmp").also { it.writeBytes(byteArrayOf(1)); it.age(1) }
        // 書き込み中の一時ファイルは消さない。
        val newTemp = File(folder, "skin-new.tmp").also { it.writeBytes(byteArrayOf(1)); it.setLastModified(baseTime - 1000) }
        // この機能が作ったものでないファイルは、古くても触らない。
        val other = File(folder, "memo.txt").also { it.writeBytes(byteArrayOf(1)); it.age(100) }

        val removed = cache({ CompletableFuture.failedFuture(IllegalStateException()) }).cleanUp()

        assertEquals(2, removed)
        assertTrue(fresh.exists())
        assertFalse(stale.exists())
        assertTrue(borderline.exists())
        assertFalse(oldTemp.exists())
        assertTrue(newTemp.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `掃除は合計が上限を超えたら古い順に消す`() {
        val size = png(1).size.toLong()
        val oldest = fileOf("1".repeat(64)).also { it.writeBytes(png(1)); it.age(5) }
        val middle = fileOf("2".repeat(64)).also { it.writeBytes(png(2)); it.age(3) }
        val newest = fileOf("3".repeat(64)).also { it.writeBytes(png(3)); it.age(1) }

        // 2枚分まで許す。一番古い1枚だけが消える。
        val removed = cache({ CompletableFuture.failedFuture(IllegalStateException()) })
            .cleanUp(maxTotalBytes = size * 2 + size / 2)

        assertEquals(1, removed)
        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun `掃除は保存先が無くても失敗しない`() {
        folder.deleteRecursively()
        assertEquals(0, cache({ CompletableFuture.failedFuture(IllegalStateException()) }).cleanUp())
    }
}
