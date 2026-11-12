/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.util

import kotlin.math.abs
import kotlin.math.max

/** `/bm spawn` が埋まらない位置を探す時に、上方向へずらす最大のマス数。 */
const val SPAWN_SEARCH_UP = 3

/** `/bm spawn` が埋まらない位置を探す時に、水平へずらす最大のマス数（四角い輪の半径）。 */
const val SPAWN_SEARCH_HORIZONTAL = 3

/** 当たり判定の辺を縮める量。ブロックにちょうど接しているだけの位置を「重なり」と数えないため。 */
private const val EPSILON = 1e-3

/** 床の判定に使う、足元の下の薄い板の厚み。 */
private const val FLOOR_THICKNESS = 0.05

/** `/bm spawn` の出現位置。 */
data class SpawnPosition(val x: Double, val y: Double, val z: Double) {
    /** 指定位置からずらしたか。 */
    fun movedFrom(x: Double, y: Double, z: Double) = this.x != x || this.y != y || this.z != z
}

/**
 * 難易度がピースフルのワールドで、敵対モブを出してはいけないか。
 * ピースフルではバニラが敵対モブを次の tick に DESPAWN で消すため、出す前に止める。
 */
fun refusesInPeaceful(peaceful: Boolean, hostile: Boolean) = peaceful && hostile

/**
 * エンティティの実装クラスが敵対モブ（[enemy] を実装する種類）か。
 * クラスが分からない種類は敵対とみなさない。
 */
fun isHostileClass(entityClass: Class<*>?, enemy: Class<*>) = entityClass != null && enemy.isAssignableFrom(entityClass)

/**
 * 当たり判定（幅 [width]・高さ [height]）のモブを置いても、ブロックと重ならない位置を探す。
 *
 * 探す順は、指定位置 → 上へ 1..[SPAWN_SEARCH_UP] マス → 水平に 1..[SPAWN_SEARCH_HORIZONTAL] マス離れた輪
 * （それぞれの輪で、近い順・上へずらす量の少ない順）。上方向を先に試し、その後に水平へ広げる。
 * 床（足元のすぐ下に立てるブロック）が無い位置は、[flying] が false なら採用しない（宙に出して落下させない）。
 *
 * @param blocked 軸に平行な箱 (minX, minY, minZ, maxX, maxY, maxZ) がブロックと重なるか。
 * 読み込まれていない・別リージョンのチャンクに触れる箱は、true を返して「使えない」ことにする。
 * @return 見つかった位置。無ければ null。
 */
fun findSpawnPosition(
    x: Double,
    y: Double,
    z: Double,
    width: Double,
    height: Double,
    flying: Boolean,
    blocked: (Double, Double, Double, Double, Double, Double) -> Boolean,
): SpawnPosition? {
    val half = width / 2
    fun fits(cx: Double, cy: Double, cz: Double): Boolean {
        if (blocked(cx - half + EPSILON, cy + EPSILON, cz - half + EPSILON, cx + half - EPSILON, cy + height - EPSILON, cz + half - EPSILON)) return false
        if (flying) return true
        return blocked(cx - half + EPSILON, cy - FLOOR_THICKNESS, cz - half + EPSILON, cx + half - EPSILON, cy - EPSILON, cz + half - EPSILON)
    }
    for (radius in 0..SPAWN_SEARCH_HORIZONTAL) {
        val ring = buildList {
            for (dx in -radius..radius) for (dz in -radius..radius) {
                if (max(abs(dx), abs(dz)) == radius) add(dx to dz)
            }
        }.sortedBy { (dx, dz) -> dx * dx + dz * dz }
        for (up in 0..SPAWN_SEARCH_UP) for ((dx, dz) in ring) {
            val cx = x + dx
            val cy = y + up
            val cz = z + dz
            if (fits(cx, cy, cz)) return SpawnPosition(cx, cy, cz)
        }
    }
    return null
}
