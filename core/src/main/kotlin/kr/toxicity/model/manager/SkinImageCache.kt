/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import com.github.benmanes.caffeine.cache.Caffeine
import kr.toxicity.model.util.handleException
import kr.toxicity.model.util.moveReplacing
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * 皮膚・マントの PNG を、URL をキーにして保存するキャッシュ。
 *
 * プレイヤーの UUID ではなく URL で引くので、全員が同じ皮膚を使う場合（Floodgate の仮の皮膚など）や、
 * 入り直し・サーバーの再起動のあとでも、同じ URL なら HTTP を使わずに済む。
 * textures.minecraft.net の URL は末尾が画像のハッシュで、同じ URL なら中身は変わらない。
 *
 * - メモリ: URL のキー → 画像。同じ URL の同時の要求は1本の読み込みにまとめる。失敗は残らない。
 * - ディスク: [folder] の `<キー>.png`。書き込みは一時ファイルから原子的に移す。読めないファイルは消して HTTP へ戻る。
 * - ディスクの読み書きは [executor] の上で行う（呼び出し元のスレッドでは行わない）。
 *
 * 返す画像は共有されるので、呼び出し側は書き換えない。
 *
 * @param folder 保存先のフォルダ。無ければ書き込む時に作る
 * @param fetch URL から PNG のバイト列を取る処理。失敗は失敗した Future で返す
 * @param executor ディスクの I/O と画像のデコードを行う実行場所
 * @param now 現在の時刻（ミリ秒）。テストで差し替える
 * @param onError 保存・読み込みの失敗の記録先。失敗しても画像は返すので、ここでは記録だけを行う
 */
class SkinImageCache(
    private val folder: File,
    private val fetch: (URI) -> CompletableFuture<ByteArray>,
    private val executor: Executor = ForkJoinPool.commonPool(),
    private val now: () -> Long = System::currentTimeMillis,
    private val onError: (String, Throwable) -> Unit = { message, error -> error.handleException(message) }
) {

    companion object {
        private const val PNG_SUFFIX = ".png"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MOJANG_TEXTURES_HOST = "textures.minecraft.net"
        private val MOJANG_HASH = Regex("[0-9a-f]{16,64}")

        /**
         * 使われていないファイルを消す日数。
         * 皮膚を変えると URL が変わるので、古い皮膚のファイルはその人が戻らない限り使われない。
         * 30日来ていない人の皮膚は、戻ってきた時に取り直しても1回の HTTP で済む。
         */
        const val DEFAULT_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

        /**
         * ディスクに置く合計の上限。
         * 皮膚1枚は 64x64 の PNG で数 KB なので、数万枚を置ける。それを超える時は古い順に消す。
         */
        const val DEFAULT_MAX_TOTAL_BYTES = 64L * 1024 * 1024

        /** 書きかけの一時ファイルは、これより古ければ中断された残りとして消す。 */
        private const val TEMP_MAX_AGE_MILLIS = 60L * 60 * 1000

        /**
         * 最終更新時刻を触る間隔。
         * 読むたびに書き込むとディスクへの書き込みが増えるので、この間隔より新しければ触らない。
         * 掃除の基準（30日）に比べて十分短い。
         */
        private const val TOUCH_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

        /** メモリに置く画像の数と、最後に使ってから残す時間。皮膚1枚は 64x64 で 16KB ほど。 */
        private const val MEMORY_MAX_SIZE = 512L
        private const val MEMORY_EXPIRE_MINUTES = 30L

        /**
         * URL から、保存のキーを作る。
         * textures.minecraft.net の URL は末尾のハッシュ（小文字の 16 進）を使い、それ以外は URL 全体の SHA-256（16 進）を使う。
         * キーはファイル名になるので、使える文字は 16 進だけに限る（パスの区切りを含む URL を、保存先の外へ出さない）。
         */
        fun keyOf(uri: URI): String {
            if (uri.host.equals(MOJANG_TEXTURES_HOST, ignoreCase = true)) {
                val last = uri.path?.substringAfterLast('/')
                if (last != null && MOJANG_HASH.matches(last)) return last
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(uri.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }

    private val memory = Caffeine.newBuilder()
        .maximumSize(MEMORY_MAX_SIZE)
        .expireAfterAccess(MEMORY_EXPIRE_MINUTES, TimeUnit.MINUTES)
        .executor(executor)
        .buildAsync<String, BufferedImage>()

    /**
     * この URL の画像を返す。メモリ、ディスク、HTTP の順に探す。
     * 同じ URL の同時の要求は1本にまとまる。読めなければ失敗した Future で返し、失敗はキャッシュに残らない。
     */
    fun get(uri: URI): CompletableFuture<BufferedImage> {
        val key = keyOf(uri)
        return memory.get(key) { _, _ -> load(uri, key) }
    }

    private fun load(uri: URI, key: String): CompletableFuture<BufferedImage> {
        return CompletableFuture.supplyAsync({ readDisk(key) }, executor).thenCompose { cached ->
            if (cached != null) {
                CompletableFuture.completedFuture(cached)
            } else {
                fetch(uri).thenApplyAsync({ bytes ->
                    val image = checkNotNull(ImageIO.read(ByteArrayInputStream(bytes))) { "Unable to decode an image: $uri" }
                    // 画像として読めたものだけを保存する。
                    writeDisk(key, bytes)
                    image
                }, executor)
            }
        }
    }

    private fun fileOf(key: String) = File(folder, key + PNG_SUFFIX)

    private fun readDisk(key: String): BufferedImage? {
        val file = fileOf(key)
        if (!file.isFile) return null
        return try {
            val image = ImageIO.read(file)
            if (image == null) {
                // 画像として読めない。壊れているので捨て、HTTP から取り直す。
                file.delete()
                null
            } else {
                touch(file)
                image
            }
        } catch (e: Exception) {
            onError("Unable to read a cached skin: ${file.name}", e)
            file.delete()
            null
        }
    }

    private fun touch(file: File) {
        val current = now()
        if (current - file.lastModified() > TOUCH_INTERVAL_MILLIS) file.setLastModified(current)
    }

    private fun writeDisk(key: String, bytes: ByteArray) {
        try {
            folder.mkdirs()
            val temp = Files.createTempFile(folder.toPath(), "skin-", TEMP_SUFFIX)
            try {
                Files.write(temp, bytes)
                moveReplacing(temp, fileOf(key).toPath())
            } catch (e: Exception) {
                Files.deleteIfExists(temp)
                throw e
            }
        } catch (e: Exception) {
            // 保存できなくても、画像は取れているので返す。次回はまた HTTP から取る。
            onError("Unable to write a skin cache: $key", e)
        }
    }

    /**
     * 使われていないファイルを消す。
     * 最終更新から [maxAgeMillis] を超えたファイルと、中断された一時ファイルを消し、
     * 残りの合計が [maxTotalBytes] を超えていれば古い順に消す。
     * 呼び出し元のスレッドで I/O を行うので、tick やメインのスレッドでは呼ばない。
     *
     * @param maxAgeMillis 最終更新からの上限（ミリ秒）
     * @param maxTotalBytes 残すファイルの合計の上限（バイト）
     * @return 消したファイルの数
     */
    fun cleanUp(
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES
    ): Int {
        val files = folder.listFiles() ?: return 0
        val current = now()
        var removed = 0
        val alive = ArrayList<File>(files.size)
        for (file in files) {
            if (!file.isFile) continue
            val temp = file.name.endsWith(TEMP_SUFFIX)
            // この機能が作ったファイル以外は触らない。
            if (!temp && !file.name.endsWith(PNG_SUFFIX)) continue
            val age = current - file.lastModified()
            if (age > if (temp) TEMP_MAX_AGE_MILLIS else maxAgeMillis) {
                if (file.delete()) removed++
            } else if (!temp) {
                alive += file
            }
        }
        var total = alive.sumOf { it.length() }
        if (total > maxTotalBytes) {
            for (file in alive.sortedBy { it.lastModified() }) {
                if (total <= maxTotalBytes) break
                val size = file.length()
                if (file.delete()) {
                    total -= size
                    removed++
                }
            }
        }
        return removed
    }
}
