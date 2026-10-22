package com.auditlab.domain

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class SerialOperationQueueTest {
    @Test fun `rapid submissions accept only one while busy`() {
        val queue = SerialOperationQueue(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        try {
            assertTrue(queue.tryExecute({ entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }, { done.countDown() }))
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(queue.busy)
            repeat(20) { assertFalse(queue.tryExecute({ fail("Duplicate action") }, {})) }
            release.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS)); assertFalse(queue.busy)
        } finally { release.countDown(); queue.close() }
    }

    @Test fun `completion releases busy before notifying caller`() {
        val queue = SerialOperationQueue(); val done = CountDownLatch(1); val observedBusy = AtomicBoolean(true)
        try {
            assertTrue(queue.tryExecute({}, { observedBusy.set(queue.busy); done.countDown() }))
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertFalse(observedBusy.get())
        } finally { queue.close() }
    }

    @Test fun `maintenance follows operation before closing resources`() {
        val queue = SerialOperationQueue(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = CountDownLatch(1)
        val order = mutableListOf<String>()
        try {
            queue.tryExecute({ entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); order += "write" }, {})
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(queue.afterPending { order += "maintenance" })
            queue.closeAfterPending { order += "close"; closed.countDown() }
            assertFalse(queue.tryExecute({ fail("Closed queue") }, {})); assertFalse(queue.afterPending {})
            release.countDown(); assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("write", "maintenance", "close"), order)
        } finally { release.countDown(); queue.close() }
    }

    @Test fun `closing twice does not run duplicate cleanup`() {
        val queue = SerialOperationQueue(); val count = AtomicInteger(); val done = CountDownLatch(1)
        queue.closeAfterPending { count.incrementAndGet(); done.countDown() }
        queue.closeAfterPending { count.incrementAndGet() }
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(1, count.get())
    }

    @Test fun `queue reuses one worker and never runs operation on caller thread`() {
        val queue = SerialOperationQueue(); val caller = Thread.currentThread(); val threads = mutableListOf<Thread>()
        try {
            repeat(3) {
                val done = CountDownLatch(1)
                assertTrue(queue.tryExecute({ threads += Thread.currentThread() }, { done.countDown() }))
                assertTrue(done.await(5, TimeUnit.SECONDS))
            }
            assertEquals(1, threads.toSet().size)
            assertTrue(threads.all { it !== caller })
        } finally { queue.close() }
    }
}
