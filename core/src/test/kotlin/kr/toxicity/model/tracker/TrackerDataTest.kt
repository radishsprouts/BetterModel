/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.tracker

import com.google.gson.JsonParser
import kr.toxicity.model.api.data.renderer.ModelRenderer
import kr.toxicity.model.api.profile.ModelProfileInfo
import kr.toxicity.model.api.tracker.TrackerData
import kr.toxicity.model.api.tracker.TrackerModifier
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * トラッカーの保存データ（付け直しに使う）の読み書きと、皮膚の扱いを確かめる。
 */
class TrackerDataTest {

    private val skinOwner = ModelProfileInfo(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch")

    private fun data(profile: ModelProfileInfo?) = TrackerData(
        "steve",
        null,
        null,
        TrackerModifier.DEFAULT,
        null,
        null,
        null,
        profile
    )

    @Test
    fun `PLAYER 型のモデルもリロードを越えて保存する`() {
        assertTrue(ModelRenderer.Type.PLAYER.isCanBeSaved)
        assertTrue(ModelRenderer.Type.GENERAL.isCanBeSaved)
    }

    @Test
    fun `皮膚は書き出して読み直しても同じになる`() {
        val restored = TrackerData.deserialize(data(skinOwner).serialize())
        assertEquals(skinOwner, restored.profile())
        assertEquals(skinOwner, restored.restorableProfile())
        assertEquals("steve", restored.id())
    }

    @Test
    fun `名前の無い皮膚も書き出して読み直せる`() {
        val noName = ModelProfileInfo(skinOwner.id(), null)
        val restored = TrackerData.deserialize(data(noName).serialize())
        assertEquals(noName, restored.restorableProfile())
    }

    @Test
    fun `皮膚の項目が無い古い形式のデータでも読め、既定の皮膚になる`() {
        val old = JsonParser.parseString(
            """{"id":"steve","modifier":{"sight-trace":true,"damage-animation":true,"damage-tint":true,"shadow":true},"mark-for-spawn":["069a79f4-44e9-4726-a5be-fca90e38aaf5"]}"""
        )
        val restored = TrackerData.deserialize(old)
        assertEquals("steve", restored.id())
        assertNull(restored.profile())
        assertNull(restored.restorableProfile())
    }

    @Test
    fun `モデル名だけの最古の形式でも読め、既定の皮膚になる`() {
        val restored = TrackerData.deserialize(JsonParser.parseString("\"steve\""))
        assertEquals("steve", restored.id())
        assertNull(restored.restorableProfile())
    }

    @Test
    fun `皮膚を持たないデータは、書き出しても皮膚の項目を作らず、読み直しても皮膚は無い`() {
        val json = data(null).serialize().asJsonObject
        assertTrue(!json.has("profile") || json.get("profile").isJsonNull)
        assertNull(TrackerData.deserialize(json).restorableProfile())
    }

    @Test
    fun `皮膚が不明のデータは既定の皮膚へ落とす`() {
        val restored = TrackerData.deserialize(data(ModelProfileInfo.UNKNOWN).serialize())
        assertNotNull(restored.profile())
        assertNull(restored.restorableProfile())
    }

    @Test
    fun `皮膚の項目が無い7引数の作り方は、皮膚なしになる`() {
        val legacy = TrackerData("steve", null, null, TrackerModifier.DEFAULT, null, null, null)
        assertNull(legacy.profile())
    }
}
