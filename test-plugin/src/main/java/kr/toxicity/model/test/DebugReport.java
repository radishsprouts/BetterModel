/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

/**
 * 検証コマンドの結果を、チャットとサーバーのログの両方へ出す。
 * <p>
 * ログは MCP の get_logs で読むため、すべての行に [bmtest] を付ける。
 * どのスレッドからでも呼べる（Folia のスレッド規則に影響しない送信だけを行う）。
 * </p>
 */
final class DebugReport {

    private static final String PREFIX = "[bmtest] ";

    private final Plugin plugin;
    private final CommandSender sender;

    DebugReport(@NotNull Plugin plugin, @NotNull CommandSender sender) {
        this.plugin = plugin;
        this.sender = sender;
    }

    /**
     * 通常の結果の1行を出す。
     *
     * @param text 本文
     */
    void line(@NotNull String text) {
        sender.sendMessage(Component.text(PREFIX + text, NamedTextColor.GRAY));
        plugin.getLogger().info(PREFIX + text);
    }

    /**
     * 失敗・異常の1行を出す。
     *
     * @param text 本文
     */
    void fail(@NotNull String text) {
        sender.sendMessage(Component.text(PREFIX + text, NamedTextColor.RED));
        plugin.getLogger().warning(PREFIX + text);
    }

    /**
     * 例外を、スタックトレースごとログへ、概要をチャットへ出す。
     *
     * @param text 何をしていたか
     * @param throwable 例外
     */
    void error(@NotNull String text, @NotNull Throwable throwable) {
        sender.sendMessage(Component.text(PREFIX + text + " 例外: " + throwable, NamedTextColor.RED));
        plugin.getLogger().log(Level.SEVERE, PREFIX + text + " 例外が出た", throwable);
    }
}
