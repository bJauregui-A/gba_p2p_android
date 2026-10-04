package com.multiplayer.gbalink.showdown

data class ShowdownUser(
    val username: String = "",
    val isLoggedIn: Boolean = false,
    val avatar: String = ""
)

data class MoveInfo(
    val slot: Int,
    val id: String,
    val name: String,
    val pp: Int,
    val maxPp: Int,
    val target: String = "normal",
    val disabled: Boolean = false
)

data class PokemonInfo(
    val ident: String,
    val name: String,
    val species: String,
    var hp: Int,
    var maxHp: Int,
    var status: String = "", // slp, brn, psn, tox, par, frz, fnt
    var isActive: Boolean = false,
    var isFainted: Boolean = false,
    var isRevealed: Boolean = true, // false = unrevealed Pokeball for opponent
    var slot: Int = 1,
    val moves: List<String> = emptyList()
) {
    val hpPercentage: Int
        get() = if (maxHp > 0) ((hp.toFloat() / maxHp) * 100).toInt() else 0
}

data class BattleState(
    val roomId: String,
    var myName: String = "",
    var opponentName: String = "",
    var mySide: String = "",
    var myActive: PokemonInfo? = null,
    var opponentActive: PokemonInfo? = null,
    val myTeam: MutableList<PokemonInfo> = mutableListOf(),
    val opponentTeam: MutableList<PokemonInfo> = mutableListOf(),
    var availableMoves: List<MoveInfo> = emptyList(),
    var canSwitch: Boolean = true,
    var isForceSwitch: Boolean = false,
    var isMyTurn: Boolean = false,
    val battleLogs: MutableList<String> = mutableListOf(),
    var winner: String? = null,
    var isSpectating: Boolean = false,
    var isTimerActive: Boolean = false,
    var timerText: String = "",
    val chatMessages: MutableList<ChatMessage> = mutableListOf(),
    var canMegaEvo: Boolean = false,
    var canTerastallize: Boolean = false,
    var canDynamax: Boolean = false,
    var canZMove: Boolean = false
)

data class ChatMessage(
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class Challenge(
    val fromUser: String,
    val format: String
)

sealed class BattleVisualEvent {
    data class Move(val isUser: Boolean, val moveName: String, val pokeName: String) : BattleVisualEvent()
    data class Damage(val isUser: Boolean, val oldHp: Int, val newHp: Int, val maxHp: Int, val damageAmount: Int) : BattleVisualEvent()
    data class Switch(val isUser: Boolean, val pokeName: String, val species: String) : BattleVisualEvent()
    data class Faint(val isUser: Boolean, val pokeName: String) : BattleVisualEvent()
}


