/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.tracker;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.profile.ModelProfileInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Represents the persistent data state of a tracker.
 * <p>
 * This record holds configuration and state information such as model ID, scaling, rotation,
 * and visibility options, which can be serialized to JSON.
 * </p>
 *
 * @param id the model ID
 * @param scaler the model scaler
 * @param rotator the model rotator
 * @param modifier the tracker modifier
 * @param bodyRotator the body rotation data
 * @param hideOption the entity hide options
 * @param markForSpawn the set of player UUIDs marked for spawning
 * @param profile the profile (skin owner) the model was attached with, or null if none was specified
 * @param type the renderer type the model was saved with (GENERAL / PLAYER), or null for old data without it
 * @since 1.15.2
 */
public record TrackerData(
    @NotNull String id,
    @Nullable ModelScaler scaler,
    @Nullable ModelRotator rotator,
    @NotNull TrackerModifier modifier,
    @Nullable @SerializedName("body-rotator") EntityBodyRotator.RotatorData bodyRotator,
    @Nullable @SerializedName("hide-option") EntityHideOption hideOption,
    @Nullable @SerializedName("mark-for-spawn") Set<UUID> markForSpawn,
    @Nullable ModelProfileInfo profile,
    @Nullable ModelRenderer.Type type
) {
    /**
     * Creates tracker data without a renderer type.
     * <p>
     * モデルの型の項目が無かった頃の形で作る。付け直す時は GENERAL、無ければ PLAYER の順で引く。
     * </p>
     *
     * @param id the model ID
     * @param scaler the model scaler
     * @param rotator the model rotator
     * @param modifier the tracker modifier
     * @param bodyRotator the body rotation data
     * @param hideOption the entity hide options
     * @param markForSpawn the set of player UUIDs marked for spawning
     * @param profile the profile (skin owner), or null
     * @since 3.7.0
     */
    public TrackerData(
        @NotNull String id,
        @Nullable ModelScaler scaler,
        @Nullable ModelRotator rotator,
        @NotNull TrackerModifier modifier,
        @Nullable EntityBodyRotator.RotatorData bodyRotator,
        @Nullable EntityHideOption hideOption,
        @Nullable Set<UUID> markForSpawn,
        @Nullable ModelProfileInfo profile
    ) {
        this(id, scaler, rotator, modifier, bodyRotator, hideOption, markForSpawn, profile, null);
    }

    /**
     * Creates tracker data without a profile.
     * <p>
     * 皮膚の項目が無かった頃の形で作る。
     * </p>
     *
     * @param id the model ID
     * @param scaler the model scaler
     * @param rotator the model rotator
     * @param modifier the tracker modifier
     * @param bodyRotator the body rotation data
     * @param hideOption the entity hide options
     * @param markForSpawn the set of player UUIDs marked for spawning
     * @since 3.7.0
     */
    public TrackerData(
        @NotNull String id,
        @Nullable ModelScaler scaler,
        @Nullable ModelRotator rotator,
        @NotNull TrackerModifier modifier,
        @Nullable EntityBodyRotator.RotatorData bodyRotator,
        @Nullable EntityHideOption hideOption,
        @Nullable Set<UUID> markForSpawn
    ) {
        this(id, scaler, rotator, modifier, bodyRotator, hideOption, markForSpawn, null, null);
    }

    /**
     * The GSON parser for serializing and deserializing tracker data.
     * @since 1.15.2
     */
    public static final Gson PARSER = new GsonBuilder()
        .registerTypeAdapter(ModelScaler.class, (JsonDeserializer<ModelScaler>) (json, _, _) -> json.isJsonObject() ? ModelScaler.deserialize(json.getAsJsonObject()) : ModelScaler.defaultScaler())
        .registerTypeAdapter(ModelScaler.class, (JsonSerializer<ModelScaler>) (src, _, _) -> src.serialize())
        .registerTypeAdapter(ModelRotator.class, (JsonDeserializer<ModelRotator>) (json, _, _) -> json.isJsonObject() ? ModelRotator.deserialize(json.getAsJsonObject()) : ModelRotator.YAW)
        .registerTypeAdapter(ModelRotator.class, (JsonSerializer<ModelRotator>) (src, _, _) -> src.serialize())
        .registerTypeAdapter(EntityHideOption.class, (JsonDeserializer<EntityHideOption>) (json, _, _) -> json.isJsonArray() ? EntityHideOption.deserialize(json.getAsJsonArray()) : EntityHideOption.DEFAULT)
        .registerTypeAdapter(EntityHideOption.class, (JsonSerializer<EntityHideOption>) (src, _, _) -> src.serialize())
        .registerTypeAdapter(UUID.class, (JsonDeserializer<UUID>) (json, _, _) -> UUID.fromString(json.getAsString()))
        .registerTypeAdapter(UUID.class, (JsonSerializer<UUID>) (src, _, _) -> new JsonPrimitive(src.toString()))
        .create();

    /**
     * Applies this data to an existing entity tracker.
     *
     * @param tracker the target tracker
     * @since 1.15.2
     */
    public void applyAs(@NotNull EntityTracker tracker) {
        tracker.markPlayerForSpawn(markForSpawn());
        tracker.hideOption(hideOption());
        tracker.scaler(scaler());
        tracker.rotator(rotator());
        tracker.bodyRotator().setValue(bodyRotator());
    }

    /**
     * Serializes this data to a JSON element.
     *
     * @return the JSON element
     * @since 1.15.2
     */
    public @NotNull JsonElement serialize() {
        return PARSER.toJsonTree(this);
    }

    /**
     * Deserializes tracker data from a JSON element.
     *
     * @param element the JSON element
     * @return the tracker data
     * @since 1.15.2
     */
    public static @NotNull TrackerData deserialize(@NotNull JsonElement element) {
        return element.isJsonPrimitive() ? new TrackerData(
            element.getAsString(),
            ModelScaler.entity(),
            null,
            TrackerModifier.DEFAULT,
            EntityBodyRotator.defaultData(),
            null,
            null,
            null,
            null
        ) : PARSER.fromJson(element, TrackerData.class);
    }

    /**
     * Returns the profile to restore the model with.
     * <p>
     * 皮膚を持たないデータ（古い形式を含む）と、皮膚が不明（{@link ModelProfileInfo#UNKNOWN}）のデータでは null を返す。
     * null の時は、既定の皮膚（エンティティ自身の皮膚、または既定の皮膚）で付け直す。
     * </p>
     *
     * <pre>{@code
     * var profile = data.restorableProfile();
     * if (profile != null) model.create(entity, supplier.supply(profile), data.modifier());
     * }</pre>
     *
     * @return the profile info, or null if the default skin should be used
     * @since 3.7.0
     */
    public @Nullable ModelProfileInfo restorableProfile() {
        return profile == null || profile.equals(ModelProfileInfo.UNKNOWN) ? null : profile;
    }

    /**
     * 保存した型に合った表からモデルを引く。
     * <p>
     * 型があればその表だけを引く（同じ名前が両方の表にあっても取り違えない）。
     * 型が無い古い形式では、GENERAL の表で引き、無ければ PLAYER の表で引く。
     * </p>
     *
     * <pre>{@code
     * var model = data.resolve(BetterModel::modelOrNull, BetterModel::limbOrNull);
     * }</pre>
     *
     * @param general GENERAL 型の表から名前で引く関数
     * @param player PLAYER 型の表から名前で引く関数
     * @param <T> the renderer type
     * @return the found model, or null
     * @since 3.7.0
     */
    public <T> @Nullable T resolve(@NotNull Function<String, T> general, @NotNull Function<String, T> player) {
        if (type == ModelRenderer.Type.PLAYER) return player.apply(id);
        if (type == ModelRenderer.Type.GENERAL) return general.apply(id);
        var found = general.apply(id);
        return found != null ? found : player.apply(id);
    }

    /**
     * Returns the model scaler, or a default entity scaler if not specified.
     *
     * @return the model scaler
     * @since 1.15.2
     */
    @Override
    public @NotNull ModelScaler scaler() {
        return scaler != null ? scaler : ModelScaler.entity();
    }

    /**
     * Returns the model rotator, or a default YAW rotator if not specified.
     *
     * @return the model rotator
     * @since 1.15.2
     */
    @Override
    public @NotNull ModelRotator rotator() {
        return rotator != null ? rotator : ModelRotator.YAW;
    }

    /**
     * Returns the entity hide option, or the default hide option if not specified.
     *
     * @return the entity hide option
     * @since 1.15.2
     */
    @Override
    public @NotNull EntityHideOption hideOption() {
        return hideOption != null ? hideOption : EntityHideOption.DEFAULT;
    }

    /**
     * Returns the set of player UUIDs marked for spawning, or an empty set if not specified.
     *
     * @return the set of player UUIDs marked for spawning
     * @since 1.15.2
     */
    @Override
    public @NotNull Set<UUID> markForSpawn() {
        return markForSpawn != null ? markForSpawn : Collections.emptySet();
    }

    /**
     * Returns the body rotation data, or default body rotation data if not specified.
     *
     * @return the body rotation data
     * @since 1.15.2
     */
    @Override
    public @NotNull EntityBodyRotator.RotatorData bodyRotator() {
        return bodyRotator != null ? bodyRotator : EntityBodyRotator.defaultData();
    }

    /**
     * Serializes this TrackerData object to a JSON string.
     *
     * @return a JSON string representation of this object
     * @since 1.15.2
     */
    @NotNull
    @Override
    public String toString() {
        return serialize().toString();
    }
}
