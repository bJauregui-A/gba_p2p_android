package com.multiplayer.gbalink

import android.app.AlertDialog
import android.graphics.Bitmap
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
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
import com.multiplayer.gbalink.core.SaveStateManager
import com.multiplayer.gbalink.databinding.ActivityMainBinding
import com.multiplayer.gbalink.databinding.ItemSaveSlotBinding
import com.multiplayer.gbalink.network.P2PConnectionManager
import com.multiplayer.gbalink.ui.GbaGlSurfaceView
import com.multiplayer.gbalink.ui.GbaShader
import com.multiplayer.gbalink.ui.MultiplayerDialog
import java.io.File
import java.io.FileInputStream
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    companion object {
        private const val AUTOSAVE_INTERVAL_MS = 5_000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var nativeCore: GbaNative
    private lateinit var emulator: GbaEmulator
    private lateinit var p2pManager: P2PConnectionManager

    private var currentRomName: String = "Juego"
    private var romFile: File? = null
    private lateinit var saveStates: SaveStateManager

    // Cartridge battery save (.sav) currently in use and hash of its last written content
    private var batterySaveFile: File? = null
    private var lastBatteryHash: Int = 0

    private val autoSaveHandler = Handler(Looper.getMainLooper())
    private val autoSaveRunnable = object : Runnable {
        override fun run() {
            autoSaveBattery()
            autoSaveHandler.postDelayed(this, AUTOSAVE_INTERVAL_MS)
        }
    }

    private fun dialogBuilder() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

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
                    nativeCore.nativeReset()
                    lastBatteryHash = 0
                    autoSaveBattery()
                    Toast.makeText(this, "Partida .sav importada (${bytes.size / 1024} KB). Juego reiniciado.", Toast.LENGTH_SHORT).show()
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
        }
        saveStates = SaveStateManager(this, romFile, currentRomName)

        if (romFile != null) {
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
        if (!isMenuShowing) emulator.resume()
        autoSaveHandler.postDelayed(autoSaveRunnable, AUTOSAVE_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
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

    /**
     * Writes the cartridge battery save (.sav) only when its content changed. The file is the
     * raw chip dump (same format as MyBoy / mGBA / VBA-M), so it can be moved between emulators.
     */
    private fun autoSaveBattery(force: Boolean = false): Boolean {
        val rf = romFile ?: return false
        return try {
            val savBytes = emulator.getSaveData() ?: return false
            val hash = savBytes.contentHashCode()
            if (!force && hash == lastBatteryHash) return true
            val savFile = batterySaveFile ?: File(rf.parentFile, "${rf.nameWithoutExtension}.sav").also { batterySaveFile = it }
            savFile.writeBytes(savBytes)
            lastBatteryHash = hash
            Log.i("MainActivity", "Battery save written (${savBytes.size} bytes) to ${savFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e("MainActivity", "Error autosaving battery", e)
            false
        }
    }

    /** Looks for an existing battery save written by this app, MyBoy, RetroArch (.srm), etc. */
    private fun findBatterySave(rom: File): File? {
        val dir = rom.parentFile ?: return null
        val base = rom.nameWithoutExtension
        val folders = listOf(dir, File(dir, "save"), File(dir, "saves"), File(dir, "Save"), File(dir, "Saves"))
        val names = listOf("$base.sav", "$base.SAV", "$base.srm", "$base.SRM", "${rom.name}.sav")
        for (folder in folders) {
            for (name in names) {
                val f = File(folder, name)
                if (f.isFile && f.length() >= 512) return f
            }
        }
        // Case-insensitive fallback in the ROM folder
        return dir.listFiles()?.firstOrNull {
            it.isFile && it.length() >= 512 &&
                it.nameWithoutExtension.equals(base, ignoreCase = true) &&
                (it.extension.equals("sav", true) || it.extension.equals("srm", true))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Save states (emulator slots, MyBoy-style)
    // ---------------------------------------------------------------------------------------

    private inner class SlotAdapter(private val forLoading: Boolean) : BaseAdapter() {
        private val slots = saveStates.slots()
        private val thumbs = HashMap<Int, Bitmap?>()
        private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        override fun getCount() = slots.size
        override fun getItem(position: Int) = slots[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = !forLoading || slots[position].exists

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val b = convertView?.let { ItemSaveSlotBinding.bind(it) }
                ?: ItemSaveSlotBinding.inflate(layoutInflater, parent, false)
            val slot = slots[position]
            b.slotTitle.text = "Slot ${slot.index}"
            if (slot.exists) {
                b.slotSubtitle.text = dateFormat.format(Date(slot.lastModified))
                b.slotThumb.setImageBitmap(thumbs.getOrPut(slot.index) { slot.loadThumbnail() })
                b.root.alpha = 1f
            } else {
                b.slotSubtitle.text = "Vacío"
                b.slotThumb.setImageDrawable(null)
                b.root.alpha = if (forLoading) 0.4f else 1f
            }
            return b.root
        }
    }

    private fun showSlotDialog(forLoading: Boolean) {
        emulator.pause()
        val list = ListView(this).apply { dividerHeight = 1 }
        list.adapter = SlotAdapter(forLoading)

        val dialog = dialogBuilder()
            .setTitle(if (forLoading) "Cargar estado" else "Guardar estado")
            .setView(list)
            .setNegativeButton("Cancelar", null)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            val slot = saveStates.slots()[position]
            if (forLoading) {
                dialog.dismiss()
                loadStateSlot(slot.index)
            } else if (slot.exists) {
                dialogBuilder()
                    .setTitle("Sobrescribir Slot ${slot.index}")
                    .setMessage("Este slot ya contiene un estado guardado. ¿Reemplazarlo?")
                    .setPositiveButton("Sobrescribir") { _, _ ->
                        dialog.dismiss()
                        saveStateSlot(slot.index)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            } else {
                dialog.dismiss()
                saveStateSlot(slot.index)
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val slot = saveStates.slots()[position]
            if (!slot.exists) return@setOnItemLongClickListener false
            dialogBuilder()
                .setTitle("Borrar Slot ${slot.index}")
                .setMessage("¿Eliminar este estado guardado?")
                .setPositiveButton("Borrar") { _, _ ->
                    saveStates.delete(slot.index)
                    list.adapter = SlotAdapter(forLoading)
                }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }
        dialog.setOnDismissListener { resumeAfterMenu() }
        dialog.show()
    }

    private fun saveStateSlot(index: Int) {
        val state = emulator.saveState()
        if (state == null) {
            Toast.makeText(this, "Error al crear el estado", Toast.LENGTH_SHORT).show()
            return
        }
        val screenshot = emulator.screenBitmap.copy(Bitmap.Config.ARGB_8888, false)
        val ok = saveStates.save(index, state, screenshot)
        Toast.makeText(
            this,
            if (ok) "Estado guardado en Slot $index" else "No se pudo escribir el Slot $index",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun loadStateSlot(index: Int) {
        val data = saveStates.load(index)
        if (data == null || !emulator.loadState(data)) {
            Toast.makeText(this, "No se pudo cargar el Slot $index", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Estado del Slot $index cargado", Toast.LENGTH_SHORT).show()
    }

    /** Resumes emulation once no dialog of the in-game menu remains open. */
    private fun resumeAfterMenu() {
        isMenuShowing = false
        enableImmersiveMode()
        emulator.resume()
    }

    private fun showShaderDialog() {
        val shaders = GbaShader.values()
        val names = shaders.map { it.displayName }.toTypedArray()
        val currentIdx = shaders.indexOf(binding.gbaSurface.getCurrentShader()).coerceAtLeast(0)

        dialogBuilder()
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
            .setOnDismissListener { resumeAfterMenu() }
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

        dialogBuilder()
            .setTitle("Velocidad de Juego")
            .setSingleChoiceItems(speeds, currentIdx) { dialog, which ->
                emulator.speedMultiplier = factors[which]
                Toast.makeText(this, "Velocidad: ${speeds[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .setOnDismissListener { resumeAfterMenu() }
            .show()
    }

    /**
     * In-game options menu displayed on pressing the screen menu button or device Back
     */
    private fun showOptionsMenu() {
        if (isMenuShowing) return
        isMenuShowing = true
        emulator.pause()
        autoSaveBattery()

        val savName = batterySaveFile?.name ?: romFile?.let { "${it.nameWithoutExtension}.sav" } ?: "$currentRomName.sav"
        val speedText = if (emulator.speedMultiplier > 1.0f) "${emulator.speedMultiplier.toInt()}x" else "1x"

        // Each entry: label -> action. Actions that open another dialog return true so the
        // emulator stays paused until that dialog is closed.
        val entries = listOf<Pair<String, () -> Boolean>>(
            "Continuar" to { false },
            "Guardar estado..." to { showSlotDialog(forLoading = false); true },
            "Cargar estado..." to { showSlotDialog(forLoading = true); true },
            "Filtros de pantalla / Shaders" to { showShaderDialog(); true },
            "Velocidad de juego ($speedText)" to { showSpeedDialog(); true },
            "Exportar partida GBA ($savName)..." to {
                autoSaveBattery(force = true)
                exportSaveLauncher.launch(savName); false
            },
            "Importar partida GBA (.sav)..." to { importSaveLauncher.launch("*/*"); false },
            "Cable Link P2P (Multijugador)" to { MultiplayerDialog(this, p2pManager).show(); false },
            "Reiniciar juego" to { emulator.reset(); false },
            "Cerrar juego" to {
                autoSaveBattery(force = true)
                finish(); false
            }
        )

        var openedSubDialog = false
        dialogBuilder()
            .setTitle(currentRomName)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                openedSubDialog = entries[which].second()
            }
            .setOnDismissListener {
                if (!openedSubDialog) resumeAfterMenu()
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

            // Auto-load the cartridge battery save (.sav from this app, MyBoy, mGBA, VBA-M...)
            val savFile = findBatterySave(file)
            batterySaveFile = savFile ?: File(file.parentFile, "${file.nameWithoutExtension}.sav")
            if (savFile != null) {
                try {
                    val savBytes = savFile.readBytes()
                    emulator.loadSaveData(savBytes)
                    nativeCore.nativeReset() // boot with the save present, like inserting the cartridge
                    lastBatteryHash = emulator.getSaveData()?.contentHashCode() ?: 0
                    Toast.makeText(this, "Partida cargada: ${savFile.name} (${savBytes.size / 1024} KB)", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error loading battery save", e)
                    Toast.makeText(this, "No se pudo leer ${savFile.name}: ${e.message}", Toast.LENGTH_LONG).show()
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
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        autoSaveBattery()
        p2pManager.disconnect()
        emulator.stop()
    }
}
