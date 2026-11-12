/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.command

import org.incendo.cloud.component.CommandComponent
import org.incendo.cloud.CommandManager
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.internal.CommandNode
import org.incendo.cloud.internal.CommandRegistrationHandler
import org.incendo.cloud.parser.standard.StringParser.stringParser
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommandTreePrimingTest {

    private class TestManager : CommandManager<Any>(
        ExecutionCoordinator.simpleCoordinator(),
        CommandRegistrationHandler.nullCommandRegistrationHandler()
    ) {
        override fun hasPermission(sender: Any, permission: String) = true
    }

    /** spawn と同じ形: bm → spawn → required → optional x3 */
    private fun spawnManager() = TestManager().apply {
        command(
            commandBuilder("bm").literal("spawn")
                .required("model", stringParser())
                .optional("type", stringParser())
                .optional("scale", stringParser())
                .optional("location", stringParser())
                .handler { }
        )
    }

    /** test と同じ形: bm → test → required x2 → optional x2 */
    private fun testManager() = TestManager().apply {
        command(
            commandBuilder("bm").literal("test")
                .required("model", stringParser())
                .required("animation", stringParser())
                .optional("speed", stringParser())
                .optional("loop", stringParser())
                .handler { }
        )
    }

    private fun TestManager.run(input: String) = commandExecutor().executeCommand(Any(), input).join()

    @Test
    fun `事前の書き込みなしだと Cloud 2_1_0 の不具合で Cannot replace owning command になる`() {
        // Cloud が直った時（版を上げた時）はこのテストが落ちる。その場合は primeOptionalOwners と
        // CommandBuildContext の呼び出しが不要になっていないか確かめ、不要なら一緒に消すこと。
        val manager = spawnManager()
        manager.run("bm spawn m pig")
        val failure = assertFailsWith<CompletionException> { manager.run("bm spawn m") }
        var cause: Throwable? = failure
        while (cause != null && !(cause is IllegalStateException && cause.message == "Cannot replace owning command")) cause = cause.cause
        assertNotNull(cause, "原因に IllegalStateException(Cannot replace owning command) が無い: $failure")
    }

    @Test
    fun `事前の書き込みがあれば引数の数と順番によらず例外にならない`() {
        val inputs = listOf(
            "bm spawn m",
            "bm spawn m pig",
            "bm spawn m pig 1",
            "bm spawn m pig 1 here"
        )
        val permutations = inputs.flatMap { a -> inputs.flatMap { b -> inputs.map { c -> listOf(a, b, c) } } }
        for (order in permutations) {
            val manager = spawnManager()
            manager.commandTree().rootNode().primeOptionalOwners()
            for (input in order) manager.run(input)
        }
    }

    private fun assertPrimed(root: CommandNode<Any>) {
        var checked = 0
        fun visit(node: CommandNode<Any>) {
            node.children().forEach(::visit)
            val component = node.component() ?: return
            if (!component.required() &&
                component.type() != CommandComponent.ComponentType.FLAG &&
                !component.hasDefaultValue() &&
                !node.isLeaf
            ) {
                checked++
                assertNotNull(node.command(), "${component.name()} に command が書かれていない")
            }
        }
        visit(root)
        assertTrue(checked > 0, "確かめる対象のノードが無い")
    }

    @Test
    fun `spawn の形では対象の全ノードに command が書かれる`() {
        val manager = spawnManager()
        manager.commandTree().rootNode().primeOptionalOwners()
        assertPrimed(manager.commandTree().rootNode())
    }

    @Test
    fun `test の形でも対象の全ノードに command が書かれる`() {
        val manager = testManager()
        manager.commandTree().rootNode().primeOptionalOwners()
        assertPrimed(manager.commandTree().rootNode())
        // 事前に書いた後は、引数の数を変えて何度打っても例外にならない
        manager.run("bm test m a")
        manager.run("bm test m a 1")
        manager.run("bm test m a 1 true")
        manager.run("bm test m a")
    }
}
