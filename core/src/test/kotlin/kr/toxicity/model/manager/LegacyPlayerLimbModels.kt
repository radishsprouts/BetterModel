/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.manager

import kr.toxicity.library.dynamicuv.*

/**
 * [PlayerLimbModels] が生成する前に使っていた手書きの定義。
 * [PlayerLimbModelsTest] の比較の基準としてだけ残している。
 */
object LegacyPlayerLimbModels {

    private val uvNamespace = UVNamespace("test", "player_limb")

    private const val DIV_FACTOR = 16F / 0.9375F
    val HEAD = UVModel(
        { uvNamespace },
        "head"
    ).addElement(
        UVElement(
            ElementVector(8F, 8F, 8F).div(DIV_FACTOR),
            ElementVector(0f, 4F, 0f).div(DIV_FACTOR),
            UVSpace(8, 8, 8),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(8, 8),
                UVFace.SOUTH to UVPos(24, 8),
                UVFace.EAST to UVPos(0, 8),
                UVFace.WEST to UVPos(16, 8),
                UVFace.UP to UVPos(8, 0),
                UVFace.DOWN to UVPos(16, 0)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(8F, 8F, 8F).div(DIV_FACTOR).inflate(0.5f),
            ElementVector(0f, 4F, 0f).div(DIV_FACTOR),
            UVSpace(8, 8, 8),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(8 + 32, 8),
                UVFace.SOUTH to UVPos(24 + 32, 8),
                UVFace.EAST to UVPos(32, 8),
                UVFace.WEST to UVPos(16 + 32, 8),
                UVFace.UP to UVPos(8 + 32, 0),
                UVFace.DOWN to UVPos(16 + 32, 0)
            )
        )
    )
    val CHEST = UVModel(
        { uvNamespace },
        "chest"
    ).addElement(
        UVElement(
            ElementVector(8f, 4f, 4f).div(DIV_FACTOR),
            ElementVector(0f, 2f, 0f).div(DIV_FACTOR),
            UVSpace(8, 4, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(20, 20),
                UVFace.SOUTH to UVPos(32, 20),
                UVFace.EAST to UVPos(16, 20),
                UVFace.WEST to UVPos(28, 20),
                UVFace.UP to UVPos(20, 16)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(8f, 4f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, 2f, 0f).div(DIV_FACTOR),
            UVSpace(8, 4, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(20, 20 + 16),
                UVFace.SOUTH to UVPos(32, 20 + 16),
                UVFace.EAST to UVPos(16, 20 + 16),
                UVFace.WEST to UVPos(28, 20 + 16),
                UVFace.UP to UVPos(20, 16 + 16)
            )
        )
    )
    val WAIST = UVModel(
        { uvNamespace },
        "waist"
    ).addElement(
        UVElement(
            ElementVector(8f, 4f, 4f).div(DIV_FACTOR),
            ElementVector(0f, 2f, 0f).div(DIV_FACTOR),
            UVSpace(8, 4, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(20, 24),
                UVFace.SOUTH to UVPos(32, 24),
                UVFace.EAST to UVPos(16, 24),
                UVFace.WEST to UVPos(28, 24)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(8f, 4f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, 2f, 0f).div(DIV_FACTOR),
            UVSpace(8, 4, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(20, 24 + 16),
                UVFace.SOUTH to UVPos(32, 24 + 16),
                UVFace.EAST to UVPos(16, 24 + 16),
                UVFace.WEST to UVPos(28, 24 + 16)
            )
        )
    )
    val HIP = UVModel(
        { uvNamespace },
        "hip"
    ).addElement(
        UVElement(
            ElementVector(8f, 4f, 4f).div(DIV_FACTOR),
            ElementVector(0f, 2f, 0f).div(DIV_FACTOR),
            UVSpace(8, 4, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(20, 28),
                UVFace.SOUTH to UVPos(32, 28),
                UVFace.EAST to UVPos(16, 28),
                UVFace.WEST to UVPos(28, 28),
                UVFace.DOWN to UVPos(28, 16)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(8f, 4f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, 2f, 0f).div(DIV_FACTOR),
            UVSpace(8, 4, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(20, 28 + 16),
                UVFace.SOUTH to UVPos(32, 28 + 16),
                UVFace.EAST to UVPos(16, 28 + 16),
                UVFace.WEST to UVPos(28, 28 + 16),
                UVFace.DOWN to UVPos(28, 16 + 16)
            )
        )
    )
    val LEFT_LEG = UVModel(
        { uvNamespace },
        "left_leg"
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(20, 52),
                UVFace.SOUTH to UVPos(28, 52),
                UVFace.EAST to UVPos(16, 52),
                UVFace.WEST to UVPos(24, 52),
                UVFace.UP to UVPos(20, 48)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(20 - 16, 52),
                UVFace.SOUTH to UVPos(28 - 16, 52),
                UVFace.EAST to UVPos(0, 52),
                UVFace.WEST to UVPos(24 - 16, 52),
                UVFace.UP to UVPos(20 - 16, 48)
            )
        )
    )
    val LEFT_FORELEG = UVModel(
        { uvNamespace },
        "left_foreleg"
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(20, 58),
                UVFace.SOUTH to UVPos(28, 58),
                UVFace.EAST to UVPos(16, 58),
                UVFace.WEST to UVPos(24, 58),
                UVFace.DOWN to UVPos(24, 48)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(20 - 16, 58),
                UVFace.SOUTH to UVPos(28 - 16, 58),
                UVFace.EAST to UVPos(0, 58),
                UVFace.WEST to UVPos(24 - 16, 58),
                UVFace.DOWN to UVPos(24 - 16, 48)
            )
        )
    )
    val RIGHT_LEG = UVModel(
        { uvNamespace },
        "right_leg"
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(4, 20),
                UVFace.SOUTH to UVPos(12, 20),
                UVFace.EAST to UVPos(0, 20),
                UVFace.WEST to UVPos(8, 20),
                UVFace.UP to UVPos(4, 16)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(4, 20 + 16),
                UVFace.SOUTH to UVPos(12, 20 + 16),
                UVFace.EAST to UVPos(0, 20 + 16),
                UVFace.WEST to UVPos(8, 20 + 16),
                UVFace.UP to UVPos(4, 16 + 16)
            )
        )
    )
    val RIGHT_FORELEG = UVModel(
        { uvNamespace },
        "right_foreleg"
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(4, 26),
                UVFace.SOUTH to UVPos(12, 26),
                UVFace.EAST to UVPos(0, 26),
                UVFace.WEST to UVPos(8, 26),
                UVFace.DOWN to UVPos(8, 16)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(4, 26 + 16),
                UVFace.SOUTH to UVPos(12, 26 + 16),
                UVFace.EAST to UVPos(0, 26 + 16),
                UVFace.WEST to UVPos(8, 26 + 16),
                UVFace.DOWN to UVPos(8, 16 + 16)
            )
        )
    )
    val LEFT_ARM = UVModel(
        { uvNamespace },
        "left_arm"
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(36, 52),
                UVFace.SOUTH to UVPos(44, 52),
                UVFace.EAST to UVPos(32, 52),
                UVFace.WEST to UVPos(40, 52),
                UVFace.UP to UVPos(36, 48)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(36 + 16, 52),
                UVFace.SOUTH to UVPos(44 + 16, 52),
                UVFace.EAST to UVPos(32 + 16, 52),
                UVFace.WEST to UVPos(40 + 16, 52),
                UVFace.UP to UVPos(36 + 16, 48)
            )
        )
    )
    val LEFT_FOREARM = UVModel(
        { uvNamespace },
        "left_forearm"
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(36, 58),
                UVFace.SOUTH to UVPos(44, 58),
                UVFace.EAST to UVPos(32, 58),
                UVFace.WEST to UVPos(40, 58),
                UVFace.DOWN to UVPos(40, 48)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(36 + 16, 58),
                UVFace.SOUTH to UVPos(44 + 16, 58),
                UVFace.EAST to UVPos(32 + 16, 58),
                UVFace.WEST to UVPos(40 + 16, 58),
                UVFace.DOWN to UVPos(40 + 16, 48)
            )
        )
    )
    val RIGHT_ARM = UVModel(
        { uvNamespace },
        "right_arm"
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(44, 20),
                UVFace.SOUTH to UVPos(52, 20),
                UVFace.EAST to UVPos(40, 20),
                UVFace.WEST to UVPos(48, 20),
                UVFace.UP to UVPos(44, 16)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(44, 20 + 16),
                UVFace.SOUTH to UVPos(52, 20 + 16),
                UVFace.EAST to UVPos(40, 20 + 16),
                UVFace.WEST to UVPos(48, 20 + 16),
                UVFace.UP to UVPos(44, 16 + 16)
            )
        )
    )
    val RIGHT_FOREARM = UVModel(
        { uvNamespace },
        "right_forearm"
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(44, 26),
                UVFace.SOUTH to UVPos(52, 26),
                UVFace.EAST to UVPos(40, 26),
                UVFace.WEST to UVPos(48, 26),
                UVFace.DOWN to UVPos(48, 16)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(4f, 6f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(4, 6, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(44, 26 + 16),
                UVFace.SOUTH to UVPos(52, 26 + 16),
                UVFace.EAST to UVPos(40, 26 + 16),
                UVFace.WEST to UVPos(48, 26 + 16),
                UVFace.DOWN to UVPos(48, 16 + 16)
            )
        )
    )
    val SLIM_LEFT_ARM = UVModel(
        { uvNamespace },
        "left_slim_arm"
    ).addElement(
        UVElement(
            ElementVector(3f, 6f, 4f).div(DIV_FACTOR),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(3, 6, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(36, 52),
                UVFace.SOUTH to UVPos(43, 52),
                UVFace.EAST to UVPos(32, 52),
                UVFace.WEST to UVPos(39, 52),
                UVFace.UP to UVPos(36, 48)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(3f, 6f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(3, 6, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(36 + 16, 52),
                UVFace.SOUTH to UVPos(43 + 16, 52),
                UVFace.EAST to UVPos(32 + 16, 52),
                UVFace.WEST to UVPos(39 + 16, 52),
                UVFace.UP to UVPos(36 + 16, 48)
            )
        )
    )
    val SLIM_LEFT_FOREARM = UVModel(
        { uvNamespace },
        "left_slim_forearm"
    ).addElement(
        UVElement(
            ElementVector(3f, 6f, 4f).div(DIV_FACTOR),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(3, 6, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(36, 58),
                UVFace.SOUTH to UVPos(43, 58),
                UVFace.EAST to UVPos(32, 58),
                UVFace.WEST to UVPos(39, 58),
                UVFace.DOWN to UVPos(39, 48)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(3f, 6f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(3, 6, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(36 + 16, 58),
                UVFace.SOUTH to UVPos(43 + 16, 58),
                UVFace.EAST to UVPos(32 + 16, 58),
                UVFace.WEST to UVPos(39 + 16, 58),
                UVFace.DOWN to UVPos(39 + 16, 48)
            )
        )
    )
    val SLIM_RIGHT_ARM = UVModel(
        { uvNamespace },
        "right_slim_arm"
    ).addElement(
        UVElement(
            ElementVector(3f, 6f, 4f).div(DIV_FACTOR),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(3, 6, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(44, 20),
                UVFace.SOUTH to UVPos(51, 20),
                UVFace.EAST to UVPos(40, 20),
                UVFace.WEST to UVPos(47, 20),
                UVFace.UP to UVPos(44, 16)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(3f, 6f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(3, 6, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(44, 20 + 16),
                UVFace.SOUTH to UVPos(51, 20 + 16),
                UVFace.EAST to UVPos(40, 20 + 16),
                UVFace.WEST to UVPos(47, 20 + 16),
                UVFace.UP to UVPos(44, 16 + 16)
            )
        )
    )
    val SLIM_RIGHT_FOREARM = UVModel(
        { uvNamespace },
        "right_slim_forearm"
    ).addElement(
        UVElement(
            ElementVector(3f, 6f, 4f).div(DIV_FACTOR),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(3, 6, 4),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(44, 26),
                UVFace.SOUTH to UVPos(51, 26),
                UVFace.EAST to UVPos(40, 26),
                UVFace.WEST to UVPos(47, 26),
                UVFace.DOWN to UVPos(47, 16)
            )
        )
    ).addElement(
        UVElement(
            ElementVector(3f, 6f, 4f).div(DIV_FACTOR).inflate(0.25f),
            ElementVector(0f, -3f, 0f).div(DIV_FACTOR),
            UVSpace(3, 6, 4),
            UVElement.ColorType.COMPLEX_ARGB,
            mapOf(
                UVFace.NORTH to UVPos(44, 26 + 16),
                UVFace.SOUTH to UVPos(51, 26 + 16),
                UVFace.EAST to UVPos(40, 26 + 16),
                UVFace.WEST to UVPos(47, 26 + 16),
                UVFace.DOWN to UVPos(47, 16 + 16)
            )
        )
    )
    val CAPE = UVModel(
        { uvNamespace },
        "cape"
    ).addElement(
        UVElement(
            ElementVector(10f, 16f, 1f).div(DIV_FACTOR),
            ElementVector(0f, -8f, 0.5f).div(DIV_FACTOR),
            UVSpace(10, 16, 1),
            UVElement.ColorType.RGB,
            mapOf(
                UVFace.NORTH to UVPos(12, 1),
                UVFace.SOUTH to UVPos(1, 1),
                UVFace.EAST to UVPos(11, 1),
                UVFace.WEST to UVPos(0, 1),
                UVFace.UP to UVPos(1, 0),
                UVFace.DOWN to UVPos(11, 0)
            )
        )
    )
}
