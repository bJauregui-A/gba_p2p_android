package com.multiplayer.gbalink.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

object LinkCableProtocol {
    const val MSG_HANDSHAKE: Byte = 0x01
    const val MSG_SIO_TRANSFER: Byte = 0x02
    const val MSG_PING: Byte = 0x03
    const val MSG_PONG: Byte = 0x04
    const val MSG_DISCONNECT: Byte = 0x05
    const val MSG_LINK: Byte = 0x06

    /**
     * Serial port packet: [type, kind, isReply, data(u32), seq(u32), cycles(u32)] little endian.
     * kind 1-3 = transfer (multi-player, normal 32, normal 8), 4 = clock sync; cycles = emulated
     * CPU cycles since the cable was plugged.
     */
    class LinkPacket(val kind: Int, val data: Int, val seq: Int, val isReply: Boolean, val cycles: Int)

    fun buildLinkPacket(kind: Int, data: Int, seq: Int, isReply: Boolean, cycles: Int): ByteArray {
        val buffer = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MSG_LINK)
        buffer.put(kind.toByte())
        buffer.put(if (isReply) 1 else 0)
        buffer.putInt(data)
        buffer.putInt(seq)
        buffer.putInt(cycles)
        return buffer.array()
    }

    fun parseLinkPacket(payload: ByteArray, length: Int): LinkPacket? {
        if (length < 15 || payload[0] != MSG_LINK) return null
        val buffer = ByteBuffer.wrap(payload, 1, 14).order(ByteOrder.LITTLE_ENDIAN)
        val kind = buffer.get().toInt()
        val isReply = buffer.get().toInt() != 0
        val data = buffer.int
        val seq = buffer.int
        return LinkPacket(kind, data, seq, isReply, buffer.int)
    }

    fun buildHandshakePacket(role: Int, romCrc: Int): ByteArray {
        val buffer = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MSG_HANDSHAKE)
        buffer.putInt(role)
        buffer.putInt(romCrc)
        return buffer.array()
    }

    fun buildSioTransferPacket(masterData: Short, slaveData: Short, sequence: Int): ByteArray {
        val buffer = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MSG_SIO_TRANSFER)
        buffer.putShort(masterData)
        buffer.putShort(slaveData)
        buffer.putInt(sequence)
        return buffer.array()
    }

    fun buildPingPacket(timestamp: Long): ByteArray {
        val buffer = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MSG_PING)
        buffer.putLong(timestamp)
        return buffer.array()
    }

    fun buildPongPacket(timestamp: Long): ByteArray {
        val buffer = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MSG_PONG)
        buffer.putLong(timestamp)
        return buffer.array()
    }

    class SioPacket(val masterData: Short, val slaveData: Short, val sequence: Int)

    fun parseSioTransfer(payload: ByteArray): SioPacket? {
        if (payload.size < 9 || payload[0] != MSG_SIO_TRANSFER) return null
        val buffer = ByteBuffer.wrap(payload, 1, 8).order(ByteOrder.LITTLE_ENDIAN)
        val master = buffer.short
        val slave = buffer.short
        val seq = buffer.int
        return SioPacket(master, slave, seq)
    }
}
