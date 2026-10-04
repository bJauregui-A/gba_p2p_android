package com.multiplayer.gbalink.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

object LinkCableProtocol {
    const val MSG_HANDSHAKE: Byte = 0x01
    const val MSG_SIO_TRANSFER: Byte = 0x02
    const val MSG_PING: Byte = 0x03
    const val MSG_PONG: Byte = 0x04
    const val MSG_DISCONNECT: Byte = 0x05

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
