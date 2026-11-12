/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.pack

import kr.toxicity.model.pack.PackOfferTracker.Action
import kr.toxicity.model.pack.PackOfferTracker.Offer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 配る内容が変わった時だけ送り直すこと、設定の正規化、統合版の判定を確かめる。 */
class PackOfferTrackerTest {

    private val url = "http://example.com/a.zip"
    private fun hash(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `最初の内容は送り、同じ内容は送らない`() {
        val tracker = PackOfferTracker()
        assertNull(tracker.current())
        assertEquals(Action.SEND, tracker.update(Offer(url, hash(1))))
        assertEquals(Action.NONE, tracker.update(Offer(url, hash(1))))
    }

    @Test
    fun `ハッシュが変わったら送り直す`() {
        val tracker = PackOfferTracker()
        tracker.update(Offer(url, hash(1)))
        assertEquals(Action.SEND, tracker.update(Offer(url, hash(2))))
    }

    @Test
    fun `URL が変わったら送り直す`() {
        val tracker = PackOfferTracker()
        tracker.update(Offer(url, hash(1)))
        assertEquals(Action.SEND, tracker.update(Offer("http://example.com/b.zip", hash(1))))
    }

    @Test
    fun `ハッシュ無しから付いただけなら送り直さない`() {
        val tracker = PackOfferTracker()
        assertEquals(Action.SEND, tracker.update(Offer(url, null)))
        assertEquals(Action.NONE, tracker.update(Offer(url, hash(1))))
        assertEquals(hash(1).toList(), tracker.current()?.sha1?.toList())
    }

    @Test
    fun `配布をやめたら外し、やめている間は何もしない`() {
        val tracker = PackOfferTracker()
        tracker.update(Offer(url, hash(1)))
        assertEquals(Action.REMOVE, tracker.update(null))
        assertEquals(Action.NONE, tracker.update(null))
        assertNull(tracker.current())
        assertEquals(Action.SEND, tracker.update(Offer(url, hash(1))))
    }

    @Test
    fun `不正な設定は既定値に戻して警告する`() {
        val warnings = mutableListOf<String>()
        val config = ResourcePackHostConfig.of(true, "ftp", null, 70000, null, null, null, false, 0, warnings::add)
        assertEquals(ResourcePackHostConfig.Mode.SELF_HOST, config.mode)
        assertEquals(ResourcePackHostConfig.DEFAULT_PORT, config.port)
        assertEquals("0.0.0.0", config.bindAddress)
        assertEquals(1, config.maxConnections)
        assertEquals(2, warnings.size)
        assertEquals(ResourcePackHostConfig.Mode.URL, ResourcePackHostConfig.of(true, "URL", null, 8163, null, null, null, false, 16).mode)
    }

    @Test
    fun `public-url が空なら server-ip とポートから作り、書いてあればそのまま使う`() {
        val config = ResourcePackHostConfig.DEFAULT
        val local = config.resolvePublicUrl("")
        assertEquals("http://127.0.0.1:8163/bettermodel.zip", local.url)
        assertTrue(local.guessed)
        assertEquals("http://192.168.0.5:8163/bettermodel.zip", config.resolvePublicUrl("192.168.0.5").url)
        assertEquals("http://[::1]:8163/bettermodel.zip", config.resolvePublicUrl("::1").url)
        val explicit = config.copy(publicUrl = " https://pack.example.com/x.zip ").resolvePublicUrl("1.2.3.4")
        assertEquals("https://pack.example.com/x.zip", explicit.url)
        assertFalse(explicit.guessed)
    }

    @Test
    fun `統合版のプレイヤーは UUID の上位ビットが 0 で判定する`() {
        assertTrue(UUID(0L, 1234L).isBedrockPlayer())
        assertFalse(UUID.randomUUID().isBedrockPlayer())
        assertFalse(UUID(1L, 0L).isBedrockPlayer())
    }

    @Test
    fun `パックの UUID は名前から作った固定の値`() {
        assertEquals(
            UUID.nameUUIDFromBytes("bettermodel-resourcepack".toByteArray()),
            ResourcePackHostConfig.PACK_ID
        )
    }
}
