package com.auditlab.agent

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class ScreenFramePipelineTest {
    @Test fun `planes with no final row padding and a nonzero buffer position copy correctly`() {
        val bytes=ByteArray(23) {it.toByte()}
        val buffer=ByteBuffer.wrap(bytes).also {it.position(3)}
        val expected=(3..10).map(Int::toByte)+(15..22).map(Int::toByte)
        assertArrayEquals(expected.toByteArray(),ScreenRgbaPixels.pack(buffer,2,2,12,4))
        assertEquals(3,buffer.position())
    }
    @Test fun `pixel padding is removed and truncated planes are rejected`() {
        val buffer=ByteBuffer.wrap(ByteArray(12) {it.toByte()})
        assertArrayEquals(byteArrayOf(0,1,2,3,8,9,10,11),ScreenRgbaPixels.pack(buffer,2,1,16,8))
        assertThrows(IllegalArgumentException::class.java) {ScreenRgbaPixels.pack(ByteBuffer.allocate(19),2,2,12,4)}
    }
    @Test fun `accepting capture without images never confirms a server delivery and times out`() {
        var now=0L;var posts=0;var confirmations=0
        val pipeline=ScreenFramePipeline({now},{posts++},{confirmations++})
        pipeline.tick();now=14_999;pipeline.tick()
        assertEquals(0,posts);assertEquals(0,confirmations)
        now=15_000
        assertThrows(IllegalStateException::class.java) {pipeline.tick()}
        assertEquals(0,confirmations)
    }
    @Test fun `static frames keep the relay alive only after successful acknowledgments`() {
        val frames=mutableListOf<ByteArray>();var confirmations=0
        val pipeline=ScreenFramePipeline({0},{frames.add(it.copyOf())},{confirmations++})
        val input=byteArrayOf(1,2,3);pipeline.offer(input);input[0]=9
        pipeline.tick();pipeline.tick()
        assertEquals(2,confirmations);assertArrayEquals(byteArrayOf(1,2,3),frames[0]);assertArrayEquals(frames[0],frames[1])
        pipeline.close();pipeline.offer(byteArrayOf(4));pipeline.tick()
        assertEquals(2,frames.size)
    }
    @Test fun `failed or late acknowledgments cannot mark a stopped capture as confirmed`() {
        var confirmations=0
        val failed=ScreenFramePipeline({0},{throw java.io.IOException("fixture")},{confirmations++})
        failed.offer(byteArrayOf(1))
        assertThrows(java.io.IOException::class.java) {failed.tick()};assertEquals(0,confirmations)
        lateinit var closing: ScreenFramePipeline
        closing=ScreenFramePipeline({0},{closing.close()},{confirmations++})
        closing.offer(byteArrayOf(1));closing.tick();assertEquals(0,confirmations)
    }
}
