/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.tracker

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TickShard] が、担当する要素がある間だけ周期タスクを動かすことを確かめる。
 */
class TickShardTest {

    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { r ->
        Thread(r).apply { isDaemon = true }
    }
    private val ticks = AtomicInteger()

    private fun shard() = TickShard<String>(executor, 0L, 1_000L) { ticks.incrementAndGet() }

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
    }

    /** 周期が 1ms なので、数十 ms 待てば何度も実行されているはず。 */
    private fun awaitTicks(count: Int) {
        val target = ticks.get() + count
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (ticks.get() < target) {
            assertTrue(System.nanoTime() < deadline, "tick が進まない: ${ticks.get()} / $target")
            Thread.sleep(2)
        }
    }

    /** 止めた後に、すでに動き出していた分が終わるのを待ってから、これ以上増えないことを確かめる。 */
    private fun assertStopped() {
        Thread.sleep(50)
        val settled = ticks.get()
        Thread.sleep(100)
        assertEquals(settled, ticks.get(), "止めたはずなのに tick が続いている")
    }

    @Test
    fun `登録するまで動かず、登録すると動き、解除すると止まり、再登録でまた動く`() {
        val shard = shard()
        assertFalse(shard.running())
        Thread.sleep(50)
        assertEquals(0, ticks.get(), "要素が無いのに tick された")

        shard.add(1L, "a")
        assertTrue(shard.running())
        awaitTicks(5)

        shard.remove(1L)
        assertFalse(shard.running())
        assertStopped()

        val before = ticks.get()
        shard.add(1L, "a")
        assertTrue(shard.running())
        awaitTicks(5)
        assertTrue(ticks.get() > before)
    }

    @Test
    fun `最後の要素が抜けるまでは止まらない`() {
        val shard = shard()
        shard.add(1L, "a")
        shard.add(2L, "b")
        shard.remove(1L)
        assertTrue(shard.running())
        awaitTicks(5)
        shard.remove(2L)
        assertFalse(shard.running())
        assertStopped()
    }

    @Test
    fun `登録した要素ごとに tick が呼ばれる`() {
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val latch = CountDownLatch(1)
        val shard = TickShard<String>(executor, 0L, 1_000L) {
            seen += it
            if (seen.size == 2) latch.countDown()
        }
        shard.add(1L, "a")
        shard.add(2L, "b")
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        shard.close()
    }

    @Test
    fun `登録と解除が並行しても、最後に要素があれば動き、無ければ止まる`() {
        val shard = shard()
        val threads = 8
        val rounds = 300
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { index ->
            Thread {
                start.await()
                repeat(rounds) {
                    shard.add(index.toLong(), "t$index")
                    shard.remove(index.toLong())
                }
                // 最後の 1 回は登録したまま終える（偶数番号のスレッドだけ）
                if (index % 2 == 0) shard.add(index.toLong(), "t$index")
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))

        // 4 要素が残っている: 動いていて、tick が進む
        assertEquals(threads / 2, shard.size())
        assertTrue(shard.running())
        awaitTicks(5)

        // 全部抜ければ止まる（二重に起動していれば、止めたのに片方が動き続ける）
        repeat(threads) { shard.remove(it.toLong()) }
        assertFalse(shard.running())
        assertStopped()
    }

    @Test
    fun `閉じると止まり、以後の登録は受け付けない`() {
        val shard = shard()
        shard.add(1L, "a")
        awaitTicks(3)
        shard.close()
        assertFalse(shard.running())
        assertStopped()

        shard.add(2L, "b")
        assertFalse(shard.running())
        assertEquals(0, shard.size())
        assertStopped()
    }

    @Test
    fun `tick の最中に自分を解除しても、止まって再登録できる`() {
        lateinit var shard: TickShard<String>
        val removed = CountDownLatch(1)
        shard = TickShard(executor, 0L, 1_000L) {
            ticks.incrementAndGet()
            shard.remove(1L)
            removed.countDown()
        }
        shard.add(1L, "a")
        assertTrue(removed.await(5, TimeUnit.SECONDS))
        assertFalse(shard.running())
        assertStopped()

        val before = ticks.get()
        shard.add(1L, "a")
        awaitTicks(1)
        assertTrue(ticks.get() > before)
    }
}
