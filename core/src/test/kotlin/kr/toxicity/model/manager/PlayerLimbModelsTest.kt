/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import kr.toxicity.library.dynamicuv.UVModel
import kr.toxicity.library.dynamicuv.UVNamespace
import java.awt.image.BufferedImage
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Checks that the generated definitions produce exactly the same models and the same texture data
 * as the hand-written ones.
 */
class PlayerLimbModelsTest {

    private val generated = PlayerLimbModels { UVNamespace("test", "player_limb") }

    private fun pairs(): List<Pair<UVModel, UVModel>> = LegacyPlayerLimbModels.run {
        listOf(
            HEAD to generated.head,
            CHEST to generated.chest,
            WAIST to generated.waist,
            HIP to generated.hip,
            LEFT_LEG to generated.leftLeg,
            LEFT_FORELEG to generated.leftForeLeg,
            RIGHT_LEG to generated.rightLeg,
            RIGHT_FORELEG to generated.rightForeLeg,
            LEFT_ARM to generated.leftArm,
            LEFT_FOREARM to generated.leftForeArm,
            RIGHT_ARM to generated.rightArm,
            RIGHT_FOREARM to generated.rightForeArm,
            SLIM_LEFT_ARM to generated.slimLeftArm,
            SLIM_LEFT_FOREARM to generated.slimLeftForeArm,
            SLIM_RIGHT_ARM to generated.slimRightArm,
            SLIM_RIGHT_FOREARM to generated.slimRightForeArm,
            CAPE to generated.cape
        )
    }

    private fun UVModel.dump(): Map<String, String> = asJson().associate { it.path() to String(it.build(), Charsets.UTF_8) }

    private fun skin(seed: Long) = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply {
        val random = Random(seed)
        for (x in 0 until 64) for (y in 0 until 64) setRGB(x, y, random.nextInt())
    }

    @Test
    fun `models are the same`() {
        val list = pairs()
        assertEquals(17, list.size)
        list.forEach { (legacy, new) ->
            assertEquals(legacy.modelName(), new.modelName())
            assertEquals(legacy.dump(), new.dump(), "model json: ${legacy.modelName()}")
        }
    }

    @Test
    fun `texture data is the same`() {
        pairs().forEach { (legacy, new) ->
            for (seed in 1L..3L) {
                val image = skin(seed)
                val a = legacy.write(image)
                val b = new.write(image)
                assertEquals(a, b, "texture data: ${legacy.modelName()} (seed $seed)")
            }
        }
    }
}
