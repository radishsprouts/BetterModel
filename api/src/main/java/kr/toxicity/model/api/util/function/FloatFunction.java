/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.util.function;

import kr.toxicity.model.api.util.MathUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Float function
 * @param <T> type
 */
@FunctionalInterface
public interface FloatFunction<T> {

    /**
     * Applies float
     * @param value float value
     * @return applied value
     */
    @NotNull T apply(float value);

    /**
     * Creates constant function by given value
     * @param t value
     * @return constant function
     * @param <T> type
     */
    static <T> @NotNull FloatConstantFunction<T> of(@NotNull T t) {
        return new FloatConstantFunction<>(Objects.requireNonNull(t));
    }

    /**
     * Maps this function to new type
     * @param mapper mapper
     * @return mapped function
     * @param <R> return type
     */
    default <R> @NotNull FloatFunction<R> map(@NotNull Function<T, R> mapper) {
        return f -> mapper.apply(apply(f));
    }

    /**
     * この関数の結果を許容誤差の区間ごとに覚えさせます。
     * <p>
     * 覚えた表は、返した関数が到達できる間だけ生きます。今は {@code ModelKeyframe#point} が作る
     * {@code VectorPoint} が持ち、モデルの読み込み中（{@code AnimationGenerator#generate}）にだけ使われ、
     * その後は焼き込み済みの {@code AnimationKeyframe} だけが残るので、表は読み込みの完了で到達不能になり回収されます。
     * 並列のストリームから呼ばれるため、表はスレッドセーフです。
     * </p>
     * @return 結果を覚える関数
     */
    default @NotNull FloatFunction<T> memoize() {
        var map = new ConcurrentHashMap<Integer, T>();
        return f -> map.computeIfAbsent(MathUtil.similarHashCode(f), _ -> apply(f));
    }
}
