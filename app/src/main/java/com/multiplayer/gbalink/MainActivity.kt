package com.multiplayer.gbalink

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.multiplayer.gbalink.core.GbaEmulator
import com.multiplayer.gbalink.core.GbaNative
import com.multiplayer.gbalink.core.HomebrewRom
import com.multiplayer.gbalink.databinding.ActivityMainBinding
import com.multiplayer.gbalink.network.P2PConnectionManager
import com.multiplayer.gbalink.showdown.ShowdownActivity
import com.multiplayer.gbalink.ui.GbaGlSurfaceView
import com.multiplayer.gbalink.ui.GbaShader
import com.multiplayer.gbalink.ui.MultiplayerDialog
import java.io.File
import java.io.FileInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var nativeCore: GbaNative
    private lateinit var emulator: GbaEmulator
    private lateinit var p2pManager: P2PConnectionManager

    private var currentRomName: String = "Juego"
    private var romFile: File? = null

    // SAF export save file
    private val exportSaveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri?.let { saveUri ->
            val sram = emulator.getSaveData()
            if (sram != null) {
                contentResolver.openOutputStream(saveUri)?.use { out ->
                    out.write(sram)
                }
                Toast.makeText(this, "Partida guardada exitosamente (${sram.size / 1024} KB)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // SAF import save file (.sav)
    private val importSaveLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importUri ->
            try {
                contentResolver.openInputStream(importUri)?.use { input ->
                    val bytes = input.readBytes()
                    emulator.loadSaveData(bytes)
                    Toast.makeText(this, "Partida cargada exitosamente (${bytes.size / 1024} KB)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error al importar partida: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // SAF import custom shader (.fsh / .glsl)
    private val importShaderLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { shaderUri ->
            try {
                contentResolver.openInputStream(shaderUri)?.use { input ->
                    val source = input.bufferedReader().readText()
                    val ok = binding.gbaSurface.loadCustomShader(source)
                    if (ok) {
                        getSharedPreferences("gba_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("current_shader", GbaShader.CUSTOM.name)
                            .putString("custom_shader_src", source)
                            .apply()
                        Toast.makeText(this, "Shader MyBoy personalizado cargado", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Error al compilar el shader", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error al leer shader: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableImmersiveMode()

        nativeCore = GbaNative()
        emulator = GbaEmulator(nativeCore)
        p2pManager = P2PConnectionManager(emulator, nativeCore)

        setupSurfaceView()
        setupController()
        setupBackPressHandler()
        restoreShaderPreference()

        // Load the ROM specified by RomMenuActivity
        val romPath = intent.getStringExtra("ROM_PATH")
        currentRomName = intent.getStringExtra("ROM_NAME") ?: "Juego"

        if (!romPath.isNullOrEmpty() && File(romPath).exists()) {
            romFile = File(romPath)
            loadRomFromFile(romFile!!)
        } else {
            val testRom = HomebrewRom.createTestRom()
            emulator.loadRom(testRom)
        }

        emulator.start()
    }

    private fun restoreShaderPreference() {
        val prefs = getSharedPreferences("gba_prefs", Context.MODE_PRIVATE)
        val shaderName = prefs.getString("current_shader", GbaShader.ORIGINAL.name)
        val shader = try {
            GbaShader.valueOf(shaderName ?: GbaShader.ORIGINAL.name)
        } catch (_: Exception) {
            GbaShader.ORIGINAL
        }

        if (shader == GbaShader.CUSTOM) {
            val customSrc = prefs.getString("custom_shader_src", null)
            if (!customSrc.isNullOrBlank()) {
                binding.gbaSurface.loadCustomShader(customSrc)
            } else {
                binding.gbaSurface.setShader(GbaShader.ORIGINAL)
            }
        } else {
            binding.gbaSurface.setShader(shader)
        }
    }

    private fun enableImmersiveMode() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        binding.gbaSurface.onResume()
        emulator.resume()
    }

    override fun onPause() {
        super.onPause()
        emulator.pause()
        binding.gbaSurface.onPause()
        autoSaveBattery()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    private var isMenuShowing = false

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSurfaceView()
        setupController()
        setupBackPressHandler()
        restoreShaderPreference()
        enableImmersiveMode()
    }

    private fun setupSurfaceView() {
        binding.btnInGameMenu.bringToFront()
        binding.btnInGameMenu.setOnClickListener {
            showOptionsMenu()
        }

        emulator.onFrameRendered = { bitmap ->
            binding.gbaSurface.updateFrame(bitmap)
        }
    }

    private fun setupController() {
        binding.touchController.onKeyMaskChanged = { mask ->
            emulator.currentKeyMask = mask
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showOptionsMenu()
            }
        })
    }

    private fun autoSaveBattery() {
        val rf = romFile ?: return
        try {
            val savBytes = emulator.getSaveData()
            if (savBytes != null && savBytes.isNotEmpty()) {
                val savFile = File(rf.parentFile, "${rf.nameWithoutExtension}.sav")
                savFile.writeBytes(savBytes)
                Log.i("MainActivity", "Auto-saved battery RAM (${savBytes.size} bytes) to ${savFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error autosaving battery", e)
        }
    }

    private fun reloadBatterySave(savName: String) {
        val rf = romFile ?: return
        val candidates = listOf(
            File(rf.parentFile, "${rf.nameWithoutExtension}.sav"),
            File(rf.parentFile, "${rf.nameWithoutExtension}.SAV")
        )
        val savFile = candidates.firstOrNull { it.exists() && it.length() > 0 }
        if (savFile != null) {
            try {
                val bytes = savFile.readBytes()
                emulator.loadSaveData(bytes)
                Toast.makeText(this, "Partida recargada desde $savName (${bytes.size / 1024} KB)", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error al recargar partida: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No se encontró el archivo $savName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showShaderDialog() {
        val shaders = GbaShader.values()
        val names = shaders.map { it.displayName }.toTypedArray()
        val currentIdx = shaders.indexOf(binding.gbaSurface.getCurrentShader()).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Filtros de Video (Shaders MyBoy)")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                val selected = shaders[which]
                if (selected == GbaShader.CUSTOM) {
                    dialog.dismiss()
                    importShaderLauncher.launch("*/*")
                } else {
                    binding.gbaSurface.setShader(selected)
                    getSharedPreferences("gba_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("current_shader", selected.name)
                        .apply()
                    Toast.makeText(this, "Filtro aplicado: ${selected.displayName}", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("Normal (1x)", "Rápido (2x)", "Muy Rápido (4x)", "Máximo (8x)")
        val factors = floatArrayOf(1.0f, 2.0f, 4.0f, 8.0f)
        val currentIdx = when (emulator.speedMultiplier) {
            2.0f -> 1
            4.0f -> 2
            8.0f -> 3
            else -> 0
        }

        AlertDialog.Builder(this)
            .setTitle("Velocidad de Juego")
            .setSingleChoiceItems(speeds, currentIdx) { dialog, which ->
                emulator.speedMultiplier = factors[which]
                Toast.makeText(this, "Velocidad: ${speeds[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /**
     * In-game options menu displayed on pressing screen menu button or device Back
     */
    private fun showOptionsMenu() {
        if (isMenuShowing) return
        isMenuShowing = true
        emulator.pause()

        val savName = romFile?.let { "${it.nameWithoutExtension}.sav" } ?: "$currentRomName.sav"
        val speedText = if (emulator.speedMultiplier > 1.0f) "${emulator.speedMultiplier.toInt()}x" else "1x"
        val options = arrayOf(
            "Guardar Partida ($savName)",
            "Cargar Partida ($savName)",
            "Filtros de Pantalla / Shaders MyBoy",
            "Velocidad de Juego ($speedText)",
            "Exportar Guardado a otro destino...",
            "Importar Guardado de otro archivo...",
            "Cable Link P2P (Multijugador)",
            "Pokémon Showdown",
            "Reiniciar Partida",
            "Cerrar Juego",
            "Continuar"
        )

        AlertDialog.Builder(this)
            .setTitle("Opciones: $currentRomName")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        autoSaveBattery()
                        Toast.makeText(this, "Partida guardada en $savName", Toast.LENGTH_SHORT).show()
                        emulator.resume()
                    }
                    1 -> {
                        reloadBatterySave(savName)
                        emulator.resume()
                    }
                    2 -> showShaderDialog()
                    3 -> showSpeedDialog()
                    4 -> exportSaveLauncher.launch(savName)
                    5 -> importSaveLauncher.launch("*/*")
                    6 -> MultiplayerDialog(this, p2pManager).show()
                    7 -> startActivity(Intent(this, ShowdownActivity::class.java))
                    8 -> {
                        nativeCore.nativeReset()
                        emulator.resume()
                    }
                    9 -> finish()
                    10 -> emulator.resume()
                }
                enableImmersiveMode()
            }
            .setOnDismissListener {
                isMenuShowing = false
                enableImmersiveMode()
                emulator.resume()
            }
            .show()
    }

    private fun loadRomFromFile(file: File) {
        try {
            val bytes = FileInputStream(file).use { it.readBytes() }
            val ok = emulator.loadRom(bytes)
            if (!ok) {
                Toast.makeText(this, "Aviso: Error al decodificar ROM", Toast.LENGTH_SHORT).show()
            }

            // Auto-load MyBoy / standard .sav battery save in the same folder
            val candidates = listOf(
                File(file.parentFile, "${file.nameWithoutExtension}.sav"),
                File(file.parentFile, "${file.nameWithoutExtension}.SAV")
            )
            val savFile = candidates.firstOrNull { it.exists() && it.length() > 0 }
            if (savFile != null) {
                try {
                    val savBytes = savFile.readBytes()
                    emulator.loadSaveData(savBytes)
                    Toast.makeText(this, "Partida MyBoy cargada (${savFile.name} - ${savBytes.size / 1024} KB)", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error loading adjacent .sav file", e)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error al cargar ROM: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Physical Gamepad Support
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showOptionsMenu()
            return true
        }

        var mask = 0
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> mask = GbaNative.KEY_A
            KeyEvent.KEYCODE_BUTTON_B -> mask = GbaNative.KEY_B
            KeyEvent.KEYCODE_BUTTON_L1 -> mask = GbaNative.KEY_L
            KeyEvent.KEYCODE_BUTTON_R1 -> mask = GbaNative.KEY_R
            KeyEvent.KEYCODE_BUTTON_START -> mask = GbaNative.KEY_START
            KeyEvent.KEYCODE_BUTTON_SELECT -> mask = GbaNative.KEY_SELECT
            KeyEvent.KEYCODE_DPAD_UP -> mask = GbaNative.KEY_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> mask = GbaNative.KEY_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> mask = GbaNative.KEY_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> mask = GbaNative.KEY_RIGHT
        }
        if (mask != 0) {
            emulator.currentKeyMask = emulator.currentKeyMask or mask
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        var mask = 0
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> mask = GbaNative.KEY_A
            KeyEvent.KEYCODE_BUTTON_B -> mask = GbaNative.KEY_B
            KeyEvent.KEYCODE_BUTTON_L1 -> mask = GbaNative.KEY_L
            KeyEvent.KEYCODE_BUTTON_R1 -> mask = GbaNative.KEY_R
            KeyEvent.KEYCODE_BUTTON_START -> mask = GbaNative.KEY_START
            KeyEvent.KEYCODE_BUTTON_SELECT -> mask = GbaNative.KEY_SELECT
            KeyEvent.KEYCODE_DPAD_UP -> mask = GbaNative.KEY_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> mask = GbaNative.KEY_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> mask = GbaNative.KEY_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> mask = GbaNative.KEY_RIGHT
        }
        if (mask != 0) {
            emulator.currentKeyMask = emulator.currentKeyMask and mask.inv()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoSaveBattery()
        p2pManager.disconnect()
        emulator.stop()
    }
}
