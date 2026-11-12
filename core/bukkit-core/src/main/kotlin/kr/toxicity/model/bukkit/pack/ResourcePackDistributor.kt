/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.pack

import kr.toxicity.model.api.BetterModelConfig.PackType
import kr.toxicity.model.api.BetterModelPlatform.ReloadResult
import kr.toxicity.model.api.pack.PackResult
import kr.toxicity.model.bukkit.BetterModelConfigImpl
import kr.toxicity.model.bukkit.util.registerListener
import kr.toxicity.model.bukkit.util.runOnOwnScheduler
import kr.toxicity.model.pack.*
import kr.toxicity.model.util.CONFIG
import kr.toxicity.model.util.PLATFORM
import kr.toxicity.model.util.info
import kr.toxicity.model.util.toComponent
import kr.toxicity.model.util.toZipBytes
import kr.toxicity.model.util.warn
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * BetterModel が作ったリソースパックを、Java 版のプレイヤーへ配る。
 *
 * - self-host: [PackHttpServer] で ZIP を配り、その URL と SHA-1 をクライアントへ渡す
 * - url: 別の場所に置かれた ZIP の URL を渡す。SHA-1 はその URL の実物を非同期で取って計算し、取れるまではハッシュ無しで送る
 *
 * 参加時に送り、リロードでパックの中身が変わった時だけ接続中のプレイヤーへ送り直す。
 * 統合版（Floodgate）のプレイヤーには送らない。Folia では、プレイヤーの操作を必ずそのプレイヤーのスケジューラで行う。
 */
object ResourcePackDistributor {

    private val tracker = PackOfferTracker()

    private var server: PackHttpServer? = null
    private var serverConfig: ResourcePackHostConfig? = null
    private var hasher: UrlHasher? = null
    private var generation = 0L
    private var warnedLoopback = false
    private var warnedHash = false

    @Volatile
    private var prompt: String? = null

    @Volatile
    private var required = false

    /** 参加時の送信とリロード後の更新を登録する。onEnable で1回呼ぶ。 */
    fun start() {
        registerListener(object : Listener {
            @EventHandler
            fun PlayerJoinEvent.join() {
                val offer = tracker.current() ?: return
                send(player, offer)
            }
        })
        PLATFORM.addReloadEndHandler { result ->
            if (result is ReloadResult.Success) onReloadEnd(result.packResult())
        }
    }

    /** 待ち受けと取得を止める。onDisable で呼ぶ（ポートを残さない）。 */
    @Synchronized
    fun stop() {
        generation++
        stopServer()
        closeHasher()
        tracker.update(null)
    }

    @Synchronized
    private fun onReloadEnd(pack: PackResult) {
        val config = (CONFIG as? BetterModelConfigImpl)?.resourcePackHost ?: return
        // § のレガシーカラーコードはそのまま渡せる（KadoRecipe の ResourcePackPublisher と同じ）
        prompt = config.prompt.takeIf(String::isNotEmpty)
        required = config.required
        generation++
        if (!config.enabled) return disable(null)
        if (CONFIG.mergeWithExternalResources() && Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            return disable("リソースパックは Nexo が配るため、BetterModel からは配りません。")
        }
        if (CONFIG.packType() == PackType.NONE) {
            return disable("pack-type が none のため、リソースパックを配りません。")
        }
        when (config.mode) {
            ResourcePackHostConfig.Mode.SELF_HOST -> selfHost(config, pack)
            ResourcePackHostConfig.Mode.URL -> urlMode(config)
        }
    }

    private fun selfHost(config: ResourcePackHostConfig, pack: PackResult) {
        closeHasher()
        val bundle = runCatching { bundleOf(pack) }.getOrElse {
            return disable("リソースパックの ZIP を読めないため、配りません: ${it.message}", error = true)
        }
        val host = ensureServer(config) ?: return disable(null)
        host.update(bundle)
        val resolved = config.resolvePublicUrl(Bukkit.getIp())
        if (resolved.guessed && !warnedLoopback) {
            warnedLoopback = true
            warn(
                "resource-pack-host.public-url が空のため ${resolved.url} をプレイヤーへ渡します。外のプレイヤーにはこの URL は届きません。".toComponent(NamedTextColor.YELLOW)
            )
        }
        publish(PackOfferTracker.Offer(resolved.url, bundle.sha1))
    }

    private fun urlMode(config: ResourcePackHostConfig) {
        if (config.url.isEmpty()) return disable("resource-pack-host.url が空のため、リソースパックを配りません。", error = true)
        stopServer()
        val url = config.url
        // 同じ URL ならこれまでに取れたハッシュを引き継ぐ（取り直している間も同じパックとして扱う）
        publish(PackOfferTracker.Offer(url, tracker.current()?.takeIf { it.url == url }?.sha1))
        val mine = generation
        val client = hasher ?: UrlHasher().also { hasher = it }
        runCatching { client.hash(url) }.getOrElse {
            return warnHash(url, it)
        }.whenComplete { hash, error ->
            synchronized(this) {
                if (mine != generation) return@whenComplete
                if (error != null) warnHash(url, error) else publish(PackOfferTracker.Offer(url, hash))
            }
        }
    }

    private fun warnHash(url: String, error: Throwable) {
        if (warnedHash) return
        warnedHash = true
        warn("リソースパックの URL から SHA-1 を計算できませんでした。ハッシュ無しで配ります（$url）: ${error.cause?.message ?: error.message}".toComponent(NamedTextColor.YELLOW))
    }

    private fun bundleOf(pack: PackResult): PackBundle = PackBundle(
        when (CONFIG.packType()) {
            PackType.ZIP -> pack.directory()?.takeIf { it.isFile }?.readBytes()
                ?: throw IllegalStateException("ZIP のファイルがありません")
            else -> pack.toZipBytes()
        }
    )

    /** 待ち受けを用意する。同じ場所で動いていれば作り直さず、使えなければエラーを1行出して null を返す。 */
    private fun ensureServer(config: ResourcePackHostConfig): PackHttpServer? {
        server?.takeIf { serverConfig?.sameListener(config) == true }?.let { return it }
        stopServer()
        val created = PackHttpServer(config.bindAddress, config.port, config.maxConnections)
        created.start().onFailure {
            warn("リソースパックの配布用ポート ${config.bindAddress}:${config.port} を使えません。配布だけを止めます: ${it.message}".toComponent(NamedTextColor.RED))
            return null
        }
        server = created
        serverConfig = config
        return created
    }

    private fun stopServer() {
        server?.stop()
        server = null
        serverConfig = null
    }

    private fun closeHasher() {
        hasher?.close()
        hasher = null
    }

    private fun disable(message: String?, error: Boolean = false) {
        stopServer()
        closeHasher()
        if (message != null) {
            if (error) warn(message.toComponent(NamedTextColor.RED)) else info(message.toComponent())
        }
        publish(null)
    }

    /** 配る内容を更新し、変わった時だけ接続中の Java 版プレイヤーへ送り直す。 */
    private fun publish(offer: PackOfferTracker.Offer?) {
        when (tracker.update(offer)) {
            PackOfferTracker.Action.NONE -> return
            PackOfferTracker.Action.REMOVE -> {
                info("リソースパックの配布を止めたため、接続中のプレイヤーから外します。".toComponent())
                forEachJavaPlayer { it.runOnOwnScheduler { it.removeResourcePack(ResourcePackHostConfig.PACK_ID) } }
            }
            PackOfferTracker.Action.SEND -> {
                offer ?: return
                info("リソースパックを配ります: ${offer.url}".toComponent(NamedTextColor.GREEN))
                forEachJavaPlayer { player ->
                    player.runOnOwnScheduler {
                        player.removeResourcePack(ResourcePackHostConfig.PACK_ID)
                        send(player, offer)
                    }
                }
            }
        }
    }

    private inline fun forEachJavaPlayer(action: (Player) -> Unit) {
        Bukkit.getOnlinePlayers().toList().forEach { if (!it.uniqueId.isBedrockPlayer()) action(it) }
    }

    /** プレイヤーへパックを送る。統合版のプレイヤーは飛ばす。そのプレイヤーのスレッドで呼ぶこと。 */
    private fun send(player: Player, offer: PackOfferTracker.Offer) {
        if (!player.isOnline || player.uniqueId.isBedrockPlayer()) return
        player.addResourcePack(ResourcePackHostConfig.PACK_ID, offer.url, offer.sha1, prompt, required)
    }
}
