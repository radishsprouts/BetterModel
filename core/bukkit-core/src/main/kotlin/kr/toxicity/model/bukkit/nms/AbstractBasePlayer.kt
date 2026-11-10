/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bukkit.nms

import kr.toxicity.model.api.bukkit.entity.BaseBukkitEntity
import kr.toxicity.model.api.bukkit.entity.BaseBukkitPlayer
import kr.toxicity.model.api.nms.Profiled
import kr.toxicity.model.api.platform.PlatformPlayer
import kr.toxicity.model.bukkit.util.wrap
import org.bukkit.entity.Player
import java.util.stream.Stream

/**
 * [BaseBukkitPlayer] の、版に依存しない部分。
 * NMS の型を使う部分（体の向き・インベントリの再送）は、各版の実装が埋める。
 *
 * @param delegate 対象のプレイヤー
 * @param entity エンティティとしての実装
 * @param profiled プロフィール・防具・スキンのパーツの実装
 */
abstract class AbstractBasePlayer(
    private val delegate: Player,
    entity: BaseBukkitEntity,
    profiled: Profiled
) : BaseBukkitEntity by entity, BaseBukkitPlayer, Profiled by profiled {

    override fun entity(): Player = delegate

    override fun platform(): PlatformPlayer = delegate.wrap()

    override fun trackedBy(): Stream<PlatformPlayer> = Stream.concat(
        Stream.of(delegate),
        delegate.trackedBy.stream()
    ).map {
        it.wrap()
    }
}
