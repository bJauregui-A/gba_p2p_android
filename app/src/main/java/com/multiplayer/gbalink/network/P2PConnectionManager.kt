package com.multiplayer.gbalink.network

import android.util.Base64
import android.util.Log
import com.multiplayer.gbalink.core.GbaEmulator
import com.multiplayer.gbalink.core.GbaNative
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class P2PConnectionManager(
    private val emulator: GbaEmulator,
    private val nativeCore: GbaNative
) : GbaNative.LinkListener {

    companion object {
        private const val TAG = "P2PManager"
        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2

        private const val CODE_V4 = 2
        private const val CODE_V6 = 3
        // Public relay used only to tell the host the joiner's address (a few bytes, once)
        private const val SIGNAL_URL = "https://ntfy.sh/"
        private const val PUNCH_INTERVAL_MS = 250L
        private const val PUNCH_TIMEOUT_MS = 45_000L
    }

    /** Where a peer can be reached: public address seen by STUN and LAN address (same Wi-Fi). */
    data class Endpoints(val public: InetSocketAddress, val lan: InetSocketAddress?)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private var jobs = SupervisorJob()
    private val scope get() = CoroutineScope(Dispatchers.IO + jobs)
    private val punchTargets = CopyOnWriteArraySet<InetSocketAddress>()

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
        nativeCore.linkListener = this
    }

    /** Room code = version + public IP:port + LAN IPv4 (same port), base64url. */
    fun encodeEndpoints(ep: Endpoints): String {
        val pub = ep.public.address.address
        val lan = (ep.lan?.address as? Inet4Address)?.address ?: ByteArray(4)
        val buffer = ByteBuffer.allocate(1 + pub.size + 2 + 4)
        buffer.put((if (pub.size == 4) CODE_V4 else CODE_V6).toByte())
        buffer.put(pub)
        buffer.putShort(ep.public.port.toShort())
        buffer.put(lan)
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    fun decodeEndpoints(roomCode: String): Endpoints? = try {
        val clean = roomCode.trim().replace(" ", "").replace("\n", "")
        val bytes = Base64.decode(clean, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        val bb = ByteBuffer.wrap(bytes)
        when {
            // Legacy 6-byte codes (IPv4 + port)
            bytes.size == 6 -> {
                val ip = ByteArray(4).also { bb.get(it) }
                Endpoints(InetSocketAddress(InetAddress.getByAddress(ip), bb.short.toInt() and 0xFFFF), null)
            }
            bytes.isNotEmpty() && (bytes[0].toInt() == CODE_V4 || bytes[0].toInt() == CODE_V6) -> {
                val ipLen = if (bb.get().toInt() == CODE_V4) 4 else 16
                val ip = ByteArray(ipLen).also { bb.get(it) }
                val port = bb.short.toInt() and 0xFFFF
                val lanBytes = ByteArray(4).also { bb.get(it) }
                val lan = if (lanBytes.all { it == 0.toByte() }) null
                    else InetSocketAddress(InetAddress.getByAddress(lanBytes), port)
                Endpoints(InetSocketAddress(InetAddress.getByAddress(ip), port), lan)
            }
            else -> null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error decoding room code: $roomCode", e)
        null
    }

    private fun localIpv4(): InetAddress? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 } // prefer Wi-Fi
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    } catch (e: Exception) {
        null
    }

    private fun signalTopic(roomCode: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(("gbalink:" + roomCode).toByteArray())
        return "gbalink_" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /** Opens the UDP socket and learns our endpoints. Returns null (and reports why) on failure. */
    private suspend fun openSocketAndDiscover(): Endpoints? {
        val sock = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        socket = sock
        updateState(STATE_CONNECTING, "Obteniendo tu dirección pública (STUN)...")
        val stun = StunClient()
        val result = stun.discoverPublicEndpoint(sock)
        val lan = localIpv4()?.let { InetSocketAddress(it, sock.localPort) }
        if (result == null) {
            if (lan != null) {
                // No internet path, but players on the same Wi-Fi can still connect
                updateState(STATE_CONNECTING, "STUN no disponible (${stun.lastError}). Solo red local.")
                return Endpoints(lan, lan)
            }
            updateState(STATE_DISCONNECTED, "No se pudo obtener tu dirección pública: ${stun.lastError}")
            return null
        }
        if (result.symmetricNat) {
            Log.w(TAG, "Symmetric NAT detected")
        }
        natWarning = if (result.symmetricNat)
            " · Tu red usa NAT estricto: si no conecta, prueben en el mismo Wi-Fi o con otra red."
        else ""
        return Endpoints(result.publicAddress, lan)
    }

    private var natWarning = ""

    /**
     * Start hosting as Master (Player 1). The returned code goes to the other player; their
     * address comes back automatically through the signaling relay so both sides punch the NAT.
     */
    suspend fun createRoom(): String? = withContext(Dispatchers.IO) {
        disconnect()
        jobs = SupervisorJob()
        isMaster = true
        nativeCore.nativeSetLinkRole(GbaNative.ROLE_MASTER)

        val ep = openSocketAndDiscover() ?: return@withContext null
        val roomCode = encodeEndpoints(ep)
        updateState(STATE_CONNECTING, "Sala creada. Comparte el código y espera al jugador 2.$natWarning")
        startReceiveLoop()
        startPunching()
        listenForJoiners(roomCode)
        roomCode
    }

    /**
     * Join an existing room as Slave (Player 2) using Room Code
     */
    suspend fun joinRoom(roomCode: String): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        jobs = SupervisorJob()
        isMaster = false
        nativeCore.nativeSetLinkRole(GbaNative.ROLE_SLAVE)

        val host = decodeEndpoints(roomCode)
        if (host == null) {
            updateState(STATE_DISCONNECTED, "Código de sala inválido")
            return@withContext false
        }

        val mine = openSocketAndDiscover() ?: return@withContext false
        punchTargets.add(host.public)
        host.lan?.let { punchTargets.add(it) }

        updateState(STATE_CONNECTING, "Conectando con el anfitrión...$natWarning")
        startReceiveLoop()
        startPunching()
        announceToHost(roomCode, mine)
        true
    }

    /** Joiner: publish our endpoints so the host starts sending packets to us too. */
    private fun announceToHost(roomCode: String, mine: Endpoints) {
        scope.launch {
            val body = encodeEndpoints(mine).toRequestBody("text/plain".toMediaType())
            repeat(3) { attempt ->
                try {
                    http.newCall(Request.Builder().url(SIGNAL_URL + signalTopic(roomCode)).post(body).build())
                        .execute().use { if (it.isSuccessful) return@launch }
                } catch (e: Exception) {
                    Log.w(TAG, "Signal publish failed (attempt $attempt)", e)
                }
                delay(1500)
            }
        }
    }

    /** Host: poll the relay for joiners' endpoints and add them as punch targets. */
    private fun listenForJoiners(roomCode: String) {
        val since = System.currentTimeMillis() / 1000 - 5
        val seen = HashSet<String>()
        scope.launch {
            val deadline = System.currentTimeMillis() + 10 * 60_000L
            while (isActive && isRunning.get() && connectionState != STATE_CONNECTED && System.currentTimeMillis() < deadline) {
                try {
                    val url = SIGNAL_URL + signalTopic(roomCode) + "/json?poll=1&since=" + since
                    http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        resp.body?.string()?.lineSequence()?.forEach { line ->
                            if (line.isBlank()) return@forEach
                            val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                            if (json.optString("event") != "message") return@forEach
                            val id = json.optString("id")
                            if (!seen.add(id)) return@forEach
                            val peer = decodeEndpoints(json.optString("message")) ?: return@forEach
                            Log.i(TAG, "Joiner announced: $peer")
                            punchTargets.add(peer.public)
                            peer.lan?.let { punchTargets.add(it) }
                            updateState(STATE_CONNECTING, "Jugador 2 encontrado, conectando...")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Signal poll failed", e)
                }
                delay(1500)
            }
        }
    }

    /** UDP hole punching: both sides fire handshakes at every known address of the other. */
    private fun startPunching() {
        scope.launch {
            val start = System.currentTimeMillis()
            val role = if (isMaster) GbaNative.ROLE_MASTER else GbaNative.ROLE_SLAVE
            val packet = LinkCableProtocol.buildHandshakePacket(role, 0)
            var lastNoticeAt = start
            while (isActive && isRunning.get() && connectionState != STATE_CONNECTED) {
                for (target in punchTargets) sendTo(packet, target)
                delay(PUNCH_INTERVAL_MS)
                val now = System.currentTimeMillis()
                // The joiner gives up after a while; the host keeps the room open
                if (!isMaster && now - start > PUNCH_TIMEOUT_MS) {
                    updateState(STATE_DISCONNECTED, "No se pudo conectar con el anfitrión.$natWarning")
                    break
                }
                if (!isMaster && now - lastNoticeAt > 10_000) {
                    lastNoticeAt = now
                    updateState(STATE_CONNECTING, "Conectando... (${(now - start) / 1000}s)")
                }
            }
        }
    }

    private fun isLocal(addr: InetSocketAddress): Boolean {
        val a = addr.address ?: return false
        return a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress
    }

    /** Connected over the public address: try the peer's LAN address too (see receive loop). */
    private fun probeLocalRoute() {
        val current = peerAddress ?: return
        if (isLocal(current)) return
        val locals = punchTargets.filter { isLocal(it) && it != current }
        if (locals.isEmpty()) return
        scope.launch {
            val hello = LinkCableProtocol.buildHandshakePacket(if (isMaster) GbaNative.ROLE_MASTER else GbaNative.ROLE_SLAVE, 0)
            repeat(10) {
                if (peerAddress?.let { isLocal(it) } == true) return@launch
                locals.forEach { sendTo(hello, it) }
                delay(150)
            }
        }
    }

    private fun sendTo(data: ByteArray, target: InetSocketAddress) {
        try {
            socket?.send(DatagramPacket(data, data.size, target))
        } catch (e: Exception) {
            Log.w(TAG, "send to $target failed: ${e.message}")
        }
    }

    private fun startReceiveLoop() {
        isRunning.set(true)
        scope.launch {
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isRunning.get() && socket != null && !socket!!.isClosed) {
                try {
                    socket?.receive(packet)
                    val senderAddress = packet.socketAddress as InetSocketAddress

                    val isHandshake = packet.length > 0 && packet.data[0] == LinkCableProtocol.MSG_HANDSHAKE
                    // Lock onto whichever address (public or LAN) the peer's handshake arrives from
                    if (isHandshake && connectionState != STATE_CONNECTED) {
                        peerAddress = senderAddress
                        Log.i(TAG, "Peer locked to: $peerAddress")
                    } else if (isHandshake && senderAddress != peerAddress &&
                        isLocal(senderAddress) && peerAddress?.let { !isLocal(it) } != false
                    ) {
                        // Same phone / same Wi-Fi: move off the public route (router hairpin,
                        // carrier NAT) to the direct local one, and tell the peer to do the same
                        peerAddress = senderAddress
                        Log.i(TAG, "Switched to local route: $peerAddress")
                        sendPacket(LinkCableProtocol.buildHandshakePacket(if (isMaster) GbaNative.ROLE_MASTER else GbaNative.ROLE_SLAVE, 0))
                        continue
                    } else if (senderAddress != peerAddress) {
                        continue // ignore stray packets (STUN replies, other hosts)
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
        scope.launch {
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
                    val route = if (peerAddress?.let { isLocal(it) } == true) "red local" else "internet"
                    updateState(STATE_CONNECTED, (if (isMaster) "Conectado (Master - J1)" else "Conectado (Slave - J2)") + " · $route")
                    // Respond with handshake
                    sendPacket(LinkCableProtocol.buildHandshakePacket(if (isMaster) GbaNative.ROLE_MASTER else GbaNative.ROLE_SLAVE, 0))
                    probeLocalRoute()
                }
            }

            LinkCableProtocol.MSG_LINK -> {
                val p = LinkCableProtocol.parseLinkPacket(data, length) ?: return
                nativeCore.nativeLinkReceive(p.kind, p.data, p.seq, p.isReply, p.cycles)
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

    /** Emulated serial port → peer (called on the emulation thread, must not block). */
    override fun onLinkSend(kind: Int, data: Int, seq: Int, isReply: Boolean, cycles: Int) {
        if (connectionState != STATE_CONNECTED) return
        sendPacket(LinkCableProtocol.buildLinkPacket(kind, data, seq, isReply, cycles))
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
        // Plug / unplug the virtual Game Link cable
        nativeCore.nativeSetLinkConnected(newState == STATE_CONNECTED)
        CoroutineScope(Dispatchers.Main).launch {
            onStateChanged?.invoke(newState, message)
        }
    }

    fun disconnect() {
        isRunning.set(false)
        jobs.cancel()
        punchTargets.clear()
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
        if (connectionState != STATE_DISCONNECTED) updateState(STATE_DISCONNECTED, "Desconectado")
        connectionState = STATE_DISCONNECTED
    }
}
