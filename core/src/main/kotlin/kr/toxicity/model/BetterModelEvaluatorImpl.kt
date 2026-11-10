/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model

import gg.moonflower.molangcompiler.api.MolangCompiler
import gg.moonflower.molangcompiler.api.MolangRuntime
import kr.toxicity.model.api.BetterModelEvaluator
import kr.toxicity.model.api.util.function.Float2FloatFunction

class BetterModelEvaluatorImpl : BetterModelEvaluator {

    private val molang = MolangCompiler.create(MolangCompiler.DEFAULT_FLAGS, javaClass.classLoader)

    // runtime はスレッドごとに使い回す。変数の保存先は書き換え可能なので、
    // 毎回の評価で空にして、作り直した runtime と同じ結果になるようにする。
    private val runtime = ThreadLocal.withInitial { MolangRuntime.runtime().create() }

    private fun Float.query(): MolangRuntime = runtime.get().apply {
        clearParameters()
        setThisValue(0F)
        edit()
            .clearVariable()
            .setQuery("life_time", this@query)
            .setQuery("anim_time", this@query)
    }

    override fun compile(expression: String): Float2FloatFunction {
        val compiled = molang.compile(expression)
        return Float2FloatFunction {
            it.query().safeResolve(compiled)
        }
    }
}
