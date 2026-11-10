/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.nms;

import kr.toxicity.model.api.platform.PlatformLocation;
import kr.toxicity.model.api.platform.PlatformPlayer;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Represents a nametag associated with a model part.
 * <p>
 * Nametags are typically implemented as invisible armor stands or text displays
 * that float above a specific bone.
 * </p>
 *
 * @since 1.15.2
 */
public interface ModelNametag {

    /**
     * Sets whether the nametag should always be visible (even through blocks).
     *
     * @param alwaysVisible true for always visible, false otherwise
     * @since 1.15.2
     */
    void alwaysVisible(boolean alwaysVisible);

    /**
     * Sets the text component of the nametag.
     *
     * @param component the text component, or null to clear
     * @since 1.15.2
     */
    void component(@Nullable Component component);

    /**
     * Teleports the nametag to a new location.
     *
     * @param location the target location
     * @since 1.15.2
     */
    void teleport(@NotNull PlatformLocation location);

    /**
     * 移動と変更分の取り出しを、tick に1回だけ行う。
     * <p>
     * {@link #teleport(PlatformLocation)} の後、{@link #send(PlatformPlayer)} の前に、tick ごとに1回呼ぶ。
     * 変更分はここで取り出して消えるため、全員へ同じ内容が届く。
     * </p>
     * <pre>{@code
     * tag.teleport(tracker.location());
     * tag.update();
     * players.forEach(tag::send);
     * }</pre>
     *
     * @since 3.7.0
     */
    void update();

    /**
     * 指定したプレイヤーへネームタグのパケットを送る。
     * <p>
     * その tick の {@link #update()} が済んでいること。
     * </p>
     *
     * @param player 送る相手
     * @since 1.15.2
     */
    void send(@NotNull PlatformPlayer player);

    /**
     * 全員からネームタグを消し、見ているプレイヤーの記録も消す。
     *
     * <pre>{@code
     * tag.remove(bundler);
     * }</pre>
     *
     * @param bundler 使うパケットバンドラー
     * @since 1.15.2
     */
    void remove(@NotNull PacketBundler bundler);

    /**
     * 1人のプレイヤーからネームタグを消し、そのプレイヤーの記録も消す。
     * <p>
     * そのプレイヤーが見ていなければ、バンドラーには何も足さない。
     * </p>
     *
     * <pre>{@code
     * tag.remove(player, bundler);
     * }</pre>
     *
     * @param player 消す相手
     * @param bundler 使うパケットバンドラー
     * @since 3.7.0
     */
    void remove(@NotNull PlatformPlayer player, @NotNull PacketBundler bundler);

    /**
     * パケットを送らずに、プレイヤーの記録だけを消す。
     * <p>
     * 接続が既に切れている（退出した）プレイヤー向け。
     * </p>
     *
     * <pre>{@code
     * tag.forget(player.uuid());
     * }</pre>
     *
     * @param uuid 見ていたプレイヤーの UUID
     * @since 3.7.0
     */
    void forget(@NotNull UUID uuid);
}
