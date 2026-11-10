/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bedrock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 識別子の規則（設計書 08-1 の 2.2）の値を確かめる。
 * 期待値は実装とは別に、node の crypto で SHA-256 の先頭 8 バイトを求めたもの。
 */
class BedrockIdsTest {

    @Test
    fun `h16 は SHA-256 の先頭 8 バイトの小文字16進`() {
        // SHA-256("abc") = ba7816bf 8f01cfea 414140de ...（FIPS 180-2 の試験値）
        assertEquals("ba7816bf8f01cfea", BedrockIds.h16("abc"))
        // SHA-256("") = e3b0c442 98fc1c14 9afbf4c8 ...
        assertEquals("e3b0c44298fc1c14", BedrockIds.h16(""))
        assertEquals("13b68a8ff807f62d", BedrockIds.h16("騎士\u0000頭"))
    }

    @Test
    fun `骨のキーはモデル名と骨の名前を NUL で区切って求める`() {
        assertEquals("3ea881319b66ee78", BedrockIds.boneKey("demon_knight", "head"))
        // 連結の仕方が違えば別のキーになる（今の jsonName は a_b+c と a+b_c が同じになる）
        assertEquals("0c1d18f5e74d2786", BedrockIds.boneKey("a_b", "c"))
        assertEquals("662f0bbbda83090c", BedrockIds.boneKey("a", "b_c"))
        assertNotEquals(BedrockIds.boneKey("a_b", "c"), BedrockIds.boneKey("a", "b_c"))
    }

    @Test
    fun `手足とテクスチャのキー`() {
        assertEquals("095ce4a56e1fa564", BedrockIds.limbKey("head"))
        assertEquals("b483f8400f319015", BedrockIds.limbKey("left_slim_arm"))
        assertEquals("da3e2c87d9e4d82d", BedrockIds.textureKey("demon_knight", "skin"))
    }

    @Test
    fun `統合版の識別子とテクスチャのパス`() {
        val key = "3ea881319b66ee78"
        assertEquals("bm_3ea881319b66ee78", BedrockIds.boneId(key))
        assertEquals("geometry.bm.3ea881319b66ee78", BedrockIds.geometryId(key))
        assertEquals("textures/bettermodel/t_da3e2c87d9e4d82d", BedrockIds.texturePath("da3e2c87d9e4d82d"))
    }

    @Test
    fun `item_model は難読化の有無で形が変わる`() {
        val key = "3ea881319b66ee78"
        assertEquals("bettermodel:b/3ea881319b66ee78", BedrockIds.boneItemModel("bettermodel", true, key, "demon_knight_head"))
        assertEquals("bettermodel:model/demon_knight_head", BedrockIds.boneItemModel("bettermodel", false, key, "demon_knight_head"))
        assertEquals("bettermodel:l/095ce4a56e1fa564", BedrockIds.limbItemModel("bettermodel", true, "head"))
        assertEquals("bettermodel:player_limb/head", BedrockIds.limbItemModel("bettermodel", false, "head"))
        assertEquals("b", BedrockIds.boneItemDirectory(true))
        assertEquals("model", BedrockIds.boneItemDirectory(false))
        assertEquals("l", BedrockIds.limbDirectory(true))
        assertEquals("player_limb", BedrockIds.limbDirectory(false))
    }
}
