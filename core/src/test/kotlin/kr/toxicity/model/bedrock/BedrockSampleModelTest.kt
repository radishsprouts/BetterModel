/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.bedrock

import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.BetterModelPlatform
import kr.toxicity.model.api.data.ModelAsset
import kr.toxicity.model.api.data.blueprint.BlueprintElement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 統合版の実機検証用の試験モデル（`test-plugin` の `bedrock_test.bbmodel`）が、
 * 読み込みから geometry への変換まで通ることを確かめる。実機での見え方は確かめない。
 */
class BedrockSampleModelTest {

    private val file = File("../test-plugin/src/main/resources/bedrock_test.bbmodel")

    /** インターフェースを返すメソッドには再帰的に同じ形のプロキシを返し、他は null を返す（骨の名前の初期化にだけ要る）。 */
    private fun stub(type: Class<*>): Any = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        when {
            method.name == "hashCode" -> System.identityHashCode(proxy)
            method.name == "equals" -> proxy === args?.firstOrNull()
            method.name == "toString" -> "stub"
            method.returnType.isInterface -> stub(method.returnType)
            method.returnType == Boolean::class.javaPrimitiveType -> false
            method.returnType == Int::class.javaPrimitiveType -> 0
            method.returnType == Long::class.javaPrimitiveType -> 0L
            method.returnType == Float::class.javaPrimitiveType -> 0F
            method.returnType == Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }

    private fun registerPlatform() {
        runCatching { BetterModel.platform() }.onFailure {
            BetterModel.register(stub(BetterModelPlatform::class.java) as BetterModelPlatform)
        }
    }

    private fun bones(): Map<String, BedrockBone> {
        registerPlatform()
        val result = ModelAsset.of(file).toResult()
        assertTrue(result.errors.isEmpty(), "読み込みの警告: ${result.errors}")
        val blueprint = result.blueprint
        val context = blueprint.context()
        val textures = HashMap<Int, BedrockTexture>()
        val groups = ArrayList<BlueprintElement.Group>()
        fun walk(list: List<BlueprintElement>) {
            for (element in list) if (element is BlueprintElement.Group) {
                groups += element
                walk(element.children())
            }
        }
        walk(blueprint.elements())
        return groups.mapNotNull { group ->
            BedrockGeometry.build(BedrockBoneSource(
                context.name(),
                group.name().rawName(),
                group.origin().invertXZ(),
                group.scale(),
                group.name().tagged(kr.toxicity.model.api.bone.BoneTags.GLOW),
                group.children().filterIsInstance<BlueprintElement.Cube>(),
                group.children().filterIsInstance<BlueprintElement.Mesh>()
            ) { i -> textures.getOrPut(i) { BedrockTexture.of(context.texture(i), context.resolution()) } })
                ?.let { group.name().rawName() to it }
        }.toMap()
    }

    @Test
    fun 試験モデルが読み込めて全部の骨がgeometryになる() {
        val bones = bones()
        for (name in listOf(
            "rot_multi", "inflate", "face_rot", "thin_plate", "anim", "glow", "translucent", "light", "mesh_tri", "mesh_quad"
        )) assertNotNull(bones[name], "geometry が作られていない骨: $name")
    }

    @Test
    fun 動くテクスチャは3コマ_発光と半透明が判定される() {
        val bones = bones()
        assertEquals(3, bones.getValue("anim").textures.single().frames)
        assertTrue(bones.getValue("glow").textures.single().glowing)
        assertTrue(bones.getValue("translucent").textures.single().translucent)
    }

    @Test
    fun メッシュの骨はpoly_meshを持つ() {
        val bones = bones()
        for (name in listOf("mesh_tri", "mesh_quad")) {
            assertTrue(bones.getValue(name).geometry.toString().contains("poly_mesh"), "poly_mesh が無い: $name")
        }
    }
}
