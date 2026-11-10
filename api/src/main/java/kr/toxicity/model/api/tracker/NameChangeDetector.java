/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.tracker;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * エンティティの名前が変わったかを、前回の値と比べて判定する。
 * <p>
 * 毎 tick 呼ばれるため、まず参照が同じかを見て、同じなら何もしない。
 * 参照が違っても {@link Component#equals(Object)} で等しければ変更とみなさない。
 * 名前が消された（null）場合も、値の1つとして扱う。
 * </p>
 * <p>
 * 1つのトラッカーの tick（同じスレッド）からだけ呼ぶこと。スレッドセーフではない。
 * </p>
 * <pre>{@code
 * var detector = new NameChangeDetector(entity.customName());
 * // tick ごと
 * var name = entity.customName();
 * if (detector.changed(name)) tag.component(name);
 * }</pre>
 *
 * @since 3.7.0
 */
@ApiStatus.Internal
public final class NameChangeDetector {

    private @Nullable Component last;

    /**
     * 作成時の名前で初期化する。
     *
     * @param initial 作成時の名前。名前が無ければ null
     * @since 3.7.0
     */
    public NameChangeDetector(@Nullable Component initial) {
        this.last = initial;
    }

    /**
     * 今の名前が前回と違うかを判定し、違えば今の名前を覚える。
     *
     * @param current 今の名前。名前が無ければ null
     * @return 前回と違えば true
     * @since 3.7.0
     */
    public boolean changed(@Nullable Component current) {
        var previous = last;
        if (current == previous) return false;
        // 参照が違っても内容が同じなら、新しい参照だけ覚えて、次回からは参照の比較で済ませる。
        last = current;
        return !Objects.equals(current, previous);
    }

    /**
     * 最後に覚えた名前を返す。
     *
     * @return 最後に覚えた名前。無ければ null
     * @since 3.7.0
     */
    public @Nullable Component last() {
        return last;
    }

    @Override
    public @NotNull String toString() {
        return "NameChangeDetector(" + last + ")";
    }
}
