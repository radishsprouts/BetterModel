/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.pack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cloudflare API を、ローカルの偽の API サーバー（JDK の HttpServer）に差し替えて確かめる。
 * 呼ぶ順番・使う認証・同じ中身で呼ばないこと・25 MiB 超・トークン無し・エラー・再試行を見る。
 */
class CloudflarePackPublisherTest {

    private class Call(val method: String, val path: String, val query: String?, val auth: String?, val body: String)

    private lateinit var server: HttpServer
    private lateinit var dir: File
    private val calls = CopyOnWriteArrayList<Call>()

    /** 呼び出しごとの応答を差し替える。null なら既定の成功。 */
    @Volatile
    private var override: (Call, Int) -> Pair<Int, String>? = { _, _ -> null }
    private val perPath = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("bm-cf").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", ::handle)
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readAllBytes().toString(Charsets.ISO_8859_1)
        val call = Call(exchange.requestMethod, exchange.requestURI.path, exchange.requestURI.query, exchange.requestHeaders.getFirst("Authorization"), body)
        calls += call
        val count = perPath.computeIfAbsent("${call.method} ${call.path}") { AtomicInteger() }.incrementAndGet()
        val (status, text) = override(call, count) ?: defaultResponse(call)
        val bytes = text.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun ok(result: String) = 200 to """{"success":true,"errors":[],"result":$result}"""

    private fun defaultResponse(call: Call): Pair<Int, String> = when {
        call.path.endsWith("/assets-upload-session") -> ok("""{"jwt":"upload-jwt","buckets":[["h"]]}""")
        call.path.endsWith("/workers/assets/upload") -> 201 to """{"success":true,"errors":[],"result":{"jwt":"completion-jwt"}}"""
        call.path.endsWith("/workers/subdomain") -> ok("""{"subdomain":"kado"}""")
        call.path.endsWith("/subdomain") -> ok("""{"enabled":true}""")
        call.path.endsWith("/workers/domains") -> ok("""{"hostname":"pack.example.com"}""")
        else -> ok("{}")
    }

    private fun config(
        token: String = "secret-token",
        domain: String = "",
        env: (String) -> String? = { null },
    ) = CloudflarePackConfig.of("acc123", token, "bettermodel-pack", domain, null, env)

    private fun publisher(
        config: CloudflarePackConfig = config(),
        verifier: ((String, ByteArray) -> Boolean)? = null,
        delays: List<Long> = listOf(1L, 1L),
    ) = CloudflarePackPublisher(config, File(dir, ".cache/cloudflare-pack.properties"), "http://127.0.0.1:${server.address.port}/client/v4", delays, verifier)

    private fun bundle(seed: Int = 1, size: Int = 5_000) = PackBundle(ByteArray(size) { (it * seed).toByte() })

    private fun pathsOf() = calls.map { "${it.method} ${it.path}" }

    @Test
    fun `成功したら API を決まった順に呼び、workers dev の URL を返す`() {
        val b = bundle()
        val result = publisher().publish(b)
        val published = assertIs<PublishResult.Published>(result)
        assertEquals("https://bettermodel-pack.kado.workers.dev/bettermodel-${b.sha1Hex.take(12)}.zip", published.url)
        assertFalse(published.reused)
        assertEquals(
            listOf(
                "POST /client/v4/accounts/acc123/workers/scripts/bettermodel-pack/assets-upload-session",
                "POST /client/v4/accounts/acc123/workers/assets/upload",
                "PUT /client/v4/accounts/acc123/workers/scripts/bettermodel-pack",
                "GET /client/v4/accounts/acc123/workers/subdomain",
                "POST /client/v4/accounts/acc123/workers/scripts/bettermodel-pack/subdomain",
            ),
            pathsOf(),
        )
        assertTrue(calls.all { it.auth != null })
        assertEquals("Bearer secret-token", calls[0].auth)
        assertEquals("Bearer upload-jwt", calls[1].auth, "ファイルのアップロードにはセッションの jwt を使う")
        assertEquals("base64=true", calls[1].query)
        assertEquals("Bearer secret-token", calls[2].auth)
        assertTrue(calls[0].body.contains("\"/bettermodel-${b.sha1Hex.take(12)}.zip\""), "マニフェストに /bettermodel-<12桁>.zip だけを入れる")
        assertTrue(calls[0].body.contains("\"size\":5000"))
        assertTrue(calls[2].body.contains("completion-jwt"), "Worker の PUT に completion token を渡す")
        assertTrue(calls[2].body.contains("\"main_module\":\"worker.js\""))
    }

    @Test
    fun `中身が同じなら API を呼ばず、前回の URL を使う`() {
        val b = bundle()
        val first = assertIs<PublishResult.Published>(publisher().publish(b))
        calls.clear()
        val second = assertIs<PublishResult.Published>(publisher().publish(b))
        assertTrue(calls.isEmpty())
        assertTrue(second.reused)
        assertEquals(first.url, second.url)
        // 中身が変われば呼ぶ
        publisher().publish(bundle(seed = 3))
        assertTrue(calls.isNotEmpty())
    }

    @Test
    fun `Worker の名前が変わったら、同じ中身でもアップロードし直す`() {
        val b = bundle()
        publisher().publish(b)
        calls.clear()
        val other = CloudflarePackConfig.of("acc123", "secret-token", "other-pack", null, null) { null }
        assertIs<PublishResult.Published>(publisher(other).publish(b))
        assertTrue(calls.isNotEmpty())
    }

    @Test
    fun `すでに置いてあるファイルなら（buckets が空）アップロードを飛ばして続ける`() {
        override = { call, _ -> if (call.path.endsWith("/assets-upload-session")) ok("""{"jwt":"completion-jwt","buckets":[]}""") else null }
        assertIs<PublishResult.Published>(publisher().publish(bundle()))
        assertFalse(pathsOf().any { it.endsWith("/workers/assets/upload") })
        assertEquals("Bearer secret-token", calls.first { it.method == "PUT" }.auth)
        assertTrue(calls.first { it.method == "PUT" }.body.contains("completion-jwt"))
    }

    @Test
    fun `カスタムドメインを指定したら、ドメインを付けてその URL を返す`() {
        val b = bundle()
        val result = assertIs<PublishResult.Published>(publisher(config(domain = "https://Pack.Example.com/")).publish(b))
        assertEquals("https://pack.example.com/bettermodel-${b.sha1Hex.take(12)}.zip", result.url)
        val domain = calls.last()
        assertEquals("PUT /client/v4/accounts/acc123/workers/domains", "${domain.method} ${domain.path}")
        assertTrue(domain.body.contains("\"hostname\":\"pack.example.com\""))
        assertTrue(domain.body.contains("\"service\":\"bettermodel-pack\""))
        assertFalse(pathsOf().any { it.endsWith("/workers/subdomain") })
    }

    @Test
    fun `25 MiB を超えたら送らずに失敗する`() {
        val result = publisher().publish(PackBundle(ByteArray(CloudflarePackPublisher.MAX_ASSET_BYTES + 1)))
        val failed = assertIs<PublishResult.Failed>(result)
        assertTrue(failed.message.contains("25 MiB"))
        assertTrue(calls.isEmpty())
        // ちょうど上限は送る
        assertIs<PublishResult.Published>(publisher().publish(PackBundle(ByteArray(CloudflarePackPublisher.MAX_ASSET_BYTES))))
    }

    @Test
    fun `トークンとアカウント ID が無ければ何が足りないかを返す`() {
        val none = CloudflarePackConfig.of(null, "", null, null, null) { null }
        assertEquals(2, none.missing().size)
        assertTrue(none.missing()[0].contains("BETTERMODEL_CLOUDFLARE_ACCOUNT_ID"))
        assertTrue(none.missing()[1].contains("BETTERMODEL_CLOUDFLARE_API_TOKEN"))
        val fromEnv = CloudflarePackConfig.of(
            "", "", null, null, null,
        ) { if (it == CloudflarePackConfig.ENV_API_TOKEN) "env-token" else if (it == CloudflarePackConfig.ENV_ACCOUNT_ID) "env-acc" else null }
        assertTrue(fromEnv.missing().isEmpty())
        assertEquals("env-token", fromEnv.apiToken)
        assertFalse(fromEnv.toString().contains("env-token"), "トークンを文字列にしない")
        assertEquals("bettermodel-pack", none.workerName)
    }

    @Test
    fun `API がエラーを返したら、その理由を含めて失敗し、状態を保存しない`() {
        override = { call, _ ->
            if (call.method == "PUT" && call.path.contains("/workers/scripts/")) 403 to """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}],"result":null}""" else null
        }
        val failed = assertIs<PublishResult.Failed>(publisher().publish(bundle()))
        assertTrue(failed.message.contains("HTTP 403"))
        assertTrue(failed.message.contains("Authentication error"))
        assertFalse(failed.message.contains("secret-token"))
        assertFalse(File(dir, ".cache/cloudflare-pack.properties").exists())
        // 4xx は再試行しない
        assertEquals(1, calls.count { it.method == "PUT" })
    }

    @Test
    fun `一時的な失敗は間隔を空けて再試行し、上限で諦める`() {
        override = { call, count ->
            if (call.path.endsWith("/assets-upload-session") && count <= 2) 503 to "unavailable" else null
        }
        assertIs<PublishResult.Published>(publisher().publish(bundle()))
        assertEquals(3, calls.count { it.path.endsWith("/assets-upload-session") })

        calls.clear()
        perPath.clear()
        override = { call, _ -> if (call.path.endsWith("/assets-upload-session")) 429 to "slow down" else null }
        val failed = assertIs<PublishResult.Failed>(publisher().publish(bundle(seed = 5)))
        assertTrue(failed.message.contains("HTTP 429"))
        assertEquals(3, calls.count { it.path.endsWith("/assets-upload-session") }, "最初の1回と再試行2回（delays の数）")
    }

    @Test
    fun `サブドメインが無いアカウントは、案内つきで失敗する`() {
        override = { call, _ -> if (call.path.endsWith("/workers/subdomain")) ok("""{"subdomain":null}""") else null }
        val failed = assertIs<PublishResult.Failed>(publisher().publish(bundle()))
        assertTrue(failed.message.contains("custom-domain"))
        assertFalse(File(dir, ".cache/cloudflare-pack.properties").exists())
    }

    @Test
    fun `公開した URL から取得できなければ失敗し、状態を保存しない`() {
        val failed = assertIs<PublishResult.Failed>(publisher(verifier = { _, _ -> false }).publish(bundle()))
        assertTrue(failed.message.contains("取得できませんでした"))
        calls.clear()
        // 保存されていないので、次回は中身が同じでも呼び直す
        assertIs<PublishResult.Published>(publisher(verifier = { _, _ -> true }).publish(bundle()))
        assertTrue(calls.isNotEmpty())
    }

    @Test
    fun `状態は小文字の固定名のプロパティファイルに、フォルダごと作って保存する`() {
        val b = bundle()
        publisher().publish(b)
        val file = File(dir, ".cache/cloudflare-pack.properties")
        assertTrue(file.isFile)
        val props = java.util.Properties().also { p -> file.inputStream().use(p::load) }
        assertEquals(b.sha1Hex, props.getProperty("sha1"))
        assertNull(props.getProperty("api-token"))
        assertFalse(file.readText().contains("secret-token"))
        assertEquals(1, dir.resolve(".cache").listFiles()!!.size, "一時ファイルを残さない")
    }
}
