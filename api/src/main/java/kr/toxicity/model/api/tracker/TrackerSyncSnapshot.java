/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.tracker;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * トラッカーがアニメーションを視聴者へ送っている状態の、読み取り専用の写し（観測用）。
 * <p>
 * {@link Tracker#syncSnapshot()} が返す。不変で、取得後にトラッカーが動いても値は変わらない。
 * </p>
 * <pre>{@code
 * var snapshot = tracker.syncSnapshot();
 * for (var viewer : snapshot.viewers()) {
 *     System.out.println(viewer.uuid() + " step=" + viewer.step() + " sent=" + viewer.sentVersion() + "/" + snapshot.version());
 * }
 * }</pre>
 *
 * @param version 姿勢が変わるたびに増える、トラッカーの今の版
 * @param tickStamp 視聴者へ送る処理を行った回数（トラッカーの tick ごとに1増える）
 * @param viewers 視聴者ごとの状態
 * @since 3.7.0
 */
public record TrackerSyncSnapshot(long version, long tickStamp, @NotNull List<ViewerSync> viewers) {

    /**
     * 視聴者1人分の状態。
     *
     * @param uuid 視聴者の UUID
     * @param step 今の LOD の段（1回の送信にまとめるフレーム数。1なら毎フレーム送る）
     * @param sentVersion この視聴者へ最後に送った姿勢の版。{@link TrackerSyncSnapshot#version()} と同じなら最新まで届いている
     * @since 3.7.0
     */
    public record ViewerSync(@NotNull UUID uuid, int step, long sentVersion) {
    }
}
