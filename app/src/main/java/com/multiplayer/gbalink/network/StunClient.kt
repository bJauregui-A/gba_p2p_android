package com.multiplayer.gbalink.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom

class StunClient(
    private val stunServerHost: String = "stun.l.google.com",
    private val stunServerPort: Int = 19302
) {

    companion object {
        private const val TAG = "StunClient"
        private const val MAGIC_COOKIE = 0x2112A442
        private const val BINDING_REQUEST = 0x0001
        private const val BINDING_RESPONSE = 0x0101

        private const val ATTR_MAPPED_ADDRESS = 0x0001
        private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    }

    data class StunResult(val publicAddress: InetSocketAddress, val localPort: Int)

    suspend fun discoverPublicEndpoint(existingSocket: DatagramSocket? = null): StunResult? = withContext(Dispatchers.IO) {
        val socket = existingSocket ?: DatagramSocket()
        socket.soTimeout = 3000

        try {
            val serverAddress = InetAddress.getByName(stunServerHost)

            // 1. Generate 12-byte random transaction ID
            val transactionId = ByteArray(12)
            SecureRandom().nextBytes(transactionId)

            // 2. Build STUN Binding Request (20 bytes)
            val request = ByteBuffer.allocate(20)
            request.putShort(BINDING_REQUEST.toShort())
            request.putShort(0.toShort()) // Message length: 0 attributes
            request.putInt(MAGIC_COOKIE)
            request.put(transactionId)

            val sendPacket = DatagramPacket(request.array(), 20, serverAddress, stunServerPort)
            socket.send(sendPacket)

            // 3. Receive STUN Response
            val recvBuffer = ByteArray(512)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
            socket.receive(recvPacket)

            val response = ByteBuffer.wrap(recvBuffer, 0, recvPacket.length)
            val msgType = response.short.toInt() and 0xFFFF
            val msgLen = response.short.toInt() and 0xFFFF
            val magicCookie = response.int

            if (msgType != BINDING_RESPONSE || magicCookie != MAGIC_COOKIE) {
                Log.w(TAG, "Invalid STUN response: type=0x${msgType.toString(16)}")
                return@withContext null
            }

            // Verify Transaction ID
            val respTxId = ByteArray(12)
            response.get(respTxId)
            if (!transactionId.contentEquals(respTxId)) {
                Log.w(TAG, "Transaction ID mismatch")
                return@withContext null
            }

            // 4. Parse Attributes
            var mappedAddress: InetSocketAddress? = null
            while (response.remaining() >= 4) {
                val attrType = response.short.toInt() and 0xFFFF
                val attrLen = response.short.toInt() and 0xFFFF
                if (response.remaining() < attrLen) break

                if (attrType == ATTR_XOR_MAPPED_ADDRESS) {
                    response.get() // Reserved 0
                    val family = response.get().toInt() and 0xFF // 0x01 = IPv4
                    val xorPort = response.short.toInt() and 0xFFFF
                    val port = xorPort xor ((MAGIC_COOKIE shr 16) and 0xFFFF)

                    if (family == 0x01) { // IPv4
                        val xorIp = response.int
                        val ipInt = xorIp xor MAGIC_COOKIE
                        val ipBytes = byteArrayOf(
                            ((ipInt shr 24) and 0xFF).toByte(),
                            ((ipInt shr 16) and 0xFF).toByte(),
                            ((ipInt shr 8) and 0xFF).toByte(),
                            (ipInt and 0xFF).toByte()
                        )
                        val address = InetAddress.getByAddress(ipBytes)
                        mappedAddress = InetSocketAddress(address, port)
                        break
                    } else {
                        response.position(response.position() + attrLen - 4)
                    }
                } else if (attrType == ATTR_MAPPED_ADDRESS) {
                    response.get() // Reserved 0
                    val family = response.get().toInt() and 0xFF
                    val port = response.short.toInt() and 0xFFFF
                    if (family == 0x01) {
                        val ipBytes = ByteArray(4)
                        response.get(ipBytes)
                        val address = InetAddress.getByAddress(ipBytes)
                        mappedAddress = InetSocketAddress(address, port)
                    } else {
                        response.position(response.position() + attrLen - 4)
                    }
                } else {
                    response.position(response.position() + attrLen)
                }
            }

            if (mappedAddress != null) {
                Log.i(TAG, "STUN mapped address: $mappedAddress (local port: ${socket.localPort})")
                return@withContext StunResult(mappedAddress, socket.localPort)
            }
        } catch (e: Exception) {
            Log.e(TAG, "STUN request failed", e)
        } finally {
            if (existingSocket == null) {
                socket.close()
            }
        }
        null
    }
}
