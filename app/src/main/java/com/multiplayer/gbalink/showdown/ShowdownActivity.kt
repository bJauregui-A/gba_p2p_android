package com.multiplayer.gbalink.showdown

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.load
import com.multiplayer.gbalink.R
import com.multiplayer.gbalink.databinding.ActivityShowdownBinding
import org.json.JSONObject

class ShowdownActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShowdownBinding
    private val showdownClient = ShowdownClient()
    private lateinit var gifImageLoader: ImageLoader

    private val formats = listOf(
        "gen3randombattle" to "Gen 3 Random Battle (GBA)",
        "gen3ou" to "Gen 3 OU (GBA Competitivo)",
        "gen9randombattle" to "Gen 9 Random Battle",
        "gen9ou" to "Gen 9 OU",
        "gen1randombattle" to "Gen 1 Random Battle",
        "gen8ou" to "Gen 8 OU",
        "gen7ou" to "Gen 7 OU",
        "gen4ou" to "Gen 4 OU"
    )

    private val globalChatMessages = mutableListOf<ChatMessage>()
    private lateinit var globalChatAdapter: ChatAdapter

    private val publicRoomsList = mutableListOf<Pair<String, String>>()
    private lateinit var publicRoomsAdapter: PublicRoomsAdapter

    private lateinit var teamsAdapter: TeamsAdapter

    private var isSearching = false
    private var isTimerOn = false
    private var battleChatDialog: AlertDialog? = null
    private var battleChatAdapter: ChatAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShowdownBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableImmersiveMode()
        setupGifLoader()
        ShowdownTeamManager.init(this)

        setupTabs()
        setupFormatsSpinner()
        setupTeamsSpinner()
        setupTeambuilderList()
        setupGlobalChat()
        setupSpectateList()
        setupListeners()
        setupShowdownCallbacks()

        // Load official Pokemon Showdown stadium background
        binding.imgBattleBackground.load("https://play.pokemonshowdown.com/fx/bg-stadium.png") {
            crossfade(true)
            placeholder(R.drawable.bg_stadium_fallback)
            error(R.drawable.bg_stadium_fallback)
        }

        showdownClient.connect()
    }

    private fun setupGifLoader() {
        gifImageLoader = ImageLoader.Builder(this)
            .components {
                if (SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
        Coil.setImageLoader(gifImageLoader)
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    private fun enableImmersiveMode() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupTabs() {
        binding.btnTabLobby.setOnClickListener { switchTab(0) }
        binding.btnTabTeams.setOnClickListener { switchTab(1) }
        binding.btnTabGlobalChat.setOnClickListener { switchTab(2) }
        binding.btnTabSpectate.setOnClickListener { switchTab(3) }
    }

    private fun switchTab(tabIndex: Int) {
        binding.layoutLobby.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        binding.layoutTeambuilder.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        binding.layoutGlobalChat.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        binding.layoutSpectate.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE

        val activeColor = getColorStateList(R.color.gba_indigo)
        val inactiveColor = getColorStateList(R.color.gba_bezel)

        binding.btnTabLobby.backgroundTintList = if (tabIndex == 0) activeColor else inactiveColor
        binding.btnTabTeams.backgroundTintList = if (tabIndex == 1) activeColor else inactiveColor
        binding.btnTabGlobalChat.backgroundTintList = if (tabIndex == 2) activeColor else inactiveColor
        binding.btnTabSpectate.backgroundTintList = if (tabIndex == 3) activeColor else inactiveColor

        if (tabIndex == 2) {
            showdownClient.joinLobby()
        } else if (tabIndex == 3 && publicRoomsList.isEmpty()) {
            showdownClient.requestPublicRooms()
        }
    }

    private fun setupFormatsSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            formats.map { it.second }
        )
        binding.spinnerFormat.adapter = adapter
    }

    private fun setupTeamsSpinner() {
        val teamOptions = mutableListOf("Sin Equipo (Random Battle)")
        teamOptions.addAll(ShowdownTeamManager.userTeams.map { "${it.name} [${it.format}]" })

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            teamOptions
        )
        binding.spinnerTeamSelect.adapter = adapter
    }

    private fun setupTeambuilderList() {
        teamsAdapter = TeamsAdapter()
        binding.listTeams.adapter = teamsAdapter

        binding.btnImportTeam.setOnClickListener {
            showImportTeamDialog()
        }
    }

    private fun setupGlobalChat() {
        globalChatAdapter = ChatAdapter(this, globalChatMessages)
        binding.listGlobalChat.adapter = globalChatAdapter

        binding.btnSendLobbyChat.setOnClickListener {
            val msg = binding.editLobbyMessage.text.toString().trim()
            if (msg.isNotEmpty()) {
                showdownClient.sendLobbyChat(msg)
                binding.editLobbyMessage.setText("")
            }
        }
    }

    private fun setupSpectateList() {
        publicRoomsAdapter = PublicRoomsAdapter()
        binding.listPublicRooms.adapter = publicRoomsAdapter

        binding.btnStartSpectate.setOnClickListener {
            val room = binding.editSpectateRoom.text.toString().trim()
            if (room.isNotEmpty()) {
                showdownClient.spectateBattle(room)
            } else {
                Toast.makeText(this, "Ingresa un ID de sala", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRefreshPublicRooms.setOnClickListener {
            showdownClient.requestPublicRooms()
            Toast.makeText(this, "Actualizando salas públicas...", Toast.LENGTH_SHORT).show()
        }

        binding.btnLeaveSpectate.setOnClickListener {
            showdownClient.leaveBattle()
            binding.layoutBattleArena.visibility = View.GONE
            switchTab(3)
        }
    }

    private fun setupListeners() {
        binding.btnBackToGba.setOnClickListener {
            finish()
        }

        binding.btnShowdownLogin.setOnClickListener {
            showLoginDialog()
        }

        binding.btnSearchOpponent.setOnClickListener {
            val selectedFormat = formats[binding.spinnerFormat.selectedItemPosition].first
            val teamPos = binding.spinnerTeamSelect.selectedItemPosition
            val packedTeam = if (teamPos > 0 && teamPos - 1 < ShowdownTeamManager.userTeams.size) {
                ShowdownTeamManager.packTeam(ShowdownTeamManager.userTeams[teamPos - 1])
            } else null

            if (!isSearching) {
                isSearching = true
                binding.btnSearchOpponent.text = "Buscando... (Cancelar)"
                binding.btnSearchOpponent.backgroundTintList = getColorStateList(R.color.gba_button_a)
                showdownClient.searchBattle(selectedFormat, packedTeam)
                binding.txtLobbyStatus.text = "Buscando rival en $selectedFormat..."
            } else {
                isSearching = false
                binding.btnSearchOpponent.text = "Buscar Contrincante (Ladder)"
                binding.btnSearchOpponent.backgroundTintList = getColorStateList(R.color.gba_indigo)
                showdownClient.cancelSearch(selectedFormat)
                binding.txtLobbyStatus.text = "Búsqueda cancelada"
            }
        }

        binding.btnChallengeDirect.setOnClickListener {
            val user = binding.editChallengeUser.text.toString().trim()
            if (user.isEmpty()) {
                Toast.makeText(this, "Ingresa un nombre de usuario", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedFormat = formats[binding.spinnerFormat.selectedItemPosition].first
            val teamPos = binding.spinnerTeamSelect.selectedItemPosition
            val packedTeam = if (teamPos > 0 && teamPos - 1 < ShowdownTeamManager.userTeams.size) {
                ShowdownTeamManager.packTeam(ShowdownTeamManager.userTeams[teamPos - 1])
            } else null

            showdownClient.challengeUser(user, selectedFormat, packedTeam)
            Toast.makeText(this, "Reto enviado a $user ($selectedFormat)", Toast.LENGTH_SHORT).show()
        }

        binding.btnSearchUser.setOnClickListener {
            val user = binding.editSearchUser.text.toString().trim()
            if (user.isNotEmpty()) {
                showdownClient.lookupUser(user)
            }
        }

        // Battle Move buttons
        val moveButtons = listOf(binding.btnMove1, binding.btnMove2, binding.btnMove3, binding.btnMove4)
        for (i in moveButtons.indices) {
            val btn = moveButtons[i]
            btn.setOnClickListener {
                val isTeraOrMega = binding.checkSpecialAction.isChecked
                val specialAction = if (isTeraOrMega) "terastallize" else null
                showdownClient.chooseMove(i + 1, specialAction)
                binding.checkSpecialAction.isChecked = false
            }
            btn.setOnLongClickListener {
                val battle = showdownClient.currentBattle
                if (battle != null && i < battle.availableMoves.size) {
                    ShowdownDex.showMoveTooltip(this, battle.availableMoves[i])
                    true
                } else false
            }
        }

        // Long click tooltips on Pokemon
        binding.layoutOpponent.setOnLongClickListener {
            showdownClient.currentBattle?.opponentActive?.let { ShowdownDex.showPokemonTooltip(this, it); true } ?: false
        }
        binding.imgOpponentSprite.setOnLongClickListener {
            showdownClient.currentBattle?.opponentActive?.let { ShowdownDex.showPokemonTooltip(this, it); true } ?: false
        }
        binding.layoutMyPokemon.setOnLongClickListener {
            showdownClient.currentBattle?.myActive?.let { ShowdownDex.showPokemonTooltip(this, it); true } ?: false
        }
        binding.imgMySprite.setOnLongClickListener {
            showdownClient.currentBattle?.myActive?.let { ShowdownDex.showPokemonTooltip(this, it); true } ?: false
        }

        binding.btnSwitchPokemon.setOnClickListener {
            showSwitchPokemonDialog()
        }
        binding.btnQuickSwitch.setOnClickListener {
            showSwitchPokemonDialog()
        }

        binding.btnForfeit.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("¿Rendirse?")
                .setMessage("¿Estás seguro de que quieres abandonar la batalla?")
                .setPositiveButton("Sí") { _, _ ->
                    showdownClient.forfeit()
                }
                .setNegativeButton("No", null)
                .show()
        }

        binding.btnToggleTimer.setOnClickListener {
            isTimerOn = !isTimerOn
            showdownClient.toggleTimer(isTimerOn)
        }

        binding.btnBattleChat.setOnClickListener {
            showBattleChatDialog()
        }
    }

    private fun setupShowdownCallbacks() {
        showdownClient.onStatusMessage = { msg ->
            binding.txtLobbyStatus.text = msg
        }

        showdownClient.onLoginSuccess = { username ->
            binding.txtShowdownUser.text = "Usuario: $username"
            binding.btnShowdownLogin.text = "Cambiar"
            Toast.makeText(this, "¡Nombre '$username' reservado con éxito!", Toast.LENGTH_SHORT).show()
        }

        showdownClient.onLoginFailed = { error ->
            Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
        }

        showdownClient.onChallengeReceived = { challenge ->
            binding.layoutIncomingChallenge.visibility = View.VISIBLE
            binding.txtChallengeFrom.text = "¡Reto de ${challenge.fromUser} (${challenge.format})!"

            binding.btnAcceptChallenge.setOnClickListener {
                val teamPos = binding.spinnerTeamSelect.selectedItemPosition
                val packedTeam = if (teamPos > 0 && teamPos - 1 < ShowdownTeamManager.userTeams.size) {
                    ShowdownTeamManager.packTeam(ShowdownTeamManager.userTeams[teamPos - 1])
                } else null
                showdownClient.acceptChallenge(challenge.fromUser, packedTeam)
                binding.layoutIncomingChallenge.visibility = View.GONE
            }

            binding.btnRejectChallenge.setOnClickListener {
                showdownClient.rejectChallenge(challenge.fromUser)
                binding.layoutIncomingChallenge.visibility = View.GONE
            }
        }

        showdownClient.onBattleStarted = { battle ->
            isSearching = false
            hideAllTabs()
            binding.layoutBattleArena.visibility = View.VISIBLE
            binding.layoutIncomingChallenge.visibility = View.GONE

            if (battle.isSpectating) {
                binding.layoutMoves.visibility = View.GONE
                binding.layoutBattleControls.visibility = View.GONE
                binding.checkSpecialAction.visibility = View.GONE
                binding.layoutSpectatorControls.visibility = View.VISIBLE
            } else {
                binding.layoutMoves.visibility = View.VISIBLE
                binding.layoutBattleControls.visibility = View.VISIBLE
                binding.layoutSpectatorControls.visibility = View.GONE
            }

            updateBattleUI(battle)
        }

        showdownClient.onBattleUpdated = { battle ->
            updateBattleUI(battle)
        }

        showdownClient.onBattleVisualEvent = { event ->
            when (event) {
                is BattleVisualEvent.Move -> {
                    val sprite = if (event.isUser) binding.imgMySprite else binding.imgOpponentSprite
                    ShowdownEffects.lungeAttack(sprite, isOpponent = !event.isUser)
                }
                is BattleVisualEvent.Damage -> {
                    val victimSprite = if (event.isUser) binding.imgMySprite else binding.imgOpponentSprite
                    val progressBar = if (event.isUser) binding.progressMyHp else binding.progressOpponentHp
                    val txtHp = if (event.isUser) binding.txtMyHp else binding.txtOpponentHp

                    ShowdownEffects.shake(victimSprite)
                    ShowdownEffects.flashDamage(victimSprite)
                    ShowdownEffects.animateHp(
                        progressBar = progressBar,
                        txtHp = txtHp,
                        fromHp = event.oldHp,
                        toHp = event.newHp,
                        maxHp = event.maxHp,
                        isPercent = !event.isUser
                    )

                    val dmgPercent = if (event.maxHp > 0) ((event.damageAmount.toFloat() / event.maxHp) * 100).toInt() else event.damageAmount
                    if (dmgPercent > 0) {
                        ShowdownEffects.showFloatingDamage(
                            container = binding.effectsOverlay,
                            text = "-$dmgPercent%",
                            targetView = victimSprite,
                            color = if (dmgPercent > 40) android.graphics.Color.RED else android.graphics.Color.YELLOW
                        )
                    }
                }
                is BattleVisualEvent.Switch -> {
                    val sprite = if (event.isUser) binding.imgMySprite else binding.imgOpponentSprite
                    ShowdownEffects.enterBattle(sprite)
                }
                is BattleVisualEvent.Faint -> {
                    val sprite = if (event.isUser) binding.imgMySprite else binding.imgOpponentSprite
                    ShowdownEffects.faint(sprite)
                }
            }
        }

        showdownClient.onBattleEnded = { winner ->
            AlertDialog.Builder(this)
                .setTitle("Fin de la Batalla")
                .setMessage("¡$winner ha ganado la batalla!")
                .setPositiveButton("Volver") { _, _ ->
                    binding.layoutBattleArena.visibility = View.GONE
                    switchTab(0)
                    binding.btnSearchOpponent.text = "Buscar Contrincante (Ladder)"
                    binding.btnSearchOpponent.backgroundTintList = getColorStateList(R.color.gba_indigo)
                }
                .show()
        }

        showdownClient.onLobbyChatMessage = { chatMsg ->
            globalChatMessages.add(chatMsg)
            globalChatAdapter.notifyDataSetChanged()
        }

        showdownClient.onBattleChatMessage = { chatMsg ->
            battleChatAdapter?.notifyDataSetChanged()
        }

        showdownClient.onTimerUpdated = { msg, active ->
            isTimerOn = active
            binding.txtBattleTimer.text = if (active) msg else "Timer: Inactivo"
            binding.txtBattleTimer.setTextColor(if (active) getColor(R.color.gba_button_a) else getColor(R.color.white))
        }

        showdownClient.onTeamPreview = { team ->
            showTeamPreviewDialog(team)
        }

        showdownClient.onUserDetailsReceived = { json ->
            showUserProfileDialog(json)
        }

        showdownClient.onPublicRoomsReceived = { rooms ->
            publicRoomsList.clear()
            publicRoomsList.addAll(rooms)
            publicRoomsAdapter.notifyDataSetChanged()
        }
    }

    private fun hideAllTabs() {
        binding.layoutLobby.visibility = View.GONE
        binding.layoutTeambuilder.visibility = View.GONE
        binding.layoutGlobalChat.visibility = View.GONE
        binding.layoutSpectate.visibility = View.GONE
    }

    private var forceSwitchDialog: AlertDialog? = null

    private fun updateBattleUI(battle: BattleState) {
        // 1. Opponent Status & Front Sprite
        val opp = battle.opponentActive
        if (opp != null) {
            binding.txtOpponentPokemon.text = "${battle.opponentName}: ${opp.name}"
            binding.progressOpponentHp.progress = opp.hpPercentage
            binding.txtOpponentHp.text = "${opp.hpPercentage}% ${opp.status}"
            updateHpColor(binding.progressOpponentHp, opp.hpPercentage)
            binding.imgOpponentSprite.alpha = if (opp.isFainted || opp.hp == 0) 0.3f else 1.0f
            binding.imgOpponentSprite.load(ShowdownDex.getFrontSpriteUrl(opp.species), gifImageLoader) {
                placeholder(R.drawable.ic_pokeball)
                error(ShowdownDex.getFallbackSpriteUrl(opp.species))
            }
        }

        // 2. Player Status & Back Sprite
        val me = battle.myActive
        if (me != null) {
            binding.txtMyPokemon.text = me.name
            binding.progressMyHp.progress = me.hpPercentage
            binding.txtMyHp.text = "${me.hp}/${me.maxHp} ${me.status}"
            updateHpColor(binding.progressMyHp, me.hpPercentage)
            binding.imgMySprite.alpha = if (me.isFainted || me.hp == 0) 0.3f else 1.0f
            binding.imgMySprite.load(ShowdownDex.getBackSpriteUrl(me.species), gifImageLoader) {
                placeholder(R.drawable.ic_pokeball)
                error(ShowdownDex.getFallbackSpriteUrl(me.species))
            }
        }

        // 3. Render Teams (Opponent Top Bar & Player Bottom Bar)
        renderOpponentTeam(battle)
        renderMyTeam(battle)

        // 4. Force Switch Handling (When player's active Pokemon faints)
        handleForceSwitchPrompt(battle)

        // 5. Special Mechanics (Terastallize / Mega)
        if (battle.canTerastallize || battle.canMegaEvo) {
            binding.checkSpecialAction.visibility = View.VISIBLE
            binding.checkSpecialAction.text = if (battle.canTerastallize) "Teracristalizar" else "Mega Evolución"
        } else {
            binding.checkSpecialAction.visibility = View.GONE
        }

        // 6. Move Buttons & Switch Control
        val moveButtons = listOf(binding.btnMove1, binding.btnMove2, binding.btnMove3, binding.btnMove4)
        for (i in moveButtons.indices) {
            val btn = moveButtons[i]
            if (battle.isForceSwitch) {
                // Cannot attack when required to switch
                btn.visibility = View.INVISIBLE
            } else if (i < battle.availableMoves.size) {
                val m = battle.availableMoves[i]
                val detail = ShowdownDex.getMoveDetail(m.id, m.name)

                btn.visibility = View.VISIBLE
                btn.text = "${detail.name}\n${m.pp}/${m.maxPp}"
                btn.isEnabled = battle.isMyTurn && !m.disabled && m.pp > 0
                btn.backgroundTintList = ColorStateList.valueOf(ShowdownDex.getTypeColor(detail.type))
            } else {
                btn.visibility = View.INVISIBLE
            }
        }

        binding.btnSwitchPokemon.isEnabled = battle.isMyTurn || battle.isForceSwitch

        // 7. Battle Log Banner
        if (battle.isForceSwitch) {
            binding.txtBattleLogs.text = "¡Tu Pokémon se debilitó! Elige qué Pokémon enviar al combate."
        } else {
            binding.txtBattleLogs.text = battle.battleLogs.lastOrNull() ?: "¡Comienza el combate Pokémon!"
        }
    }

    private fun renderOpponentTeam(battle: BattleState) {
        val oppSlots = listOf(
            binding.oppSlot1, binding.oppSlot2, binding.oppSlot3,
            binding.oppSlot4, binding.oppSlot5, binding.oppSlot6
        )
        for (i in oppSlots.indices) {
            val slotBinding = oppSlots[i]
            if (i < battle.opponentTeam.size) {
                val poke = battle.opponentTeam[i]
                slotBinding.rootTeamSlot.visibility = View.VISIBLE
                if (!poke.isRevealed) {
                    // Not yet revealed: standard Pokeball
                    slotBinding.imgPokeIcon.setImageResource(R.drawable.ic_pokeball)
                    slotBinding.imgFaintCross.visibility = View.GONE
                    slotBinding.txtStatusBadge.visibility = View.GONE
                    slotBinding.barSlotHp.visibility = View.INVISIBLE
                    slotBinding.rootTeamSlot.setBackgroundResource(R.drawable.bg_team_slot)
                } else {
                    // Revealed Pokémon: load mini sprite icon!
                    slotBinding.barSlotHp.visibility = View.VISIBLE
                    slotBinding.barSlotHp.progress = poke.hpPercentage
                    updateHpColor(slotBinding.barSlotHp, poke.hpPercentage)
                    slotBinding.imgPokeIcon.load(ShowdownDex.getIconUrl(poke.species)) {
                        placeholder(R.drawable.ic_pokeball)
                        error(R.drawable.ic_pokeball)
                    }

                    if (poke.isFainted || poke.hp == 0) {
                        slotBinding.imgFaintCross.visibility = View.VISIBLE
                        slotBinding.rootTeamSlot.setBackgroundResource(R.drawable.bg_team_slot_fainted)
                        slotBinding.txtStatusBadge.visibility = View.GONE
                        slotBinding.barSlotHp.progress = 0
                    } else {
                        slotBinding.imgFaintCross.visibility = View.GONE
                        if (poke.isActive) {
                            slotBinding.rootTeamSlot.setBackgroundResource(R.drawable.bg_team_slot_active)
                        } else {
                            slotBinding.rootTeamSlot.setBackgroundResource(R.drawable.bg_team_slot)
                        }
                        if (poke.status.isNotEmpty()) {
                            slotBinding.txtStatusBadge.visibility = View.VISIBLE
                            slotBinding.txtStatusBadge.text = poke.status.uppercase()
                        } else {
                            slotBinding.txtStatusBadge.visibility = View.GONE
                        }
                    }
                }

                slotBinding.rootTeamSlot.setOnClickListener {
                    if (poke.isRevealed) {
                        val statusText = if (poke.isFainted || poke.hp == 0) "Debilitado" else "${poke.hpPercentage}% ${poke.status}"
                        Toast.makeText(this, "${poke.name}: $statusText", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Pokémon rival aún no revelado", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                slotBinding.rootTeamSlot.visibility = View.INVISIBLE
            }
        }
    }

    private fun renderMyTeam(battle: BattleState) {
        val mySlots = listOf(
            binding.mySlot1, binding.mySlot2, binding.mySlot3,
            binding.mySlot4, binding.mySlot5, binding.mySlot6
        )
        for (i in mySlots.indices) {
            val slotBinding = mySlots[i]
            if (i < battle.myTeam.size) {
                val poke = battle.myTeam[i]
                slotBinding.rootTeamSlot.visibility = View.VISIBLE
                slotBinding.barSlotHp.visibility = View.VISIBLE
                slotBinding.barSlotHp.progress = poke.hpPercentage
                updateHpColor(slotBinding.barSlotHp, poke.hpPercentage)

                slotBinding.imgPokeIcon.load(ShowdownDex.getIconUrl(poke.species)) {
                    placeholder(R.drawable.ic_pokeball)
                    error(R.drawable.ic_pokeball)
                }

                if (poke.isFainted || poke.hp == 0) {
                    slotBinding.imgFaintCross.visibility = View.VISIBLE
                    slotBinding.rootTeamSlot.setBackgroundResource(R.drawable.bg_team_slot_fainted)
                    slotBinding.txtStatusBadge.visibility = View.GONE
                    slotBinding.barSlotHp.progress = 0
                } else {
                    slotBinding.imgFaintCross.visibility = View.GONE
                    if (poke.isActive) {
                        slotBinding.rootTeamSlot.setBackgroundResource(R.drawable.bg_team_slot_active)
                    } else {
                        slotBinding.rootTeamSlot.setBackgroundResource(R.drawable.bg_team_slot)
                    }
                    if (poke.status.isNotEmpty()) {
                        slotBinding.txtStatusBadge.visibility = View.VISIBLE
                        slotBinding.txtStatusBadge.text = poke.status.uppercase()
                    } else {
                        slotBinding.txtStatusBadge.visibility = View.GONE
                    }
                }

                slotBinding.rootTeamSlot.setOnClickListener {
                    onTeamSlotClicked(poke, battle)
                }
                slotBinding.rootTeamSlot.setOnLongClickListener {
                    ShowdownDex.showPokemonTooltip(this, poke)
                    true
                }
            } else {
                slotBinding.rootTeamSlot.visibility = View.INVISIBLE
            }
        }
    }

    private fun onTeamSlotClicked(poke: PokemonInfo, battle: BattleState) {
        if (poke.isFainted || poke.hp == 0) {
            Toast.makeText(this, "${poke.name} está debilitado.", Toast.LENGTH_SHORT).show()
            return
        }
        if (poke.isActive) {
            Toast.makeText(this, "${poke.name} ya está en combate.", Toast.LENGTH_SHORT).show()
            return
        }

        if (battle.isForceSwitch || battle.isMyTurn) {
            AlertDialog.Builder(this)
                .setTitle("Cambiar Pokémon")
                .setMessage("¿Enviar a ${poke.name} al combate?")
                .setPositiveButton("Cambiar") { _, _ ->
                    showdownClient.chooseSwitch(poke.slot)
                    forceSwitchDialog?.dismiss()
                    forceSwitchDialog = null
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            Toast.makeText(this, "Espera a tu turno para cambiar.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleForceSwitchPrompt(battle: BattleState) {
        if (battle.isForceSwitch) {
            binding.layoutForceSwitchBanner.visibility = View.VISIBLE
            if (forceSwitchDialog == null || !forceSwitchDialog!!.isShowing) {
                showForceSwitchDialog(battle)
            }
        } else {
            binding.layoutForceSwitchBanner.visibility = View.GONE
            forceSwitchDialog?.dismiss()
            forceSwitchDialog = null
        }
    }

    private fun showForceSwitchDialog(battle: BattleState) {
        val available = battle.myTeam.filter { !it.isFainted && !it.isActive && it.hp > 0 }
        if (available.isEmpty()) {
            return
        }

        val items = available.map { "${it.name} (${it.hp}/${it.maxHp}) ${it.status}" }.toTypedArray()
        forceSwitchDialog = AlertDialog.Builder(this)
            .setTitle("¡Tu Pokémon se debilitó!")
            .setMessage("Selecciona tu próximo Pokémon:")
            .setCancelable(false)
            .setItems(items) { _, which ->
                val chosen = available[which]
                showdownClient.chooseSwitch(chosen.slot)
                forceSwitchDialog = null
            }
            .create()
        forceSwitchDialog?.show()
    }

    private fun updateHpColor(progressBar: ProgressBar, hpPercent: Int) {
        val color = when {
            hpPercent > 50 -> getColor(R.color.gba_online_green)
            hpPercent > 20 -> getColor(R.color.gba_warning_yellow)
            else -> getColor(R.color.gba_button_a)
        }
        progressBar.progressTintList = ColorStateList.valueOf(color)
    }

    private fun showSwitchPokemonDialog() {
        val battle = showdownClient.currentBattle ?: return
        val availableTeam = battle.myTeam.filter { !it.isActive && !it.isFainted && it.hp > 0 }

        if (availableTeam.isEmpty()) {
            Toast.makeText(this, "No tienes otros Pokémon disponibles", Toast.LENGTH_SHORT).show()
            return
        }

        val names = availableTeam.map { "${it.name} (${it.hp}/${it.maxHp}) ${it.status}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (battle.isForceSwitch) "¡Elige tu relevo!" else "Cambiar de Pokémon")
            .setCancelable(!battle.isForceSwitch)
            .setItems(names) { _, which ->
                val chosen = availableTeam[which]
                showdownClient.chooseSwitch(chosen.slot)
                forceSwitchDialog?.dismiss()
                forceSwitchDialog = null
            }
            .show()
    }

    private fun showTeamPreviewDialog(team: List<PokemonInfo>) {
        val names = team.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Vista Previa: Elige tu Pokémon inicial")
            .setItems(names) { _, which ->
                val order = (which + 1).toString()
                showdownClient.chooseTeamOrder(order)
            }
            .setCancelable(false)
            .show()
    }

    private fun showBattleChatDialog() {
        val battle = showdownClient.currentBattle ?: return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_battle_chat, null)
        val listChat = dialogView.findViewById<ListView>(R.id.listBattleChat)
        val editMsg = dialogView.findViewById<EditText>(R.id.editBattleChatMessage)
        val btnSend = dialogView.findViewById<Button>(R.id.btnSendBattleChat)

        battleChatAdapter = ChatAdapter(this, battle.chatMessages)
        listChat.adapter = battleChatAdapter

        btnSend.setOnClickListener {
            val text = editMsg.text.toString().trim()
            if (text.isNotEmpty()) {
                showdownClient.sendBattleChat(text)
                editMsg.setText("")
            }
        }

        battleChatDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun showImportTeamDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_import_team, null)
        val editName = dialogView.findViewById<EditText>(R.id.editImportTeamName)
        val editText = dialogView.findViewById<EditText>(R.id.editImportTeamText)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Importar") { _, _ ->
                val name = editName.text.toString().trim().ifEmpty { "Equipo Importado" }
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    val team = ShowdownTeamManager.parseExportFormat(text, name, "gen9ou")
                    ShowdownTeamManager.addTeam(this, team)
                    setupTeamsSpinner()
                    teamsAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "¡Equipo importado con ${team.pokemonList.size} Pokémon!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showUserProfileDialog(json: JSONObject) {
        val username = json.optString("username", "Desconocido")
        val avatar = json.optString("avatar", "1")
        val registered = json.optBoolean("registered", false)

        val ratingsSb = StringBuilder()
        if (json.has("ratings")) {
            val ratings = json.getJSONObject("ratings")
            val keys = ratings.keys()
            while (keys.hasNext()) {
                val f = keys.next()
                val rObj = ratings.getJSONObject(f)
                val elo = rObj.optInt("elo", 1000)
                ratingsSb.append("• $f: $elo Elo\n")
            }
        }

        val message = "Usuario: $username\n" +
                "Registrado: ${if (registered) "Sí" else "No"}\n" +
                "Avatar ID: $avatar\n\n" +
                if (ratingsSb.isNotEmpty()) "Rankings:\n$ratingsSb" else "Sin partidas clasificadas registradas."

        AlertDialog.Builder(this)
            .setTitle("Perfil de Jugador")
            .setMessage(message)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun showLoginDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
        }

        val editUser = EditText(this).apply {
            hint = "Apodo / Nombre de usuario"
            setSingleLine()
        }
        val editPass = EditText(this).apply {
            hint = "Contraseña (opcional)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }

        layout.addView(editUser)
        layout.addView(editPass)

        AlertDialog.Builder(this)
            .setTitle("Reservar Apodo / Iniciar Sesión")
            .setMessage("Elige tu apodo para combatir en Pokémon Showdown. Si tienes una cuenta registrada, escribe también tu contraseña:")
            .setView(layout)
            .setPositiveButton("Aceptar") { _, _ ->
                val name = editUser.text.toString().trim()
                val pass = editPass.text.toString().trim().ifEmpty { null }
                if (name.isNotEmpty()) {
                    showdownClient.login(name, pass)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }


    override fun onDestroy() {
        super.onDestroy()
        showdownClient.disconnect()
    }

    // Adapters
    inner class ChatAdapter(context: Context, private val messages: List<ChatMessage>) :
        ArrayAdapter<ChatMessage>(context, R.layout.item_chat_message, messages) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_chat_message, parent, false)
            val msg = messages[position]

            val txtSender = view.findViewById<TextView>(R.id.txtChatSender)
            val txtMessage = view.findViewById<TextView>(R.id.txtChatMessage)

            txtSender.text = "${msg.sender}:"
            txtMessage.text = msg.text

            return view
        }
    }

    inner class TeamsAdapter : BaseAdapter() {
        override fun getCount(): Int = ShowdownTeamManager.userTeams.size
        override fun getItem(position: Int): Any = ShowdownTeamManager.userTeams[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@ShowdownActivity).inflate(R.layout.item_team, parent, false)
            val team = ShowdownTeamManager.userTeams[position]

            val txtName = view.findViewById<TextView>(R.id.txtTeamName)
            val txtFormat = view.findViewById<TextView>(R.id.txtTeamFormat)
            val txtMembers = view.findViewById<TextView>(R.id.txtTeamMembers)
            val btnExport = view.findViewById<Button>(R.id.btnExportTeam)
            val btnDelete = view.findViewById<Button>(R.id.btnDeleteTeam)

            txtName.text = team.name
            txtFormat.text = "[${team.format}]"
            txtMembers.text = team.pokemonList.joinToString(", ") { it.name }

            btnExport.setOnClickListener {
                val text = ShowdownTeamManager.exportToText(team)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Showdown Team", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@ShowdownActivity, "Equipo copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }

            btnDelete.visibility = if (team.id.startsWith("custom_")) View.VISIBLE else View.GONE
            btnDelete.setOnClickListener {
                ShowdownTeamManager.deleteTeam(this@ShowdownActivity, team.id)
                setupTeamsSpinner()
                notifyDataSetChanged()
            }

            return view
        }
    }

    inner class PublicRoomsAdapter : BaseAdapter() {
        override fun getCount(): Int = publicRoomsList.size
        override fun getItem(position: Int): Any = publicRoomsList[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@ShowdownActivity).inflate(R.layout.item_public_room, parent, false)
            val (roomId, desc) = publicRoomsList[position]

            val txtTitle = view.findViewById<TextView>(R.id.txtRoomTitle)
            val txtRoomId = view.findViewById<TextView>(R.id.txtRoomId)
            val btnJoin = view.findViewById<Button>(R.id.btnJoinSpectate)

            txtTitle.text = desc
            txtRoomId.text = roomId

            btnJoin.setOnClickListener {
                showdownClient.spectateBattle(roomId)
            }

            return view
        }
    }
}
