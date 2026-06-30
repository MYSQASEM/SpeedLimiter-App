package com.qasim.speedlimiter.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder

object NetworkPacketUtils {

    /**
     * دالة لاستخراج نوع البروتوكول من حزمة IPv4 القادمة من النفق
     * (6 لـ TCP، و 17 لـ UDP)
     */
    fun getProtocolFromPacket(buffer: ByteBuffer): Int {
        if (buffer.remaining() < 20) return -1
        val versionAndIHL = buffer.get(0).toInt()
        val version = (versionAndIHL shr 4) and 0x0F
        if (version != 4) return -1 // تدعم فقط IPv4 حالياً
        
        return buffer.get(9).toInt() and 0xFF
    }

    /**
     * دالة جوهرية لبناء ترويسة IPv4 و TCP كاملة للحزم المستلمة من الإنترنت الحقيقي
     * وضخها بشكل يقبله نظام الأندرويد دون عمل Packet Drop
     */
    fun buildTcpPacket(
        packetBuffer: ByteBuffer,
        session: VpnConnectionSession,
        dataBuffer: ByteBuffer,
        dataLength: Int
    ) {
        packetBuffer.clear()
        packetBuffer.order(ByteOrder.BIG_ENDIAN)

        // --- 1. بناء ترويسة IP Header (20 بايت) ---
        packetBuffer.put(0, ((4 shl 4) or 5).toByte()) // Version = 4, IHL = 5 (20 bytes)
        packetBuffer.put(1, 0.toByte()) // Type of Service
        
        val totalLength = dataLength + 40 // 20 IP + 20 TCP + Data
        packetBuffer.putShort(2, totalLength.toShort())
        packetBuffer.putShort(4, 0.toShort()) // Identification
        packetBuffer.putShort(6, 0x4000.toShort()) // Flags = Don't Fragment (DF)
        packetBuffer.put(8, 64.toByte()) // TTL = 64
        packetBuffer.put(9, 6.toByte()) // Protocol = 6 (TCP)
        packetBuffer.putShort(10, 0.toShort()) // IP Checksum (صفر مؤقتاً للحساب)

        // عكس الوجهة: المصدر هو السيرفر الخارجي، والهدف هو الهاتف
        packetBuffer.putInt(12, session.remoteAddressIp) // Source IP
        packetBuffer.putInt(16, session.localAddressIp)  // Destination IP

        // حساب الـ IP Checksum الرياضي
        val ipChecksum = calculateChecksum(packetBuffer, 0, 20)
        packetBuffer.putShort(10, ipChecksum)

        // --- 2. بناء ترويسة TCP Header (20 بايت) ---
        packetBuffer.putShort(20, session.remotePort.toShort()) // Source Port
        packetBuffer.putShort(22, session.localPort.toShort())  // Destination Port
        packetBuffer.putInt(24, session.sendNextSequenceNumber) // Sequence Number
        packetBuffer.putInt(28, session.receiveNextSequenceNumber) // Acknowledgment Number
        
        packetBuffer.put(32, (5 shl 4).toByte()) // Data Offset = 5 (20 bytes, no options)
        packetBuffer.put(33, 0x18.toByte()) // Flags = PSH | ACK (ضخ البيانات وتأكيد الاستلام)
        packetBuffer.putShort(34, 65535.toShort()) // Window Size
        packetBuffer.putShort(36, 0.toShort()) // TCP Checksum (صفر مؤقتاً)
        packetBuffer.putShort(38, 0.toShort()) // Urgent Pointer

        // --- 3. نسخ البيانات الفعلية (Payload) ---
        packetBuffer.position(40)
        packetBuffer.put(dataBuffer)

        // --- 4. حساب الـ TCP Checksum المعقد (بحاجة لتروِيسة وهمية Pseudo Header) ---
        val tcpLength = dataLength + 20
        val pseudoBuffer = ByteBuffer.allocate(12 + tcpLength)
        pseudoBuffer.order(ByteOrder.BIG_ENDIAN)
        pseudoBuffer.putInt(session.remoteAddressIp) // Src IP
        pseudoBuffer.putInt(session.localAddressIp)  // Dst IP
        pseudoBuffer.put(0.toByte()) // Reserved
        pseudoBuffer.put(6.toByte()) // Protocol (TCP)
        pseudoBuffer.putShort(tcpLength.toShort()) // TCP Length
        
        // نسخ ترويسة الـ TCP والبيانات للـ Pseudo Buffer للحساب
        packetBuffer.position(20)
        pseudoBuffer.put(packetBuffer)
        
        val tcpChecksum = calculateChecksum(pseudoBuffer, 0, pseudoBuffer.capacity())
        packetBuffer.putShort(36, tcpChecksum)

        // إعادة ضبط الـ Buffer للإرسال الفوري
        packetBuffer.clear()
        packetBuffer.limit(totalLength)
        
        // 🚀 [الإصلاح المعجزة]: حذف الزيادة المكررة هنا لأنها تتم بالفعل وبشكل صحيح داخل خيط الـ TcpSelectorEngine
        // session.sendNextSequenceNumber += dataLength (تم حذف السطر المسبب للمشكلة)
    }

    /**
     * الخوارزمية الرياضية القياسية (Internet Checksum RFC 1071)
     * لحساب سلامة ترويسات الشبكة وتجنب سقوط الحزم
     */
    private fun calculateChecksum(buffer: ByteBuffer, offset: Int, length: Int): Short {
        var sum = 0
        val oldPosition = buffer.position()
        buffer.position(offset)

        for (i in 0 until length / 2) {
            sum += buffer.short.toInt() and 0xFFFF
        }
        if (length % 2 != 0) {
            sum += (buffer.get().toInt() and 0xFF) shl 8
        }

        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        buffer.position(oldPosition)
        return (sum.inv()).toShort()
    }
}
