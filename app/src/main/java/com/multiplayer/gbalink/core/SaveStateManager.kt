package com.multiplayer.gbalink.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * Emulator save states organised in numbered slots (like MyBoy), stored next to the ROM:
 *   <carpeta ROM>/states/<rom>.ss<N>      snapshot of the whole machine
 *   <carpeta ROM>/states/<rom>.ss<N>.png  screenshot shown in the slot picker
 *
 * These are independent from the cartridge battery save (<rom>.sav), which is what the game
 * itself writes when you save in-game and is compatible with MyBoy / mGBA / VBA-M.
 */
class SaveStateManager(context: Context, private val romFile: File?, romName: String) {

    companion object {
        private const val TAG = "SaveStateManager"
        const val SLOT_COUNT = 10
    }

    data class Slot(val index: Int, val stateFile: File, val thumbFile: File) {
        val exists: Boolean get() = stateFile.exists() && stateFile.length() > 0
        val lastModified: Long get() = stateFile.lastModified()
        fun loadThumbnail(): Bitmap? =
            if (thumbFile.exists()) BitmapFactory.decodeFile(thumbFile.absolutePath) else null
    }

    private val baseName: String = romFile?.nameWithoutExtension ?: romName
    private val directory: File = resolveDirectory(context)

    private fun resolveDirectory(context: Context): File {
        romFile?.parentFile?.let { parent ->
            val dir = File(parent, "states")
            if ((dir.isDirectory || dir.mkdirs()) && dir.canWrite()) return dir
        }
        // Fallback when the ROM folder is read-only
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "states")
        dir.mkdirs()
        return dir
    }

    fun slots(): List<Slot> = (1..SLOT_COUNT).map { slot(it) }

    fun slot(index: Int) = Slot(
        index,
        File(directory, "$baseName.ss$index"),
        File(directory, "$baseName.ss$index.png")
    )

    fun save(index: Int, state: ByteArray, screenshot: Bitmap?): Boolean = try {
        val s = slot(index)
        val tmp = File(s.stateFile.path + ".tmp")
        tmp.writeBytes(state)
        if (!tmp.renameTo(s.stateFile)) {
            s.stateFile.writeBytes(state)
            tmp.delete()
        }
        screenshot?.let { bmp ->
            s.thumbFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Error saving state slot $index", e)
        false
    }

    fun load(index: Int): ByteArray? = try {
        slot(index).takeIf { it.exists }?.stateFile?.readBytes()
    } catch (e: Exception) {
        Log.e(TAG, "Error reading state slot $index", e)
        null
    }

    fun delete(index: Int) {
        val s = slot(index)
        s.stateFile.delete()
        s.thumbFile.delete()
    }
}
