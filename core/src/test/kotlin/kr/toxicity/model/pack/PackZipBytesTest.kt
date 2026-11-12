/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.pack

import kr.toxicity.model.api.pack.PackByte
import kr.toxicity.model.api.pack.PackMeta
import kr.toxicity.model.api.pack.PackPath
import kr.toxicity.model.api.pack.PackResult
import kr.toxicity.model.util.toZipBytes
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** フォルダ形式のパックからメモリ上で作る ZIP が、中身を保ち、同じ中身なら同じバイト列になること。 */
class PackZipBytesTest {

    private fun result(vararg files: Pair<String, ByteArray>) =
        // PackMeta.builder() は実行中のサーバー（BetterModel.nms()）を読むので、単体試験では直接組み立てる
        PackResult(PackMeta(PackMeta.Pack(1, "test", null, PackMeta.PackVersion(1), PackMeta.PackVersion(1)), null), null).apply {
            files.forEach { (name, bytes) -> set(null, PackByte(PackPath(name), bytes)) }
            freeze()
        }

    @Test
    fun `ZIP に全ファイルが入り、読み戻すと同じ中身になる`() {
        val png = ByteArray(300) { (it * 3).toByte() }
        val json = """{"a":1}""".toByteArray()
        val bytes = result("assets/x/a.png" to png, "assets/x/a.json" to json).toZipBytes()
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        assertEquals(setOf("assets/x/a.png", "assets/x/a.json"), entries.keys)
        assertContentEquals(png, entries["assets/x/a.png"])
        assertContentEquals(json, entries["assets/x/a.json"])
    }

    @Test
    fun `同じ中身なら作る時刻が違っても同じバイト列（同じ SHA-1）になる`() {
        val make = { result("assets/x/a.json" to "{}".toByteArray(), "assets/x/b.png" to ByteArray(10)).toZipBytes() }
        val first = make()
        Thread.sleep(1100)
        assertEquals(PackBundle(first).sha1Hex, PackBundle(make()).sha1Hex)
    }
}
