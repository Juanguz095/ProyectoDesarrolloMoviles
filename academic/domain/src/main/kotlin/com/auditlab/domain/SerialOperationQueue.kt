package com.auditlab.domain

import java.util.concurrent.Executors

/** One foreground operation at a time; maintenance/close always follows accepted work. */
class SerialOperationQueue : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "auditlab-io").apply { isDaemon = true } }
    private val lock = Any()
    private var running = false
    private var closed = false
    val busy: Boolean get() = synchronized(lock) { running }

    fun tryExecute(action: () -> Unit, completed: () -> Unit): Boolean = synchronized(lock) {
        if (closed || running) return false
        running = true
        worker.execute {
            try { action() } finally {
                synchronized(lock) { running = false }
                completed()
            }
        }
        true
    }

    fun afterPending(action: () -> Unit): Boolean = synchronized(lock) {
        if (closed) return false
        worker.execute(action)
        true
    }

    fun closeAfterPending(closedAction: () -> Unit) = synchronized(lock) {
        if (closed) return
        closed = true
        worker.execute(closedAction)
        worker.shutdown()
    }

    override fun close() = closeAfterPending {}
}
