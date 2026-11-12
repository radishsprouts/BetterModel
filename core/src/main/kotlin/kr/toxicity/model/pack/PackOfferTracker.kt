/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.pack

import java.util.concurrent.CompletableFuture
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * クライアントへ渡しているパックの内容と、接続中のプレイヤーへ送り直す必要があるかの判定。
 * 同じパックを何度も送らないための状態で、スレッドから安全に使える。
 */
class PackOfferTracker {

    /**
     * @property url パックの URL
     * @property contentId パックの中身の識別（SHA-1）。**クライアントへは渡さない**。送り直すかの判定にだけ使う。まだ取れていなければ null
     */
    class Offer(val url: String, val contentId: ByteArray?) {
        override fun equals(other: Any?) = other is Offer && url == other.url && contentId.contentEqualsNullable(other.contentId)
        override fun hashCode() = 31 * url.hashCode() + (contentId?.contentHashCode() ?: 0)
        private fun ByteArray?.contentEqualsNullable(other: ByteArray?) = if (this == null) other == null else other != null && contentEquals(other)
    }

    /** 接続中のプレイヤーへ何をするか。 */
    enum class Action {
        /** 何もしない。 */
        NONE,

        /** パックを外す。 */
        REMOVE,

        /** 外して送り直す。 */
        SEND,
    }

    @Volatile
    private var current: Offer? = null

    /** 今配っている内容。参加するプレイヤーへ送る。無ければ配っていない。 */
    fun current(): Offer? = current

    /**
     * 配る内容を更新し、接続中のプレイヤーへの処理を返す。
     * 識別が無い状態から付いた状態になっただけ（URL が同じ）の時は、すでに同じ URL を送っているので送り直さない。
     * 識別付きから無しに戻った時（取得に失敗した等）も、パックの実体は変わらないので送り直さない。
     */
    @Synchronized
    fun update(next: Offer?): Action {
        val previous = current
        current = next
        return when {
            previous == null && next == null -> Action.NONE
            next == null -> Action.REMOVE
            previous == null -> Action.SEND
            previous.url != next.url -> Action.SEND
            previous.contentId == null || next.contentId == null -> Action.NONE
            previous.contentId.contentEquals(next.contentId) -> Action.NONE
            else -> Action.SEND
        }
    }
}

/**
 * URL の実物を取って SHA-1 を計算する（url モード用）。ダウンロードは非同期で、ディスクには書かない。
 */
class UrlHasher(private val client: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(10))
    .build()) : AutoCloseable {

    /**
     * @param url 取得する URL
     * @return SHA-1。取れなければ例外で完了する
     */
    fun hash(url: String): CompletableFuture<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build()
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenApplyAsync { response ->
            response.body().use { stream ->
                if (response.statusCode() !in 200..299) throw IllegalStateException("HTTP ${response.statusCode()}")
                val digest = MessageDigest.getInstance("SHA-1")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest()
            }
        }
    }

    override fun close() {
        client.close()
    }
}
