/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.command

import org.incendo.cloud.component.CommandComponent
import org.incendo.cloud.internal.CommandNode

/**
 * 任意引数のノードへ、持ち主のコマンドを前もって書き込む。
 *
 * Cloud 2.1.0 の `CommandTree.attemptParseUnambiguousChild`（CommandTree.java:445-458）は、入力が尽きた時点で
 * 次の子が「任意引数・既定値なし・FLAG でない」かつ `command() == null` なら、子孫を左端に沿って下り、
 * コマンドを持つノードを見つけるたびに `child.command(...)` で子のノードへ遅れて書き込む。
 * このループには break が無いので、下りる途中にコマンドを持つノードが2つ以上あると、
 * 2回目の `CommandNode.command(...)`（CommandNode.java:178-183。既にコマンドがあれば例外）が
 * `IllegalStateException: Cannot replace owning command` を投げる。
 *
 * 再現: spawn のように任意引数が3つ続く（type → scale → location）コマンドを、
 * `/bm spawn m pig` → `/bm spawn m` の順で打つ。1回目で type 側のノードが書き込まれ、2回目で scale・location の
 * 2つを見つけて例外になる。書き込みは同期化されていないので、並行して実行しても競合しうる。
 * 2.1.0 が最新の公開版で、版を上げても直らない。
 *
 * 全コマンドの登録後にこの関数で一度たどって、Cloud が後から書き込むはずのノードへ同じコマンドを書いておけば、
 * 解析中の書き込み（`child.command() == null` の分岐）は起きない。
 * 条件は Cloud の書き込み条件（CommandTree.java:429 FLAG の除外、:431 既定値なし、:445 任意引数）と揃える。
 * 書き込む先のコマンドは、Cloud が選ぶのと同じ「子孫を左端に沿って下って最初に見つかるもの」。
 */
internal fun <C : Any> CommandNode<C>.primeOptionalOwners() {
    // 下りて見つけるコマンドが前もって書かれた値でも同じになるよう、子を先に処理する。
    children().forEach { it.primeOptionalOwners() }
    val component = component() ?: return
    if (command() != null || isLeaf) return
    if (component.required() || component.type() == CommandComponent.ComponentType.FLAG || component.hasDefaultValue()) return
    var node = this
    while (!node.isLeaf) {
        node = node.children()[0]
        node.command()?.let {
            command(it)
            return
        }
    }
}
