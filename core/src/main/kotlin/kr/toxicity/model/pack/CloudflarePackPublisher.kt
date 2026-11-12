/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.pack

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.apache.commons.codec.digest.Blake3
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
 * @property customDomain 付けるカスタムドメイン（必須。workers.dev は使わない）。空と [FORBIDDEN_DOMAIN] は配布できない
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
        domainProblem()?.let(::add)
    }

    /** custom-domain の問題の説明。問題が無ければ null。 */
    fun domainProblem(): String? = when {
        customDomain.isEmpty() ->
            "custom-domain（BetterModel 専用のカスタムドメイン。workers.dev は使いません。例: $DEFAULT_CUSTOM_DOMAIN）"
        customDomain == FORBIDDEN_DOMAIN ->
            "custom-domain（$FORBIDDEN_DOMAIN は別のリソースパックを配る Worker のホスト名のため使えません。$DEFAULT_CUSTOM_DOMAIN のような別のホスト名にしてください）"
        else -> null
    }

    override fun toString() = "CloudflarePackConfig(accountId=$accountId, workerName=$workerName, customDomain=$customDomain)"

    companion object {
        const val ENV_API_TOKEN = "BETTERMODEL_CLOUDFLARE_API_TOKEN"
        const val ENV_ACCOUNT_ID = "BETTERMODEL_CLOUDFLARE_ACCOUNT_ID"
        const val DEFAULT_WORKER_NAME = "bettermodel-pack"
        const val DEFAULT_COMPATIBILITY_DATE = "2025-09-01"

        /** custom-domain の既定（キーが無い時）。config.yml の既定値と同じ。 */
        const val DEFAULT_CUSTOM_DOMAIN = "bettermodel.minecraft.kaiware.games"

        /** 他のリソースパックを配っている Worker（file-server）のホスト名。奪わないよう、使わせない。 */
        const val FORBIDDEN_DOMAIN = "resource.minecraft.kaiware.games"

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
            // null（キー無し）は既定のドメイン。空文字は「未設定」として配布を止める
            customDomain = (customDomain ?: DEFAULT_CUSTOM_DOMAIN).trim().lowercase()
                .removePrefix("https://").removePrefix("http://").trimEnd('/').trimEnd('.'),
            compatibilityDate = compatibilityDate?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_COMPATIBILITY_DATE,
        )
    }
}

/** 公開した URL からパックを取れるかの1回の確認の結果。 */
enum class VerifyResult {
    /** 取れて、SHA-1 も一致した */
    OK,

    /** 接続・TLS の失敗、404、5xx など。証明書の発行や DNS の反映を待てば直りうるので、再試行する */
    RETRY,

    /** 中身が食い違う等、待っても直らない失敗。再試行しない */
    FATAL,
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
 * そのあとカスタムドメインを付け、workers.dev と preview の URL を無効にする（workers.dev は使わない）。
 * Worker が無ければ PUT で作られる。カスタムドメインは必須で、他の Worker のホスト名や既存の DNS レコードは上書きしない。
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
 * @property verifier 公開した URL からパックが取れるかの1回の確認（URL と期待する SHA-1 を受け取る）。null なら確認しない。
 *   [VerifyResult.RETRY] の間は [verifyIntervalMillis] おきに [verifyTimeoutMillis] まで繰り返す
 *   （新しいカスタムドメインは、証明書の発行が済むまで TLS の握手に失敗する）
 * @property verifyIntervalMillis 取得確認の再試行の間隔
 * @property verifyTimeoutMillis 取得確認を待つ最長の時間
 * @property onWaiting 取得確認の最初の失敗で1回だけ呼ぶ（待っている旨をログに出すため）。引数は URL
 */
class CloudflarePackPublisher(
    private val config: CloudflarePackConfig,
    private val stateFile: File,
    private val apiBase: String = API_BASE,
    private val retryDelaysMillis: List<Long> = listOf(1_000L, 3_000L, 9_000L),
    private val verifier: ((String, ByteArray) -> VerifyResult)? = null,
    private val verifyIntervalMillis: Long = VERIFY_INTERVAL_MILLIS,
    private val verifyTimeoutMillis: Long = VERIFY_TIMEOUT_MILLIS,
    private val onWaiting: (String) -> Unit = {},
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
        config.domainProblem()?.let {
            return PublishResult.Failed("cloudflare.custom-domain を設定してください。何もアップロードしません。詳細: $it")
        }
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
            if (verifier != null && !awaitReachable(verifier, url, bundle.sha1)) {
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

    /**
     * 取れるようになるまで、間隔を空けて確認する。[VerifyResult.RETRY] だけ、[verifyTimeoutMillis] まで繰り返す。
     * 割り込まれたら [InterruptedException] をそのまま投げる（[publish] が中断として扱う）。
     */
    private fun awaitReachable(check: (String, ByteArray) -> VerifyResult, url: String, sha1: ByteArray): Boolean {
        val deadline = System.nanoTime() + verifyTimeoutMillis * 1_000_000L
        var notified = false
        while (true) {
            when (check(url, sha1)) {
                VerifyResult.OK -> return true
                VerifyResult.FATAL -> return false
                VerifyResult.RETRY -> Unit
            }
            if (System.nanoTime() + verifyIntervalMillis * 1_000_000L > deadline) return false
            if (!notified) {
                notified = true
                onWaiting(url)
            }
            Thread.sleep(verifyIntervalMillis)
        }
    }

    override fun close() {
        client.shutdownNow()
    }

    private fun upload(bundle: PackBundle, fileName: String): String {
        val account = "$apiBase/accounts/${config.accountId}"
        val path = "/$fileName"
        val encoded = Base64.getEncoder().encode(bundle.bytes)
        val hash = manifestHashOfEncoded(encoded)

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

    /** カスタムドメインを付け、workers.dev を無効にして、ファイルの URL を返す（workers.dev の URL は返さない）。 */
    private fun expose(account: String, fileName: String): String {
        // override_existing_origin / override_existing_dns_record は渡さない（既存のものを上書きさせない）
        val body = JsonObject().apply {
            addProperty("hostname", config.customDomain)
            addProperty("service", config.workerName)
        }
        try {
            call("カスタムドメインの付与") {
                request("$account/workers/domains", config.apiToken)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build()
            }
        } catch (e: ApiException) {
            // 付けられなかった時に、新しい Worker の URL が公開されたままにならないよう、無効化を試みる
            val closed = runCatching { disableWorkersDev(account) }.isSuccess
            throw ApiException(
                "${e.message}（${config.customDomain} が別の Worker に付いているか、同じ名前の DNS レコードが既にある場合、BetterModel は上書きしません。" +
                    "別のホスト名を cloudflare.custom-domain に設定してください）" +
                    if (closed) "" else "（Worker の公開 URL の無効化にも失敗しました。Cloudflare のダッシュボードで確認してください）",
                false,
            )
        }
        disableWorkersDev(account)
        return "https://${config.customDomain}/$fileName"
    }

    /** Worker の公開 URL と preview の URL を無効にする（Cloudflare は新しい Worker で既定で有効にすることがある）。 */
    private fun disableWorkersDev(account: String) {
        val body = JsonObject().apply {
            addProperty("enabled", false)
            addProperty("previews_enabled", false)
        }
        call("Worker の公開 URL（workers.dev）の無効化") {
            request("$account/workers/scripts/${config.workerName}/subdomain", config.apiToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
        }
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

        /**
         * マニフェストに入れるファイルのハッシュ。wrangler の `hashFile` と同じ計算
         * （`BLAKE3(ファイルの base64 + 拡張子（先頭の . を除く）)` の 16 進の先頭 32 桁。拡張子は常に zip）。
         *
         * @param content ファイルの中身
         * @return 32 桁の 16 進文字列
         */
        @JvmStatic
        fun manifestHash(content: ByteArray): String = manifestHashOfEncoded(Base64.getEncoder().encode(content))

        private fun manifestHashOfEncoded(encoded: ByteArray): String =
            Blake3.hash(encoded + "zip".toByteArray(Charsets.US_ASCII)).toHex().take(32)

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

        /** 取得確認の再試行の間隔（証明書の発行・DNS の反映待ち）。 */
        const val VERIFY_INTERVAL_MILLIS = 15_000L

        /** 取得確認を待つ最長の時間（3 分）。 */
        const val VERIFY_TIMEOUT_MILLIS = 180_000L

        /**
         * 公開した URL を1回取って SHA-1 を照合する既定の確認。繰り返しは [CloudflarePackPublisher] が行う。
         * 接続・TLS の失敗、404、5xx は [VerifyResult.RETRY]、中身の食い違いやその他の応答は [VerifyResult.FATAL]。
         * 割り込みは握りつぶさず、そのまま投げる。
         *
         * @param client 取得に使うクライアント
         */
        @JvmStatic
        fun downloadVerifier(client: HttpClient): (String, ByteArray) -> VerifyResult = { url, sha1 ->
            try {
                val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                val status = response.statusCode()
                when {
                    status == 200 ->
                        if (MessageDigest.getInstance("SHA-1").digest(response.body()).contentEquals(sha1)) VerifyResult.OK
                        else VerifyResult.FATAL
                    status == 404 || status >= 500 -> VerifyResult.RETRY
                    else -> VerifyResult.FATAL
                }
            } catch (e: IOException) {
                VerifyResult.RETRY
            }
        }
    }
}
