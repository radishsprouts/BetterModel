/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.command

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.data.renderer.ModelRenderer
import net.kyori.adventure.audience.Audience
import org.incendo.cloud.Command
import org.incendo.cloud.CommandManager
import org.incendo.cloud.context.CommandContext

fun CommandManager<Audience>.register(
    name: String,
    description: String,
    commandMapper: CommandBuilder.(Command.Builder<Audience>) -> Command.Builder<Audience>,
    vararg aliases: String,
    block: CommandBuilder.() -> Unit
) = CommandBuildContext(this, commandMapper, name, description, *aliases).run {
    root.block()
    build()
}

/** 見つからない時、[notFound] へは入力された名前（入力が無ければ [key]）を渡す。 */
inline fun CommandContext<*>.limb(key: String, notFound: (String) -> ModelRenderer): ModelRenderer {
    val input = optional<String>(key).orElse(null)
    return input?.let { BetterModel.limb(it).orElse(null) } ?: notFound(input ?: key)
}

/** 見つからない時、[notFound] へは入力された名前（入力が無ければ [key]）を渡す。 */
inline fun CommandContext<*>.model(key: String, notFound: (String) -> ModelRenderer): ModelRenderer {
    val input = optional<String>(key).orElse(null)
    return input?.let { BetterModel.model(it).orElse(null) } ?: notFound(input ?: key)
}

inline fun <T> CommandContext<*>.string(key: String, mapper: (String) -> T) = mapper(get(key))

fun <T> CommandContext<*>.nullableString(key: String, mapper: (String) -> T): T? = optional<String>(key).map { mapper(it) }.orElse(null)

inline fun <reified T : Any> CommandContext<*>.nullable(key: String): T? = optional<T>(key).orElse(null)
inline fun <reified T : Any> CommandContext<*>.nullable(key: String, ifNotFound: T): T = optional<T>(key).orElse(null) ?: ifNotFound
inline fun <reified T : Any> CommandContext<*>.nullable(key: String, ifNotFound: () -> T): T = optional<T>(key).orElse(null) ?: ifNotFound()
