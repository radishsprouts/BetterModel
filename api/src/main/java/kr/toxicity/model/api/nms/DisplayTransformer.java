/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.nms;

import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Handles the transformation (position, scale, rotation) of a display entity.
 * <p>
 * This interface abstracts the interpolation logic for smooth animations.
 * </p>
 *
 * @since 1.15.2
 */
public interface DisplayTransformer {

    /**
     * Applies a transformation to the display.
     * <p>
     * 位置・回転・拡大の変更は項目ごとに版で覚える。{@code sinceVersion} より後に変わった項目だけを
     * バンドラーへ詰むので、受け手ごとに最後に送った版が違っても、同じ値で何度呼んでも、
     * 各自が最新の値を受け取れる。
     * </p>
     *
     * @param duration the interpolation duration in ticks
     * @param position the target position
     * @param scale the target scale
     * @param rotation the target rotation
     * @param version この姿勢の版（呼び出しのたびに増えるか、変わらない。前より小さくしない）
     * @param sinceVersion 受け手がすでに持っている最後の版
     * @param bundler the packet bundler to use
     * @since 1.15.2
     */
    void transform(int duration, @NotNull Vector3f position, @NotNull Vector3f scale, @NotNull Quaternionf rotation, long version, long sinceVersion, @NotNull AnimationBundler bundler);

    /**
     * Sends the current transformation state to clients.
     *
     * @param bundler the packet bundler to use
     * @since 1.15.2
     */
    void sendTransformation(@NotNull PacketBundler bundler);
}
