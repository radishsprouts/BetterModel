/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.pack

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.Properties
import java.util.UUID

/**
 * Cloudflare の設定（config.yml の `resource-pack-host.cloudflare` の節）。
 *
 * @property accountId アカウント ID
 * @property apiToken API トークン（ログに出さない）
 * @property workerName BetterModel 専用の Worker の名前
 * @property customDomain 付けるカスタムドメイン。空なら workers.dev の URL を使う
 * @property compatibilityDate Worker の compatibility_date
 */
data class CloudflarePackConfig(
    val accountId: String,
    val apiToken: String,
    val workerName: String,
    val customDomain: String,
    val compatibilityDate: String,
) {
    /** 足りない設定の説明。空なら配布できる。 */
    fun missing(): List<String> = buildList {
        if (accountId.isEmpty()) add("account-id（または環境変数 $ENV_ACCOUNT_ID）")
        if (apiToken.isEmpty()) add("api-token（または環境変数 $ENV_API_TOKEN）")
    }

    override fun toString() = "CloudflarePackConfig(accountId=$accountId, workerName=$workerName, customDomain=$customDomain)"

    companion object {
        const val ENV_API_TOKEN = "BETTERMODEL_CLOUDFLARE_API_TOKEN"
        const val ENV_ACCOUNT_ID = "BETTERMODEL_CLOUDFLARE_ACCOUNT_ID"
        const val DEFAULT_WORKER_NAME = "bettermodel-pack"
        const val DEFAULT_COMPATIBILITY_DATE = "2025-09-01"

        /** 節が無い時の既定値。環境変数があれば読む。 */
        val DEFAULT get() = of(null, null, null, null, null)

        /**
         * 設定の生の値を正規化して作る。空のアカウント ID・トークンは環境変数で補う。
         * Worker の名前は Cloudflare の規則（英小文字・数字・ハイフン、63 文字まで）に直す。
         */
        @JvmStatic
        fun of(
            accountId: String?,
            apiToken: String?,
            workerName: String?,
            customDomain: String?,
            compatibilityDate: String?,
            env: (String) -> String? = System::getenv,
        ) = CloudflarePackConfig(
            accountId = accountId?.trim().orEmpty().ifEmpty { env(ENV_ACCOUNT_ID)?.trim().orEmpty() },
            apiToken = apiToken?.trim().orEmpty().ifEmpty { env(ENV_API_TOKEN)?.trim().orEmpty() },
            workerName = workerName?.trim()?.lowercase()?.replace(Regex("[^a-z0-9-]"), "-")?.take(63)?.trim('-')
                ?.takeIf(String::isNotEmpty) ?: DEFAULT_WORKER_NAME,
            customDomain = customDomain?.trim()?.lowercase()?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/').orEmpty(),
            compatibilityDate = compatibilityDate?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_COMPATIBILITY_DATE,
        )
    }
}

/** [CloudflarePackPublisher.publish] の結果。 */
sealed interface PublishResult {
    /**
     * @property url クライアントへ渡す URL
     * @property reused 前回と中身が同じで、API を呼ばずに前回の URL を使ったか
     */
    data class Published(val url: String, val reused: Boolean) : PublishResult

    /** @property message 失敗の理由（トークンは含まない） */
    data class Failed(val message: String) : PublishResult
}

/**
 * リソースパックを、BetterModel 専用の Cloudflare Worker（Static Assets だけの Worker）へアップロードする。
 *
 * 外部のプロセス（node・wrangler 等）は使わず、JDK の [HttpClient] だけで Cloudflare API を呼ぶ。
 * 手順は Workers Static Assets の direct upload のとおり
 * （マニフェストの登録 → ファイルのアップロード → completion token で Worker を PUT）。
 * そのあと workers.dev の有効化（またはカスタムドメインの付与）を行う。Worker が無ければ PUT で作られる。
 *
 * 置くファイルは `/bettermodel-<SHA-1 の先頭12桁>.zip` の1つだけで、デプロイのたびに版ごと置き換わる
 * （古いパックが残らず、URL とハッシュの食い違いも起きない）。
 * 前回成功した SHA-1 と URL は [stateFile] に保存し、中身が同じなら API を呼ばない。
 * [publish] は呼び出したスレッドをブロックするので、メインスレッド・リージョンのスレッドでは呼ばないこと。
 *
 * @property config Cloudflare の設定
 * @property stateFile 前回の状態の保存先
 * @property apiBase API の基点（テストで差し替える）
 * @property retryDelaysMillis 一時的な失敗（通信エラー・429・5xx）の再試行の間隔。要素数が再試行の上限
 * @property verifier 公開した URL からパックが取れるかの確認（URL と期待する SHA-1 を受け取る）。null なら確認しない
 */
class CloudflarePackPublisher(
    private val config: CloudflarePackConfig,
    private val stateFile: File,
    private val apiBase: String = API_BASE,
    private val retryDelaysMillis: List<Long> = listOf(1_000L, 3_000L, 9_000L),
    private val verifier: ((String, ByteArray) -> Boolean)? = null,
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : AutoCloseable {

    private class ApiException(message: String, val retryable: Boolean) : Exception(message)

    /**
     * パックをアップロードして URL を返す。
     *
     * @param bundle 配る ZIP
     * @return 成功なら URL、失敗なら理由
     */
    @Synchronized
    fun publish(bundle: PackBundle): PublishResult {
        if (bundle.bytes.size > MAX_ASSET_BYTES) {
            return PublishResult.Failed(
                "パックの大きさ（${bundle.bytes.size} バイト）が Cloudflare の 1 ファイルの上限 $MAX_ASSET_BYTES バイト（25 MiB）を超えているため、アップロードしません。"
            )
        }
        val fileName = "bettermodel-${bundle.sha1Hex.take(12)}.zip"
        loadState()?.takeIf {
            it.getProperty(KEY_SHA1) == bundle.sha1Hex &&
                it.getProperty(KEY_ACCOUNT) == config.accountId &&
                it.getProperty(KEY_WORKER) == config.workerName &&
                it.getProperty(KEY_DOMAIN) == config.customDomain
        }?.getProperty(KEY_URL)?.takeIf(String::isNotEmpty)?.let { return PublishResult.Published(it, true) }
        return try {
            val url = upload(bundle, fileName)
            if (verifier != null && !verifier.invoke(url, bundle.sha1)) {
                return PublishResult.Failed("アップロードしましたが、$url からパックを取得できませんでした（時間を置いてリロードしてください）。")
            }
            saveState(bundle, url)
            PublishResult.Published(url, false)
        } catch (e: ApiException) {
            PublishResult.Failed(e.message ?: "Cloudflare API の呼び出しに失敗しました")
        } catch (e: IOException) {
            PublishResult.Failed("Cloudflare との通信に失敗しました: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            PublishResult.Failed("アップロードが中断されました")
        }
    }

    override fun close() {
        client.shutdownNow()
    }

    private fun upload(bundle: PackBundle, fileName: String): String {
        val account = "$apiBase/accounts/${config.accountId}"
        val path = "/$fileName"
        val encoded = Base64.getEncoder().encode(bundle.bytes)
        // wrangler と同じ形のハッシュ（base64 の内容 + 拡張子 を SHA-256 にかけた先頭 32 桁）
        val hash = MessageDigest.getInstance("SHA-256").run {
            update(encoded)
            update("zip".toByteArray(Charsets.US_ASCII))
            digest().toHex().take(32)
        }

        val manifest = JsonObject().apply {
            add("manifest", JsonObject().apply {
                add(path, JsonObject().apply {
                    addProperty("hash", hash)
                    addProperty("size", bundle.bytes.size)
                })
            })
        }
        val session = call("アップロードセッションの作成") {
            request("$account/workers/scripts/${config.workerName}/assets-upload-session", config.apiToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(manifest.toString()))
                .build()
        }
        var completion = jwtOf(session) ?: throw ApiException("アップロードセッションの応答に jwt がありません", false)
        val buckets = session.getAsJsonObject("result")?.getAsJsonArray("buckets")
        if (buckets != null && buckets.size() > 0) {
            val boundary = boundary()
            val body = multipart(boundary) {
                part("Content-Disposition: form-data; name=\"$hash\"; filename=\"$hash\"", "application/zip", encoded)
            }
            val uploaded = call("ファイルのアップロード") {
                request("$account/workers/assets/upload?base64=true", completion)
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build()
            }
            completion = jwtOf(uploaded) ?: throw ApiException("アップロードの応答に completion token がありません", false)
        }

        val metadata = JsonObject().apply {
            addProperty("main_module", WORKER_FILE)
            addProperty("compatibility_date", config.compatibilityDate)
            add("assets", JsonObject().apply { addProperty("jwt", completion) })
        }
        val boundary = boundary()
        val script = multipart(boundary) {
            part("Content-Disposition: form-data; name=\"metadata\"", "application/json", metadata.toString().toByteArray(Charsets.UTF_8))
            part(
                "Content-Disposition: form-data; name=\"$WORKER_FILE\"; filename=\"$WORKER_FILE\"",
                "application/javascript+module",
                WORKER_SCRIPT.toByteArray(Charsets.UTF_8),
            )
        }
        call("Worker の作成・更新") {
            request("$account/workers/scripts/${config.workerName}", config.apiToken)
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(script))
                .build()
        }
        return expose(account, fileName)
    }

    /** workers.dev の有効化、またはカスタムドメインの付与をして、ファイルの URL を返す。 */
    private fun expose(account: String, fileName: String): String {
        if (config.customDomain.isNotEmpty()) {
            val body = JsonObject().apply {
                addProperty("hostname", config.customDomain)
                addProperty("service", config.workerName)
            }
            call("カスタムドメインの付与") {
                request("$account/workers/domains", config.apiToken)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build()
            }
            return "https://${config.customDomain}/$fileName"
        }
        val subdomain = call("workers.dev のサブドメインの取得") {
            request("$account/workers/subdomain", config.apiToken).GET().build()
        }.getAsJsonObject("result")?.get("subdomain")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (subdomain.isEmpty()) {
            throw ApiException(
                "このアカウントには workers.dev のサブドメインがありません。Cloudflare のダッシュボードの Workers & Pages で登録するか、cloudflare.custom-domain を設定してください。",
                false,
            )
        }
        val body = JsonObject().apply {
            addProperty("enabled", true)
            addProperty("previews_enabled", false)
        }
        call("workers.dev の有効化") {
            request("$account/workers/scripts/${config.workerName}/subdomain", config.apiToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
        }
        return "https://${config.workerName}.$subdomain.workers.dev/$fileName"
    }

    private fun request(url: String, token: String) = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(120))
        .header("Authorization", "Bearer $token")

    /** API を呼ぶ。通信エラー・429・5xx は間隔を空けて再試行し、それ以外の失敗はすぐ [ApiException] にする。 */
    private fun call(label: String, build: () -> HttpRequest): JsonObject {
        var attempt = 0
        while (true) {
            try {
                val response = client.send(build(), HttpResponse.BodyHandlers.ofString())
                val status = response.statusCode()
                val json = parse(response.body())
                if (status in 200..299 && json?.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean != false) {
                    return json ?: JsonObject()
                }
                throw ApiException(
                    "$label に失敗しました（HTTP $status）: ${errorOf(json, response.body())}",
                    status == 429 || status >= 500,
                )
            } catch (e: IOException) {
                if (attempt >= retryDelaysMillis.size) throw ApiException("$label に失敗しました: 通信エラー（${e.message}）", false)
            } catch (e: ApiException) {
                if (!e.retryable || attempt >= retryDelaysMillis.size) throw e
            }
            Thread.sleep(retryDelaysMillis[attempt++])
        }
    }

    private fun parse(body: String?): JsonObject? = runCatching {
        JsonParser.parseString(body ?: "").takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    private fun errorOf(json: JsonObject?, raw: String?): String {
        val text = runCatching {
            json?.getAsJsonArray("errors")?.filter { it.isJsonObject }?.joinToString("; ") {
                val error = it.asJsonObject
                "${error.get("code")?.asString ?: "?"}: ${error.get("message")?.asString ?: ""}"
            }
        }.getOrNull()
        return text?.takeIf(String::isNotEmpty) ?: raw.orEmpty().take(300)
    }

    private fun jwtOf(json: JsonObject): String? =
        (json.getAsJsonObject("result")?.get("jwt") ?: json.get("jwt"))?.takeIf { it.isJsonPrimitive }?.asString

    private fun boundary() = "----bettermodel${UUID.randomUUID().toString().replace("-", "")}"

    private class MultipartBuilder(private val boundary: String) {
        val out = ByteArrayOutputStream()
        fun part(disposition: String, contentType: String, content: ByteArray) {
            out.write("--$boundary\r\n$disposition\r\nContent-Type: $contentType\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write(content)
            out.write("\r\n".toByteArray(Charsets.US_ASCII))
        }
    }

    private fun multipart(boundary: String, block: MultipartBuilder.() -> Unit): ByteArray {
        val builder = MultipartBuilder(boundary).apply(block)
        builder.out.write("--$boundary--\r\n".toByteArray(Charsets.US_ASCII))
        return builder.out.toByteArray()
    }

    private fun loadState(): Properties? {
        if (!stateFile.isFile) return null
        return runCatching {
            Properties().also { props -> stateFile.inputStream().use { props.load(it) } }
        }.getOrNull()
    }

    private fun saveState(bundle: PackBundle, url: String) {
        val props = Properties().apply {
            setProperty(KEY_SHA1, bundle.sha1Hex)
            setProperty(KEY_URL, url)
            setProperty(KEY_ACCOUNT, config.accountId)
            setProperty(KEY_WORKER, config.workerName)
            setProperty(KEY_DOMAIN, config.customDomain)
        }
        val parent = stateFile.absoluteFile.parentFile
        parent.mkdirs()
        val temp = Files.createTempFile(parent.toPath(), "cloudflare-pack", ".tmp")
        try {
            Files.newOutputStream(temp).use { props.store(it, null) }
            try {
                Files.move(temp, stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val API_BASE = "https://api.cloudflare.com/client/v4"

        /** Cloudflare の Static Assets の 1 ファイルの上限（25 MiB）。 */
        const val MAX_ASSET_BYTES = 26_214_400

        private const val WORKER_FILE = "worker.js"

        /** assets に無いパスへの要求にだけ使われる、何も返さない最小のスクリプト。 */
        private const val WORKER_SCRIPT = "export default { fetch() { return new Response(\"Not Found\", { status: 404 }); } };\n"

        private const val KEY_SHA1 = "sha1"
        private const val KEY_URL = "url"
        private const val KEY_ACCOUNT = "account-id"
        private const val KEY_WORKER = "worker-name"
        private const val KEY_DOMAIN = "custom-domain"

        /**
         * 公開した URL を取って SHA-1 を照合する既定の確認。反映の遅れを見込んで間隔を空けて繰り返す。
         *
         * @param client 取得に使うクライアント
         * @param attempts 試す回数
         * @param delayMillis 試行の間隔
         */
        @JvmStatic
        fun downloadVerifier(
            client: HttpClient,
            attempts: Int = 12,
            delayMillis: Long = 5_000L,
        ): (String, ByteArray) -> Boolean = { url, sha1 ->
            var ok = false
            for (i in 0 until attempts) {
                ok = runCatching {
                    val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                    response.statusCode() == 200 &&
                        MessageDigest.getInstance("SHA-1").digest(response.body()).contentEquals(sha1)
                }.getOrDefault(false)
                if (ok) break
                if (i < attempts - 1) Thread.sleep(delayMillis)
            }
            ok
        }
    }
}
