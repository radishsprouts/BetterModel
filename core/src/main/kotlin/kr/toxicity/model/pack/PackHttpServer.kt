/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.pack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * 配るリソースパックの ZIP。バイト列と SHA-1 はメモリに持ち、リクエストごとにディスクを読まない。
 *
 * @property bytes ZIP のバイト列
 */
class PackBundle(val bytes: ByteArray) {
    /** SHA-1（クライアントへ渡すハッシュ）。 */
    val sha1: ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)

    /** SHA-1 の16進文字列。 */
    val sha1Hex: String = sha1.toHex()

    /** ETag（引用符付き）。 */
    val etag: String = "\"$sha1Hex\""
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * リソースパックの ZIP を、1つのパスだけで配る小さな HTTP サーバー（JDK 標準の `com.sun.net.httpserver`）。
 *
 * - GET と HEAD だけに答える。それ以外は 405、[PACK_PATH] 以外は 404
 * - ETag / If-None-Match に答える（一致なら 304）
 * - 処理は仮想スレッドで行い、同時に処理するリクエストは [maxConnections] まで（超えたら 503）
 *
 * @property bindAddress 待ち受けるアドレス
 * @property port 待ち受けるポート（0 なら空いているポート）
 * @property maxConnections 同時に処理するリクエストの上限
 */
class PackHttpServer(
    private val bindAddress: String,
    private val port: Int,
    private val maxConnections: Int,
) {
    @Volatile
    private var bundle: PackBundle? = null
    private var server: HttpServer? = null
    private val slots = Semaphore(maxConnections.coerceAtLeast(1))

    /** 実際に待ち受けているポート。起動していなければ -1。 */
    val boundPort: Int get() = server?.address?.port ?: -1

    /**
     * 待ち受けを始める。ポートが使えない等で失敗しても例外は投げず、失敗を返す。
     *
     * @return 成功なら success、失敗なら原因の例外を持つ failure
     */
    @Synchronized
    fun start(): Result<Unit> {
        if (server != null) return Result.success(Unit)
        return runCatching {
            val created = HttpServer.create(InetSocketAddress(bindAddress, port), 0)
            try {
                created.createContext("/", ::handle)
                created.executor = Executors.newVirtualThreadPerTaskExecutor()
                created.start()
            } catch (e: Throwable) {
                created.stop(0)
                throw e
            }
            server = created
        }
    }

    /** 配るパックを差し替える。 */
    fun update(bundle: PackBundle) {
        this.bundle = bundle
    }

    /** 待ち受けを止めてポートを解放する。 */
    @Synchronized
    fun stop() {
        val current = server ?: return
        server = null
        bundle = null
        current.stop(0)
        (current.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            if (!slots.tryAcquire()) {
                respond(exchange, 503, "Service Unavailable")
                return
            }
            try {
                serve(exchange)
            } finally {
                slots.release()
            }
        }
    }

    private fun serve(exchange: HttpExchange) {
        if (exchange.requestURI.rawPath != PACK_PATH) return respond(exchange, 404, "Not Found")
        val method = exchange.requestMethod
        if (method != "GET" && method != "HEAD") {
            exchange.responseHeaders.set("Allow", "GET, HEAD")
            return respond(exchange, 405, "Method Not Allowed")
        }
        val current = bundle ?: return respond(exchange, 404, "Not Found")
        val headers = exchange.responseHeaders
        headers.set("ETag", current.etag)
        headers.set("Cache-Control", "no-cache")
        if (matches(exchange.requestHeaders.getFirst("If-None-Match"), current.etag)) {
            exchange.sendResponseHeaders(304, -1)
            return
        }
        headers.set("Content-Type", "application/zip")
        if (method == "HEAD") {
            headers.set("Content-Length", current.bytes.size.toString())
            exchange.sendResponseHeaders(200, -1)
            return
        }
        exchange.sendResponseHeaders(200, current.bytes.size.toLong())
        exchange.responseBody.use { it.write(current.bytes) }
    }

    private fun respond(exchange: HttpExchange, status: Int, text: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        if (exchange.requestMethod == "HEAD") {
            exchange.sendResponseHeaders(status, -1)
            return
        }
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun matches(header: String?, etag: String): Boolean {
        if (header == null) return false
        return header.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == etag }
    }

    companion object {
        /** 配るパスはこれだけ。 */
        const val PACK_PATH = "/bettermodel.zip"
    }
}
