/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.platform;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.scheduler.ModelTask;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.EntityTrackerRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Represents an entity in the underlying platform.
 * <p>
 * This interface provides access to basic entity properties like UUID and location,
 * as well as integration with the {@link EntityTrackerRegistry}.
 * </p>
 *
 * @since 2.0.0
 */
public interface PlatformEntity extends PlatformRegionHolder {

    /**
     * Returns the unique identifier of the entity.
     *
     * @return the UUID
     * @since 2.0.0
     */
    @NotNull UUID uuid();

    /**
     * Returns the current location of the entity.
     *
     * @return the location
     * @since 2.0.0
     */
    @NotNull PlatformLocation location();

    @Override
    default @Nullable ModelTask task(@NotNull Runnable runnable) {
        return location().task(runnable);
    }

    @Override
    default @Nullable ModelTask taskLater(long delay, @NotNull Runnable runnable) {
        return location().taskLater(delay, runnable);
    }

    /**
     * このエンティティを所有するスレッドでタスクを実行するよう予約する。
     * <p>
     * タスクが実行される前にエンティティが取り除かれた場合は、代わりに {@code retired} を実行する（通知できるプラットフォームのみ）。
     * 既定の実装は {@code retired} を無視し、{@link #task(Runnable)} に委譲する。
     * </p>
     *
     * @param runnable 実行するタスク
     * @param retired エンティティが取り除かれていた場合に、代わりに実行する処理
     * @return 予約したタスク。予約に失敗した場合は null
     * @since 3.7.0
     */
    default @Nullable ModelTask task(@NotNull Runnable runnable, @NotNull Runnable retired) {
        return task(runnable);
    }

    /**
     * このエンティティを所有するスレッドで、遅延後にタスクを実行するよう予約する。
     * <p>
     * タスクが実行される前にエンティティが取り除かれた場合は、代わりに {@code retired} を実行する（通知できるプラットフォームのみ）。
     * 既定の実装は {@code retired} を無視し、{@link #taskLater(long, Runnable)} に委譲する。
     * </p>
     *
     * @param delay 遅延（tick）
     * @param runnable 実行するタスク
     * @param retired エンティティが取り除かれていた場合に、代わりに実行する処理
     * @return 予約したタスク。予約に失敗した場合は null
     * @since 3.7.0
     */
    default @Nullable ModelTask taskLater(long delay, @NotNull Runnable runnable, @NotNull Runnable retired) {
        return taskLater(delay, runnable);
    }

    /**
     * 現在のスレッドがこのエンティティを所有しており、直接読み書きしてよいかを調べる。
     * <p>
     * Folia では、すべての tick スレッドではなく、このエンティティを所有するリージョンのスレッドでのみ true になる。
     * 既定の実装は、tick スレッドであれば true を返す。
     * </p>
     *
     * @return 現在のスレッドがこのエンティティにアクセスしてよい場合は true
     * @since 3.7.0
     */
    default boolean isOwnedByCurrentThread() {
        return BetterModel.platform().adapter().isTickThread();
    }

    /**
     * Retrieves the tracker registry associated with this entity.
     *
     * @return an optional containing the registry if it exists
     * @since 2.0.0
     */
    default @NotNull Optional<EntityTrackerRegistry> registry() {
        return BetterModel.registry(uuid());
    }

    /**
     * Retrieves a specific tracker by name from this entity's registry.
     *
     * @param name the name of the tracker
     * @return an optional containing the tracker if found
     * @since 2.0.0
     */
    default @NotNull Optional<EntityTracker> tracker(@NotNull String name) {
        return registry().map(registry -> registry.tracker(name));
    }
}
