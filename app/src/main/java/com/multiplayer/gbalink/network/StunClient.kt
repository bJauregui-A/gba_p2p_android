package com.multiplayer.gbalink.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Minimal STUN (RFC 5389) client used to learn the public IP:port the NAT assigned to our UDP
 * socket. Several public servers are tried, IPv4 first (what peers on another network can reach),
 * with retransmissions because single UDP packets are often lost on mobile networks.
 */
class StunClient {

    companion object {
        private const val TAG = "StunClient"
        private const val MAGIC_COOKIE = 0x2112A442
        private const val BINDING_REQUEST = 0x0001
        private const val BINDING_RESPONSE = 0x0101
        private const val ATTR_MAPPED_ADDRESS = 0x0001
        private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
        private const val ATTEMPTS_PER_SERVER = 2
        private const val TIMEOUT_MS = 1200

        val SERVERS = listOf(
            "stun.l.google.com" to 19302,
            "stun1.l.google.com" to 19302,
            "stun.cloudflare.com" to 3478,
            "stun2.l.google.com" to 19302,
            "stun.nextcloud.com" to 3478,
            "stun.sipgate.net" to 3478
        )
    }

    data class StunResult(
        val publicAddress: InetSocketAddress,
        val localPort: Int,
        val server: String,
        /** true when two servers saw different public ports: direct P2P will likely fail. */
        val symmetricNat: Boolean = false
    )

    /** Last error, to show the user something more useful than "failed". */
    var lastError: String? = null
        private set

    suspend fun discoverPublicEndpoint(socket: DatagramSocket): StunResult? = withContext(Dispatchers.IO) {
        val previousTimeout = socket.soTimeout
        socket.soTimeout = TIMEOUT_MS
        lastError = null
        try {
            var first: StunResult? = null
            for (preferV4 in listOf(true, false)) {
                var extraTries = 0
                for ((host, port) in SERVERS) {
                    if (first != null && extraTries++ >= 2) break // NAT check is optional, don't stall
                    val result = queryServer(socket, host, port, preferV4) ?: continue
                    if (first == null) {
                        first = result
                        continue
                    }
                    // Second answer from another server: compare the mapping to detect symmetric NAT
                    val symmetric = result.publicAddress.address == first.publicAddress.address &&
                        result.publicAddress.port != first.publicAddress.port
                    return@withContext first.copy(symmetricNat = symmetric)
                }
                if (first != null) return@withContext first
            }
            if (lastError == null) lastError = "ningún servidor STUN respondió (¿UDP bloqueado en esta red?)"
            null
        } finally {
            socket.soTimeout = previousTimeout
        }
    }

    private fun resolve(host: String, preferV4: Boolean): InetAddress? = try {
        val all = InetAddress.getAllByName(host)
        if (preferV4) all.firstOrNull { it is Inet4Address } else all.firstOrNull { it is Inet6Address }
    } catch (e: Exception) {
        lastError = "no se pudo resolver $host (${e.javaClass.simpleName})"
        null
    }

    private fun queryServer(socket: DatagramSocket, host: String, port: Int, preferV4: Boolean): StunResult? {
        val server = resolve(host, preferV4) ?: return null
        val transactionId = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val request = ByteBuffer.allocate(20).apply {
            putShort(BINDING_REQUEST.toShort())
            putShort(0)
            putInt(MAGIC_COOKIE)
            put(transactionId)
        }.array()

        repeat(ATTEMPTS_PER_SERVER) {
            try {
                socket.send(DatagramPacket(request, request.size, server, port))
                val deadline = System.currentTimeMillis() + TIMEOUT_MS
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val mapped = parseResponse(buf, packet.length, transactionId) ?: continue
                    Log.i(TAG, "STUN $host -> $mapped (local port ${socket.localPort})")
                    return StunResult(mapped, socket.localPort, host)
                }
            } catch (e: SocketTimeoutException) {
                // retransmit
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "STUN $host failed", e)
                return null
            }
        }
        Log.w(TAG, "STUN $host: no response")
        return null
    }

    private fun parseResponse(data: ByteArray, length: Int, transactionId: ByteArray): InetSocketAddress? {
        if (length < 20) return null
        val bb = ByteBuffer.wrap(data, 0, length)
        val type = bb.short.toInt() and 0xFFFF
        val msgLen = bb.short.toInt() and 0xFFFF
        val cookie = bb.int
        val txId = ByteArray(12).also { bb.get(it) }
        if (type != BINDING_RESPONSE || cookie != MAGIC_COOKIE || !txId.contentEquals(transactionId)) return null

        val end = minOf(length, 20 + msgLen)
        var xorMapped: InetSocketAddress? = null
        var mapped: InetSocketAddress? = null
        while (bb.position() + 4 <= end) {
            val attrType = bb.short.toInt() and 0xFFFF
            val attrLen = bb.short.toInt() and 0xFFFF
            val valueStart = bb.position()
            if (valueStart + attrLen > end) break
            when (attrType) {
                ATTR_XOR_MAPPED_ADDRESS -> xorMapped = readAddress(bb, xor = true, txId = transactionId)
                ATTR_MAPPED_ADDRESS -> mapped = readAddress(bb, xor = false, txId = transactionId)
            }
            // Attributes are padded to a multiple of 4 bytes
            bb.position(valueStart + ((attrLen + 3) and 3.inv()).coerceAtMost(end - valueStart))
        }
        return xorMapped ?: mapped
    }

    private fun readAddress(bb: ByteBuffer, xor: Boolean, txId: ByteArray): InetSocketAddress? {
        bb.get() // reserved
        val family = bb.get().toInt() and 0xFF
        var port = bb.short.toInt() and 0xFFFF
        if (xor) port = port xor (MAGIC_COOKIE ushr 16)
        val ip = when (family) {
            0x01 -> ByteArray(4)
            0x02 -> ByteArray(16)
            else -> return null
        }
        bb.get(ip)
        if (xor) {
            // XOR with magic cookie (+ transaction id for IPv6)
            val key = ByteBuffer.allocate(16).putInt(MAGIC_COOKIE).put(txId).array()
            for (i in ip.indices) ip[i] = (ip[i].toInt() xor key[i].toInt()).toByte()
        }
        return InetSocketAddress(InetAddress.getByAddress(ip), port)
    }
}
