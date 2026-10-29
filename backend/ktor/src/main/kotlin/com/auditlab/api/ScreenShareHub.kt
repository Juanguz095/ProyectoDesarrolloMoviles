package com.auditlab.api

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Ephemeral, bounded relay for user-started screen sharing. Frames are never persisted. */
class ScreenShareHub(
    private val now: () -> Instant = Instant::now,
    private val maxFrameBytes: Int = 512 * 1024,
) {
    data class Frame(val bytes: ByteArray, val capturedAt: Instant)
    private val frames = ConcurrentHashMap<UUID, Frame>()

    @Synchronized fun publish(deviceId: UUID, bytes: ByteArray) {
        purgeExpired()
        require(frames.containsKey(deviceId) || frames.size < 16) { "relay capacity exceeded" }
        require(bytes.isNotEmpty() && bytes.size <= maxFrameBytes) { "invalid frame size" }
        require(bytes[0] == 0xff.toByte() && bytes.getOrNull(1) == 0xd8.toByte()) { "JPEG required" }
        frames[deviceId] = Frame(bytes.copyOf(), now())
    }

    fun latest(deviceId: UUID): Frame? {
        val frame = frames[deviceId] ?: return null
        if (now().isAfter(frame.capturedAt.plusSeconds(5))) {
            frames.remove(deviceId, frame)
            return null
        }
        return Frame(frame.bytes.copyOf(), frame.capturedAt)
    }

    fun stop(deviceId: UUID) { frames.remove(deviceId) }
    fun purgeExpired() { frames.entries.removeIf { now().isAfter(it.value.capturedAt.plusSeconds(5)) } }
    fun clear() { frames.clear() }

    companion object { val shared = ScreenShareHub() }
}
