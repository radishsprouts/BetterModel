/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.config;

import org.jetbrains.annotations.NotNull;

/**
 * 統合版（Bedrock、Geyser 経由）向けの出力の設定です。
 * <p>
 * 有効な場合、リロードのたびにプラグインのフォルダへ統合版用のモデル・テクスチャ・目録を書き出します。
 * </p>
 *
 * @param enabled 統合版用の出力を作るか
 * @param outputFolder 出力先のフォルダ名（プラグインのデータフォルダからの相対パス）
 * @since 3.7.0
 */
public record BedrockConfig(
    boolean enabled,
    @NotNull String outputFolder
) {
    /**
     * 既定の出力先のフォルダ名です。
     * @since 3.7.0
     */
    public static final String DEFAULT_OUTPUT_FOLDER = "bedrock";

    /**
     * 既定の設定です（出力する、出力先は {@code bedrock}）。
     * @since 3.7.0
     */
    public static final BedrockConfig DEFAULT = new BedrockConfig(true, DEFAULT_OUTPUT_FOLDER);
}
