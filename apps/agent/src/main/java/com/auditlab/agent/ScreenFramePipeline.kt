package com.auditlab.agent

import com.auditlab.common.ScreenRelayClient
import java.nio.ByteBuffer

/** ImageReader planes may omit the final row's padding and may have a pixel stride >4. */
internal object ScreenRgbaPixels {
    fun pack(buffer: ByteBuffer,width: Int,height: Int,rowStride: Int,pixelStride: Int): ByteArray {
        require(width in 1..4096 && height in 1..4096 && pixelStride>=4)
        require(rowStride.toLong()>=(width-1L)*pixelStride+4)
        val input=buffer.duplicate()
        val base=input.position()
        require(base.toLong()+(height-1L)*rowStride+(width-1L)*pixelStride+4<=input.limit())
        val packed=ByteArray(width*height*4)
        for(y in 0 until height) {
            if(pixelStride==4) {
                input.position(base+y*rowStride);input.get(packed,y*width*4,width*4)
            } else for(x in 0 until width) for(channel in 0..3)
                packed[(y*width+x)*4+channel]=input.get(base+y*rowStride+x*pixelStride+channel)
        }
        return packed
    }
}

/** One RAM frame for this authorized session; no disk queue, late sends or false confirmation. */
internal class ScreenFramePipeline(
    private val now: ()->Long,
    private val publish: (ByteArray)->Unit,
    private val confirmed: ()->Unit,
) {
    private val started=now()
    private var latest: ByteArray?=null
    private var closed=false
    @Synchronized fun offer(bytes: ByteArray) {
        if(closed) return
        require(bytes.isNotEmpty() && bytes.size<=ScreenRelayClient.MAX_FRAME_BYTES)
        latest=bytes.copyOf()
    }
    fun tick() {
        val bytes=synchronized(this) {
            if(closed) return
            latest ?: run {
                check(now()-started<15_000) {"Android no entregó ninguna imagen de pantalla en 15 segundos. Vuelve a autorizar la captura con el teléfono desbloqueado."}
                return
            }
        }
        publish(bytes)
        synchronized(this) {if(!closed) confirmed()}
    }
    @Synchronized fun close() {closed=true;latest=null}
}
