/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.test;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.EntityTrackerRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 実機確認（V2・V3・V4・V5・V9・V10）のための検証コマンド {@code /bmtest}。
 * <p>
 * 開発・検証専用で運用の手順には載せない（rules/17 の例外4）。op だけが使える。
 * 対象の Mob は、プレイヤーが見ている Mob（見えなければ近くの最寄りの Mob）か、{@code target=<UUID>} で指定する。
 * エンティティに触れる処理は、必ずそのエンティティの EntityScheduler の上で行う（Folia）。
 * </p>
 */
public final class DebugCommandTester implements ModelTester, TabCompleter, org.bukkit.command.CommandExecutor {

    private static final Set<String> OPTION_KEYS = Set.of("target", "model", "bone", "period", "count", "speed", "limit");
    private static final List<String> SUBCOMMANDS = List.of("help", "targets", "info", "lod", "watch", "anim", "tag", "hitbox", "skin");

    private BetterModelTest test;
    private DebugActions actions;
    private final DebugObserver observer = new DebugObserver();

    @Override
    public void start(@NotNull BetterModelTest test) {
        this.test = test;
        this.actions = new DebugActions(test);
        actions.start();
        var command = test.getCommand("bmtest");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    @Override
    public void end(@NotNull BetterModelTest test) {
        if (actions != null) actions.end();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] raw) {
        // 権限の設定ではなく、op であることを必ず確かめる（検証専用のため）。
        if (!sender.isOp()) {
            sender.sendMessage(Component.text("[bmtest] op だけが使えます。", NamedTextColor.RED));
            return true;
        }
        var out = new DebugReport(test, sender);
        out.line("コマンド: /" + label + " " + String.join(" ", raw) + " (実行者=" + sender.getName() + ")");
        try {
            dispatch(sender, out, raw);
        } catch (IllegalArgumentException e) {
            out.fail("引数が不正: " + e.getMessage());
        } catch (Throwable throwable) {
            out.error("コマンドの処理中に", throwable);
        }
        return true;
    }

    private void dispatch(@NotNull CommandSender sender, @NotNull DebugReport out, @NotNull String[] raw) {
        if (raw.length == 0) {
            help(out);
            return;
        }
        var args = Args.parse(raw, 1);
        switch (raw[0]) {
            case "help" -> help(out);
            case "targets" -> targets(out);
            case "info" -> run(sender, out, args, true, (entity, tracker) -> observer.info(out, entity, tracker));
            case "lod" -> run(sender, out, args, true, (_, tracker) -> observer.lod(out, tracker));
            case "watch" -> watch(sender, out, args);
            case "anim" -> anim(sender, out, args);
            case "tag" -> tag(sender, out, args);
            case "hitbox" -> hitbox(sender, out, args);
            case "skin" -> {
                var model = required(args, 0, "モデル名");
                var skin = required(args, 1, "皮膚の名前（プレイヤー名 / fail / broken）");
                run(sender, out, args, false, (entity, _) -> actions.skin(out, entity, model, skin));
            }
            default -> {
                out.fail("不明なサブコマンド: " + raw[0]);
                help(out);
            }
        }
    }

    private void watch(@NotNull CommandSender sender, @NotNull DebugReport out, @NotNull Args args) {
        switch (required(args, 0, "start | stop")) {
            case "start" -> {
                var period = args.intOption("period", 20);
                var count = args.intOption("count", 10);
                if (period <= 0 || count <= 0 || count > 200) throw new IllegalArgumentException("period は 1 以上、count は 1〜200");
                var bones = args.options().containsKey("bone") ? Arrays.asList(args.options().get("bone").split(",")) : List.<String>of();
                run(sender, out, args, true, (_, tracker) -> observer.watchStart(out, tracker, period, count, bones));
            }
            case "stop" -> run(sender, out, args, true, (_, tracker) -> observer.watchStop(out, tracker));
            default -> throw new IllegalArgumentException("watch は start か stop");
        }
    }

    private void anim(@NotNull CommandSender sender, @NotNull DebugReport out, @NotNull Args args) {
        switch (required(args, 0, "list | play | stop | replace | pose")) {
            case "list" -> run(sender, out, args, true, (_, tracker) -> actions.animList(out, tracker));
            case "play" -> {
                var name = required(args, 1, "アニメーション名");
                var type = args.word(2);
                var speed = args.options().containsKey("speed") ? Float.parseFloat(args.options().get("speed")) : null;
                run(sender, out, args, true, (entity, tracker) -> actions.animPlay(out, entity, tracker, name, type, speed));
            }
            case "stop" -> {
                var name = required(args, 1, "アニメーション名");
                run(sender, out, args, true, (entity, tracker) -> actions.animStop(out, entity, tracker, name));
            }
            case "replace" -> {
                var target = required(args, 1, "差し替え元のアニメーション名");
                var name = required(args, 2, "差し替え先のアニメーション名");
                var type = args.word(3);
                run(sender, out, args, true, (entity, tracker) -> actions.animReplace(out, entity, tracker, target, name, type));
            }
            case "pose" -> {
                var limit = args.intOption("limit", 12);
                run(sender, out, args, true, (_, tracker) -> actions.animPose(out, tracker, limit));
            }
            default -> throw new IllegalArgumentException("anim は list / play / stop / replace / pose");
        }
    }

    private void tag(@NotNull CommandSender sender, @NotNull DebugReport out, @NotNull Args args) {
        switch (required(args, 0, "name | show | attach | text | remove")) {
            case "name" -> {
                var text = args.text(1);
                if (text.isEmpty()) throw new IllegalArgumentException("名前（消すなら clear）を書く");
                run(sender, out, args, true, (entity, tracker) -> actions.nameEntity(out, entity, tracker, text.equals("clear") ? null : text));
            }
            case "show" -> run(sender, out, args, true, (_, tracker) -> actions.nametagShow(out, tracker));
            case "attach" -> {
                var text = args.text(1);
                if (text.isEmpty()) throw new IllegalArgumentException("ネームタグの文字を書く");
                var bone = args.options().get("bone");
                run(sender, out, args, true, (_, tracker) -> actions.nametagAttach(out, tracker, text, bone));
            }
            case "text" -> {
                var text = args.text(1);
                if (text.isEmpty()) throw new IllegalArgumentException("ネームタグの文字を書く");
                run(sender, out, args, true, (_, tracker) -> actions.nametagText(out, tracker, text));
            }
            case "remove" -> run(sender, out, args, true, (_, tracker) -> actions.nametagRemove(out, tracker));
            default -> throw new IllegalArgumentException("tag は name / show / attach / text / remove");
        }
    }

    private void hitbox(@NotNull CommandSender sender, @NotNull DebugReport out, @NotNull Args args) {
        switch (required(args, 0, "cancel on|off | status")) {
            case "cancel" -> {
                var mode = required(args, 1, "on | off");
                if (!mode.equals("on") && !mode.equals("off")) throw new IllegalArgumentException("cancel は on か off");
                run(sender, out, args, true, (entity, tracker) -> actions.hitboxCancel(out, entity, tracker, mode.equals("on")));
            }
            case "status" -> run(sender, out, args, true, (entity, tracker) -> actions.hitboxStatus(out, entity, tracker));
            default -> throw new IllegalArgumentException("hitbox は cancel on|off か status");
        }
    }

    private void help(@NotNull DebugReport out) {
        out.line("使い方: /bmtest <サブコマンド> ...（op 専用。対象は見ている Mob、見えなければ近くの最寄り。target=<UUID> で指定、model=<名前> でモデルを選ぶ）");
        out.line("  targets                              … モデルが付いたエンティティの UUID 一覧");
        out.line("  info / lod                           … V3/V4 トラッカーの状態と、視聴者ごとの距離・実際のLOD段・期待LOD段・最後に送った版");
        out.line("  watch start [period=20] [count=10] [bone=a,b] / watch stop … V3/V4 一定間隔で姿勢と距離・LOD段・送った版をログへ（stop はハンドラを外す）");
        out.line("  anim list|pose [limit=12]            … V2 アニメーション一覧・骨の姿勢");
        out.line("  anim play <名前> [once|loop|hold] [speed=1.0] / anim stop <名前> / anim replace <元> <先> [once|loop|hold] … V2");
        out.line("  tag name <文字|clear>                … V5 Bukkit の customName を変える（モデルのネームタグが追随する）");
        out.line("  tag show                             … V5 骨のネームタグの今の文字と、エンティティの名前を出す");
        out.line("  tag attach <文字> [bone=名前] / tag text <文字> / tag remove … V5 テスト用ネームタグの追加・文字変更・削除");
        out.line("  hitbox cancel on|off / hitbox status … V10 ヒットボックスの攻撃・右クリックを取り消す");
        out.line("  skin <モデル名> <プレイヤー名|fail|broken> … V9 皮膚を使うモデルを付ける（fail は取得失敗する名前、broken は complete が例外）");
    }

    private void targets(@NotNull DebugReport out) {
        var registries = EntityTrackerRegistry.registries();
        out.line("モデルが付いたエンティティ " + registries.size() + " 件");
        for (EntityTrackerRegistry registry : registries) {
            var models = new ArrayList<String>();
            registry.trackers().forEach(tracker -> models.add(tracker.name()));
            out.line("  uuid=" + registry.uuid() + " models=" + models + " 視聴者数=" + registry.viewedPlayer().count());
        }
    }

    /**
     * 対象を決め、そのエンティティの EntityScheduler の上で処理を実行する。
     */
    private void run(@NotNull CommandSender sender, @NotNull DebugReport out, @NotNull Args args, boolean needTracker, @NotNull Action action) {
        var entity = resolve(sender, args, out);
        if (entity == null) return;
        var model = args.options().get("model");
        entity.getScheduler().run(test, _ -> {
            try {
                EntityTracker tracker = null;
                if (needTracker) {
                    var registry = BetterModel.registryOrNull(BukkitAdapter.adapt(entity));
                    tracker = registry == null ? null : model != null ? registry.tracker(model) : registry.first();
                    if (tracker == null) {
                        out.fail("対象にモデル(トラッカー)が付いていない: " + entity.getType() + " " + entity.getUniqueId()
                            + (model != null ? " model=" + model : "") + "（skin で付けるか targets で探す）");
                        return;
                    }
                }
                action.run(entity, tracker);
            } catch (IllegalArgumentException e) {
                out.fail("引数が不正: " + e.getMessage());
            } catch (Throwable throwable) {
                out.error("処理中に", throwable);
            }
        }, () -> out.fail("対象が消えたため実行できなかった: " + entity.getUniqueId()));
    }

    private @Nullable Entity resolve(@NotNull CommandSender sender, @NotNull Args args, @NotNull DebugReport out) {
        var target = args.options().get("target");
        if (target != null) {
            var found = Bukkit.getEntity(UUID.fromString(target));
            if (found == null) out.fail("UUID のエンティティが見つからない（読み込まれていない可能性）: " + target);
            return found;
        }
        if (!(sender instanceof Player player)) {
            out.fail("コンソールからは target=<UUID> が必要（targets で UUID を調べる）");
            return null;
        }
        // コマンドはプレイヤーのリージョンのスレッドで呼ばれるため、プレイヤー周辺の読み取りはここで行ってよい。
        var looked = player.getTargetEntity(32);
        if (looked instanceof LivingEntity && !(looked instanceof Player)) return looked;
        Entity nearest = null;
        var best = Double.MAX_VALUE;
        for (Entity entity : player.getNearbyEntities(8, 8, 8)) {
            if (!(entity instanceof LivingEntity) || entity instanceof Player) continue;
            var distance = entity.getLocation().distanceSquared(player.getLocation());
            if (distance < best) {
                best = distance;
                nearest = entity;
            }
        }
        if (nearest == null) out.fail("見ている Mob も、8 ブロック以内の Mob も無い");
        else out.line("見ている Mob が無いため最寄りを対象にした: " + nearest.getType() + " " + nearest.getUniqueId());
        return nearest;
    }

    private static @NotNull String required(@NotNull Args args, int index, @NotNull String name) {
        var word = args.word(index);
        if (word == null) throw new IllegalArgumentException(name + " が必要");
        return word;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.isOp()) return List.of();
        if (args.length == 1) return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0])).toList();
        var sub = args[0];
        var prefix = args[args.length - 1];
        List<String> candidates = switch (sub) {
            case "watch" -> args.length == 2 ? List.of("start", "stop") : List.of("period=", "count=", "bone=");
            case "anim" -> args.length == 2 ? List.of("list", "play", "stop", "replace", "pose") : List.of("once", "loop", "hold", "speed=");
            case "tag" -> args.length == 2 ? List.of("name", "show", "attach", "text", "remove") : List.of("bone=");
            case "hitbox" -> args.length == 2 ? List.of("cancel", "status") : List.of("on", "off");
            case "skin" -> args.length == 3 ? List.of("fail", "broken") : args.length == 2 ? new ArrayList<>(BetterModel.limbKeys()) : List.of();
            default -> List.of();
        };
        var result = new ArrayList<>(candidates);
        result.add("target=");
        result.add("model=");
        return result.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    @FunctionalInterface
    private interface Action {
        void run(@NotNull Entity entity, @Nullable EntityTracker tracker);
    }

    private record Args(@NotNull List<String> words, @NotNull Map<String, String> options) {

        static @NotNull Args parse(@NotNull String[] raw, int from) {
            var words = new ArrayList<String>();
            var options = new HashMap<String, String>();
            for (int i = from; i < raw.length; i++) {
                var token = raw[i];
                var eq = token.indexOf('=');
                if (eq > 0 && OPTION_KEYS.contains(token.substring(0, eq))) options.put(token.substring(0, eq), token.substring(eq + 1));
                else words.add(token);
            }
            return new Args(words, options);
        }

        @Nullable String word(int index) {
            return index < words.size() ? words.get(index) : null;
        }

        @NotNull String text(int from) {
            return from >= words.size() ? "" : String.join(" ", words.subList(from, words.size()));
        }

        int intOption(@NotNull String key, int defaultValue) {
            var value = options.get(key);
            return value == null ? defaultValue : Integer.parseInt(value);
        }
    }
}
