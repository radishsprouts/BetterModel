/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.test;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.BetterModelConfig;
import kr.toxicity.model.api.bone.RenderedBone;
import kr.toxicity.model.api.platform.PlatformLocation;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.Tracker;
import kr.toxicity.model.api.tracker.TrackerSyncSnapshot;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

/**
 * トラッカーの状態を読む入口（V3 距離による LOD、V4 視界外の間の回転の確認用）。
 * <p>
 * 公開 API だけを使う。視聴者ごとの実際の LOD の段と最後に送った版は {@link Tracker#syncSnapshot()} で読み、
 * 設定値と同じ式で計算した期待値と並べて出す。
 * </p>
 */
final class DebugObserver {

    private final Map<Tracker, Watch> watches = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 対象とトラッカーの状態を一覧にする。EntityScheduler の上で呼ぶ。
     *
     * @param out 出力先
     * @param entity 対象
     * @param tracker 対象のトラッカー
     */
    void info(@NotNull DebugReport out, @NotNull Entity entity, @NotNull EntityTracker tracker) {
        var location = entity.getLocation();
        out.line("対象: " + entity.getType() + " " + entity.getUniqueId() + " world=" + location.getWorld().getName()
            + " pos=(" + f(location.getX()) + ", " + f(location.getY()) + ", " + f(location.getZ()) + ")"
            + " yaw=" + f(location.getYaw()) + " pitch=" + f(location.getPitch()));
        var source = tracker.sourceEntity();
        var rotation = tracker.rotation();
        out.line("トラッカー: model=" + tracker.name()
            + " 閉じた=" + tracker.isClosed()
            + " tick稼働中=" + tracker.isScheduled()
            + " 視聴者数=" + tracker.playerCount()
            + " 骨数=" + tracker.bones().size()
            + " ヒットボックス数=" + tracker.registry().hitBoxes().size());
        out.line("回転: モデル(x=" + f(rotation.x()) + ", y=" + f(rotation.y()) + ")"
            + " 本体(pitch=" + f(source.pitch()) + ", yaw=" + f(source.yaw()) + ")"
            + " 差(yaw)=" + f(rotation.y() - source.yaw()));
        out.line("走行中のアニメーション: " + running(tracker));
        out.line(lodConfig());
        viewers(out, tracker);
    }

    /**
     * 視聴者ごとの距離と、設定値から期待される LOD の段を出す。EntityScheduler の上で呼ぶ。
     *
     * @param out 出力先
     * @param tracker 対象のトラッカー
     */
    void lod(@NotNull DebugReport out, @NotNull EntityTracker tracker) {
        out.line(lodConfig());
        out.line("実際の段・送った版はトラッカーから読んだ値。期待値は設定値からテスト側で計算した値（段は 1 MC tick ごとに更新されるため、境界では一時的にずれる）");
        viewers(out, tracker);
    }

    /**
     * 一定の間隔で骨の姿勢と視聴者の距離を出す観測を始める（既に動いていれば設定し直す）。
     * <p>
     * トラッカーは視聴者が 0 人の間は止まるため、その間は出力されない。
     * </p>
     *
     * @param out 出力先
     * @param tracker 対象のトラッカー
     * @param period 出力の間隔（Minecraft の tick）
     * @param count 出力する回数
     * @param boneNames 出す骨の名前。空なら先頭の3つ
     */
    void watchStart(@NotNull DebugReport out, @NotNull EntityTracker tracker, int period, int count, @NotNull List<String> boneNames) {
        Watch watch;
        var created = false;
        synchronized (watches) {
            watch = watches.get(tracker);
            if (watch == null) {
                watch = new Watch();
                watches.put(tracker, watch);
                created = true;
            }
        }
        watch.out = out;
        watch.period = period;
        watch.remaining = count;
        watch.boneNames = List.copyOf(boneNames);
        watch.ticks = 0;
        watch.startMillis = System.currentTimeMillis();
        watch.active = true;
        if (created) {
            var target = watch;
            // 観測はトラッカー自身の tick（シャードのスレッド）の上で行う。外すための戻り値を持っておく。
            watch.remove = tracker.tickHandle(_ -> sample(target, tracker));
        }
        out.line("watch 開始: 間隔=" + period + "tick 回数=" + count + " tick稼働中=" + tracker.isScheduled()
            + " 視聴者数=" + tracker.playerCount() + "（視聴者が 0 の間はトラッカーが止まり出力されない）");
    }

    /**
     * 観測を止める。
     *
     * @param out 出力先
     * @param tracker 対象のトラッカー
     */
    void watchStop(@NotNull DebugReport out, @NotNull EntityTracker tracker) {
        var watch = watches.get(tracker);
        if (watch == null || !watch.active) {
            out.line("watch は動いていない");
            return;
        }
        var before = tracker.handlerCount();
        finish(tracker, watch);
        out.line("watch を止めた（残り " + watch.remaining + " 回）。ハンドラを外した: 登録数 " + before + " -> " + tracker.handlerCount());
    }

    /**
     * 観測を終え、トラッカーに登録したハンドラを外す。
     */
    private void finish(@NotNull Tracker tracker, @NotNull Watch watch) {
        watch.active = false;
        var remove = watch.remove;
        if (remove != null) remove.run();
        watches.remove(tracker);
    }

    private void sample(@NotNull Watch watch, @NotNull EntityTracker tracker) {
        if (!watch.active) return;
        var out = watch.out;
        try {
            var n = ++watch.ticks;
            if ((n - 1) % watch.period != 0) return;
            var location = tracker.location();
            var rotation = tracker.rotation();
            var source = tracker.sourceEntity();
            out.line("watch #" + n + " +" + (System.currentTimeMillis() - watch.startMillis) + "ms"
                + " pos=(" + f(location.x()) + ", " + f(location.y()) + ", " + f(location.z()) + ")"
                + " モデル回転(x=" + f(rotation.x()) + ", y=" + f(rotation.y()) + ")"
                + " 本体(pitch=" + f(source.pitch()) + ", yaw=" + f(source.yaw()) + ")"
                + " 走行中=" + running(tracker));
            for (RenderedBone bone : pickBones(tracker, watch.boneNames)) out.line("  " + bone(bone));
            viewers(out, tracker);
            if (--watch.remaining <= 0) {
                finish(tracker, watch);
                out.line("watch 終了（ハンドラを外した。登録数 " + tracker.handlerCount() + "）");
            }
        } catch (Throwable throwable) {
            finish(tracker, watch);
            out.error("watch の観測中に", throwable);
        }
    }

    private static @NotNull List<RenderedBone> pickBones(@NotNull Tracker tracker, @NotNull List<String> names) {
        var result = new ArrayList<RenderedBone>();
        if (names.isEmpty()) {
            for (RenderedBone bone : tracker.bones()) {
                if (result.size() >= 3) break;
                result.add(bone);
            }
        } else {
            for (String name : names) {
                var bone = tracker.bone(name);
                if (bone != null) result.add(bone);
            }
        }
        return result;
    }

    /**
     * 骨1つの姿勢を1行にする。
     *
     * @param bone 骨
     * @return 文字列
     */
    static @NotNull String bone(@NotNull RenderedBone bone) {
        var rotation = bone.rotation();
        var position = bone.worldPosition();
        var worldRotation = bone.worldRotation();
        var animation = bone.runningAnimation();
        return "骨 " + bone.name().name()
            + " rot(x=" + f(rotation.x()) + ", y=" + f(rotation.y()) + ")"
            + " worldPos=(" + f(position.x) + ", " + f(position.y) + ", " + f(position.z) + ")"
            + " worldRot=(" + f(worldRotation.x) + ", " + f(worldRotation.y) + ", " + f(worldRotation.z) + ")"
            + " anim=" + (animation == null ? "-" : animation.name() + "/" + animation.type());
    }

    /**
     * 走行中のアニメーション名ごとの骨の数を文字列にする。
     *
     * @param tracker トラッカー
     * @return 文字列
     */
    static @NotNull String running(@NotNull Tracker tracker) {
        var counts = new TreeMap<String, Integer>();
        for (RenderedBone bone : tracker.bones()) {
            var animation = bone.runningAnimation();
            if (animation != null) counts.merge(animation.name() + "/" + animation.type(), 1, Integer::sum);
        }
        return counts.isEmpty() ? "なし" : counts.entrySet().stream()
            .map(entry -> entry.getKey() + "×" + entry.getValue() + "骨")
            .collect(Collectors.joining(", "));
    }

    private static void viewers(@NotNull DebugReport out, @NotNull EntityTracker tracker) {
        var config = BetterModel.config();
        var location = tracker.location();
        var snapshot = tracker.syncSnapshot();
        var actual = new HashMap<UUID, TrackerSyncSnapshot.ViewerSync>();
        snapshot.viewers().forEach(sync -> actual.put(sync.uuid(), sync));
        out.line("送信状態: 姿勢の版=" + snapshot.version() + " 送信処理の回数=" + snapshot.tickStamp() + " 登録ハンドラ数=" + tracker.handlerCount());
        var lines = new ArrayList<String>();
        tracker.registry().viewedPlayer().forEach(channel -> {
            var player = channel.player();
            if (!tracker.isSpawned(channel.uuid())) return;
            var playerLocation = player.location();
            var expected = expectedStep(config, playerLocation, location);
            var sync = actual.get(channel.uuid());
            lines.add("視聴者 " + player.name()
                + " 距離=" + f(distance(playerLocation, location))
                + " 実際のLOD段=" + (sync == null ? "未計測(個別アニメーション中か、次の tick 待ち)" : String.valueOf(sync.step()))
                + " 期待LOD段=" + expected
                + (sync != null && sync.step() != expected ? "(差あり)" : "")
                + " 最後に送った版=" + (sync == null ? "-" : sync.sentVersion() + "/" + snapshot.version()));
        });
        if (lines.isEmpty()) out.line("視聴者: なし");
        else lines.forEach(out::line);
    }

    private static @NotNull String lodConfig() {
        var config = BetterModel.config();
        return "LOD設定: 有効=" + config.animationLod()
            + " 近い距離=" + config.animationLodNearDistance() + "(段" + config.animationLodNearStep() + ")"
            + " 遠い距離=" + config.animationLodFarDistance() + "(段" + config.animationLodFarStep() + ")";
    }

    /**
     * 設定値から、トラッカーが使うはずの LOD の段を計算する。
     * <p>
     * トラッカー内部（TrackerAnimationTicker）の式と同じ。内部の値そのものではない。
     * </p>
     */
    private static int expectedStep(@NotNull BetterModelConfig config, @NotNull PlatformLocation player, @NotNull PlatformLocation model) {
        if (!config.animationLod()) return 1;
        if (!player.world().equals(model.world())) return config.animationLodFarStep();
        var dx = player.x() - model.x();
        var dy = player.y() - model.y();
        var dz = player.z() - model.z();
        var distanceSquared = dx * dx + dy * dy + dz * dz;
        var far = config.animationLodFarDistance();
        if (distanceSquared >= far * far) return config.animationLodFarStep();
        var near = config.animationLodNearDistance();
        if (distanceSquared >= near * near) return config.animationLodNearStep();
        return 1;
    }

    private static double distance(@NotNull PlatformLocation a, @NotNull PlatformLocation b) {
        if (!a.world().equals(b.world())) return Double.POSITIVE_INFINITY;
        var dx = a.x() - b.x();
        var dy = a.y() - b.y();
        var dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static @NotNull String f(double value) {
        return String.format("%.3f", value);
    }

    private static final class Watch {
        private volatile boolean active;
        private volatile DebugReport out;
        private volatile int period = 20;
        private volatile int remaining;
        private volatile List<String> boneNames = List.of();
        private volatile long startMillis;
        // トラッカーに登録したハンドラを外すための戻り値。
        private volatile Runnable remove;
        // トラッカーのシャードのスレッドだけが触る。
        private int ticks;
    }
}
