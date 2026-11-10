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
     * Schedules a task on the thread that owns this entity.
     * <p>
     * If the entity is removed before the task runs, {@code retired} runs instead (on platforms that report it).
     * The default implementation ignores {@code retired} and delegates to {@link #task(Runnable)}.
     * </p>
     *
     * @param runnable the task to run
     * @param retired the task to run instead if the entity has been removed
     * @return the scheduled task, or null if scheduling failed
     * @since 3.7.0
     */
    default @Nullable ModelTask task(@NotNull Runnable runnable, @NotNull Runnable retired) {
        return task(runnable);
    }

    /**
     * Schedules a delayed task on the thread that owns this entity.
     * <p>
     * If the entity is removed before the task runs, {@code retired} runs instead (on platforms that report it).
     * The default implementation ignores {@code retired} and delegates to {@link #taskLater(long, Runnable)}.
     * </p>
     *
     * @param delay the delay in ticks
     * @param runnable the task to run
     * @param retired the task to run instead if the entity has been removed
     * @return the scheduled task, or null if scheduling failed
     * @since 3.7.0
     */
    default @Nullable ModelTask taskLater(long delay, @NotNull Runnable runnable, @NotNull Runnable retired) {
        return taskLater(delay, runnable);
    }

    /**
     * Checks whether the current thread owns this entity, so that it can be read and modified directly.
     * <p>
     * On Folia this is true only on the region thread that owns the entity, not on every tick thread.
     * The default implementation is true on a tick thread.
     * </p>
     *
     * @return true if the current thread may access this entity
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
