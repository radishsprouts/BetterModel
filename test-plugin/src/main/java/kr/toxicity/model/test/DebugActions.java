/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.test;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bone.RenderedBone;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.entity.BaseEntity;
import kr.toxicity.model.api.event.hitbox.HitBoxDamagedEvent;
import kr.toxicity.model.api.event.hitbox.HitBoxInteractAtEvent;
import kr.toxicity.model.api.nms.ModelNametag;
import kr.toxicity.model.api.profile.ModelProfile;
import kr.toxicity.model.api.profile.ModelProfileInfo;
import kr.toxicity.model.api.profile.ModelProfileSkin;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.TrackerModifier;
import kr.toxicity.model.api.util.function.BonePredicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * トラッカーを操作する検証の入口（V2 アニメーション、V5 ネームタグ、V9 皮膚、V10 ヒットボックス）。
 * <p>
 * ここのメソッドはすべて、対象エンティティの EntityScheduler の上で呼ぶこと。
 * 公開された API だけを使い、本体の挙動は変えない。
 * </p>
 */
final class DebugActions implements Listener {

    private final BetterModelTest test;
    private final Map<kr.toxicity.model.api.tracker.Tracker, ModelNametag> nametags = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<kr.toxicity.model.api.tracker.Tracker, HitBoxProbe> probes = Collections.synchronizedMap(new WeakHashMap<>());
    // 本体の取り消し後に、Bukkit 側のイベントとして実際に通った回数（エンティティの UUID ごと）。
    private final Map<UUID, BukkitSeen> bukkitSeen = new ConcurrentHashMap<>();

    DebugActions(@NotNull BetterModelTest test) {
        this.test = test;
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, test);
    }

    void end() {
        HandlerList.unregisterAll(this);
    }

    //--- V2 アニメーション ---

    /**
     * モデルのアニメーション名の一覧と長さ・既定のループ方式を出す。
     */
    void animList(@NotNull DebugReport out, @NotNull EntityTracker tracker) {
        var animations = tracker.renderer().animations();
        out.line("アニメーション " + animations.size() + " 件 (model=" + tracker.name() + ")");
        animations.forEach((name, animation) -> out.line("  " + name + " length=" + animation.length() + "s 既定=" + animation.loop()));
    }

    /**
     * アニメーションを再生する。直後・次の tracker tick・3 tick 後の状態を出す。
     *
     * @param typeName once / loop / hold。null なら既定
     * @param speed 速度。null なら既定
     */
    void animPlay(@NotNull DebugReport out, @NotNull Entity entity, @NotNull EntityTracker tracker, @NotNull String name, @Nullable String typeName, @Nullable Float speed) {
        var started = System.nanoTime();
        var modifier = modifier(typeName, speed);
        out.line("再生 呼出前: 走行中=" + DebugObserver.running(tracker));
        var result = tracker.animate(name, modifier, () -> {
            out.line("終了コールバック: 経過=" + millis(started) + "ms 走行中=" + DebugObserver.running(tracker));
            poseLines(out, tracker, 3);
        });
        out.line("animate(" + name + ", " + (typeName == null ? "既定" : typeName) + ") 戻り値=" + result);
        followUp(out, entity, tracker, "再生", started);
    }

    /**
     * アニメーションを止める。
     */
    void animStop(@NotNull DebugReport out, @NotNull Entity entity, @NotNull EntityTracker tracker, @NotNull String name) {
        var started = System.nanoTime();
        out.line("停止 呼出前: 走行中=" + DebugObserver.running(tracker));
        var result = tracker.stopAnimation(name);
        out.line("stopAnimation(" + name + ") 戻り値=" + result);
        followUp(out, entity, tracker, "停止", started);
    }

    /**
     * 走行中のアニメーションを別のアニメーションへ差し替える。
     */
    void animReplace(@NotNull DebugReport out, @NotNull Entity entity, @NotNull EntityTracker tracker, @NotNull String target, @NotNull String name, @Nullable String typeName) {
        var started = System.nanoTime();
        out.line("差し替え 呼出前: 走行中=" + DebugObserver.running(tracker));
        var result = tracker.replace(target, name, modifier(typeName, null));
        out.line("replace(" + target + " -> " + name + ") 戻り値=" + result);
        followUp(out, entity, tracker, "差し替え", started);
    }

    /**
     * 骨の姿勢を出す。
     */
    void animPose(@NotNull DebugReport out, @NotNull EntityTracker tracker, int limit) {
        out.line("姿勢 走行中=" + DebugObserver.running(tracker));
        poseLines(out, tracker, limit);
    }

    private void followUp(@NotNull DebugReport out, @NotNull Entity entity, @NotNull EntityTracker tracker, @NotNull String label, long started) {
        out.line(label + " 直後(+" + millis(started) + "ms): 走行中=" + DebugObserver.running(tracker));
        // 次の tracker tick の先頭で実行される。視聴者が 0 人でトラッカーが止まっていると、動き出すまで出ない。
        tracker.task(() -> out.line(label + " 次のtracker tick(+" + millis(started) + "ms): 走行中=" + DebugObserver.running(tracker)));
        entity.getScheduler().runDelayed(test, _ -> {
            out.line(label + " 3tick後(+" + millis(started) + "ms): 走行中=" + DebugObserver.running(tracker));
            poseLines(out, tracker, 3);
        }, () -> out.fail(label + " 3tick後の確認は、対象が消えたため行えなかった"), 3);
    }

    private static void poseLines(@NotNull DebugReport out, @NotNull EntityTracker tracker, int limit) {
        var count = 0;
        for (RenderedBone bone : tracker.bones()) {
            if (count++ >= limit) break;
            out.line("  " + DebugObserver.bone(bone));
        }
    }

    private static @NotNull AnimationModifier modifier(@Nullable String typeName, @Nullable Float speed) {
        var builder = AnimationModifier.builder();
        if (typeName != null) builder.type(switch (typeName) {
            case "once" -> AnimationIterator.Type.PLAY_ONCE;
            case "loop" -> AnimationIterator.Type.LOOP;
            case "hold" -> AnimationIterator.Type.HOLD_ON_LAST;
            default -> throw new IllegalArgumentException("種類は once / loop / hold のどれか: " + typeName);
        });
        if (speed != null) builder.speed(speed);
        return builder.build();
    }

    private static long millis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    //--- V5 ネームタグ ---

    /**
     * エンティティのカスタム名を Bukkit API で変える。
     * <p>
     * 本体は名前の変更を tick ごとに見て、自動で作ったネームタグへ反映する。
     * 変えた名前がモデルのネームタグへ届くかを確かめる用で、反映の結果は {@link #nametagShow} で読む。
     * </p>
     *
     * @param text 新しい名前。null なら消す
     */
    void nameEntity(@NotNull DebugReport out, @NotNull Entity entity, @NotNull EntityTracker tracker, @Nullable String text) {
        var before = entity.customName();
        entity.customName(text == null ? null : Component.text(text));
        entity.setCustomNameVisible(text != null);
        out.line("customName を変更: " + before + " -> " + entity.customName());
        out.line("BaseEntity.customName()=" + describe(tracker.sourceEntity().customName()) + "（ネームタグへの反映は次の tick 以降。tag show で確かめる）");
        viewerLine(out, tracker);
    }

    /**
     * 骨が持つネームタグの今の文字を、エンティティの名前と並べて出す。
     * <p>
     * 本体が自動で作ったものと、{@link #nametagAttach} で付けたものを区別して出す。
     * </p>
     */
    void nametagShow(@NotNull DebugReport out, @NotNull EntityTracker tracker) {
        var attached = nametags.get(tracker);
        var found = 0;
        for (RenderedBone bone : tracker.bones()) {
            var tag = bone.getNametag();
            if (tag == null) continue;
            found++;
            out.line("骨 " + bone.name().name() + " ネームタグの文字=" + describe(tag.component())
                + (tag == attached ? "（tag attach で付けたもの）" : "（本体が自動で作ったもの）"));
        }
        if (found == 0) out.line("ネームタグを持つ骨が無い");
        out.line("エンティティの名前(BaseEntity.customName)=" + describe(tracker.sourceEntity().customName()));
    }

    private static @NotNull String describe(@Nullable Component component) {
        if (component == null) return "なし(null)";
        return component instanceof TextComponent text ? "「" + text.content() + "」" : component.toString();
    }

    /**
     * 骨に、このテスト用のネームタグを足す。足した後は {@link #nametagText} で文字を変えられる。
     * <p>
     * 本体が既にネームタグを作った骨には足せない。骨を指定しない時は、足せた最初の骨に付ける。
     * </p>
     */
    void nametagAttach(@NotNull DebugReport out, @NotNull EntityTracker tracker, @NotNull String text, @Nullable String boneName) {
        var candidates = new java.util.ArrayList<RenderedBone>();
        if (boneName != null) {
            var bone = tracker.bone(boneName);
            if (bone == null) {
                out.fail("骨が無い: " + boneName);
                return;
            }
            candidates.add(bone);
        } else candidates.addAll(tracker.bones());
        var component = Component.text(text);
        for (RenderedBone bone : candidates) {
            var holder = new ModelNametag[1];
            var created = tracker.createNametag(BonePredicate.from(b -> b == bone), (_, tag) -> {
                tag.alwaysVisible(true);
                tag.component(component);
                holder[0] = tag;
            });
            if (created && holder[0] != null) {
                nametags.put(tracker, holder[0]);
                out.line("ネームタグを付けた: 骨=" + bone.name().name() + " 文字=" + text);
                viewerLine(out, tracker);
                return;
            }
        }
        out.fail("ネームタグを付けられる骨が無かった（試した骨 " + candidates.size() + " 件。本体が作成済みの骨には足せない）");
    }

    /**
     * 足したネームタグの文字を変える。次の tick の更新で視聴者へ差分が送られる。
     */
    void nametagText(@NotNull DebugReport out, @NotNull EntityTracker tracker, @NotNull String text) {
        var tag = nametags.get(tracker);
        if (tag == null) {
            out.fail("このトラッカーに tag attach で付けたネームタグが無い");
            return;
        }
        tag.component(Component.text(text));
        out.line("ModelNametag.component を変更: " + text + "（次の tick に送信）");
        viewerLine(out, tracker);
    }

    /**
     * 足したネームタグを視聴者から消す。
     */
    void nametagRemove(@NotNull DebugReport out, @NotNull EntityTracker tracker) {
        var tag = nametags.remove(tracker);
        if (tag == null) {
            out.fail("このトラッカーに tag attach で付けたネームタグが無い");
            return;
        }
        var bundler = BetterModel.nms().createBundler(1);
        tag.remove(bundler);
        tracker.registry().viewedPlayer().forEach(channel -> bundler.send(channel.player()));
        out.line("ネームタグを消した");
    }

    private static void viewerLine(@NotNull DebugReport out, @NotNull EntityTracker tracker) {
        var names = new java.util.ArrayList<String>();
        tracker.registry().viewedPlayer().forEach(channel -> {
            if (tracker.isSpawned(channel.uuid())) names.add(channel.player().name());
        });
        out.line("届け先の候補（モデルを見ているプレイヤー）: " + (names.isEmpty() ? "なし" : String.join(", ", names)));
    }

    //--- V9 皮膚 ---

    /**
     * 皮膚を使うモデルを、指定した名前の皮膚で対象に付ける。
     *
     * @param modelName モデル名
     * @param skin 皮膚の名前。fail は取得に失敗する名前、broken は complete が例外で失敗する模擬
     */
    void skin(@NotNull DebugReport out, @NotNull Entity entity, @NotNull String modelName, @NotNull String skin) {
        var renderer = BetterModel.limb(modelName).or(() -> BetterModel.model(modelName)).orElse(null);
        if (renderer == null) {
            out.fail("モデルが無い: " + modelName + " (limb=" + BetterModel.limbKeys() + " model=" + BetterModel.modelKeys() + ")");
            return;
        }
        ModelProfile.Uncompleted base;
        switch (skin) {
            case "broken" -> {
                var info = new ModelProfileInfo(UUID.randomUUID(), "bmtest_broken");
                base = new ModelProfile.Uncompleted() {
                    @Override
                    public @NotNull ModelProfileInfo info() {
                        return info;
                    }

                    @Override
                    public @NotNull CompletableFuture<ModelProfile> complete() {
                        return CompletableFuture.failedFuture(new IllegalStateException("bmtest: 皮膚の取得失敗の模擬"));
                    }
                };
            }
            case "fail" -> base = BetterModel.platform().profileManager().supplier()
                .supply(new ModelProfileInfo(UUID.randomUUID(), "bmtest_" + UUID.randomUUID().toString().substring(0, 8)));
            default -> {
                var cached = Bukkit.getOfflinePlayerIfCached(skin);
                var id = cached != null ? cached.getUniqueId() : UUID.nameUUIDFromBytes(("OfflinePlayer:" + skin).getBytes(StandardCharsets.UTF_8));
                base = BetterModel.platform().profileManager().supplier().supply(new ModelProfileInfo(id, skin));
            }
        }
        var info = base.info();
        out.line("皮膚の取得を開始: 名前=" + info.name() + " uuid=" + info.id() + " 方式=" + skin);
        var started = System.nanoTime();
        var logged = new LoggedProfile(base, out, started);
        var platformEntity = BukkitAdapter.adapt(entity);
        // 既に同じモデルが付いている場合は、皮膚を変えるため外してから付け直す。
        BetterModel.registry(platformEntity).ifPresent(registry -> registry.remove(renderer.name()));
        var tracker = renderer.getOrCreate(BaseEntity.of(platformEntity), logged, TrackerModifier.DEFAULT);
        out.line("トラッカーを作成: 閉じた=" + tracker.isClosed() + " 視聴者数=" + tracker.playerCount());
        entity.getScheduler().runDelayed(test, _ -> out.line("皮膚の付与から40tick後(+" + millis(started) + "ms): 閉じた=" + tracker.isClosed()
            + " 視聴者数=" + tracker.playerCount() + " 対象は有効=" + entity.isValid()
            + " レジストリにトラッカー=" + (BetterModel.registryOrNull(platformEntity) != null && BetterModel.registryOrNull(platformEntity).tracker(renderer.name()) != null)),
            () -> out.fail("皮膚の付与から40tick後の確認前に、対象が消えた（例外で消えたのか、通常の除去かはログの例外の有無で見る）"), 40);
    }

    /**
     * 皮膚の取得の成否と所要時間をログに出す。取得自体は包んだ先のものをそのまま使う。
     */
    private record LoggedProfile(@NotNull ModelProfile.Uncompleted delegate, @NotNull DebugReport out, long started) implements ModelProfile.Uncompleted {
        @Override
        public @NotNull ModelProfileInfo info() {
            return delegate.info();
        }

        @Override
        public @NotNull CompletableFuture<ModelProfile> complete() {
            return delegate.complete().whenComplete((profile, throwable) -> {
                if (throwable != null) out.fail("皮膚の取得が失敗(+" + millis(started) + "ms): " + throwable);
                else out.line("皮膚の取得が完了(+" + millis(started) + "ms): 既定の皮膚=" + (profile.skin().equals(ModelProfileSkin.EMPTY))
                    + " slim=" + profile.skin().slim());
            });
        }

        @Override
        public @NotNull ModelProfile fallback() {
            out.line("fallback（既定の皮膚）が使われた");
            return delegate.fallback();
        }
    }

    //--- V10 ヒットボックス ---

    /**
     * ヒットボックスへの攻撃・右クリックを取り消すリスナーを付ける／外す（付けたまま、取り消すかをフラグで切り替える）。
     */
    void hitboxCancel(@NotNull DebugReport out, @NotNull Entity entity, @NotNull EntityTracker tracker, boolean cancel) {
        HitBoxProbe probe;
        var created = false;
        synchronized (probes) {
            probe = probes.get(tracker);
            if (probe == null) {
                probe = new HitBoxProbe();
                probes.put(tracker, probe);
                created = true;
            }
        }
        probe.cancel = cancel;
        bukkitSeen.computeIfAbsent(entity.getUniqueId(), _ -> new BukkitSeen());
        if (created) {
            var target = probe;
            tracker.listenHitBox(HitBoxDamagedEvent.class, event -> {
                var count = target.damaged.incrementAndGet();
                var cancelled = target.cancel;
                if (cancelled) {
                    event.setCancelled(true);
                    target.cancelledCount.incrementAndGet();
                }
                test.getLogger().info("[bmtest] HitBoxDamagedEvent #" + count + " damage=" + event.getDamage() + " 取り消し=" + cancelled);
            });
            tracker.listenHitBox(HitBoxInteractAtEvent.class, event -> {
                var count = target.interacted.incrementAndGet();
                var cancelled = target.cancel;
                if (cancelled) {
                    event.setCancelled(true);
                    target.cancelledCount.incrementAndGet();
                }
                test.getLogger().info("[bmtest] HitBoxInteractAtEvent #" + count + " who=" + event.getWho().name() + " 取り消し=" + cancelled);
            });
        }
        out.line("ヒットボックスのリスナー: 取り消し=" + cancel + (created ? "（新しく付けた）" : "（付いていたものの設定を変えた）")
            + " ヒットボックス数=" + tracker.registry().hitBoxes().size());
        hitboxStatus(out, entity, tracker);
    }

    /**
     * ヒットボックスのイベントの受信数と、Bukkit 側のイベント・体力を出す。
     */
    void hitboxStatus(@NotNull DebugReport out, @NotNull Entity entity, @NotNull EntityTracker tracker) {
        var probe = probes.get(tracker);
        var seen = bukkitSeen.get(entity.getUniqueId());
        out.line("ヒットボックス数=" + tracker.registry().hitBoxes().size()
            + " リスナー=" + (probe == null ? "未設定" : "取り消し=" + probe.cancel
            + " 受信(攻撃)=" + probe.damaged.get() + " 受信(右クリック)=" + probe.interacted.get() + " 取り消した=" + probe.cancelledCount.get()));
        out.line("Bukkit 側で観測した回数: ダメージ=" + (seen == null ? "未観測" : seen.damage.get())
            + " 右クリック=" + (seen == null ? "未観測" : seen.interact.get())
            + (entity instanceof LivingEntity living ? " 体力=" + DebugObserver.f(living.getHealth()) : ""));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void damage(@NotNull EntityDamageEvent event) {
        var seen = bukkitSeen.get(event.getEntity().getUniqueId());
        if (seen == null) return;
        var count = seen.damage.incrementAndGet();
        test.getLogger().info("[bmtest] Bukkit EntityDamageEvent #" + count + " cause=" + event.getCause() + " damage=" + event.getFinalDamage() + " 取り消し済み=" + event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void interact(@NotNull PlayerInteractEntityEvent event) {
        var seen = bukkitSeen.get(event.getRightClicked().getUniqueId());
        if (seen == null) return;
        var count = seen.interact.incrementAndGet();
        test.getLogger().info("[bmtest] Bukkit PlayerInteractEntityEvent #" + count + " who=" + event.getPlayer().getName() + " 取り消し済み=" + event.isCancelled());
    }

    private static final class HitBoxProbe {
        private volatile boolean cancel;
        private final AtomicInteger damaged = new AtomicInteger();
        private final AtomicInteger interacted = new AtomicInteger();
        private final AtomicInteger cancelledCount = new AtomicInteger();
    }

    private static final class BukkitSeen {
        private final AtomicInteger damage = new AtomicInteger();
        private final AtomicInteger interact = new AtomicInteger();
    }
}
