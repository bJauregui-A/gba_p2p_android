package com.multiplayer.gbalink

import android.app.AlertDialog
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import android.view.Window
import android.widget.BaseAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.multiplayer.gbalink.core.GbaEmulator
import com.multiplayer.gbalink.core.GbaNative
import com.multiplayer.gbalink.core.HomebrewRom
import com.multiplayer.gbalink.core.SaveStateManager
import com.multiplayer.gbalink.databinding.ActivityMainBinding
import com.multiplayer.gbalink.databinding.DialogIngameMenuBinding
import com.multiplayer.gbalink.databinding.DialogSaveSlotsBinding
import com.multiplayer.gbalink.databinding.ItemMenuRowBinding
import com.multiplayer.gbalink.databinding.ItemMenuTileBinding
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
    private var sourceFile: File? = null   // file the user picked (the .zip for zipped ROMs)
    private var backupDone = false
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
        // For zipped ROMs the playable file is an extracted copy; saves belong next to the .zip
        sourceFile = intent.getStringExtra("ROM_SOURCE_PATH")?.let { File(it) }?.takeIf { it.exists() } ?: romFile
        saveStates = SaveStateManager(this, sourceFile, currentRomName)

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
        val rf = sourceFile ?: romFile ?: return false
        return try {
            val savBytes = emulator.getSaveData() ?: return false
            // Never replace a real save with an empty chip (all 0xFF / 0x00)
            if (isBlankSave(savBytes)) return false
            val hash = savBytes.contentHashCode()
            if (!force && hash == lastBatteryHash) return true
            val savFile = batterySaveFile ?: File(rf.parentFile, "${rf.nameWithoutExtension}.sav").also { batterySaveFile = it }
            // One backup per session of whatever was there before we first overwrite it
            if (!backupDone && savFile.exists() && savFile.length() > 0) {
                savFile.copyTo(File(savFile.path + ".bak"), overwrite = true)
                backupDone = true
            }
            savFile.writeBytes(savBytes)
            lastBatteryHash = hash
            Log.i("MainActivity", "Battery save written (${savBytes.size} bytes) to ${savFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e("MainActivity", "Error autosaving battery", e)
            false
        }
    }

    private fun isBlankSave(data: ByteArray): Boolean {
        val first = data.firstOrNull() ?: return true
        if (first != 0xFF.toByte() && first != 0.toByte()) return false
        return data.all { it == first }
    }

    /**
     * Looks for the battery save of [rom] written by this app, MyBoy (/MyBoy/save), RetroArch
     * (.srm), etc. Empty saves are ignored and, among the real ones, the most recent wins.
     */
    private fun findBatterySave(rom: File): File? {
        val base = rom.nameWithoutExtension
        val dir = rom.parentFile
        val storage = android.os.Environment.getExternalStorageDirectory()
        val folders = listOfNotNull(
            dir,
            dir?.let { File(it, "save") },
            dir?.let { File(it, "saves") },
            File(storage, "MyBoy/save"),
            File(storage, "MyBoy/saves"),
            File(storage, "MyBoy"),
            File(storage, "RetroArch/saves"),
            File(storage, "Download/MyBoy/save")
        ).distinct()
        val extensions = setOf("sav", "srm")

        val candidates = folders.flatMap { folder ->
            folder.listFiles()?.filter {
                it.isFile && it.length() >= 512 &&
                    it.extension.lowercase() in extensions &&
                    (it.nameWithoutExtension.equals(base, ignoreCase = true) ||
                        it.nameWithoutExtension.equals(rom.name, ignoreCase = true))
            } ?: emptyList()
        }
        Log.i("MainActivity", "Battery save candidates for '$base': ${candidates.map { "${it.path} (${it.length()} B)" }}")

        return candidates
            .filter { f -> runCatching { !isBlankSave(f.readBytes()) }.getOrDefault(false) }
            .maxByOrNull { it.lastModified() }
    }

    // ---------------------------------------------------------------------------------------
    // Save states (emulator slots, MyBoy-style)
    // ---------------------------------------------------------------------------------------

    private inner class SlotAdapter(private val forLoading: Boolean) : BaseAdapter() {
        private var slots = saveStates.slots()
        private var newest = newestSlot()
        private val thumbs = HashMap<Int, Bitmap?>()
        private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        private fun newestSlot() = slots.filter { it.exists }.maxByOrNull { it.lastModified }?.index

        fun refresh() {
            slots = saveStates.slots()
            newest = newestSlot()
            thumbs.clear()
            notifyDataSetChanged()
        }

        override fun getCount() = slots.size
        override fun getItem(position: Int) = slots[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = !forLoading || slots[position].exists

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val b = convertView?.let { ItemSaveSlotBinding.bind(it) }
                ?: ItemSaveSlotBinding.inflate(layoutInflater, parent, false)
            val slot = slots[position]
            val isNewest = slot.index == newest
            b.slotTitle.text = "Slot ${slot.index}"
            b.slotBadge.visibility = if (isNewest) View.VISIBLE else View.GONE
            b.slotCard.setBackgroundResource(if (isNewest) R.drawable.bg_slot_card_recent else R.drawable.bg_slot_card)
            if (slot.exists) {
                b.slotSubtitle.text = dateFormat.format(Date(slot.lastModified))
                b.slotThumb.setImageBitmap(thumbs.getOrPut(slot.index) { slot.loadThumbnail() })
                b.slotEmptyIcon.visibility = View.GONE
                b.root.alpha = 1f
            } else {
                b.slotSubtitle.text = if (forLoading) "Vacío" else "Vacío · toca para guardar"
                b.slotThumb.setImageDrawable(null)
                b.slotEmptyIcon.visibility = View.VISIBLE
                b.root.alpha = if (forLoading) 0.45f else 1f
            }
            return b.root
        }
    }

    /** Dark rounded floating panel used by the in-game menu and the slot picker. */
    private fun createPanelDialog(content: View, maxWidthDp: Int, heightFraction: Float? = null): Dialog {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0.65f)
            val dm = resources.displayMetrics
            val width = minOf((dm.widthPixels * 0.94f).toInt(), (maxWidthDp * dm.density).toInt())
            val height = heightFraction?.let { (dm.heightPixels * it).toInt() } ?: ViewGroup.LayoutParams.WRAP_CONTENT
            w.setLayout(width, height)
        }
        return dialog
    }

    private fun showSlotDialog(forLoading: Boolean) {
        emulator.pause()
        val b = DialogSaveSlotsBinding.inflate(layoutInflater)
        val adapter = SlotAdapter(forLoading)
        b.slotsTitle.text = if (forLoading) "Cargar estado" else "Guardar estado"
        b.slotsHint.text = if (forLoading) "Elige un slot · mantén pulsado para borrar" else "Elige dónde guardar · mantén pulsado para borrar"
        b.slotsIcon.setImageResource(if (forLoading) R.drawable.ic_restore else R.drawable.ic_save)
        b.slotGrid.adapter = adapter

        val dialog = createPanelDialog(b.root, maxWidthDp = 720, heightFraction = 0.88f)
        b.btnSlotsClose.setOnClickListener { dialog.dismiss() }

        b.slotGrid.setOnItemClickListener { _, _, position, _ ->
            val slot = adapter.getItem(position)
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
        b.slotGrid.setOnItemLongClickListener { _, _, position, _ ->
            val slot = adapter.getItem(position)
            if (!slot.exists) return@setOnItemLongClickListener false
            dialogBuilder()
                .setTitle("Borrar Slot ${slot.index}")
                .setMessage("¿Eliminar este estado guardado?")
                .setPositiveButton("Borrar") { _, _ ->
                    saveStates.delete(slot.index)
                    adapter.refresh()
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
            showOsd("Error al crear el estado")
            return
        }
        val screenshot = emulator.screenBitmap.copy(Bitmap.Config.ARGB_8888, false)
        val ok = saveStates.save(index, state, screenshot)
        showOsd(if (ok) "Estado guardado · Slot $index" else "No se pudo escribir el Slot $index")
    }

    private fun loadStateSlot(index: Int) {
        val data = saveStates.load(index)
        if (data == null || !emulator.loadState(data)) {
            showOsd("No se pudo cargar el Slot $index")
            return
        }
        showOsd("Estado cargado · Slot $index")
    }

    /** Short MyBoy-style message over the game screen. */
    private fun showOsd(text: String) {
        val osd = binding.txtOsd
        osd.text = text
        osd.animate().cancel()
        osd.visibility = View.VISIBLE
        osd.alpha = 0f
        osd.animate().alpha(1f).setDuration(150).withEndAction {
            osd.animate().alpha(0f).setStartDelay(1600).setDuration(300).withEndAction {
                osd.visibility = View.GONE
            }.start()
        }.start()
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
                    showOsd("Filtro: ${selected.displayName}")
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
                setSpeed(factors[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .setOnDismissListener { resumeAfterMenu() }
            .show()
    }

    private fun setSpeed(factor: Float) {
        emulator.speedMultiplier = factor
        if (factor > 1f) {
            getSharedPreferences("gba_prefs", Context.MODE_PRIVATE).edit().putFloat("turbo_speed", factor).apply()
        }
        showOsd(if (factor > 1f) "Turbo ${factor.toInt()}x" else "Velocidad normal")
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
        val hasBattery = batterySaveFile?.exists() == true
        val speed = emulator.speedMultiplier
        val turbo = getSharedPreferences("gba_prefs", Context.MODE_PRIVATE).getFloat("turbo_speed", 2f)

        val b = DialogIngameMenuBinding.inflate(layoutInflater)
        val dialog = createPanelDialog(b.root, maxWidthDp = 460)
        var openedSubDialog = false

        b.menuTitle.text = currentRomName
        b.menuSubtitle.text = buildString {
            append(if (hasBattery) "Partida: ${batterySaveFile?.parentFile?.name}/$savName" else "Sin partida guardada aún")
            append(" · ${if (speed > 1f) "${speed.toInt()}x" else "1x"}")
        }

        // Action that opens another dialog: keep the emulator paused until that one closes
        fun sub(action: () -> Unit): () -> Unit = {
            openedSubDialog = true
            dialog.dismiss()
            action()
        }
        // Action that returns to the game
        fun now(action: () -> Unit): () -> Unit = {
            dialog.dismiss()
            action()
        }

        fun tile(icon: Int, label: String, active: Boolean = false, onClick: () -> Unit) {
            val t = ItemMenuTileBinding.inflate(layoutInflater, b.menuTiles, false)
            t.tileIcon.setImageResource(icon)
            t.tileLabel.text = label
            if (active) t.root.setBackgroundResource(R.drawable.bg_menu_tile_active)
            t.root.setOnClickListener { onClick() }
            b.menuTiles.addView(t.root)
        }

        fun section(title: String) {
            val v = layoutInflater.inflate(R.layout.item_menu_section, b.menuRows, false) as android.widget.TextView
            v.text = title
            b.menuRows.addView(v)
        }

        fun row(icon: Int, label: String, value: String? = null, danger: Boolean = false, onClick: () -> Unit) {
            val r = ItemMenuRowBinding.inflate(layoutInflater, b.menuRows, false)
            r.rowIcon.setImageResource(icon)
            r.rowLabel.text = label
            r.rowValue.text = value ?: ""
            r.rowValue.visibility = if (value == null) View.GONE else View.VISIBLE
            if (danger) {
                val red = getColor(R.color.danger)
                r.rowLabel.setTextColor(red)
                ImageViewCompat.setImageTintList(r.rowIcon, ColorStateList.valueOf(red))
            }
            r.root.setOnClickListener { onClick() }
            b.menuRows.addView(r.root)
        }

        tile(R.drawable.ic_save, "Guardar\nestado", onClick = sub { showSlotDialog(forLoading = false) })
        tile(R.drawable.ic_restore, "Cargar\nestado", onClick = sub { showSlotDialog(forLoading = true) })
        tile(R.drawable.ic_fast_forward, if (speed > 1f) "Turbo\n${speed.toInt()}x" else "Turbo\n${turbo.toInt()}x",
            active = speed > 1f, onClick = now { setSpeed(if (speed > 1f) 1f else turbo) })
        tile(R.drawable.ic_screen, "Filtros\nvideo", onClick = sub { showShaderDialog() })

        section("Juego")
        row(R.drawable.ic_speed, "Velocidad", if (speed > 1f) "${speed.toInt()}x" else "Normal", onClick = sub { showSpeedDialog() })
        row(R.drawable.ic_link, "Cable Link P2P", "Multijugador", onClick = now { MultiplayerDialog(this, p2pManager).show() })
        row(R.drawable.ic_refresh, "Reiniciar juego", onClick = {
            openedSubDialog = true
            dialog.dismiss()
            dialogBuilder()
                .setTitle("Reiniciar juego")
                .setMessage("Se perderá el progreso que no hayas guardado. ¿Continuar?")
                .setPositiveButton("Reiniciar") { _, _ -> emulator.reset() }
                .setNegativeButton("Cancelar", null)
                .setOnDismissListener { resumeAfterMenu() }
                .show()
        })

        section("Partida GBA (.sav)")
        row(R.drawable.ic_upload, "Exportar partida", savName, onClick = now {
            autoSaveBattery(force = true)
            exportSaveLauncher.launch(savName)
        })
        row(R.drawable.ic_download, "Importar partida (.sav)", onClick = now { importSaveLauncher.launch("*/*") })

        section("")
        row(R.drawable.ic_exit, "Cerrar juego", danger = true, onClick = now {
            autoSaveBattery(force = true)
            finish()
        })

        b.btnMenuClose.setOnClickListener { dialog.dismiss() }
        b.btnMenuContinue.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            if (!openedSubDialog) resumeAfterMenu()
        }
        dialog.show()
    }

    private fun loadRomFromFile(file: File) {
        try {
            val bytes = FileInputStream(file).use { it.readBytes() }
            val ok = emulator.loadRom(bytes)
            if (!ok) {
                Toast.makeText(this, "Aviso: Error al decodificar ROM", Toast.LENGTH_SHORT).show()
            }

            // Auto-load the cartridge battery save (.sav from this app, MyBoy, mGBA, VBA-M...)
            val base = sourceFile ?: file
            val savFile = findBatterySave(base)
            batterySaveFile = savFile ?: File(base.parentFile, "${base.nameWithoutExtension}.sav")
            if (savFile != null) {
                try {
                    val savBytes = savFile.readBytes()
                    emulator.loadSaveData(savBytes)
                    nativeCore.nativeReset() // boot with the save present, like inserting the cartridge
                    lastBatteryHash = emulator.getSaveData()?.contentHashCode() ?: 0
                    Toast.makeText(this, "Partida cargada: ${savFile.parentFile?.name}/${savFile.name} (${savBytes.size / 1024} KB)", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error loading battery save", e)
                    Toast.makeText(this, "No se pudo leer ${savFile.name}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "No se encontró partida (.sav) para ${base.nameWithoutExtension}", Toast.LENGTH_LONG).show()
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
