/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bedrock

import java.security.MessageDigest

/**
 * 統合版（Bedrock）の出力と Java 版のパックで共有する識別子の規則。
 *
 * 識別子は、モデルの名前と骨の名前だけから毎回同じ値になる。
 * リロードの順番・難読化の出現順・Java 版のパックの再生成に影響されないので、
 * モデルが変わっていなければ Geyser を再起動しなくても統合版の見た目が合ったままになる。
 */
internal object BedrockIds {

    /** 統合版の骨の geometry に使う区切り（名前の連結が衝突しないようにする）。 */
    private const val SEPARATOR = "\u0000"

    /**
     * 頭の item の custom_model_data の strings に、被せる兜のアイテムの id を載せる時の接頭辞。
     * KadoDisplayEntity がこの接頭辞で兜を読む（書式は `bm:armor:<namespace>:<id>`）。
     * 防具の select は strings の 0 番（防具）と 1 番（トリム）しか見ないので、末尾に足しても Java 版の見た目は変わらない。
     */
    const val ARMOR_PREFIX = "bm:armor:"

    /**
     * 文字列を UTF-8 にした SHA-256 の先頭 8 バイトを、小文字の16進 16 文字にする。
     *
     * @param value 元の文字列
     * @return 16 文字の16進
     */
    fun h16(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(16) {
            for (i in 0 until 8) append("%02x".format(digest[i].toInt() and 0xFF))
        }
    }

    /**
     * 骨のキー。
     *
     * @param modelName モデルの名前（[kr.toxicity.model.api.data.blueprint.BlueprintLoadContext.name]）
     * @param boneRawName 骨の生の名前
     * @return 16 文字の16進
     */
    fun boneKey(modelName: String, boneRawName: String): String = h16(modelName + SEPARATOR + boneRawName)

    /**
     * 手足（プレイヤーのモデルの部位）のキー。
     *
     * @param uvModelName 部位のモデル名（例: `head`、`left_slim_arm`）
     * @return 16 文字の16進
     */
    fun limbKey(uvModelName: String): String = h16("player_limb" + SEPARATOR + uvModelName)

    /**
     * テクスチャのキー。
     *
     * @param modelName モデルの名前
     * @param textureName テクスチャの名前
     * @return 16 文字の16進
     */
    fun textureKey(modelName: String, textureName: String): String = h16(modelName + SEPARATOR + textureName)

    /** 統合版の骨の識別子。 */
    fun boneId(boneKey: String): String = "bm_$boneKey"

    /** 統合版の骨の geometry の識別子。 */
    fun geometryId(boneKey: String): String = "geometry.bm.$boneKey"

    /** テクスチャのパス（拡張子なし）。 */
    fun texturePath(textureKey: String): String = "textures/bettermodel/t_$textureKey"

    /**
     * Java 版の骨の item_model の、`items` 直下のフォルダ名。
     *
     * @param obfuscation 難読化が有効か
     * @return 難読化が有効なら `b`、無効なら `model`
     */
    fun boneItemDirectory(obfuscation: Boolean): String = if (obfuscation) "b" else "model"

    /**
     * Java 版の骨の item_model の、フォルダの下のファイル名（拡張子なし）。
     *
     * @param obfuscation 難読化が有効か
     * @param boneKey 骨のキー
     * @param jsonName 骨の JSON 名（難読化なしで使う）
     * @return 難読化が有効なら骨のキー、無効なら JSON 名
     */
    fun boneItemName(obfuscation: Boolean, boneKey: String, jsonName: String): String = if (obfuscation) boneKey else jsonName

    /**
     * Java 版の骨の item_model。
     *
     * @param namespace 名前空間
     * @return `名前空間:パス`
     */
    fun boneItemModel(namespace: String, obfuscation: Boolean, boneKey: String, jsonName: String): String =
        "$namespace:${boneItemDirectory(obfuscation)}/${boneItemName(obfuscation, boneKey, jsonName)}"

    /**
     * Java 版の手足の item_model を置くフォルダ名（`items`・`models`・`textures/item` の直下で共通）。
     *
     * @param obfuscation 難読化が有効か
     * @return 難読化が有効なら `l`、無効なら `player_limb`
     */
    fun limbDirectory(obfuscation: Boolean): String = if (obfuscation) "l" else "player_limb"

    /**
     * Java 版の手足の item_model のパック名（フォルダの下のファイル名）。
     *
     * @param obfuscation 難読化が有効か
     * @param uvModelName 部位のモデル名
     * @return 難読化が有効なら手足のキー、無効ならモデル名
     */
    fun limbPackName(obfuscation: Boolean, uvModelName: String): String = if (obfuscation) limbKey(uvModelName) else uvModelName

    /**
     * Java 版の手足の item_model。
     *
     * @param namespace 名前空間
     * @return `名前空間:パス`
     */
    fun limbItemModel(namespace: String, obfuscation: Boolean, uvModelName: String): String =
        "$namespace:${limbDirectory(obfuscation)}/${limbPackName(obfuscation, uvModelName)}"
}
