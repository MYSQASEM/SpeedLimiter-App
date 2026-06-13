package com.qasim.speedlimiter.data.services

import java.nio.ByteBuffer

object NetworkPacketUtils {

    class IPv4Header(buffer: ByteBuffer) {
        val version: Int
        val ihl: Int
        val protocol: Int
        val sourceAddress: String
        val destinationAddress: String
        val totalLength: Int

        init {
            val firstByte = buffer.get().toInt()
            version = (firstByte shr 4) and 0x0F
            ihl = (firstByte and 0x0F) * 4
            
            buffer.position(buffer.position() + 1)
            totalLength = buffer.short.toInt() and 0xFFFF
            
            buffer.position(buffer.position() + 5)
            protocol = buffer.get().toInt() and 0xFF
            
            buffer.position(buffer.position() + 2)
            
            sourceAddress = ipToString(buffer.int)
            destinationAddress = ipToString(buffer.int)
        }

        private fun ipToString(ip: Int): String {
            return String.format("%d.%d.%d.%d",
                (ip shr 24) and 0xFF,
                (ip shr 16) and 0xFF,
                (ip shr 8) and 0xFF,
                ip and 0xFF)
        }
    }

    class TCPHeader(buffer: ByteBuffer) {
        val sourcePort: Int
        val destinationPort: Int
        val sequenceNumber: Long
        val acknowledgmentNumber: Long
        val dataOffset: Int
        val flags: Int

        init {
            sourcePort = buffer.short.toInt() and 0xFFFF
            destinationPort = buffer.short.toInt() and 0xFFFF
            sequenceNumber = buffer.int.toLong() and 0xFFFFFFFFL
            acknowledgmentNumber = buffer.int.toLong() and 0xFFFFFFFFL
            
            val offsetAndFlags = buffer.short.toInt()
            dataOffset = ((offsetAndFlags shr 12) and 0x0F) * 4
            flags = offsetAndFlags and 0x3F
        }
    }

    class UDPHeader(buffer: ByteBuffer) {
        val sourcePort: Int
        val destinationPort: Int
        val length: Int

        init {
            sourcePort = buffer.short.toInt() and 0xFFFF
            destinationPort = buffer.short.toInt() and 0xFFFF
            length = buffer.short.toInt() and 0xFFFF
        }
    }

    object Protocol {
        const val TCP = 6
        const val UDP = 17
    }
}