package com.qasim.speedlimiter.utils

import java.nio.ByteBuffer

object NetworkPacketUtils {
    fun getUidFromPacket(buffer: ByteBuffer): Int {
        if (buffer.remaining() < 20) return -1
        val versionAndIHL = buffer.get(0).toInt()
        val version = (versionAndIHL shr 4) and 0x0F
        if (version != 4) return -1
        val protocol = buffer.get(9).toInt()
        return protocol
    }
}