/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.tracker;

import org.jetbrains.annotations.ApiStatus;

/**
 * エンティティのモデルのネームタグを、常に表示するかどうかの決め方。
 * <p>
 * vanilla と同じ見え方にする。名前が付いた Mob は見た時に名前が出て、
 * customNameVisible が立っている Mob は常に名前が出る。
 * 常に表示しない場合の「見た時」の判定は、ネームタグ側の視線と距離の条件が行う。
 * </p>
 * <pre>{@code
 * tag.alwaysVisible(NametagVisibility.alwaysVisible(playerTagged, entity instanceof BasePlayer, entity.customNameVisible()));
 * }</pre>
 *
 * @since 3.7.0
 */
@ApiStatus.Internal
public final class NametagVisibility {

    private NametagVisibility() {
        throw new UnsupportedOperationException();
    }

    /**
     * ネームタグを常に表示するかを決める。
     * <ul>
     *     <li>骨のタグが PLAYER_TAG なら、常に表示する</li>
     *     <li>それ以外でプレイヤーなら、常に表示する</li>
     *     <li>それ以外（MOB_TAG を含む）は、エンティティの customNameVisible に従う</li>
     * </ul>
     *
     * @param playerTagged 骨のタグが PLAYER_TAG か
     * @param player 対象のエンティティがプレイヤーか
     * @param customNameVisible エンティティの customNameVisible
     * @return 常に表示するなら true
     * @since 3.7.0
     */
    public static boolean alwaysVisible(boolean playerTagged, boolean player, boolean customNameVisible) {
        return playerTagged || player || customNameVisible;
    }
}
