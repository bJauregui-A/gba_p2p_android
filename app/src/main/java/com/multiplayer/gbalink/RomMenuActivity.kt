package com.multiplayer.gbalink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.multiplayer.gbalink.databinding.ActivityRomMenuBinding
import com.multiplayer.gbalink.showdown.ShowdownActivity
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class RomMenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRomMenuBinding
    private var currentDirectory: File = File(Environment.getExternalStorageDirectory(), "Download")

    data class ExplorerItem(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val isDemoRom: Boolean = false,
        val sizeString: String = "",
        val isParent: Boolean = false,
        val badge: String? = null
    )

    private val items = mutableListOf<ExplorerItem>()
    private lateinit var adapter: ExplorerAdapter

    // SAF folder picker
    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Toast.makeText(this, "Carpeta SAF seleccionada", Toast.LENGTH_SHORT).show()
        }
    }

    // SAF single file fallback
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { copyAndLaunchRom(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRomMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableImmersiveMode()
        checkStoragePermissions()

        val savedPath = getSharedPreferences("myboy_prefs", Context.MODE_PRIVATE).getString("last_dir", null)
        if (savedPath != null && File(savedPath).exists()) {
            currentDirectory = File(savedPath)
        } else {
            val downloadDir = File(Environment.getExternalStorageDirectory(), "Download")
            val romSubDir = File(downloadDir, "downloaded_rom")
            currentDirectory = when {
                romSubDir.exists() -> romSubDir
                downloadDir.exists() -> downloadDir
                else -> filesDir
            }
        }

        adapter = ExplorerAdapter()
        binding.listExplorer.adapter = adapter

        binding.listExplorer.setOnItemClickListener { _, _, position, _ ->
            val item = items[position]
            if (item.isDemoRom) {
                launchGame(null, "Demo Homebrew")
            } else if (item.isDirectory) {
                navigateTo(File(item.path))
            } else {
                handleRomSelection(item.path, File(item.path).nameWithoutExtension)
            }
        }

        binding.btnSelectFolder.setOnClickListener {
            val options = arrayOf(
                "Elegir archivo ROM (.gba, .zip)",
                "Ir a /Download/downloaded_rom",
                "Ir a /Download",
                "Elegir carpeta con SAF"
            )
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Explorador de ROMs")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> filePickerLauncher.launch("*/*")
                        1 -> {
                            val target = File(Environment.getExternalStorageDirectory(), "Download/downloaded_rom")
                            if (target.exists()) navigateTo(target)
                        }
                        2 -> {
                            val target = File(Environment.getExternalStorageDirectory(), "Download")
                            if (target.exists()) navigateTo(target)
                        }
                        3 -> folderPickerLauncher.launch(null)
                    }
                }
                .show()
        }

        binding.btnMenuShowdown.setOnClickListener {
            startActivity(Intent(this, ShowdownActivity::class.java))
        }

        binding.cardContinue.setOnClickListener {
            val prefs = getSharedPreferences("myboy_prefs", Context.MODE_PRIVATE)
            val path = prefs.getString("last_rom_path", null)
            if (path != null && File(path).exists()) {
                launchGame(path, prefs.getString("last_rom_name", null) ?: File(path).nameWithoutExtension)
            }
        }

        refreshDirectory()
    }

    /** Shows the "Continuar" card for the last played ROM, if it still exists. */
    private fun refreshContinueCard() {
        val prefs = getSharedPreferences("myboy_prefs", Context.MODE_PRIVATE)
        val path = prefs.getString("last_rom_path", null)
        if (path != null && File(path).exists()) {
            binding.txtContinueName.text = prefs.getString("last_rom_name", null) ?: File(path).nameWithoutExtension
            binding.cardContinue.visibility = View.VISIBLE
        } else {
            binding.cardContinue.visibility = View.GONE
        }
    }

    /** "Partida" chip when a battery save exists, plus the number of emulator state slots used. */
    private fun saveBadge(rom: File): String? {
        val base = rom.nameWithoutExtension
        val dir = rom.parentFile ?: return null
        val hasSav = listOf("$base.sav", "$base.SAV", "$base.srm").any { File(dir, it).isFile }
        val states = File(dir, "states").listFiles { f ->
            f.name.startsWith("$base.ss") && !f.name.endsWith(".png") && !f.name.endsWith(".tmp")
        }?.size ?: 0
        return when {
            hasSav && states > 0 -> "Partida · $states"
            hasSav -> "Partida"
            states > 0 -> "$states estados"
            else -> null
        }
    }

    private fun checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 101)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        refreshDirectory()
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

    private fun navigateTo(dir: File) {
        if (dir.exists() && dir.isDirectory) {
            currentDirectory = dir
            getSharedPreferences("myboy_prefs", Context.MODE_PRIVATE)
                .edit().putString("last_dir", dir.absolutePath).apply()
            refreshDirectory()
        }
    }

    private fun refreshDirectory() {
        binding.txtCurrentPath.text = currentDirectory.absolutePath
        refreshContinueCard()
        items.clear()

        // 1. Built-in Demo ROM always at top
        items.add(ExplorerItem("ROM Demo (Homebrew)", "", isDirectory = false, isDemoRom = true, sizeString = "ROM de prueba integrada"))

        // 2. Parent directory if not at root
        val parent = currentDirectory.parentFile
        if (parent != null && parent.canRead()) {
            items.add(ExplorerItem("Subir un nivel", parent.absolutePath, isDirectory = true, sizeString = parent.name.ifEmpty { "/" }, isParent = true))
        }

        // 3. Scan current directory for folders and ROM files
        val files = currentDirectory.listFiles()
        if (files != null) {
            val dirList = mutableListOf<ExplorerItem>()
            val romList = mutableListOf<ExplorerItem>()
            val validExtensions = setOf("gba", "bin", "agb", "gb", "gbc", "zip", "7z")

            for (f in files) {
                if (f.isDirectory && !f.name.startsWith(".")) {
                    dirList.add(ExplorerItem(f.name, f.absolutePath, isDirectory = true, sizeString = "Carpeta"))
                } else if (f.isFile) {
                    val ext = f.extension.lowercase()
                    if (validExtensions.contains(ext)) {
                        val sizeMb = String.format("%.1f MB", f.length().toDouble() / (1024 * 1024))
                        val desc = when (ext) {
                            "zip", "7z" -> "$sizeMb • Archivo Comprimido ($ext)"
                            "gb", "gbc" -> "$sizeMb • ROM Game Boy"
                            else -> "$sizeMb • ROM GBA"
                        }
                        romList.add(ExplorerItem(f.name, f.absolutePath, isDirectory = false, sizeString = desc, badge = saveBadge(f)))
                    }
                }
            }

            dirList.sortBy { it.name.lowercase() }
            romList.sortBy { it.name.lowercase() }

            items.addAll(dirList)
            items.addAll(romList)
        }

        val hasRoms = items.any { !it.isDirectory && !it.isDemoRom }
        binding.txtEmpty.visibility = if (hasRoms) View.GONE else View.VISIBLE

        adapter.notifyDataSetChanged()
    }

    private fun handleRomSelection(path: String, defaultName: String) {
        val file = File(path)
        if (file.extension.equals("zip", ignoreCase = true)) {
            val extracted = extractRomFromZip(file)
            if (extracted != null) {
                launchGame(extracted.absolutePath, extracted.nameWithoutExtension)
            } else {
                Toast.makeText(this, "No se encontró un archivo .gba dentro del zip", Toast.LENGTH_SHORT).show()
            }
        } else {
            launchGame(path, defaultName)
        }
    }

    private fun extractRomFromZip(zipFile: File): File? {
        try {
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val ext = File(entry.name).extension.lowercase()
                    if (ext == "gba" || ext == "bin" || ext == "agb") {
                        val targetDir = File(filesDir, "roms").apply { if (!exists()) mkdirs() }
                        val outFile = File(targetDir, File(entry.name).name)
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        return outFile
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun copyAndLaunchRom(uri: Uri) {
        try {
            var fileName = "Game.gba"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            }

            val internalDir = File(filesDir, "roms").apply { if (!exists()) mkdirs() }
            val destFile = File(internalDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            handleRomSelection(destFile.absolutePath, destFile.nameWithoutExtension)
        } catch (e: Exception) {
            Toast.makeText(this, "Error al abrir ROM: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchGame(romPath: String?, romName: String) {
        if (romPath != null) {
            getSharedPreferences("myboy_prefs", Context.MODE_PRIVATE).edit()
                .putString("last_rom_path", romPath)
                .putString("last_rom_name", romName)
                .apply()
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("ROM_PATH", romPath)
            putExtra("ROM_NAME", romName)
        }
        startActivity(intent)
    }

    inner class ExplorerAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@RomMenuActivity).inflate(R.layout.item_explorer, parent, false)
            val item = items[position]

            val txtName = view.findViewById<TextView>(R.id.txtItemName)
            val txtDetail = view.findViewById<TextView>(R.id.txtItemDetail)
            val txtBadge = view.findViewById<TextView>(R.id.txtItemBadge)
            val imgIcon = view.findViewById<ImageView>(R.id.imgItemIcon)

            txtName.text = item.name
            txtDetail.text = item.sizeString
            txtBadge.text = item.badge ?: ""
            txtBadge.visibility = if (item.badge != null) View.VISIBLE else View.GONE

            val ext = File(item.path).extension.lowercase()
            val (icon, tint) = when {
                item.isParent -> R.drawable.ic_arrow_up to R.color.text_secondary
                item.isDirectory -> R.drawable.ic_folder to R.color.gba_warning_yellow
                item.isDemoRom -> R.drawable.ic_play to R.color.gba_online_green
                ext == "zip" || ext == "7z" -> R.drawable.ic_archive to R.color.gba_button_b
                else -> R.drawable.ic_gamepad to R.color.gba_accent
            }
            imgIcon.setImageResource(icon)
            androidx.core.widget.ImageViewCompat.setImageTintList(
                imgIcon, android.content.res.ColorStateList.valueOf(getColor(tint))
            )

            return view
        }
    }
}
