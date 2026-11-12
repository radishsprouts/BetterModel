/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.pack

import java.util.UUID

/**
 * リソースパックの配布の設定（config.yml の `resource-pack-host` の節）。Bukkit に依存しない値の入れ物。
 *
 * @property enabled 配布するか
 * @property mode 配り方
 * @property bindAddress self-host で待ち受けるアドレス
 * @property port self-host で待ち受けるポート
 * @property publicUrl self-host でクライアントへ渡す URL。空なら [resolvePublicUrl] で作る
 * @property url url モードで ZIP が置かれている URL
 * @property prompt クライアントに出す適用の確認文
 * @property required 適用を必須にするか
 * @property maxConnections self-host で同時に処理するリクエストの上限
 */
data class ResourcePackHostConfig(
    val enabled: Boolean,
    val mode: Mode,
    val bindAddress: String,
    val port: Int,
    val publicUrl: String,
    val url: String,
    val prompt: String,
    val required: Boolean,
    val maxConnections: Int,
    val cloudflare: CloudflarePackConfig = CloudflarePackConfig.DEFAULT,
) {
    /** 配り方。 */
    enum class Mode {
        /** BetterModel 専用の Cloudflare Worker へアップロードして配る（既定）。 */
        CLOUDFLARE,

        /** BetterModel が HTTP サーバーを立てて配る。 */
        SELF_HOST,

        /** 別の場所に置いた ZIP の URL を渡す。 */
        URL,
    }

    /** self-host の待ち受けを変える必要があるか（待ち受けの場所が変わった時だけサーバーを作り直すため）。 */
    fun sameListener(other: ResourcePackHostConfig) =
        bindAddress == other.bindAddress && port == other.port && maxConnections == other.maxConnections

    /**
     * self-host でクライアントへ渡す URL を返す。[publicUrl] があればそれを、無ければ
     * `server-ip`（空なら 127.0.0.1）とポートから作る。
     *
     * @param serverIp server.properties の server-ip（空なら空文字）
     * @return URL と、その URL が外のプレイヤーに届かない可能性があるか
     */
    fun resolvePublicUrl(serverIp: String): ResolvedUrl {
        if (publicUrl.isNotBlank()) return ResolvedUrl(publicUrl.trim(), false)
        val host = serverIp.trim().ifEmpty { LOOPBACK }
        val formatted = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return ResolvedUrl("http://$formatted:$port${PackHttpServer.PACK_PATH}", true)
    }

    /**
     * @property url クライアントへ渡す URL
     * @property guessed `public-url` を書かずに作った URL か（外のプレイヤーに届かない恐れがある）
     */
    data class ResolvedUrl(val url: String, val guessed: Boolean)

    companion object {
        const val DEFAULT_PORT = 8163
        const val DEFAULT_MAX_CONNECTIONS = 16
        const val DEFAULT_PROMPT = "このサーバーのモデルを表示するためのリソースパックです。適用してください。"
        private const val LOOPBACK = "127.0.0.1"

        /** 節が無い時の既定値。 */
        val DEFAULT = of(true, null, null, DEFAULT_PORT, null, null, null, false, DEFAULT_MAX_CONNECTIONS)

        /** 配るパックの固定の UUID（名前から作る。クライアントはこの UUID で差し替える）。 */
        val PACK_ID: UUID = UUID.nameUUIDFromBytes("bettermodel-resourcepack".toByteArray(Charsets.UTF_8))

        /**
         * 設定の生の値を正規化して作る。不正な値は既定値に戻し、[warn] へ理由を渡す。
         */
        @JvmStatic
        fun of(
            enabled: Boolean,
            mode: String?,
            bindAddress: String?,
            port: Int,
            publicUrl: String?,
            url: String?,
            prompt: String?,
            required: Boolean,
            maxConnections: Int,
            warn: (String) -> Unit = {},
            cloudflare: CloudflarePackConfig = CloudflarePackConfig.DEFAULT,
        ): ResourcePackHostConfig {
            val parsedMode = when (val key = mode?.trim()?.lowercase()?.replace('_', '-')) {
                null, "", "cloudflare" -> Mode.CLOUDFLARE
                "self-host" -> Mode.SELF_HOST
                "url" -> Mode.URL
                else -> {
                    warn("resource-pack-host.mode が不正です（$key）。cloudflare で動かします。")
                    Mode.CLOUDFLARE
                }
            }
            val parsedPort = if (port in 1..65535) port else {
                warn("resource-pack-host.port が範囲外です（$port）。$DEFAULT_PORT を使います。")
                DEFAULT_PORT
            }
            return ResourcePackHostConfig(
                enabled = enabled,
                mode = parsedMode,
                bindAddress = bindAddress?.trim()?.takeIf(String::isNotEmpty) ?: "0.0.0.0",
                port = parsedPort,
                publicUrl = publicUrl?.trim() ?: "",
                url = url?.trim() ?: "",
                prompt = prompt ?: DEFAULT_PROMPT,
                required = required,
                maxConnections = maxConnections.coerceAtLeast(1),
                cloudflare = cloudflare,
            )
        }
    }
}

/**
 * Floodgate（統合版）のプレイヤーか。Floodgate が発行する UUID は上位 64bit がすべて 0 という規約で判定する
 * （Floodgate への依存を増やさないため。KadoRecipe の判定と同じ）。
 */
fun UUID.isBedrockPlayer(): Boolean = mostSignificantBits == 0L
