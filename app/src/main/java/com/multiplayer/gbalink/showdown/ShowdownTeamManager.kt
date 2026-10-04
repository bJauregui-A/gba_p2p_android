package com.multiplayer.gbalink.showdown

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TeamPokemon(
    var name: String,
    var species: String = "",
    var item: String = "",
    var ability: String = "",
    val moves: MutableList<String> = mutableListOf(),
    var nature: String = "Hardy",
    var evs: String = "", // e.g. "252/0/0/252/4/0" (HP/Atk/Def/SpA/SpD/Spe)
    var gender: String = "",
    var ivs: String = "",
    var shiny: Boolean = false,
    var level: Int = 100,
    var happiness: Int = 255,
    var teraType: String = ""
)

data class ShowdownTeam(
    val id: String,
    var name: String,
    var format: String,
    val pokemonList: MutableList<TeamPokemon> = mutableListOf()
)

object ShowdownTeamManager {

    private const val PREFS_NAME = "showdown_teams_pref"
    private const val KEY_TEAMS = "saved_teams_json"

    val userTeams = mutableListOf<ShowdownTeam>()

    /**
     * Initializes default competitive teams and loads saved user teams.
     */
    fun init(context: Context) {
        userTeams.clear()

        // 1. Built-in Gen 3 OU Meta Team (ADV OU)
        userTeams.add(createGen3OuMetaTeam())

        // 2. Built-in Gen 9 OU Meta Team (SV OU)
        userTeams.add(createGen9OuMetaTeam())

        // 3. Load user-created or imported teams from SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_TEAMS, null)
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    userTeams.add(fromJson(obj))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun saveTeams(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Only save custom teams (skip built-ins or save all custom)
        val custom = userTeams.filter { it.id.startsWith("custom_") }
        val arr = JSONArray()
        for (team in custom) {
            arr.put(toJson(team))
        }
        prefs.edit().putString(KEY_TEAMS, arr.toString()).apply()
    }

    fun addTeam(context: Context, team: ShowdownTeam) {
        userTeams.add(team)
        saveTeams(context)
    }

    fun deleteTeam(context: Context, teamId: String) {
        userTeams.removeAll { it.id == teamId }
        saveTeams(context)
    }

    /**
     * Packs a team into Showdown's compressed packed string format.
     * Specification from sim/teams.ts:
     * Pokemon separated by ']'
     * Fields separated by '|':
     * 1. name
     * 2. species (empty if same as name)
     * 3. item
     * 4. ability
     * 5. moves (comma-separated)
     * 6. nature
     * 7. evs (hp,atk,def,spa,spd,spe)
     * 8. gender
     * 9. ivs
     * 10. shiny ('S' if true)
     * 11. level (if != 100)
     * 12. happiness (if != 255)
     * 13. extra (e.g. ,,,TeraType)
     */
    fun packTeam(team: ShowdownTeam): String {
        val buf = StringBuilder()
        for (i in team.pokemonList.indices) {
            if (i > 0) buf.append("]")
            val p = team.pokemonList[i]

            val name = p.name.trim()
            val spec = if (p.species.isNotEmpty()) p.species.trim() else name
            val specId = cleanId(spec)
            val nameId = cleanId(name)

            // 1. Name
            buf.append(name)

            // 2. Species (blank if same id as name)
            buf.append("|")
            if (nameId != specId) {
                buf.append(specId)
            }

            // 3. Item
            buf.append("|").append(cleanId(p.item))

            // 4. Ability
            buf.append("|").append(cleanId(p.ability))

            // 5. Moves
            buf.append("|").append(p.moves.joinToString(",") { cleanId(it) })

            // 6. Nature
            buf.append("|").append(p.nature.trim())

            // 7. EVs
            buf.append("|").append(p.evs.trim())

            // 8. Gender
            buf.append("|").append(p.gender.trim())

            // 9. IVs
            buf.append("|").append(p.ivs.trim())

            // 10. Shiny
            buf.append("|").append(if (p.shiny) "S" else "")

            // 11. Level
            buf.append("|").append(if (p.level != 100) p.level.toString() else "")

            // 12. Happiness
            buf.append("|").append(if (p.happiness != 255) p.happiness.toString() else "")

            // 13. Tera Type
            if (p.teraType.isNotEmpty()) {
                buf.append("|,,,${p.teraType.trim()}")
            }
        }
        return buf.toString()
    }

    /**
     * Parses human-readable Pokémon Showdown / Pokepaste export format.
     */
    fun parseExportFormat(text: String, teamName: String, format: String): ShowdownTeam {
        val team = ShowdownTeam(
            id = "custom_${System.currentTimeMillis()}",
            name = teamName,
            format = format
        )

        val blocks = text.trim().split(Regex("\n\n+"))
        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.isEmpty() || lines[0].isBlank()) continue

            // First line: e.g. "Articuno @ Leftovers" or "Zapdos (M) @ Damp Rock"
            val line1 = lines[0].trim()
            var name = ""
            var species = ""
            var item = ""
            var gender = ""

            val partsAt = line1.split("@")
            val pokePart = partsAt[0].trim()
            if (partsAt.size > 1) {
                item = partsAt[1].trim()
            }

            // Check for gender: e.g. "Zapdos (M)"
            val genderMatch = Regex("\\(([MF])\\)").find(pokePart)
            if (genderMatch != null) {
                gender = genderMatch.groupValues[1]
            }

            // Check for nickname and species: "Sparky (Pikachu)"
            val parenMatch = Regex("^(.*?)\\s*\\((.*?)\\)").find(pokePart)
            if (parenMatch != null && parenMatch.groupValues[2] != "M" && parenMatch.groupValues[2] != "F") {
                name = parenMatch.groupValues[1].trim()
                species = parenMatch.groupValues[2].trim()
            } else {
                name = pokePart.replace(Regex("\\([MF]\\)"), "").trim()
                species = name
            }

            var ability = ""
            var nature = "Hardy"
            var evs = ""
            var ivs = ""
            var teraType = ""
            var shiny = false
            var level = 100
            val moves = mutableListOf<String>()

            for (idx in 1 until lines.size) {
                val l = lines[idx].trim()
                when {
                    l.startsWith("Ability:", ignoreCase = true) -> {
                        ability = l.substringAfter(":").trim()
                    }
                    l.startsWith("Tera Type:", ignoreCase = true) -> {
                        teraType = l.substringAfter(":").trim()
                    }
                    l.endsWith("Nature", ignoreCase = true) -> {
                        nature = l.split(" ")[0].trim()
                    }
                    l.startsWith("EVs:", ignoreCase = true) -> {
                        evs = parseStatsString(l.substringAfter(":").trim())
                    }
                    l.startsWith("IVs:", ignoreCase = true) -> {
                        ivs = parseStatsString(l.substringAfter(":").trim())
                    }
                    l.startsWith("Shiny:", ignoreCase = true) -> {
                        shiny = l.substringAfter(":").trim().equals("Yes", ignoreCase = true)
                    }
                    l.startsWith("Level:", ignoreCase = true) -> {
                        level = l.substringAfter(":").trim().toIntOrNull() ?: 100
                    }
                    l.startsWith("-") -> {
                        moves.add(l.removePrefix("-").trim())
                    }
                }
            }

            if (name.isNotEmpty()) {
                team.pokemonList.add(
                    TeamPokemon(
                        name = name,
                        species = species,
                        item = item,
                        ability = ability,
                        moves = moves,
                        nature = nature,
                        evs = evs,
                        ivs = ivs,
                        gender = gender,
                        shiny = shiny,
                        level = level,
                        teraType = teraType
                    )
                )
            }
        }

        return team
    }

    /**
     * Converts a ShowdownTeam to standard human-readable export format (Pokepaste).
     */
    fun exportToText(team: ShowdownTeam): String {
        val sb = StringBuilder()
        for (p in team.pokemonList) {
            val nameStr = if (p.species.isNotEmpty() && p.species != p.name) "${p.name} (${p.species})" else p.name
            val genderStr = if (p.gender.isNotEmpty()) " (${p.gender})" else ""
            val itemStr = if (p.item.isNotEmpty()) " @ ${p.item}" else ""

            sb.append("$nameStr$genderStr$itemStr\n")
            if (p.ability.isNotEmpty()) sb.append("Ability: ${p.ability}\n")
            if (p.teraType.isNotEmpty()) sb.append("Tera Type: ${p.teraType}\n")
            if (p.level != 100) sb.append("Level: ${p.level}\n")
            if (p.shiny) sb.append("Shiny: Yes\n")
            if (p.nature.isNotEmpty()) sb.append("${p.nature} Nature\n")
            for (m in p.moves) {
                sb.append("- $m\n")
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    private fun parseStatsString(str: String): String {
        // e.g. "252 Atk / 4 SpD / 252 Spe" -> "0,252,0,0,4,252"
        var hp = 0
        var atk = 0
        var def = 0
        var spa = 0
        var spd = 0
        var spe = 0

        val parts = str.split("/")
        for (part in parts) {
            val trimmed = part.trim()
            val tokens = trimmed.split(" ")
            if (tokens.size >= 2) {
                val value = tokens[0].toIntOrNull() ?: 0
                val stat = tokens[1].lowercase()
                when (stat) {
                    "hp" -> hp = value
                    "atk" -> atk = value
                    "def" -> def = value
                    "spa" -> spa = value
                    "spd" -> spd = value
                    "spe" -> spe = value
                }
            }
        }
        return "$hp,$atk,$def,$spa,$spd,$spe"
    }

    private fun cleanId(text: String): String {
        return text.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    private fun createGen3OuMetaTeam(): ShowdownTeam {
        return ShowdownTeam(
            id = "preset_gen3ou",
            name = "Gen 3 ADV OU (Standard)",
            format = "gen3ou",
            pokemonList = mutableListOf(
                TeamPokemon("Tyranitar", item = "Leftovers", ability = "Sand Stream", moves = mutableListOf("Dragon Dance", "Rock Slide", "Earthquake", "Hidden Power Bug"), nature = "Adamant", evs = "4,252,0,0,0,252"),
                TeamPokemon("Swampert", item = "Leftovers", ability = "Torrent", moves = mutableListOf("Earthquake", "Hydro Pump", "Ice Beam", "Protect"), nature = "Relaxed", evs = "252,0,216,40,0,0"),
                TeamPokemon("Skarmory", item = "Leftovers", ability = "Keen Eye", moves = mutableListOf("Spikes", "Toxic", "Roar", "Protect"), nature = "Impish", evs = "252,0,252,0,4,0"),
                TeamPokemon("Blissey", item = "Leftovers", ability = "Natural Cure", moves = mutableListOf("Soft-Boiled", "Seismic Toss", "Aromatherapy", "Thunder Wave"), nature = "Bold", evs = "252,0,252,0,4,0"),
                TeamPokemon("Gengar", item = "Leftovers", ability = "Levitate", moves = mutableListOf("Thunderbolt", "Ice Punch", "Will-O-Wisp", "Taunt"), nature = "Timid", evs = "0,0,0,252,4,252"),
                TeamPokemon("Salamence", item = "Choice Band", ability = "Intimidate", moves = mutableListOf("Hidden Power Flying", "Earthquake", "Rock Slide", "Fire Blast"), nature = "Adamant", evs = "4,252,0,0,0,252")
            )
        )
    }

    private fun createGen9OuMetaTeam(): ShowdownTeam {
        return ShowdownTeam(
            id = "preset_gen9ou",
            name = "Gen 9 SV OU (Standard)",
            format = "gen9ou",
            pokemonList = mutableListOf(
                TeamPokemon("Great Tusk", item = "Booster Energy", ability = "Protosynthesis", moves = mutableListOf("Headlong Rush", "Close Combat", "Ice Spinner", "Rapid Spin"), nature = "Jolly", evs = "0,252,0,0,4,252", teraType = "Ice"),
                TeamPokemon("Kingambit", item = "Leftovers", ability = "Supreme Overlord", moves = mutableListOf("Swords Dance", "Sucker Punch", "Kowtow Cleave", "Iron Head"), nature = "Adamant", evs = "212,252,0,0,0,44", teraType = "Flying"),
                TeamPokemon("Dragapult", item = "Choice Specs", ability = "Infiltrator", moves = mutableListOf("Shadow Ball", "Draco Meteor", "Flamethrower", "U-turn"), nature = "Timid", evs = "0,0,0,252,4,252", teraType = "Ghost"),
                TeamPokemon("Ogerpon-Wellspring", item = "Wellspring Mask", ability = "Water Absorb", moves = mutableListOf("Ivy Cudgel", "Horn Leech", "Play Rough", "Swords Dance"), nature = "Jolly", evs = "0,252,0,0,4,252", teraType = "Water"),
                TeamPokemon("Gholdengo", item = "Choice Scarf", ability = "Good as Gold", moves = mutableListOf("Shadow Ball", "Make It Rain", "Trick", "Focus Blast"), nature = "Timid", evs = "0,0,0,252,4,252", teraType = "Fighting"),
                TeamPokemon("Dragonite", item = "Heavy-Duty Boots", ability = "Multiscale", moves = mutableListOf("Dragon Dance", "Extreme Speed", "Earthquake", "Roost"), nature = "Adamant", evs = "144,252,0,0,0,112", teraType = "Normal")
            )
        )
    }

    private fun toJson(team: ShowdownTeam): JSONObject {
        val obj = JSONObject()
        obj.put("id", team.id)
        obj.put("name", team.name)
        obj.put("format", team.format)
        val arr = JSONArray()
        for (p in team.pokemonList) {
            val pObj = JSONObject()
            pObj.put("name", p.name)
            pObj.put("species", p.species)
            pObj.put("item", p.item)
            pObj.put("ability", p.ability)
            pObj.put("nature", p.nature)
            pObj.put("evs", p.evs)
            pObj.put("ivs", p.ivs)
            pObj.put("gender", p.gender)
            pObj.put("shiny", p.shiny)
            pObj.put("level", p.level)
            pObj.put("teraType", p.teraType)
            val mArr = JSONArray()
            p.moves.forEach { mArr.put(it) }
            pObj.put("moves", mArr)
            arr.put(pObj)
        }
        obj.put("pokemon", arr)
        return obj
    }

    private fun fromJson(obj: JSONObject): ShowdownTeam {
        val team = ShowdownTeam(
            id = obj.getString("id"),
            name = obj.getString("name"),
            format = obj.getString("format")
        )
        val arr = obj.getJSONArray("pokemon")
        for (i in 0 until arr.length()) {
            val pObj = arr.getJSONObject(i)
            val moves = mutableListOf<String>()
            val mArr = pObj.getJSONArray("moves")
            for (j in 0 until mArr.length()) {
                moves.add(mArr.getString(j))
            }
            team.pokemonList.add(
                TeamPokemon(
                    name = pObj.getString("name"),
                    species = pObj.optString("species", ""),
                    item = pObj.optString("item", ""),
                    ability = pObj.optString("ability", ""),
                    moves = moves,
                    nature = pObj.optString("nature", "Hardy"),
                    evs = pObj.optString("evs", ""),
                    ivs = pObj.optString("ivs", ""),
                    gender = pObj.optString("gender", ""),
                    shiny = pObj.optBoolean("shiny", false),
                    level = pObj.optInt("level", 100),
                    teraType = pObj.optString("teraType", "")
                )
            )
        }
        return team
    }
}
