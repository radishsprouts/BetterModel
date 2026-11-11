/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.tracker;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.BetterModelConfig;
import kr.toxicity.model.api.data.renderer.RenderPipeline;
import kr.toxicity.model.api.nms.AnimationBundler;
import kr.toxicity.model.api.nms.PlayerChannelHandler;
import kr.toxicity.model.api.platform.PlatformLocation;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Coordinates animation ticking and packet dispatching for a tracker.
 * <p>
 * Manages animation frame updates via the {@link RenderPipeline}, broadcasting animation packets
 * to viewing players and handling isolated per-player animation overrides with reference counting.
 * </p>
 * <p>
 * アニメーションの状態は毎フレーム進める。間引くのはパケットの送信だけで、プレイヤーごとの距離で決める
 * （LOD）。モデルから遠いプレイヤーには最新の姿勢をより低い頻度で送り、
 * 飛ばしたフレーム分は補間時間を延ばして埋める。
 * </p>
 * <p>Example:</p>
 * <pre>{@code
 * TrackerAnimationTicker ticker = new TrackerAnimationTicker(pipeline);
 * ticker.tick(frame, location);
 * }</pre>
 *
 * @since 3.7.0
 */
final class TrackerAnimationTicker {

    private final RenderPipeline pipeline;

    private final List<PlayerChannelHandler> viewedPlayers = new ObjectArrayList<>();
    // 書き込みはトラッカーのシャードのスレッドだけ。観測（snapshot）が別のスレッドから読むため ConcurrentHashMap にしている。
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private final Map<UUID, PerPlayerCache> perPlayerAnimationCache = new ConcurrentHashMap<>();

    // 書き込みはトラッカーのシャードのスレッドだけ。観測（snapshot）が読むため volatile にしている。
    private volatile long version = 0L;
    private volatile long tickStamp = 0L;
    private final int phase = ThreadLocalRandom.current().nextInt(64);

    private volatile boolean immediate;
    // 視聴者が出入りした（スポーン・プレイヤー個別アニメーションの終了）ので、次の tick で数え直す。
    private volatile boolean recount0;
    // 最新の版をまだ受け取っていない視聴者がいるか（LOD で番を待っている）。tick のスレッドだけが読み書きする。
    private boolean waiting;
    // スポーン済みだが視野の判定（viewFilter）で外れている人がいるか。tick のスレッドだけが読み書きする。
    // その人が視野へ戻ったことは姿勢の変化を待たずに気付く必要があるので、いる間は 1 MC ティックに 1 回数え直す。
    private boolean hidden;
    private int passedCount;

    /**
     * Constructs a new animation ticker.
     *
     * @param pipeline the render pipeline
     * @since 3.7.0
     */
    TrackerAnimationTicker(@NotNull RenderPipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    /**
     * 次の tick で、LOD を無視して変化した姿勢を全員へ一度に送らせる。
     * API の呼び出しでアニメーションを再生・停止した時に使う。
     *
     * @since 3.7.0
     */
    void requestImmediate() {
        immediate = true;
    }

    /**
     * プレイヤーへモデルをスポーンさせた時に呼ぶ。次の tick でそのプレイヤーを新しい視聴者として数え直す。
     * <p>
     * スポーンのパケットの姿勢は、誰にも送られていない間に変わった分を含まないことがある
     * （視聴者がいない間や、LOD で番を待つ遠い視聴者だけの間は、送る時に取り込む姿勢が更新されない）。
     * そこで新しい視聴者は「何も受け取っていない」扱いで登録し、最初の tick で LOD の番を待たずに、
     * 変わったことのある項目の最新の値を全部受け取らせる。静止したモデルでも次の tick で必ず数え直す。
     * </p>
     *
     * @param uuid スポーンさせたプレイヤーの UUID
     * @since 3.7.0
     */
    void spawned(@NotNull UUID uuid) {
        viewers.remove(uuid);
        recount0 = true;
    }

    /**
     * Advances animation playback in the render pipeline and dispatches animation packets to viewers.
     *
     * @param frame 現在のトラッカーのフレーム
     * @param location 距離による LOD に使うトラッカーの現在位置
     * @since 3.7.0
     */
    void tick(long frame, @NotNull PlatformLocation location) {
        var forced = immediate;
        if (forced) immediate = false;
        var recount = recount0;
        if (recount) recount0 = false;
        var posed = pipeline.tick();
        var next = version + 1;
        if (posed) pipeline.sync(next);
        // 拡大・乗り位置・表示アイテムは骨の姿勢とは別の入力で、骨が止まっていても変わる。
        // 変わった骨にも同じ版を記録し、姿勢の変化と同じ経路（版の比較）で全員へ届ける。
        var external = pipeline.syncExternal(next);
        var changed = posed || external;
        if (changed) version = next;
        // 視聴者の数え直し（距離・視野の判定を含み重い）は、送るものがある時だけ行う。
        // 姿勢が変わった／強制の送信／最新の版をまだ受け取っていない視聴者がいる／視聴者の出入りがあった、のどれかの時。
        // 視野の外の人がいる間は、静止していても視野へ戻ったら最新の姿勢を届けるため、1 MC ティックに 1 回数え直す。
        var peek = hidden && frame % Tracker.MINECRAFT_TICK_MULTIPLIER == 0;
        if (changed || forced || waiting || recount || peek) {
            recalculateViewedPlayers();
            sendGlobal(frame, location, forced);
        }
        if (!perPlayerAnimationCache.isEmpty()) perPlayerAnimationCache.values().forEach(PerPlayerCache::send);
    }

    /**
     * Increments the reference count of active per-player animations for the specified player.
     * <p>
     * If this is the first animation registered for the player, {@code startConsumer} is invoked.
     * </p>
     *
     * @param uuid the unique identifier of the player
     * @param startConsumer the callback invoked when the per-player animation starts
     * @since 3.7.0
     */
    void countAdd(@NotNull UUID uuid, @NotNull Consumer<PlayerChannelHandler> startConsumer) {
        perPlayerAnimationCache
            .computeIfAbsent(uuid, PerPlayerCache::new)
            .add(startConsumer);
    }

    /**
     * Decrements the reference count of active per-player animations for the specified player.
     * <p>
     * If no active animations remain for the player, cached entries are removed,
     * bone transformations are forcibly synchronized, and {@code endConsumer} is invoked.
     * </p>
     *
     * @param uuid the unique identifier of the player
     * @param endConsumer the callback invoked when the per-player animation ends
     * @since 3.7.0
     */
    void countRemove(@NotNull UUID uuid, @NotNull Consumer<PlayerChannelHandler> endConsumer) {
        var get = perPlayerAnimationCache.get(uuid);
        if (get != null) get.remove(endConsumer);
    }

    private void recalculateViewedPlayers() {
        viewedPlayers.clear();
        passedCount = 0;
        pipeline.forEachViewedPlayer(channel -> {
            passedCount++;
            if (!perPlayerAnimationCache.containsKey(channel.uuid())) viewedPlayers.add(channel);
        });
        hidden = passedCount < pipeline.playerCount();
    }

    /**
     * スポーンしていない（デスポーンした）人の記録だけを消す。
     * 視野の外へ出た人の記録は残し、戻った時に視野の外にいた間の差分だけを送る（全量を送り直さない）。
     */
    private void dropDespawned() {
        if (viewers.size() > pipeline.playerCount()) viewers.keySet().removeIf(uuid -> pipeline.channel(uuid) == null);
    }

    private void sendGlobal(long frame, @NotNull PlatformLocation location, boolean forced) {
        if (viewedPlayers.isEmpty()) {
            dropDespawned();
            waiting = false;
            return;
        }
        var stillWaiting = false;
        var config = BetterModel.config();
        var lod = !forced && config.animationLod();
        // 距離の計算は 1 MC ティックに 1 回。
        var refresh = forced || frame % Tracker.MINECRAFT_TICK_MULTIPLIER == 0;
        tickStamp = tickStamp + 1;
        var version = this.version;
        Long2ReferenceOpenHashMap<AnimationBundler> bundlers = null;
        for (PlayerChannelHandler channel : viewedPlayers) {
            var viewer = viewers.get(channel.uuid());
            if (viewer == null) {
                // 新しい視聴者のスポーンのパケットは、誰にも送られていない間に変わった姿勢を含まないことがある。
                // 何も受け取っていない扱い（UNSEEN）で登録し、今の tick で最新の値を全部送る。
                viewer = new Viewer(Viewer.UNSEEN);
                viewers.put(channel.uuid(), viewer);
                viewer.step = lod ? stepOf(config, channel, location) : 1;
            } else if (refresh) {
                viewer.step = lod ? stepOf(config, channel, location) : 1;
            }
            var sent = viewer.sent;
            if (sent >= version) continue;
            var step = viewer.step;
            // 新しい視聴者は、スポーンの姿勢が古いままの時間を作らないよう、LOD の番を待たず最初の送信を行う。
            var fresh = sent == Viewer.UNSEEN;
            if (!fresh && step > 1 && (frame + phase) % step != 0) {
                stillWaiting = true;
                continue;
            }
            var minDuration = !fresh && step > 1 ? (step + Tracker.MINECRAFT_TICK_MULTIPLIER - 1) / Tracker.MINECRAFT_TICK_MULTIPLIER : 0;
            var key = (sent << 16) | (minDuration & 0xFFFF);
            if (bundlers == null) bundlers = new Long2ReferenceOpenHashMap<>();
            var bundler = bundlers.get(key);
            if (bundler == null) {
                bundler = pipeline.createAnimationBundler();
                pipeline.send(bundler, version, sent, minDuration);
                bundlers.put(key, bundler);
            }
            if (bundler.isNotEmpty()) bundler.send(channel);
            viewer.sent = version;
        }
        waiting = stillWaiting;
        dropDespawned();
    }

    private static int stepOf(@NotNull BetterModelConfig config, @NotNull PlayerChannelHandler channel, @NotNull PlatformLocation location) {
        var playerLocation = channel.player().location();
        if (!playerLocation.world().equals(location.world())) return config.animationLodFarStep();
        var dx = playerLocation.x() - location.x();
        var dy = playerLocation.y() - location.y();
        var dz = playerLocation.z() - location.z();
        var distanceSquared = dx * dx + dy * dy + dz * dz;
        var far = config.animationLodFarDistance();
        if (distanceSquared >= far * far) return config.animationLodFarStep();
        var near = config.animationLodNearDistance();
        if (distanceSquared >= near * near) return config.animationLodNearStep();
        return 1;
    }

    /**
     * 視聴者ごとの今の LOD の段と、最後に送った版を、コピーして返す（観測用。状態は変えない）。
     * <p>
     * 別のスレッドから呼んでよい。tick と同時に呼ぶと、視聴者ごとの値は互いに少しずれた時点のものになりうる。
     * </p>
     *
     * @return 読み取り専用の写し
     * @since 3.7.0
     */
    @NotNull TrackerSyncSnapshot snapshot() {
        var list = new ArrayList<TrackerSyncSnapshot.ViewerSync>(viewers.size());
        viewers.forEach((uuid, viewer) -> list.add(new TrackerSyncSnapshot.ViewerSync(uuid, viewer.step, viewer.sent)));
        return new TrackerSyncSnapshot(version, tickStamp, List.copyOf(list));
    }

    private static final class Viewer {
        /** 何も受け取っていない視聴者の版。どの版（0 以上）よりも小さく、{@code sent << 16} が他の版と重ならない。 */
        private static final long UNSEEN = -1L;
        /** この視聴者へ送った姿勢の最後の版。 */
        private volatile long sent;
        /** 1回の送信にまとめるフレーム数。 */
        private volatile int step = 1;

        private Viewer(long sent) {
            this.sent = sent;
        }
    }

    @RequiredArgsConstructor
    private final class PerPlayerCache {
        private final UUID uuid;
        private final AtomicInteger counter = new AtomicInteger();
        private AnimationBundler bundler = pipeline.createAnimationBundler();

        private @NotNull Optional<PlayerChannelHandler> channel() {
            return Optional.ofNullable(pipeline.channel(uuid));
        }

        public void add(@NotNull Consumer<PlayerChannelHandler> consumer) {
            if (counter.getAndIncrement() == 0) {
                channel().ifPresent(consumer);
            }
        }

        public void remove(@NotNull Consumer<PlayerChannelHandler> consumer) {
            if (counter.decrementAndGet() == 0) {
                perPlayerAnimationCache.remove(uuid);
                // 個別アニメーションの間は全体の送信から外れていたので、戻った時は新しい視聴者として
                // 最新の値を全部受け取らせる（下で送る全体の状態は、誰にも送られていない間の変化を含まないことがある）。
                spawned(uuid);
                channel().ifPresent(channel -> {
                    var bundler = pipeline.createBundler();
                    pipeline.forEach(bone -> bone.forceTransformation(bundler));
                    bundler.send(channel);
                    consumer.accept(channel);
                });
            }
        }

        private void send() {
            if (pipeline.tick(uuid, bundler) && bundler.isNotEmpty()) {
                channel().ifPresent(bundler::send);
                bundler = pipeline.createAnimationBundler();
            }
        }
    }
}
