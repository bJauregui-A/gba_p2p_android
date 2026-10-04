package com.multiplayer.gbalink.network

import android.util.Base64
import android.util.Log
import com.multiplayer.gbalink.core.GbaEmulator
import com.multiplayer.gbalink.core.GbaNative
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class P2PConnectionManager(
    private val emulator: GbaEmulator,
    private val nativeCore: GbaNative
) : GbaNative.SioListener {

    companion object {
        private const val TAG = "P2PManager"
        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
    }

    private var socket: DatagramSocket? = null
    private var peerAddress: InetSocketAddress? = null
    private val isRunning = AtomicBoolean(false)

    var connectionState: Int = STATE_DISCONNECTED
        private set

    var onStateChanged: ((Int, String) -> Unit)? = null
    var onLatencyUpdated: ((Long) -> Unit)? = null

    private var isMaster: Boolean = false
    private var lastSequence: Int = 0

    init {
        nativeCore.sioListener = this
    }

    /**
     * Encodes public endpoint into a short shareable Room Code
     */
    fun encodeEndpoint(address: InetSocketAddress): String {
        val ipBytes = address.address.address
        val port = address.port
        val buffer = ByteBuffer.allocate(6)
        buffer.put(ipBytes)
        buffer.putShort(port.toShort())
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP or Base64.URL_SAFE)
            .trimEnd('=')
    }

    /**
     * Decodes short Room Code back into an InetSocketAddress
     */
    fun decodeEndpoint(roomCode: String): InetSocketAddress? {
        return try {
            var padded = roomCode
            while (padded.length % 4 != 0) padded += "="
            val bytes = Base64.decode(padded, Base64.NO_WRAP or Base64.URL_SAFE)
            if (bytes.size != 6) return null
            val buffer = ByteBuffer.wrap(bytes)
            val ipBytes = ByteArray(4)
            buffer.get(ipBytes)
            val port = buffer.short.toInt() and 0xFFFF
            val inetAddress = java.net.InetAddress.getByAddress(ipBytes)
            InetSocketAddress(inetAddress, port)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding room code: $roomCode", e)
            null
        }
    }

    /**
     * Start hosting as Master (Player 1)
     */
    suspend fun createRoom(): String? = withContext(Dispatchers.IO) {
        disconnect()
        isMaster = true
        nativeCore.nativeSetLinkRole(GbaNative.ROLE_MASTER)

        socket = DatagramSocket()
        val stun = StunClient()
        val stunResult = stun.discoverPublicEndpoint(socket)

        if (stunResult == null) {
            updateState(STATE_DISCONNECTED, "No se pudo obtener endpoint STUN")
            return@withContext null
        }

        val roomCode = encodeEndpoint(stunResult.publicAddress)
        updateState(STATE_CONNECTING, "Sala creada. Código: $roomCode")
        startReceiveLoop()
        roomCode
    }

    /**
     * Join an existing room as Slave (Player 2) using Room Code
     */
    suspend fun joinRoom(roomCode: String): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        isMaster = false
        nativeCore.nativeSetLinkRole(GbaNative.ROLE_SLAVE)

        val targetAddress = decodeEndpoint(roomCode)
        if (targetAddress == null) {
            updateState(STATE_DISCONNECTED, "Código de sala inválido")
            return@withContext false
        }
        peerAddress = targetAddress

        socket = DatagramSocket()
        val stun = StunClient()
        val stunResult = stun.discoverPublicEndpoint(socket)

        updateState(STATE_CONNECTING, "Conectando P2P a $targetAddress...")
        startReceiveLoop()

        // UDP Hole Punching burst
        launch {
            for (i in 0 until 10) {
                if (connectionState == STATE_CONNECTED) break
                sendPacket(LinkCableProtocol.buildHandshakePacket(GbaNative.ROLE_SLAVE, 0))
                delay(200)
            }
        }

        true
    }

    private fun startReceiveLoop() {
        isRunning.set(true)
        CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isRunning.get() && socket != null && !socket!!.isClosed) {
                try {
                    socket?.receive(packet)
                    val senderAddress = packet.socketAddress as InetSocketAddress

                    if (peerAddress == null) {
                        peerAddress = senderAddress
                        Log.i(TAG, "Peer locked to: $peerAddress")
                    }

                    handleIncomingPacket(packet.data, packet.length)
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Receive error", e)
                    }
                }
            }
        }

        // Keepalive & ping loop
        CoroutineScope(Dispatchers.IO).launch {
            while (isRunning.get()) {
                if (connectionState == STATE_CONNECTED) {
                    sendPacket(LinkCableProtocol.buildPingPacket(System.currentTimeMillis()))
                }
                delay(1000)
            }
        }
    }

    private fun handleIncomingPacket(data: ByteArray, length: Int) {
        if (length == 0) return
        val type = data[0]

        when (type) {
            LinkCableProtocol.MSG_HANDSHAKE -> {
                Log.i(TAG, "P2P Handshake received!")
                if (connectionState != STATE_CONNECTED) {
                    updateState(STATE_CONNECTED, if (isMaster) "Conectado (Master - J1)" else "Conectado (Slave - J2)")
                    // Respond with handshake
                    sendPacket(LinkCableProtocol.buildHandshakePacket(if (isMaster) GbaNative.ROLE_MASTER else GbaNative.ROLE_SLAVE, 0))
                }
            }

            LinkCableProtocol.MSG_SIO_TRANSFER -> {
                val sio = LinkCableProtocol.parseSioTransfer(data) ?: return
                if (isMaster) {
                    // Master receives Slave's response -> Complete SIO transfer in native core
                    emulator.onSioPacketReceived(sio.masterData, sio.slaveData)
                } else {
                    // Slave receives Master's transfer request
                    // Reply back with our local SIOMLT_SEND and trigger IRQ
                    val localData: Short = 0x0000 // In native core, it reads m_siomlt_send
                    sendPacket(LinkCableProtocol.buildSioTransferPacket(sio.masterData, localData, sio.sequence))
                    emulator.onSioPacketReceived(sio.masterData, localData)
                }
            }

            LinkCableProtocol.MSG_PING -> {
                val buf = ByteBuffer.wrap(data, 1, 8)
                val ts = buf.long
                sendPacket(LinkCableProtocol.buildPongPacket(ts))
            }

            LinkCableProtocol.MSG_PONG -> {
                val buf = ByteBuffer.wrap(data, 1, 8)
                val ts = buf.long
                val latency = System.currentTimeMillis() - ts
                onLatencyUpdated?.invoke(latency)
            }

            LinkCableProtocol.MSG_DISCONNECT -> {
                updateState(STATE_DISCONNECTED, "El compañero se ha desconectado")
            }
        }
    }

    /**
     * SioListener callback from C++ JNI when Master initiates transfer
     */
    override fun onSioMasterTransferInitiated(masterData: Short) {
        if (connectionState != STATE_CONNECTED || peerAddress == null) {
            // Standalone fallback: echo back
            emulator.onSioPacketReceived(masterData, 0xFFFF.toShort())
            return
        }

        lastSequence++
        val packet = LinkCableProtocol.buildSioTransferPacket(masterData, 0xFFFF.toShort(), lastSequence)
        sendPacket(packet)
    }

    private fun sendPacket(data: ByteArray) {
        val target = peerAddress ?: return
        val sock = socket ?: return
        try {
            val packet = DatagramPacket(data, data.size, target)
            sock.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending UDP packet", e)
        }
    }

    private fun updateState(newState: Int, message: String) {
        connectionState = newState
        CoroutineScope(Dispatchers.Main).launch {
            onStateChanged?.invoke(newState, message)
        }
    }

    fun disconnect() {
        isRunning.set(false)
        try {
            if (connectionState == STATE_CONNECTED) {
                sendPacket(byteArrayOf(LinkCableProtocol.MSG_DISCONNECT))
            }
            socket?.close()
        } catch (ignored: Exception) {
        }
        socket = null
        peerAddress = null
        nativeCore.nativeSetLinkRole(GbaNative.ROLE_STANDALONE)
        updateState(STATE_DISCONNECTED, "Desconectado")
    }
}
