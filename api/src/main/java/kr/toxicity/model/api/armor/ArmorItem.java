/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.armor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Armor item
 * @param tint tint value
 * @param type armor type
 * @param trim trim
 * @param palette palette
 * @param itemId 防具のアイテムの id（例 {@code minecraft:iron_helmet}）。統合版で兜を被せるために使う。不明なら null
 */
public record ArmorItem(int tint, @NotNull String type, @Nullable String trim, @Nullable String palette, @Nullable String itemId) {

    /**
     * アイテムの id を持たない防具を作る（統合版の兜は被せない）。
     * @param tint 色
     * @param type 防具の種類
     * @param trim トリム
     * @param palette トリムの色のパレット
     */
    public ArmorItem(int tint, @NotNull String type, @Nullable String trim, @Nullable String palette) {
        this(tint, type, trim, palette, null);
    }
}
