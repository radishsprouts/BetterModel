/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.pack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.apache.commons.codec.digest.Blake3
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
        // 全部が新しいファイルとして、マニフェストの全ハッシュを1つの bucket で要求する
        call.path.endsWith("/assets-upload-session") ->
            ok("""{"jwt":"upload-jwt","buckets":[[${hashesOf(call.body).joinToString(",") { "\"$it\"" }}]]}""")
        call.path.endsWith("/workers/assets/upload") -> 201 to """{"success":true,"errors":[],"result":{"jwt":"completion-jwt"}}"""
        call.path.endsWith("/subdomain") -> ok("""{"enabled":false}""")
        call.path.endsWith("/workers/domains") -> ok("""{"hostname":"bettermodel.minecraft.kaiware.games"}""")
        else -> ok("{}")
    }

    private fun hashesOf(body: String) = Regex("\"hash\":\"([0-9a-f]+)\"").findAll(body).map { it.groupValues[1] }.toList()

    private fun config(
        token: String = "secret-token",
        domain: String = DOMAIN,
        fileName: String = FILE,
        env: (String) -> String? = { null },
    ) = CloudflarePackConfig.of("acc123", token, "bettermodel-pack", domain, null, fileName, env)

    private fun publisher(
        config: CloudflarePackConfig = config(),
        verifier: ((String, ByteArray) -> VerifyResult)? = null,
        delays: List<Long> = listOf(1L, 1L),
        verifyIntervalMillis: Long = 1L,
        verifyTimeoutMillis: Long = 5_000L,
        onWaiting: (String) -> Unit = {},
        fetcher: (String) -> FetchResult = { FetchResult.NotFound },
    ) = CloudflarePackPublisher(
        config, File(dir, ".cache/cloudflare-pack.properties"), "http://127.0.0.1:${server.address.port}/client/v4", delays, verifier,
        verifyIntervalMillis = verifyIntervalMillis, verifyTimeoutMillis = verifyTimeoutMillis, onWaiting = onWaiting, fetcher = fetcher,
    )

    private fun bundle(seed: Int = 1, size: Int = 5_000) = PackBundle(ByteArray(size) { (it * seed).toByte() })

    private companion object {
        const val DOMAIN = "bettermodel.minecraft.kaiware.games"
        const val FILE = "BetterModel-ResourcePack-dev.zip"
        const val OTHER = "/BetterModel-ResourcePack.zip"
    }

    private fun entryJson(bytes: ByteArray) = """{"hash":"${CloudflarePackPublisher.manifestHash(bytes)}","size":${bytes.size}}"""

    /** Worker に置いてある目録（パス順）。 */
    private fun catalogJson(files: Map<String, ByteArray>) =
        """{"files":{${files.toSortedMap().entries.joinToString(",") { "\"${it.key}\":${entryJson(it.value)}" }}}}"""

    private fun isCatalog(url: String) = url.substringBefore('?').endsWith(CloudflarePackPublisher.CATALOG_PATH)

    private fun calls(method: String, suffix: String) = calls.filter { it.method == method && it.path.endsWith(suffix) }

    private fun pathsOf() = calls.map { "${it.method} ${it.path}" }

    @Test
    fun `成功したら API を決まった順に呼び、カスタムドメインの URL を返す`() {
        val b = bundle()
        val result = publisher().publish(b)
        val published = assertIs<PublishResult.Published>(result)
        assertEquals("https://$DOMAIN/$FILE", published.url)
        assertFalse(published.reused)
        assertEquals(
            listOf(
                "POST /client/v4/accounts/acc123/workers/scripts/bettermodel-pack/assets-upload-session",
                "POST /client/v4/accounts/acc123/workers/assets/upload",
                "PUT /client/v4/accounts/acc123/workers/scripts/bettermodel-pack",
                "PUT /client/v4/accounts/acc123/workers/domains",
                "POST /client/v4/accounts/acc123/workers/scripts/bettermodel-pack/subdomain",
            ),
            pathsOf(),
        )
        assertTrue(calls.all { it.auth != null })
        assertEquals("Bearer secret-token", calls[0].auth)
        assertEquals("Bearer upload-jwt", calls[1].auth, "ファイルのアップロードにはセッションの jwt を使う")
        assertEquals("base64=true", calls[1].query)
        assertEquals("Bearer secret-token", calls[2].auth)
        assertTrue(calls[0].body.contains("\"/$FILE\""), "マニフェストに設定の file-name のパスを入れる")
        assertTrue(calls[0].body.contains("\"${CloudflarePackPublisher.CATALOG_PATH}\""), "目録も一緒に置く")
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
    fun `カスタムドメインの指定は大文字・スキーム・末尾のスラッシュとドットを直して付ける`() {
        val b = bundle()
        val result = assertIs<PublishResult.Published>(publisher(config(domain = "https://Pack.Example.com./")).publish(b))
        assertEquals("https://pack.example.com/$FILE", result.url)
        val domain = calls.first { it.path.endsWith("/workers/domains") }
        assertEquals("PUT", domain.method)
        assertTrue(domain.body.contains("\"hostname\":\"pack.example.com\""))
        assertTrue(domain.body.contains("\"service\":\"bettermodel-pack\""))
    }

    @Test
    fun `custom-domain が空なら、何も呼ばずにエラーになる`() {
        val failed = assertIs<PublishResult.Failed>(publisher(config(domain = "")).publish(bundle()))
        assertTrue(failed.message.contains("cloudflare.custom-domain を設定してください"))
        assertTrue(calls.isEmpty(), "アップロードも workers.dev の取得も呼ばない")
        assertFalse(File(dir, ".cache/cloudflare-pack.properties").exists())
        // 設定の検査にも出る（配布側が警告に使う）
        assertTrue(config(domain = "").missing().single().contains("custom-domain"))
        // キーが無い（null）時は既定のドメインを使う
        assertEquals(DOMAIN, CloudflarePackConfig.of("a", "t", null, null, null) { null }.customDomain)
    }

    @Test
    fun `file-server の resource minecraft kaiware games は、大文字・末尾のドットでもアップロードしない`() {
        for (domain in listOf("resource.minecraft.kaiware.games", "Resource.Minecraft.Kaiware.Games.", "https://resource.minecraft.kaiware.games/")) {
            val failed = assertIs<PublishResult.Failed>(publisher(config(domain = domain)).publish(bundle()))
            assertTrue(failed.message.contains("resource.minecraft.kaiware.games"), domain)
            assertTrue(config(domain = domain).missing().isNotEmpty(), domain)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `workers dev のサブドメインを取得せず、script の公開 URL と preview を無効にする`() {
        assertIs<PublishResult.Published>(publisher().publish(bundle()))
        assertFalse(pathsOf().any { it.endsWith("/workers/subdomain") }, "GET /workers/subdomain は呼ばない")
        assertTrue(calls.none { it.method == "GET" })
        val disable = calls.single { it.path.endsWith("/workers/scripts/bettermodel-pack/subdomain") }
        assertEquals("POST", disable.method)
        assertTrue(disable.body.contains("\"enabled\":false"))
        assertTrue(disable.body.contains("\"previews_enabled\":false"))
        assertTrue(calls.indexOfFirst { it.path.endsWith("/workers/domains") } < calls.indexOf(disable), "ドメインを付けた後に無効にする")
    }

    @Test
    fun `ドメインの付与で既存のものを上書きしない（override を送らない）`() {
        assertIs<PublishResult.Published>(publisher().publish(bundle()))
        val domain = calls.single { it.path.endsWith("/workers/domains") }
        assertFalse(domain.body.contains("override"), domain.body)
    }

    @Test
    fun `ホスト名が既に使われていて付与が断られたら、上書きせず警告して止まり、公開 URL も無効にする`() {
        override = { call, _ ->
            if (call.path.endsWith("/workers/domains")) 409 to """{"success":false,"errors":[{"code":100117,"message":"Hostname already has externally managed DNS records"}],"result":null}""" else null
        }
        val failed = assertIs<PublishResult.Failed>(publisher().publish(bundle()))
        assertTrue(failed.message.contains("HTTP 409"))
        assertTrue(failed.message.contains("上書きしません"))
        assertEquals(1, calls.count { it.path.endsWith("/workers/domains") }, "再試行も上書きもしない")
        assertTrue(calls.any { it.path.endsWith("/workers/scripts/bettermodel-pack/subdomain") && it.body.contains("\"enabled\":false") })
        assertFalse(File(dir, ".cache/cloudflare-pack.properties").exists())
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
    fun `公開した URL から取得できなければ失敗し、状態を保存しない`() {
        val failed = assertIs<PublishResult.Failed>(
            publisher(verifier = { _, _ -> VerifyResult.RETRY }, verifyTimeoutMillis = 50L).publish(bundle())
        )
        assertTrue(failed.message.contains("取得できませんでした"))
        calls.clear()
        // 保存されていないので、次回は中身が同じでも呼び直す
        assertIs<PublishResult.Published>(publisher(verifier = { _, _ -> VerifyResult.OK }).publish(bundle()))
        assertTrue(calls.isNotEmpty())
    }

    @Test
    fun `最初の数回は取れなくても、あとで取れれば成功し、待ち始めを1回だけ知らせる`() {
        val tries = AtomicInteger()
        val waiting = CopyOnWriteArrayList<String>()
        val result = publisher(
            verifier = { _, _ -> if (tries.incrementAndGet() <= 4) VerifyResult.RETRY else VerifyResult.OK },
            onWaiting = { waiting += it },
        ).publish(bundle())
        val published = assertIs<PublishResult.Published>(result)
        assertEquals(5, tries.get())
        assertEquals(listOf(published.url), waiting.toList(), "毎回ではなく最初の失敗で1回")
    }

    @Test
    fun `最後まで取れなければ、待ち時間の間に何度か試して失敗する`() {
        val tries = AtomicInteger()
        val failed = assertIs<PublishResult.Failed>(
            publisher(
                verifier = { _, _ -> tries.incrementAndGet(); VerifyResult.RETRY },
                verifyTimeoutMillis = 100L,
            ).publish(bundle())
        )
        assertTrue(failed.message.contains("取得できませんでした"))
        assertTrue(tries.get() > 1, "再試行している")
    }

    @Test
    fun `待っても直らない失敗は再試行しない`() {
        val tries = AtomicInteger()
        val failed = assertIs<PublishResult.Failed>(
            publisher(verifier = { _, _ -> tries.incrementAndGet(); VerifyResult.FATAL }).publish(bundle())
        )
        assertTrue(failed.message.contains("取得できませんでした"))
        assertEquals(1, tries.get())
    }

    @Test
    fun `待っている間に割り込まれたら、すぐやめて中断として返す`() {
        val tries = AtomicInteger()
        val result = AtomicReference<PublishResult>()
        val worker = Thread {
            result.set(
                publisher(
                    verifier = { _, _ -> tries.incrementAndGet(); VerifyResult.RETRY },
                    verifyIntervalMillis = 600_000L,
                    verifyTimeoutMillis = 1_200_000L,
                ).publish(bundle())
            )
        }
        worker.start()
        // 1回目の確認が済んで、長い間隔の sleep に入ってから割り込む
        while (tries.get() < 1) Thread.sleep(5)
        Thread.sleep(50)
        worker.interrupt()
        worker.join(10_000)
        assertFalse(worker.isAlive, "割り込みで直ちに戻る")
        val failed = assertIs<PublishResult.Failed>(result.get())
        assertTrue(failed.message.contains("中断"))
        assertEquals(1, tries.get())
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

    @Test
    fun `他のファイルがマニフェストに残り、目録も他の行を引き継いで置かれる`() {
        val other = ByteArray(300) { (it * 7).toByte() }
        val b = bundle()
        val published = assertIs<PublishResult.Published>(
            publisher(fetcher = { if (isCatalog(it)) FetchResult.Found(catalogJson(mapOf(OTHER to other)).toByteArray()) else FetchResult.NotFound })
                .publish(b)
        )
        assertEquals("https://$DOMAIN/$FILE", published.url)
        val manifest = calls[0].body
        assertTrue(manifest.contains("\"$OTHER\":{\"hash\":\"${CloudflarePackPublisher.manifestHash(other)}\",\"size\":300}"), "他のファイルの行をそのまま載せる")
        assertTrue(manifest.contains("\"/$FILE\":{\"hash\":\"${CloudflarePackPublisher.manifestHash(b.bytes)}\""))
        val expectedCatalog = catalogJson(mapOf(OTHER to other, "/$FILE" to b.bytes)).toByteArray()
        assertTrue(
            manifest.contains("\"${CloudflarePackPublisher.CATALOG_PATH}\":{\"hash\":\"${CloudflarePackPublisher.manifestHash(expectedCatalog, "json")}\",\"size\":${expectedCatalog.size}}"),
            "目録は他の行と自分の行を持つ",
        )
    }

    @Test
    fun `自分の file-name のファイルだけが差し替わり、他のファイルの行は変わらない`() {
        val old = ByteArray(200) { 1 }
        val other = ByteArray(300) { 2 }
        val b = bundle()
        val catalog = catalogJson(mapOf("/$FILE" to old, OTHER to other))
        assertIs<PublishResult.Published>(
            publisher(fetcher = { if (isCatalog(it)) FetchResult.Found(catalog.toByteArray()) else FetchResult.NotFound }).publish(b)
        )
        val manifest = calls[0].body
        assertFalse(manifest.contains(CloudflarePackPublisher.manifestHash(old)), "自分の古い行は消える")
        assertTrue(manifest.contains(CloudflarePackPublisher.manifestHash(b.bytes)))
        assertTrue(manifest.contains(CloudflarePackPublisher.manifestHash(other)), "他のファイルは変えない")
        assertEquals(3, hashesOf(manifest).size, "自分・他のファイル・目録の3行")
    }

    @Test
    fun `他のファイルのハッシュを要求されたら、カスタムドメインから取って一緒に上げる`() {
        val other = ByteArray(300) { (it * 5).toByte() }
        val b = bundle()
        val fetched = CopyOnWriteArrayList<String>()
        // fake の API は、マニフェストの全ハッシュ（他のファイルも）を bucket で要求する
        assertIs<PublishResult.Published>(
            publisher(fetcher = {
                fetched += it
                when {
                    isCatalog(it) -> FetchResult.Found(catalogJson(mapOf(OTHER to other)).toByteArray())
                    it.substringBefore('?').endsWith(OTHER) -> FetchResult.Found(other)
                    else -> FetchResult.NotFound
                }
            }).publish(b)
        )
        assertTrue(fetched.any { it.startsWith("https://$DOMAIN$OTHER?") }, "他のファイルをカスタムドメインから取る")
        val upload = calls("POST", "/workers/assets/upload").single()
        val otherHash = CloudflarePackPublisher.manifestHash(other)
        assertTrue(upload.body.contains("name=\"$otherHash\""))
        assertTrue(upload.body.contains(java.util.Base64.getEncoder().encodeToString(other)), "取った中身（base64）を送る")
        assertEquals(1, calls("PUT", "/workers/scripts/bettermodel-pack").size)
    }

    @Test
    fun `他のファイルを取れなければ、他のパックを消さないよう中止し、何も PUT しない`() {
        val other = ByteArray(300) { 9 }
        val failed = assertIs<PublishResult.Failed>(
            publisher(fetcher = { if (isCatalog(it)) FetchResult.Found(catalogJson(mapOf(OTHER to other)).toByteArray()) else FetchResult.NotFound })
                .publish(bundle())
        )
        assertTrue(failed.message.contains("中止"), failed.message)
        assertTrue(failed.message.contains(OTHER))
        assertTrue(calls.none { it.method == "PUT" }, "Worker を更新しない")
        assertTrue(calls("POST", "/workers/assets/upload").isEmpty(), "1つも送らない")
        assertFalse(File(dir, ".cache/cloudflare-pack.properties").exists())

        // 取れても中身が目録と違えば同じく中止する
        calls.clear()
        val mismatch = assertIs<PublishResult.Failed>(
            publisher(fetcher = {
                when {
                    isCatalog(it) -> FetchResult.Found(catalogJson(mapOf(OTHER to other)).toByteArray())
                    else -> FetchResult.Found(ByteArray(300) { 8 })
                }
            }).publish(bundle(seed = 4))
        )
        assertTrue(mismatch.message.contains("食い違"))
        assertTrue(calls.none { it.method == "PUT" })
    }

    @Test
    fun `目録を読めない時は、Worker がまだ無ければ空として続け、あれば中止する`() {
        // 初回（Worker が無い）: 設定の取得が 404
        override = { call, _ ->
            if (call.path.endsWith("/settings")) 404 to """{"success":false,"errors":[{"code":10007,"message":"This Worker does not exist"}],"result":null}""" else null
        }
        val first = publisher(fetcher = { FetchResult.Unreachable("TLS") }).publish(bundle())
        assertIs<PublishResult.Published>(first)
        assertEquals(1, calls("GET", "/workers/scripts/bettermodel-pack/settings").size)

        // Worker はあるのに目録を取れない: 他のパックを消さないよう中止
        calls.clear()
        override = { _, _ -> null }
        val failed = assertIs<PublishResult.Failed>(publisher(fetcher = { FetchResult.Unreachable("TLS") }).publish(bundle(seed = 6)))
        assertTrue(failed.message.contains("中止"))
        assertTrue(calls.none { it.method == "PUT" || it.method == "POST" })

        // 目録が壊れている時も中止
        calls.clear()
        val broken = assertIs<PublishResult.Failed>(
            publisher(fetcher = { if (isCatalog(it)) FetchResult.Found("not json".toByteArray()) else FetchResult.NotFound }).publish(bundle(seed = 7))
        )
        assertTrue(broken.message.contains("中止"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `不正な file-name は、何もアップロードせず理由を返す`() {
        val bad = listOf("", "../x.zip", "a/b.zip", "a\\b.zip", "x.txt", "x.ZIP", "a b.zip", ".hidden.zip", "a..b.zip", "日本語.zip", "x".repeat(100) + ".zip")
        for (name in bad) {
            val config = config(fileName = name)
            val failed = assertIs<PublishResult.Failed>(publisher(config).publish(bundle()), name)
            assertTrue(failed.message.contains("file-name"), name)
            assertTrue(config.missing().any { it.contains("file-name") }, name)
        }
        assertTrue(calls.isEmpty(), "アップロードも API の呼び出しもしない")
        assertFalse(File(dir, ".cache/cloudflare-pack.properties").exists())
        // 使える名前
        for (name in listOf("BetterModel-ResourcePack.zip", "BetterModel-ResourcePack-dev.zip", "a_b-1.2.zip")) {
            assertNull(config(fileName = name).fileNameProblem(), name)
        }
        // キーが無い（null）時は既定の名前
        assertEquals("BetterModel-ResourcePack.zip", CloudflarePackConfig.of("a", "t", null, null, null) { null }.fileName)
    }

    @Test
    fun `前回の状態の照合に file-name も効く`() {
        val b = bundle()
        assertIs<PublishResult.Published>(publisher().publish(b))
        calls.clear()
        // 同じ file-name なら呼ばない
        assertTrue(assertIs<PublishResult.Published>(publisher().publish(b)).reused)
        assertTrue(calls.isEmpty())
        // file-name が変われば、同じ中身でもアップロードし直し、URL も新しい名前になる
        val renamed = assertIs<PublishResult.Published>(publisher(config(fileName = "BetterModel-ResourcePack.zip")).publish(b))
        assertFalse(renamed.reused)
        assertEquals("https://$DOMAIN/BetterModel-ResourcePack.zip", renamed.url)
        assertTrue(calls.isNotEmpty())
        val props = java.util.Properties().also { p -> File(dir, ".cache/cloudflare-pack.properties").inputStream().use(p::load) }
        assertEquals("BetterModel-ResourcePack.zip", props.getProperty("file-name"))
    }

    @Test
    fun `取得確認は自分の file-name の URL で行う`() {
        val urls = CopyOnWriteArrayList<String>()
        assertIs<PublishResult.Published>(publisher(verifier = { url, _ -> urls += url; VerifyResult.OK }).publish(bundle()))
        assertEquals(listOf("https://$DOMAIN/$FILE"), urls.toList())
    }

    @Test
    fun `アップロード後に他のファイルの行が落ちていたら、1回だけ戻す`() {
        val other = ByteArray(300) { 3 }
        val b = bundle()
        val reads = AtomicInteger()
        val full = catalogJson(mapOf(OTHER to other, "/$FILE" to b.bytes)).toByteArray()
        val dropped = catalogJson(mapOf("/$FILE" to b.bytes)).toByteArray() // 別の機械が後から上げて、他の行を落とした
        val result = publisher(fetcher = {
            when {
                // 1回目: アップロード前（他の行がある）／2回目: アップロード後の確認（落ちている）／3回目: やり直し後（戻っている）
                isCatalog(it) -> when (reads.incrementAndGet()) {
                    1 -> FetchResult.Found(catalogJson(mapOf(OTHER to other)).toByteArray())
                    2 -> FetchResult.Found(dropped)
                    else -> FetchResult.Found(full)
                }
                it.substringBefore('?').endsWith(OTHER) -> FetchResult.Found(other)
                else -> FetchResult.NotFound
            }
        }).publish(b)
        assertIs<PublishResult.Published>(result)
        assertEquals(2, calls("PUT", "/workers/scripts/bettermodel-pack").size, "戻すために1回だけやり直す")
        assertEquals(2, calls("POST", "/assets-upload-session").size)
        assertTrue(calls("POST", "/assets-upload-session")[1].body.contains(CloudflarePackPublisher.manifestHash(other)), "やり直しのマニフェストに他の行を載せる")
        assertEquals(3, reads.get())
    }

    @Test
    fun `やり直しても戻らなければ失敗し、状態を保存しない（やり直しは1回まで）`() {
        val other = ByteArray(300) { 3 }
        val b = bundle()
        val reads = AtomicInteger()
        val dropped = catalogJson(mapOf("/$FILE" to b.bytes)).toByteArray()
        val failed = assertIs<PublishResult.Failed>(
            publisher(fetcher = {
                when {
                    isCatalog(it) -> if (reads.incrementAndGet() == 1) FetchResult.Found(catalogJson(mapOf(OTHER to other)).toByteArray()) else FetchResult.Found(dropped)
                    it.substringBefore('?').endsWith(OTHER) -> FetchResult.Found(other)
                    else -> FetchResult.NotFound
                }
            }).publish(b)
        )
        assertTrue(failed.message.contains(OTHER), failed.message)
        assertEquals(2, calls("PUT", "/workers/scripts/bettermodel-pack").size, "やり直しは1回だけ")
        assertFalse(File(dir, ".cache/cloudflare-pack.properties").exists())
    }

    @Test
    fun `BLAKE3 は公式のテストベクタと一致する`() {
        // BLAKE3 公式（github.com/BLAKE3-team/BLAKE3 の test_vectors.json）の、入力長 0 と、入力長 1（バイト 0x00）の hash の先頭 32 バイト
        assertEquals("af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262", Blake3.hash(ByteArray(0)).toHex())
        assertEquals("2d3adedff11b61f14c886e35afa036736dcd87a74d27b5c1510225d0f592e213", Blake3.hash(byteArrayOf(0)).toHex())
    }

    @Test
    fun `マニフェストのハッシュは wrangler の hashFile と同じ値になる`() {
        // wrangler 同梱の blake3-wasm（E:\Projects\cloudflare\file-server\node_modules\blake3-wasm）で
        // blake3.hash(contents.toString("base64") + "zip").toString("hex").slice(0, 32) を実行して得た値
        assertEquals("c2373647be0ce149900d6c8548bf2c6a", CloudflarePackPublisher.manifestHash(ByteArray(0)))
        assertEquals("66bd22b344574905a85edb89ea084590", CloudflarePackPublisher.manifestHash(ByteArray(256) { it.toByte() }))
        assertEquals("20ae711611e2ee8ba302b0f90c2a4823", CloudflarePackPublisher.manifestHash(ByteArray(5000) { it.toByte() }))
    }

    @Test
    fun `マニフェストへ入れるハッシュと、アップロードする部品の名前が一致する`() {
        val b = bundle()
        publisher().publish(b)
        val hash = CloudflarePackPublisher.manifestHash(b.bytes)
        assertTrue(calls[0].body.contains("\"hash\":\"$hash\""))
        assertTrue(calls[1].body.contains("name=\"$hash\""))
    }
}
