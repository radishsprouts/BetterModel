/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.bukkit.scheduler;

import kr.toxicity.model.api.scheduler.ModelScheduler;
import kr.toxicity.model.api.scheduler.ModelTask;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a Bukkit-specific scheduler for model tasks.
 * <p>
 * This interface extends {@link ModelScheduler} to provide methods for scheduling tasks
 * that are synchronized with specific locations (e.g., for Folia compatibility).
 * </p>
 *
 * @since 2.0.0
 */
public interface BukkitModelScheduler extends ModelScheduler {

    /**
     * Schedules a task to run on the next tick, synchronized with the given location.
     *
     * @param location the location to synchronize with
     * @param runnable the task to run
     * @return the scheduled task, or null if scheduling failed
     * @since 2.0.0
     */
    @Nullable ModelTask task(@NotNull Location location, @NotNull Runnable runnable);

    /**
     * Schedules a task to run after a delay, synchronized with the given location.
     *
     * @param location the location to synchronize with
     * @param delay the delay in ticks
     * @param runnable the task to run
     * @return the scheduled task, or null if scheduling failed
     * @since 2.0.0
     */
    @Nullable ModelTask taskLater(@NotNull Location location, long delay, @NotNull Runnable runnable);

    /**
     * Schedules a task to run on the next tick on the thread that owns the given entity.
     * <p>
     * On Folia this uses the entity scheduler, so the task follows the entity when it moves to another region.
     * </p>
     *
     * @param entity the entity to synchronize with
     * @param runnable the task to run
     * @param retired the task to run instead if the entity is removed before the task could run
     * @return the scheduled task, or null if scheduling failed
     * @since 3.7.0
     */
    @Nullable ModelTask task(@NotNull Entity entity, @NotNull Runnable runnable, @NotNull Runnable retired);

    /**
     * Schedules a task to run after a delay on the thread that owns the given entity.
     * <p>
     * On Folia this uses the entity scheduler, so the task follows the entity when it moves to another region.
     * </p>
     *
     * @param entity the entity to synchronize with
     * @param delay the delay in ticks
     * @param runnable the task to run
     * @param retired the task to run instead if the entity is removed before the task could run
     * @return the scheduled task, or null if scheduling failed
     * @since 3.7.0
     */
    @Nullable ModelTask taskLater(@NotNull Entity entity, long delay, @NotNull Runnable runnable, @NotNull Runnable retired);
}
