package com.multiplayer.gbalink.showdown

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import coil.load
import com.multiplayer.gbalink.R

object ShowdownDex {

    data class MoveDetail(
        val name: String,
        val type: String,
        val category: String, // Physical, Special, Status
        val basePower: Int,
        val accuracy: Int,
        val desc: String
    )

    data class PokemonDetail(
        val name: String,
        val types: List<String>,
        val ability: String,
        val baseStats: String,
        val desc: String
    )

    fun getTypeColor(type: String): Int {
        val hex = when (type.lowercase()) {
            "fire", "fuego" -> "#EE8130"
            "water", "agua" -> "#6390F0"
            "grass", "planta" -> "#7AC74C"
            "electric", "eléctrico", "electrico" -> "#F7D02C"
            "ice", "hielo" -> "#96D9D6"
            "fighting", "lucha" -> "#C22E28"
            "poison", "veneno" -> "#A33EA1"
            "ground", "tierra" -> "#E2BF65"
            "flying", "volador" -> "#A98FF3"
            "psychic", "psíquico", "psiquico" -> "#F95587"
            "bug", "bicho" -> "#A6B91A"
            "rock", "roca" -> "#B6A136"
            "ghost", "fantasma" -> "#735797"
            "dragon", "dragón" -> "#6F35FC"
            "steel", "acero" -> "#B7B7CE"
            "dark", "siniestro" -> "#705746"
            "fairy", "hada" -> "#D685AD"
            else -> "#A8A878" // Normal
        }
        return Color.parseColor(hex)
    }

    fun cleanSpeciesName(species: String): String {
        return species.lowercase()
            .replace(" ", "")
            .replace("-", "")
            .replace(".", "")
            .replace("'", "")
            .replace(":", "")
    }

    fun getFrontSpriteUrl(species: String): String {
        val clean = cleanSpeciesName(species)
        return "https://play.pokemonshowdown.com/sprites/ani/$clean.gif"
    }

    fun getBackSpriteUrl(species: String): String {
        val clean = cleanSpeciesName(species)
        return "https://play.pokemonshowdown.com/sprites/ani-back/$clean.gif"
    }

    fun getIconUrl(species: String): String {
        val clean = cleanSpeciesName(species)
        return "https://play.pokemonshowdown.com/sprites/gen5/$clean.png"
    }

    fun getFallbackSpriteUrl(species: String): String {
        val clean = cleanSpeciesName(species)
        return "https://play.pokemonshowdown.com/sprites/dex/$clean.png"
    }

    // Comprehensive Move Database
    private val movesDb = mapOf(
        "flamethrower" to MoveDetail("Lanzallamas", "Fire", "Special", 90, 100, "10% de probabilidad de quemar al objetivo."),
        "fireblast" to MoveDetail("Llamarada", "Fire", "Special", 110, 85, "10% de probabilidad de quemar al objetivo."),
        "hydroshock" to MoveDetail("Hidrobomba", "Water", "Special", 110, 80, "Potente ataque de agua."),
        "surf" to MoveDetail("Surf", "Water", "Special", 90, 100, "Golpea a todos los Pokémon adyacentes."),
        "thunderbolt" to MoveDetail("Rayo", "Electric", "Special", 90, 100, "10% de probabilidad de paralizar."),
        "thunder" to MoveDetail("Trueno", "Electric", "Special", 110, 70, "30% de probabilidad de paralizar. Precisión infalible con lluvia."),
        "icebeam" to MoveDetail("Rayo Hielo", "Ice", "Special", 90, 100, "10% de probabilidad de congelar."),
        "blizzard" to MoveDetail("Ventisca", "Ice", "Special", 110, 70, "10% de probabilidad de congelar. Precisión infalible con nieve."),
        "earthquake" to MoveDetail("Terremoto", "Ground", "Physical", 100, 100, "Golpea a todos los Pokémon en el campo. Daño doble si el rival usa Excavar."),
        "closecombat" to MoveDetail("A Bocajarro", "Fighting", "Physical", 120, 100, "Baja la Defensa y Defensa Especial del usuario en 1 nivel."),
        "superpower" to MoveDetail("Fuerza Bruta", "Fighting", "Physical", 120, 100, "Baja el Ataque y Defensa del usuario en 1 nivel."),
        "dracometeor" to MoveDetail("Cometa Draco", "Dragon", "Special", 130, 90, "Baja el Ataque Especial del usuario en 2 niveles."),
        "outrage" to MoveDetail("Enfado", "Dragon", "Physical", 120, 100, "Ataca durante 2 o 3 turnos, luego el usuario se confunde."),
        "shadowball" to MoveDetail("Bola Sombra", "Ghost", "Special", 80, 100, "20% de probabilidad de bajar la Defensa Especial del rival en 1 nivel."),
        "psychic" to MoveDetail("Psíquico", "Psychic", "Special", 90, 100, "10% de probabilidad de bajar la Defensa Especial del rival."),
        "gigadrain" to MoveDetail("Gigadrenado", "Grass", "Special", 75, 100, "El usuario recupera el 50% del daño infligido."),
        "leafstorm" to MoveDetail("Lluevehojas", "Grass", "Special", 130, 90, "Baja el Ataque Especial del usuario en 2 niveles."),
        "stealthrock" to MoveDetail("Trampa Rocas", "Rock", "Status", 0, 100, "Coloca rocas flotantes que dañan a los Pokémon rivales al entrar al combate."),
        "toxic" to MoveDetail("Tóxico", "Poison", "Status", 0, 90, "Envenena gravemente al rival. El daño aumenta en cada turno."),
        "willowisp" to MoveDetail("Fuego Fatuo", "Fire", "Status", 0, 85, "Quema al objetivo, reduciendo su Ataque físico a la mitad."),
        "thunderwave" to MoveDetail("Onda Trueno", "Electric", "Status", 0, 90, "Paraliza al objetivo, reduciendo su Velocidad a la mitad."),
        "roost" to MoveDetail("Respiro", "Flying", "Status", 0, 100, "Recupera hasta el 50% de los PS máximos del usuario."),
        "recover" to MoveDetail("Recuperación", "Normal", "Status", 0, 100, "Recupera hasta el 50% de los PS máximos del usuario."),
        "protect" to MoveDetail("Protección", "Normal", "Status", 0, 100, "El usuario evade todos los ataques durante ese turno."),
        "swordsdance" to MoveDetail("Danza Espada", "Normal", "Status", 0, 100, "Aumenta el Ataque del usuario en 2 niveles."),
        "calmmind" to MoveDetail("Paz Mental", "Psychic", "Status", 0, 100, "Aumenta el Ataque Especial y Defensa Especial del usuario en 1 nivel."),
        "nastyplot" to MoveDetail("Maquinación", "Dark", "Status", 0, 100, "Aumenta el Ataque Especial del usuario en 2 niveles."),
        "dragondance" to MoveDetail("Danza Dragón", "Dragon", "Status", 0, 100, "Aumenta el Ataque y la Velocidad del usuario en 1 nivel."),
        "scald" to MoveDetail("Escaldar", "Water", "Special", 80, 100, "30% de probabilidad de quemar al rival."),
        "uturn" to MoveDetail("Ida y Vuelta", "Bug", "Physical", 70, 100, "El usuario ataca y luego cambia de Pokémon inmediatamente."),
        "voltswitch" to MoveDetail("Voltiocambio", "Electric", "Special", 70, 100, "El usuario ataca y cambia de Pokémon inmediatamente."),
        "knockoff" to MoveDetail("Desarme", "Dark", "Physical", 65, 100, "1.5x daño y quita el objeto equipado al rival."),
        "suckerpunch" to MoveDetail("Golpe Bajo", "Dark", "Physical", 70, 100, "Ataque con prioridad +1. Falla si el rival no prepara un ataque."),
        "extremespeed" to MoveDetail("Velocidad Extrema", "Normal", "Physical", 80, 100, "Ataque con prioridad +2.")
    )

    fun getMoveDetail(moveId: String, moveName: String): MoveDetail {
        val clean = cleanSpeciesName(moveId)
        return movesDb[clean] ?: MoveDetail(
            name = moveName,
            type = "Normal",
            category = "Physical",
            basePower = 80,
            accuracy = 100,
            desc = "Movimiento competitivo de Pokémon Showdown."
        )
    }

    /**
     * Shows a beautiful tooltip dialog for a move when held down (Long Click)
     */
    fun showMoveTooltip(context: Context, moveInfo: MoveInfo) {
        val detail = getMoveDetail(moveInfo.id, moveInfo.name)

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_tooltip_move, null)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtTooltipMoveTitle)
        val txtType = dialogView.findViewById<TextView>(R.id.txtTooltipMoveType)
        val txtCategory = dialogView.findViewById<TextView>(R.id.txtTooltipMoveCategory)
        val txtPower = dialogView.findViewById<TextView>(R.id.txtTooltipMovePower)
        val txtAcc = dialogView.findViewById<TextView>(R.id.txtTooltipMoveAccuracy)
        val txtPP = dialogView.findViewById<TextView>(R.id.txtTooltipMovePP)
        val txtDesc = dialogView.findViewById<TextView>(R.id.txtTooltipMoveDesc)

        txtTitle.text = detail.name
        txtType.text = detail.type.uppercase()
        txtType.setBackgroundColor(getTypeColor(detail.type))

        txtCategory.text = detail.category
        txtPower.text = if (detail.basePower > 0) "Poder: ${detail.basePower}" else "Poder: -"
        txtAcc.text = if (detail.accuracy > 0) "Precisión: ${detail.accuracy}%" else "Precisión: -"
        txtPP.text = "PP: ${moveInfo.pp}/${moveInfo.maxPp}"
        txtDesc.text = detail.desc

        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(dialogView)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /**
     * Shows a tooltip dialog for a Pokemon when held down (Long Click)
     */
    fun showPokemonTooltip(context: Context, pokemon: PokemonInfo) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_tooltip_pokemon, null)
        val imgSprite = dialogView.findViewById<ImageView>(R.id.imgTooltipSprite)
        val txtName = dialogView.findViewById<TextView>(R.id.txtTooltipPokeName)
        val txtSpecies = dialogView.findViewById<TextView>(R.id.txtTooltipPokeSpecies)
        val txtHp = dialogView.findViewById<TextView>(R.id.txtTooltipPokeHp)
        val txtStatus = dialogView.findViewById<TextView>(R.id.txtTooltipPokeStatus)

        txtName.text = pokemon.name
        txtSpecies.text = "Especie: ${pokemon.species}"
        txtHp.text = "Salud: ${pokemon.hp}/${pokemon.maxHp} (${pokemon.hpPercentage}%)"
        txtStatus.text = if (pokemon.status.isNotEmpty()) "Estado: ${pokemon.status.uppercase()}" else "Estado: Normal"

        // Load animated front sprite
        imgSprite.load(getFrontSpriteUrl(pokemon.species)) {
            crossfade(true)
            placeholder(R.drawable.ic_launcher)
            error(R.drawable.ic_launcher)
        }

        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(dialogView)
            .setPositiveButton("Cerrar", null)
            .show()
    }
}
