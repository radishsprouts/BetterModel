/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.tracker;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 1つの周期タスクで tick する要素の集まり。
 * <p>
 * 担当する要素がある間だけ周期タスクを動かす。最初の要素が入った時に始め、最後の要素が抜けた時に止める。
 * 登録・解除は同じロックで直列化するので、登録した要素が tick されない・タスクが二重に動く、は起きない。
 * </p>
 * <p>Example:</p>
 * <pre>{@code
 * TickShard<Tracker> shard = new TickShard<>(executor, 0L, 25_000L, Tracker::tick);
 * shard.add(tracker.id(), tracker);
 * shard.remove(tracker.id());
 * }</pre>
 *
 * @param <T> 要素の型
 * @since 3.7.0
 */
final class TickShard<T> {

    private final ScheduledExecutorService executor;
    private final long initialDelayMicros;
    private final long periodMicros;
    private final Consumer<T> handler;
    private final Map<Long, T> items = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    // lock の中だけで読み書きする。
    private ScheduledFuture<?> future;
    private boolean closed;

    private volatile long lastTick = 0L;

    /**
     * @param executor 周期タスクを動かす実行器
     * @param initialDelayMicros 最初の実行までの遅れ（マイクロ秒。シャード同士が同時に動かないようずらすのに使う）
     * @param periodMicros 周期（マイクロ秒）
     * @param handler 要素1つ分の処理。例外は呼び出し側で扱う
     * @since 3.7.0
     */
    TickShard(@NotNull ScheduledExecutorService executor, long initialDelayMicros, long periodMicros, @NotNull Consumer<T> handler) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.initialDelayMicros = initialDelayMicros;
        this.periodMicros = periodMicros;
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * 要素を登録する。周期タスクが止まっていれば始める。
     *
     * @param id 要素の識別子
     * @param item 要素
     * @since 3.7.0
     */
    void add(long id, @NotNull T item) {
        synchronized (lock) {
            if (closed) return;
            items.put(id, item);
            if (future != null) return;
            lastTick = 0L;
            future = executor.scheduleAtFixedRate(this::tickAll, initialDelayMicros, periodMicros, TimeUnit.MICROSECONDS);
        }
    }

    /**
     * 要素を解除する。要素が無くなれば周期タスクを止める。
     *
     * @param id 要素の識別子
     * @since 3.7.0
     */
    void remove(long id) {
        synchronized (lock) {
            items.remove(id);
            if (items.isEmpty()) cancel();
        }
    }

    /**
     * 周期タスクを止め、以後の登録を受け付けない。
     *
     * @since 3.7.0
     */
    void close() {
        synchronized (lock) {
            closed = true;
            items.clear();
            cancel();
        }
    }

    /**
     * @return 周期タスクが動いているか
     * @since 3.7.0
     */
    boolean running() {
        synchronized (lock) {
            return future != null;
        }
    }

    /**
     * @return 登録している要素の数
     * @since 3.7.0
     */
    int size() {
        return items.size();
    }

    private void cancel() {
        var f = future;
        if (f == null) return;
        future = null;
        f.cancel(false);
    }

    private void tickAll() {
        var now = System.nanoTime();
        // scheduleAtFixedRate は遅れた後に連続で追いつき実行する。その実行は捨てる。
        // 追いつくとアニメーションが早送りになり、負荷もさらに跳ね上がる。
        var last = lastTick;
        if (last != 0L && now - last < periodMicros * 500L) return;
        lastTick = now;
        for (T item : items.values()) handler.accept(item);
    }
}
