package com.multiplayer.gbalink.showdown

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ShowdownClient {

    companion object {
        private const val TAG = "ShowdownClient"
        private const val WS_URL = "wss://sim3.psim.us/showdown/websocket"
        private const val LOGIN_API_URL = "https://play.pokemonshowdown.com/~~showdown/action.php"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // For WebSocket long-lived connection
        .build()

    private var webSocket: WebSocket? = null
    private var challstr: String? = null

    var currentUser: ShowdownUser = ShowdownUser()
        private set

    var currentBattle: BattleState? = null
        private set

    // Callbacks for UI
    var onLoginSuccess: ((String) -> Unit)? = null
    var onLoginFailed: ((String) -> Unit)? = null
    var onSearchStarted: ((String) -> Unit)? = null
    var onBattleStarted: ((BattleState) -> Unit)? = null
    var onBattleUpdated: ((BattleState) -> Unit)? = null
    var onBattleEnded: ((String) -> Unit)? = null
    var onChallengeReceived: ((Challenge) -> Unit)? = null
    var onStatusMessage: ((String) -> Unit)? = null
    var onBattleVisualEvent: ((BattleVisualEvent) -> Unit)? = null
    var onLobbyChatMessage: ((ChatMessage) -> Unit)? = null
    var onBattleChatMessage: ((ChatMessage) -> Unit)? = null
    var onTimerUpdated: ((message: String, isTimerOn: Boolean) -> Unit)? = null
    var onUserDetailsReceived: ((JSONObject) -> Unit)? = null
    var onTeamPreview: ((List<PokemonInfo>) -> Unit)? = null
    var onPublicRoomsReceived: ((List<Pair<String, String>>) -> Unit)? = null

    fun connect() {
        if (webSocket != null) return

        val request = Request.Builder().url(WS_URL).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Showdown WebSocket connected")
                CoroutineScope(Dispatchers.Main).launch {
                    onStatusMessage?.invoke("Conectado a Pokémon Showdown")
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Showdown WebSocket closing: $reason")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Showdown WebSocket failure", t)
                CoroutineScope(Dispatchers.Main).launch {
                    onStatusMessage?.invoke("Error de conexión: ${t.message}")
                }
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        currentBattle = null
        challstr = null
    }

    fun sendRaw(message: String) {
        Log.d(TAG, ">> $message")
        webSocket?.send(message)
    }

    /**
     * Reserve / Login username with Showdown's authentication API
     */
    fun login(username: String, password: String? = null) {
        val chall = challstr
        if (chall == null) {
            onLoginFailed?.invoke("Aún no se ha recibido el handshake del servidor (challstr)")
            return
        }

        val cleanUser = username.trim()
        val userId = cleanUser.lowercase().replace(Regex("[^a-z0-9]"), "")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (password.isNullOrEmpty()) {
                    // Unregistered nickname / reserve name using act=getassertion
                    val formBody = FormBody.Builder()
                        .add("act", "getassertion")
                        .add("userid", userId)
                        .add("challstr", chall)
                        .build()

                    val request = Request.Builder()
                        .url(LOGIN_API_URL)
                        .post(formBody)
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        val respStr = response.body?.string()?.trim() ?: ""
                        if (respStr.startsWith(";;")) {
                            withContext(Dispatchers.Main) {
                                onLoginFailed?.invoke(respStr.substring(2))
                            }
                        } else if (respStr == ";") {
                            withContext(Dispatchers.Main) {
                                onLoginFailed?.invoke("El nombre '$cleanUser' requiere contraseña porque ya fue registrado. Ingresa tu contraseña o usa otro apodo.")
                            }
                        } else if (respStr.isNotEmpty()) {
                            // Valid assertion token received from Showdown
                            sendRaw("|/trn $cleanUser,0,$respStr")
                        } else {
                            withContext(Dispatchers.Main) {
                                onLoginFailed?.invoke("Respuesta vacía del servidor Showdown")
                            }
                        }
                    }
                } else {
                    // Registered account login using act=login
                    val formBody = FormBody.Builder()
                        .add("act", "login")
                        .add("name", cleanUser)
                        .add("pass", password)
                        .add("challstr", chall)
                        .build()

                    val request = Request.Builder()
                        .url(LOGIN_API_URL)
                        .post(formBody)
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        val respStr = response.body?.string()?.trim() ?: ""
                        val jsonStr = if (respStr.startsWith("]")) respStr.substring(1) else respStr
                        val json = JSONObject(jsonStr)

                        if (json.has("assertion")) {
                            val assertion = json.getString("assertion")
                            if (assertion.startsWith(";;")) {
                                withContext(Dispatchers.Main) {
                                    onLoginFailed?.invoke(assertion.substring(2))
                                }
                            } else {
                                sendRaw("|/trn $cleanUser,0,$assertion")
                            }
                        } else if (json.has("actionerror")) {
                            withContext(Dispatchers.Main) {
                                onLoginFailed?.invoke(json.getString("actionerror"))
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                onLoginFailed?.invoke("Error al iniciar sesión con contraseña")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in Showdown login", e)
                withContext(Dispatchers.Main) {
                    onLoginFailed?.invoke("Error de red al iniciar sesión: ${e.message}")
                }
            }
        }
    }


    /**
     * Search for an opponent on the ladder (e.g. "gen3randombattle", "gen3ou", "gen9ou")
     */
    fun searchBattle(format: String = "gen3randombattle", packedTeam: String? = null) {
        if (!packedTeam.isNullOrEmpty()) {
            sendRaw("|/utm $packedTeam")
        } else {
            sendRaw("|/utm null")
        }
        sendRaw("|/search $format")
        onSearchStarted?.invoke(format)
    }

    fun cancelSearch(format: String = "gen3randombattle") {
        sendRaw("|/cancelsearch $format")
    }

    /**
     * Challenge a specific player
     */
    fun challengeUser(targetUser: String, format: String = "gen3randombattle", packedTeam: String? = null) {
        if (!packedTeam.isNullOrEmpty()) {
            sendRaw("|/utm $packedTeam")
        } else {
            sendRaw("|/utm null")
        }
        sendRaw("|/challenge $targetUser, $format")
    }

    fun acceptChallenge(fromUser: String, packedTeam: String? = null) {
        if (!packedTeam.isNullOrEmpty()) {
            sendRaw("|/utm $packedTeam")
        }
        sendRaw("|/accept $fromUser")
    }

    fun rejectChallenge(fromUser: String) {
        sendRaw("|/reject $fromUser")
    }

    /**
     * Lobby Chat
     */
    fun joinLobby() {
        sendRaw("|/join lobby")
    }

    fun sendLobbyChat(message: String) {
        if (message.isNotBlank()) {
            sendRaw("lobby|$message")
        }
    }

    /**
     * In-Battle Chat
     */
    fun sendBattleChat(message: String) {
        val battle = currentBattle ?: return
        if (message.isNotBlank()) {
            sendRaw("${battle.roomId}|$message")
        }
    }

    /**
     * Spectate a battle by room ID or URL
     */
    fun spectateBattle(rawRoomOrUrl: String) {
        val clean = rawRoomOrUrl.trim()
            .removePrefix("https://play.pokemonshowdown.com/")
            .removePrefix("http://play.pokemonshowdown.com/")
            .removePrefix("/")
        val roomId = if (clean.startsWith("battle-")) clean else "battle-$clean"

        val specBattle = BattleState(roomId = roomId, isSpectating = true)
        currentBattle = specBattle
        onBattleStarted?.invoke(specBattle)
        sendRaw("|/join $roomId")
    }

    fun leaveBattle() {
        val battle = currentBattle ?: return
        sendRaw("${battle.roomId}|/leave")
        currentBattle = null
    }

    /**
     * Team Preview Choice (Order of Pokemon)
     */
    fun chooseTeamOrder(order: String) {
        val battle = currentBattle ?: return
        sendRaw("${battle.roomId}|/team $order")
    }

    /**
     * Battle moves & switches
     */
    fun chooseMove(slot: Int, specialAction: String? = null) {
        val battle = currentBattle ?: return
        val cmd = if (!specialAction.isNullOrEmpty()) "|/choose move $slot $specialAction" else "|/choose move $slot"
        sendRaw("${battle.roomId}$cmd")
        battle.isMyTurn = false
        onBattleUpdated?.invoke(battle)
    }

    fun chooseSwitch(pokemonSlot: Int) {
        val battle = currentBattle ?: return
        sendRaw("${battle.roomId}|/choose switch $pokemonSlot")
        battle.isMyTurn = false
        onBattleUpdated?.invoke(battle)
    }

    fun forfeit() {
        val battle = currentBattle ?: return
        sendRaw("${battle.roomId}|/forfeit")
    }

    fun toggleTimer(on: Boolean) {
        val battle = currentBattle ?: return
        sendRaw("${battle.roomId}|/timer ${if (on) "on" else "off"}")
    }

    fun lookupUser(username: String) {
        sendRaw("|/cmd user $username")
    }

    fun requestPublicRooms() {
        sendRaw("|/cmd roomlist")
    }


    // Message Protocol Handler
    private fun handleIncomingMessage(text: String) {
        val lines = text.split("\n")
        var roomId = ""

        for (line in lines) {
            var raw = line
            if (raw.startsWith(">")) {
                roomId = raw.substring(1)
                continue
            }

            if (!raw.startsWith("|")) continue
            val parts = raw.split("|")
            if (parts.size < 2) continue
            val cmd = parts[1]

            when (cmd) {
                "challstr" -> {
                    // |challstr|CHALLSTR
                    challstr = if (parts.size >= 4) "${parts[2]}|${parts[3]}" else parts.getOrNull(2) ?: ""
                    Log.i(TAG, "challstr received: $challstr")
                }

                "updateuser" -> {
                    // |updateuser|USERNAME|LOGGEDIN|AVATAR
                    val name = parts.getOrNull(2) ?: ""
                    val isLogged = parts.getOrNull(3) == "1"
                    val avatar = parts.getOrNull(4) ?: ""
                    currentUser = ShowdownUser(name, isLogged, avatar)
                    if (isLogged) {
                        CoroutineScope(Dispatchers.Main).launch {
                            onLoginSuccess?.invoke(name)
                        }
                    }
                }

                "updatechallenges" -> {
                    // |updatechallenges|JSON
                    val jsonStr = parts.getOrNull(2) ?: "{}"
                    try {
                        val json = JSONObject(jsonStr)
                        if (json.has("challengesFrom")) {
                            val fromObj = json.getJSONObject("challengesFrom")
                            val keys = fromObj.keys()
                            while (keys.hasNext()) {
                                val fromUser = keys.next()
                                val format = fromObj.getString(fromUser)
                                CoroutineScope(Dispatchers.Main).launch {
                                    onChallengeReceived?.invoke(Challenge(fromUser, format))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing challenges", e)
                    }
                }

                "init" -> {
                    // |init|battle
                    if (parts.getOrNull(2) == "battle") {
                        val isSpec = currentBattle?.isSpectating == true
                        val newBattle = BattleState(roomId = roomId, isSpectating = isSpec)
                        currentBattle = newBattle
                        CoroutineScope(Dispatchers.Main).launch {
                            onBattleStarted?.invoke(newBattle)
                        }
                    }
                }


                "player" -> {
                    // |player|p1|Username
                    val battle = currentBattle
                    if (battle != null && battle.roomId == roomId && parts.size >= 4) {
                        val side = parts[2]
                        val name = parts[3]
                        if (name.equals(currentUser.username, ignoreCase = true)) {
                            battle.myName = name
                            battle.mySide = side
                        } else {
                            battle.opponentName = name
                        }
                        notifyBattleUpdated(battle)
                    }
                }

                "request" -> {
                    // |request|JSON
                    val jsonStr = parts.getOrNull(2) ?: ""
                    if (jsonStr.isNotEmpty() && currentBattle != null) {
                        parseBattleRequest(jsonStr, currentBattle!!)
                    }
                }

                "teamsize" -> {
                    // |teamsize|p1|6
                    val side = parts.getOrNull(2) ?: ""
                    val count = parts.getOrNull(3)?.toIntOrNull() ?: 6
                    val battle = currentBattle
                    if (battle != null) {
                        val isOpponent = (battle.mySide.isNotEmpty() && side != battle.mySide) || (battle.mySide.isEmpty() && side == "p2")
                        if (isOpponent) {
                            battle.opponentTeam.clear()
                            for (i in 1..count) {
                                battle.opponentTeam.add(
                                    PokemonInfo(
                                        ident = "${side}a: Pokemon $i",
                                        name = "Pokémon",
                                        species = "pokeball",
                                        hp = 100,
                                        maxHp = 100,
                                        isRevealed = false,
                                        slot = i
                                    )
                                )
                            }
                            notifyBattleUpdated(battle)
                        }
                    }
                }

                "poke" -> {
                    // |poke|p2|Gengar, L50, M|item
                    val side = parts.getOrNull(2) ?: ""
                    val details = parts.getOrNull(3) ?: ""
                    val battle = currentBattle
                    if (battle != null && details.isNotEmpty()) {
                        val isOpponent = (battle.mySide.isNotEmpty() && side != battle.mySide) || (battle.mySide.isEmpty() && side == "p2")
                        if (isOpponent) {
                            val species = details.split(",")[0].trim()
                            val existing = battle.opponentTeam.firstOrNull { it.species.equals(species, ignoreCase = true) }
                            if (existing == null) {
                                val unrevealed = battle.opponentTeam.firstOrNull { !it.isRevealed }
                                if (unrevealed != null) {
                                    val idx = battle.opponentTeam.indexOf(unrevealed)
                                    battle.opponentTeam[idx] = unrevealed.copy(
                                        species = species,
                                        name = species
                                    )
                                }
                            }
                            notifyBattleUpdated(battle)
                        }
                    }
                }

                "switch", "drag", "replace" -> {
                    // |switch|p1a: Charizard|Charizard, L80, M|100/100
                    val battle = currentBattle
                    if (battle != null && parts.size >= 5) {
                        val slot = parts[2]
                        val details = parts[3]
                        val hpStr = parts[4]

                        val name = slot.substringAfter(": ").trim()
                        val species = details.split(",")[0].trim()
                        val condParts = hpStr.split(" ")
                        val hpParts = condParts[0].split("/")
                        val hp = hpParts.getOrNull(0)?.toIntOrNull() ?: 100
                        val maxHp = hpParts.getOrNull(1)?.toIntOrNull() ?: 100
                        val status = condParts.getOrNull(1) ?: ""

                        val isUser = isSlotUser(battle, slot)
                        if (isUser) {
                            val poke = PokemonInfo(
                                ident = slot,
                                name = name,
                                species = species,
                                hp = hp,
                                maxHp = maxHp,
                                status = status,
                                isActive = true,
                                isRevealed = true
                            )
                            battle.myActive = poke
                            battle.isForceSwitch = false
                            battle.battleLogs.add("¡Adelante, $name!")

                            for (p in battle.myTeam) {
                                p.isActive = p.name.equals(name, ignoreCase = true) || p.species.equals(species, ignoreCase = true)
                                if (p.isActive) {
                                    p.hp = hp
                                    p.maxHp = maxHp
                                    p.status = status
                                    p.isFainted = false
                                }
                            }
                        } else {
                            val poke = PokemonInfo(
                                ident = slot,
                                name = name,
                                species = species,
                                hp = hp,
                                maxHp = maxHp,
                                status = status,
                                isActive = true,
                                isRevealed = true
                            )
                            battle.opponentActive = poke
                            battle.battleLogs.add("¡El rival sacó a $name!")

                            if (battle.opponentTeam.isEmpty()) {
                                val oppSide = if (battle.mySide == "p1") "p2" else "p1"
                                for (i in 1..6) {
                                    battle.opponentTeam.add(
                                        PokemonInfo(
                                            ident = "${oppSide}a: Pokemon $i",
                                            name = "Pokémon",
                                            species = "pokeball",
                                            hp = 100,
                                            maxHp = 100,
                                            isRevealed = false,
                                            slot = i
                                        )
                                    )
                                }
                            }

                            var targetIdx = battle.opponentTeam.indexOfFirst {
                                it.species.equals(species, ignoreCase = true) || it.name.equals(name, ignoreCase = true)
                            }
                            if (targetIdx == -1) {
                                targetIdx = battle.opponentTeam.indexOfFirst { !it.isRevealed }
                            }

                            if (targetIdx != -1) {
                                battle.opponentTeam[targetIdx] = PokemonInfo(
                                    ident = slot,
                                    name = name,
                                    species = species,
                                    hp = hp,
                                    maxHp = maxHp,
                                    status = status,
                                    isActive = true,
                                    isRevealed = true,
                                    isFainted = false,
                                    slot = targetIdx + 1
                                )
                            } else {
                                battle.opponentTeam.add(
                                    PokemonInfo(
                                        ident = slot,
                                        name = name,
                                        species = species,
                                        hp = hp,
                                        maxHp = maxHp,
                                        status = status,
                                        isActive = true,
                                        isRevealed = true,
                                        isFainted = false,
                                        slot = battle.opponentTeam.size + 1
                                    )
                                )
                            }

                            for (i in battle.opponentTeam.indices) {
                                if (i != targetIdx) {
                                    battle.opponentTeam[i].isActive = false
                                }
                            }
                        }

                        notifyBattleUpdated(battle)
                        CoroutineScope(Dispatchers.Main).launch {
                            onBattleVisualEvent?.invoke(BattleVisualEvent.Switch(isUser, name, species))
                        }
                    }
                }

                "move" -> {
                    // |move|p1a: Charizard|Flamethrower|p2a: Venusaur
                    val battle = currentBattle
                    if (battle != null && parts.size >= 4) {
                        val slot = parts[2]
                        val user = slot.substringAfter(": ").trim()
                        val move = parts[3]
                        val isUser = isSlotUser(battle, slot)
                        battle.battleLogs.add("¡$user usó $move!")
                        notifyBattleUpdated(battle)
                        CoroutineScope(Dispatchers.Main).launch {
                            onBattleVisualEvent?.invoke(BattleVisualEvent.Move(isUser, move, user))
                        }
                    }
                }

                "-damage", "-heal" -> {
                    // |-damage|p2a: Venusaur|30/100
                    val battle = currentBattle
                    if (battle != null && parts.size >= 4) {
                        val slot = parts[2]
                        val name = slot.substringAfter(": ").trim()
                        val hpStr = parts[3]
                        val condParts = hpStr.split(" ")
                        val hpParts = condParts[0].split("/")
                        val newHp = hpParts.getOrNull(0)?.toIntOrNull() ?: 0
                        val maxHp = hpParts.getOrNull(1)?.toIntOrNull() ?: 100
                        val status = condParts.getOrNull(1) ?: ""
                        val isUser = isSlotUser(battle, slot)

                        val oldHp = if (isUser) (battle.myActive?.hp ?: maxHp) else (battle.opponentActive?.hp ?: maxHp)
                        val damageAmount = (oldHp - newHp).coerceAtLeast(0)

                        if (isUser) {
                            battle.myActive?.hp = newHp
                            if (status.isNotEmpty()) battle.myActive?.status = status
                            for (p in battle.myTeam) {
                                if (p.name.equals(name, ignoreCase = true) || p.isActive) {
                                    p.hp = newHp
                                    if (status.isNotEmpty()) p.status = status
                                    if (newHp == 0) {
                                        p.isFainted = true
                                        p.isActive = false
                                    }
                                }
                            }
                        } else {
                            battle.opponentActive?.hp = newHp
                            if (status.isNotEmpty()) battle.opponentActive?.status = status
                            for (p in battle.opponentTeam) {
                                if (p.name.equals(name, ignoreCase = true) || p.isActive) {
                                    p.hp = newHp
                                    if (status.isNotEmpty()) p.status = status
                                    if (newHp == 0) {
                                        p.isFainted = true
                                        p.isActive = false
                                    }
                                }
                            }
                        }
                        notifyBattleUpdated(battle)
                        CoroutineScope(Dispatchers.Main).launch {
                            onBattleVisualEvent?.invoke(BattleVisualEvent.Damage(isUser, oldHp, newHp, maxHp, damageAmount))
                        }
                    }
                }

                "faint" -> {
                    // |faint|p2a: Venusaur
                    val battle = currentBattle
                    if (battle != null && parts.size >= 3) {
                        val slot = parts[2]
                        val name = slot.substringAfter(": ").trim()
                        val isUser = isSlotUser(battle, slot)
                        battle.battleLogs.add("¡$name se debilitó!")
                        if (isUser) {
                            battle.myActive?.hp = 0
                            battle.myActive?.isFainted = true
                            battle.myActive?.isActive = false
                            for (p in battle.myTeam) {
                                if (p.name.equals(name, ignoreCase = true) || p.isActive) {
                                    p.hp = 0
                                    p.isFainted = true
                                    p.isActive = false
                                }
                            }
                            // When player's active pokemon faints, force a switch choice
                            battle.isForceSwitch = true
                            battle.isMyTurn = true
                            battle.availableMoves = emptyList()
                        } else {
                            battle.opponentActive?.hp = 0
                            battle.opponentActive?.isFainted = true
                            battle.opponentActive?.isActive = false
                            for (p in battle.opponentTeam) {
                                if (p.name.equals(name, ignoreCase = true) || p.isActive) {
                                    p.hp = 0
                                    p.isFainted = true
                                    p.isActive = false
                                }
                            }
                        }
                        notifyBattleUpdated(battle)
                        CoroutineScope(Dispatchers.Main).launch {
                            onBattleVisualEvent?.invoke(BattleVisualEvent.Faint(isUser, name))
                        }
                    }
                }

                "c" -> {
                    // |c| USER|MESSAGE
                    if (parts.size >= 4) {
                        val user = parts[2].trim()
                        val msg = parts.subList(3, parts.size).joinToString("|")
                        val chatMsg = ChatMessage(user, msg)
                        if (roomId == "lobby" || roomId.isEmpty()) {
                            CoroutineScope(Dispatchers.Main).launch {
                                onLobbyChatMessage?.invoke(chatMsg)
                            }
                        } else if (currentBattle != null && roomId == currentBattle?.roomId) {
                            currentBattle?.chatMessages?.add(chatMsg)
                            CoroutineScope(Dispatchers.Main).launch {
                                onBattleChatMessage?.invoke(chatMsg)
                            }
                        }
                    }
                }

                "c:" -> {
                    // |c:|TIMESTAMP| USER|MESSAGE
                    if (parts.size >= 5) {
                        val user = parts[3].trim()
                        val msg = parts.subList(4, parts.size).joinToString("|")
                        val chatMsg = ChatMessage(user, msg)
                        if (roomId == "lobby" || roomId.isEmpty()) {
                            CoroutineScope(Dispatchers.Main).launch {
                                onLobbyChatMessage?.invoke(chatMsg)
                            }
                        } else if (currentBattle != null && roomId == currentBattle?.roomId) {
                            currentBattle?.chatMessages?.add(chatMsg)
                            CoroutineScope(Dispatchers.Main).launch {
                                onBattleChatMessage?.invoke(chatMsg)
                            }
                        }
                    }
                }

                "inactive" -> {
                    val msg = parts.getOrNull(2) ?: ""
                    currentBattle?.isTimerActive = true
                    currentBattle?.timerText = msg
                    CoroutineScope(Dispatchers.Main).launch {
                        onTimerUpdated?.invoke(msg, true)
                    }
                }

                "inactiveoff" -> {
                    val msg = parts.getOrNull(2) ?: ""
                    currentBattle?.isTimerActive = false
                    currentBattle?.timerText = ""
                    CoroutineScope(Dispatchers.Main).launch {
                        onTimerUpdated?.invoke(msg, false)
                    }
                }

                "teampreview" -> {
                    val battle = currentBattle
                    if (battle != null) {
                        CoroutineScope(Dispatchers.Main).launch {
                            onTeamPreview?.invoke(battle.myTeam)
                        }
                    }
                }

                "queryresponse" -> {
                    val qType = parts.getOrNull(2)
                    val jsonStr = parts.getOrNull(3) ?: ""
                    if (qType == "userdetails" && jsonStr.isNotEmpty()) {
                        try {
                            val json = JSONObject(jsonStr)
                            CoroutineScope(Dispatchers.Main).launch {
                                onUserDetailsReceived?.invoke(json)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else if (qType == "roomlist" && jsonStr.isNotEmpty()) {
                        try {
                            val json = JSONObject(jsonStr)
                            if (json.has("rooms")) {
                                val roomsObj = json.getJSONObject("rooms")
                                val list = mutableListOf<Pair<String, String>>()
                                val keys = roomsObj.keys()
                                while (keys.hasNext()) {
                                    val rId = keys.next()
                                    val rObj = roomsObj.getJSONObject(rId)
                                    val p1 = rObj.optString("p1", "")
                                    val p2 = rObj.optString("p2", "")
                                    val desc = if (p1.isNotEmpty() && p2.isNotEmpty()) "$p1 vs $p2" else rId
                                    list.add(rId to desc)
                                }
                                CoroutineScope(Dispatchers.Main).launch {
                                    onPublicRoomsReceived?.invoke(list)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                "win" -> {
                    // |win|Username
                    val battle = currentBattle
                    val winner = parts.getOrNull(2) ?: ""
                    if (battle != null) {
                        battle.winner = winner
                        battle.battleLogs.add("¡$winner ganó la batalla!")
                        notifyBattleUpdated(battle)
                        CoroutineScope(Dispatchers.Main).launch {
                            onBattleEnded?.invoke(winner)
                        }
                    }
                }
            }
        }
    }


    private fun parseBattleRequest(jsonStr: String, battle: BattleState) {
        try {
            val json = JSONObject(jsonStr)

            // Parse available active moves
            if (json.has("active")) {
                val activeArr = json.getJSONArray("active")
                if (activeArr.length() > 0) {
                    val activeObj = activeArr.getJSONObject(0)
                    battle.canMegaEvo = activeObj.optBoolean("canMegaEvo", false)
                    battle.canTerastallize = activeObj.optBoolean("canTerastallize", false)
                    battle.canDynamax = activeObj.optBoolean("canDynamax", false)
                    battle.canZMove = activeObj.optBoolean("canZMove", false)

                    val movesArr = activeObj.getJSONArray("moves")
                    val movesList = mutableListOf<MoveInfo>()

                    for (i in 0 until movesArr.length()) {
                        val mObj = movesArr.getJSONObject(i)
                        movesList.add(
                            MoveInfo(
                                slot = i + 1,
                                id = mObj.getString("id"),
                                name = mObj.getString("move"),
                                pp = mObj.getInt("pp"),
                                maxPp = mObj.getInt("maxpp"),
                                target = mObj.optString("target", "normal"),
                                disabled = mObj.optBoolean("disabled", false)
                            )
                        )
                    }
                    battle.availableMoves = movesList
                    battle.isMyTurn = true
                }
            }

            // Check forceSwitch (e.g. when active Pokémon faints or after U-turn/Teleport)
            val forceSwitchArr = json.optJSONArray("forceSwitch")
            val isForced = forceSwitchArr != null && (0 until forceSwitchArr.length()).any { forceSwitchArr.optBoolean(it, false) }
            battle.isForceSwitch = isForced
            if (isForced) {
                battle.availableMoves = emptyList()
                battle.isMyTurn = true
            }

            if (json.optBoolean("teamPreview", false)) {
                CoroutineScope(Dispatchers.Main).launch {
                    onTeamPreview?.invoke(battle.myTeam)
                }
            }

            // Parse team
            if (json.has("side")) {
                val sideObj = json.getJSONObject("side")
                val sideId = sideObj.optString("id", "")
                if (sideId.isNotEmpty()) {
                    battle.mySide = sideId
                }
                val pokeArr = sideObj.getJSONArray("pokemon")
                battle.myTeam.clear()

                for (i in 0 until pokeArr.length()) {
                    val pObj = pokeArr.getJSONObject(i)
                    val ident = pObj.getString("ident")
                    val details = pObj.getString("details")
                    val condition = pObj.getString("condition") // e.g. "280/280" or "0 fnt"

                    val condParts = condition.split(" ")
                    val hpParts = condParts[0].split("/")
                    val hp = hpParts.getOrNull(0)?.toIntOrNull() ?: 0
                    val maxHp = hpParts.getOrNull(1)?.toIntOrNull() ?: 100
                    val status = condParts.getOrNull(1) ?: ""
                    val isFnt = status == "fnt" || hp == 0
                    val isActive = pObj.optBoolean("active", false)

                    val poke = PokemonInfo(
                        ident = ident,
                        name = ident.substringAfter(": ").trim(),
                        species = details.split(",")[0].trim(),
                        hp = hp,
                        maxHp = maxHp,
                        status = status,
                        isActive = isActive,
                        isFainted = isFnt,
                        isRevealed = true,
                        slot = i + 1
                    )
                    battle.myTeam.add(poke)
                    if (isActive && !isFnt) {
                        battle.myActive = poke
                    }
                }
            }

            notifyBattleUpdated(battle)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing battle request", e)
        }
    }

    private fun isSlotUser(battle: BattleState, slot: String): Boolean {
        if (battle.mySide.isNotEmpty()) {
            return slot.startsWith(battle.mySide)
        }
        val myName = currentUser.username
        if (myName.isNotEmpty()) {
            if (slot.startsWith("p1") && battle.myName.equals(myName, ignoreCase = true)) return true
            if (slot.startsWith("p2") && !battle.myName.equals(myName, ignoreCase = true)) return true
        }
        return slot.startsWith("p1")
    }

    private fun notifyBattleUpdated(battle: BattleState) {
        CoroutineScope(Dispatchers.Main).launch {
            onBattleUpdated?.invoke(battle)
        }
    }
}

