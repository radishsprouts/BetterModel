/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.pack

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * パックを配る HTTP サーバーが、配ったバイト列と SHA-1 を一致させること、
 * 404・405・ETag の扱い、ポートが使えない時に例外で止まらないことを確かめる。
 */
class PackHttpServerTest {

    private val client = HttpClient.newHttpClient()
    private val servers = mutableListOf<PackHttpServer>()

    @AfterTest
    fun tearDown() {
        servers.forEach(PackHttpServer::stop)
        client.close()
    }

    private fun start(bundle: PackBundle? = PackBundle(ByteArray(10_000) { (it % 251).toByte() })): PackHttpServer {
        val server = PackHttpServer("127.0.0.1", 0, 4).also(servers::add)
        assertTrue(server.start().isSuccess)
        bundle?.let(server::update)
        return server
    }

    private fun uri(server: PackHttpServer, path: String = PackHttpServer.PACK_PATH) =
        URI.create("http://127.0.0.1:${server.boundPort}$path")

    private fun send(request: HttpRequest) = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

    @Test
    fun `配ったバイト列と SHA-1 が、クライアントへ渡すハッシュと一致する`() {
        val bundle = PackBundle(ByteArray(50_000) { (it * 7).toByte() })
        val server = start(bundle)
        val response = send(HttpRequest.newBuilder(uri(server)).GET().build())
        assertEquals(200, response.statusCode())
        assertContentEquals(bundle.bytes, response.body())
        assertContentEquals(bundle.sha1, MessageDigest.getInstance("SHA-1").digest(response.body()))
        assertEquals("application/zip", response.headers().firstValue("Content-Type").orElse(null))
        assertEquals(bundle.etag, response.headers().firstValue("ETag").orElse(null))
    }

    @Test
    fun `HEAD は本文無しで長さを返す`() {
        val bundle = PackBundle(ByteArray(1234) { 1 })
        val server = start(bundle)
        val response = send(HttpRequest.newBuilder(uri(server)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build())
        assertEquals(200, response.statusCode())
        assertEquals(0, response.body().size)
        assertEquals("1234", response.headers().firstValue("Content-Length").orElse(null))
        assertEquals(bundle.etag, response.headers().firstValue("ETag").orElse(null))
    }

    @Test
    fun `If-None-Match が一致すると 304 を返し、違えば本文を返す`() {
        val bundle = PackBundle(ByteArray(100) { 2 })
        val server = start(bundle)
        val same = send(HttpRequest.newBuilder(uri(server)).header("If-None-Match", bundle.etag).GET().build())
        assertEquals(304, same.statusCode())
        assertEquals(0, same.body().size)
        val star = send(HttpRequest.newBuilder(uri(server)).header("If-None-Match", "*").GET().build())
        assertEquals(304, star.statusCode())
        val other = send(HttpRequest.newBuilder(uri(server)).header("If-None-Match", "\"other\"").GET().build())
        assertEquals(200, other.statusCode())
    }

    @Test
    fun `配るパス以外は 404、GET と HEAD 以外は 405`() {
        val server = start()
        assertEquals(404, send(HttpRequest.newBuilder(uri(server, "/")).GET().build()).statusCode())
        assertEquals(404, send(HttpRequest.newBuilder(uri(server, "/bettermodel.zip.bak")).GET().build()).statusCode())
        assertEquals(404, send(HttpRequest.newBuilder(uri(server, "/other")).POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode())
        for (method in listOf("POST", "PUT", "DELETE", "PATCH", "OPTIONS")) {
            val response = send(HttpRequest.newBuilder(uri(server)).method(method, HttpRequest.BodyPublishers.noBody()).build())
            assertEquals(405, response.statusCode(), method)
            assertEquals("GET, HEAD", response.headers().firstValue("Allow").orElse(null), method)
        }
    }

    @Test
    fun `パックが未設定の間は 404`() {
        val server = start(null)
        assertEquals(404, send(HttpRequest.newBuilder(uri(server)).GET().build()).statusCode())
    }

    @Test
    fun `差し替えたパックは次のリクエストから配られ、ETag も変わる`() {
        val first = PackBundle(ByteArray(10) { 1 })
        val second = PackBundle(ByteArray(10) { 2 })
        val server = start(first)
        val before = send(HttpRequest.newBuilder(uri(server)).GET().build())
        server.update(second)
        val after = send(HttpRequest.newBuilder(uri(server)).GET().build())
        assertContentEquals(first.bytes, before.body())
        assertContentEquals(second.bytes, after.body())
        assertNotEquals(before.headers().firstValue("ETag").get(), after.headers().firstValue("ETag").get())
    }

    @Test
    fun `ポートが使えない時は例外で止まらず失敗を返す`() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { occupied ->
            val server = PackHttpServer("127.0.0.1", occupied.localPort, 4).also(servers::add)
            val result = server.start()
            assertTrue(result.isFailure)
            assertEquals(-1, server.boundPort)
            server.stop()
        }
    }

    @Test
    fun `不正なアドレスでも例外で止まらず失敗を返す`() {
        val server = PackHttpServer("256.256.256.256", 0, 4).also(servers::add)
        assertTrue(server.start().isFailure)
    }

    @Test
    fun `停止するとポートが解放される`() {
        val server = start()
        val port = server.boundPort
        server.stop()
        ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).close()
    }

    @Test
    fun `url モードのハッシュ取得は実物の SHA-1 を返し、取れなければ失敗で完了する`() {
        val bundle = PackBundle(ByteArray(200_000) { (it % 13).toByte() })
        val server = start(bundle)
        UrlHasher().use { hasher ->
            val hash = hasher.hash(uri(server).toString()).get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertContentEquals(bundle.sha1, hash)
            val missing = hasher.hash(uri(server, "/nothing").toString())
            assertFailsWith<java.util.concurrent.ExecutionException> { missing.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            val unreachable = hasher.hash("http://127.0.0.1:1/x.zip")
            assertFailsWith<java.util.concurrent.ExecutionException> { unreachable.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `同時処理の上限を超えたリクエストは 503`() {
        val server = PackHttpServer("127.0.0.1", 0, 1).also(servers::add)
        assertTrue(server.start().isSuccess)
        // 上限 1 のスロットを塞ぐため、本文を読み終えない接続を1本開く
        val big = PackBundle(ByteArray(64 * 1024 * 1024))
        server.update(big)
        val first = client.send(HttpRequest.newBuilder(uri(server)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        first.body().use { stream ->
            stream.read()
            var status = 0
            repeat(20) {
                if (status != 503) {
                    status = send(HttpRequest.newBuilder(uri(server)).GET().build()).statusCode()
                }
            }
            assertEquals(503, status)
        }
    }
}
