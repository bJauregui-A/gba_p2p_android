package com.multiplayer.gbalink.core

import android.graphics.Bitmap
import android.util.Log

class GbaNative {

    companion object {
        private const val TAG = "GbaNative"

        init {
            try {
                System.loadLibrary("gbalink_core")
                Log.i(TAG, "Native library 'gbalink_core' loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load 'gbalink_core' library", e)
            }
        }

        const val ROLE_STANDALONE = 0
        const val ROLE_MASTER = 1
        const val ROLE_SLAVE = 2

        // GBA Key masks
        const val KEY_A = 1 shl 0
        const val KEY_B = 1 shl 1
        const val KEY_SELECT = 1 shl 2
        const val KEY_START = 1 shl 3
        const val KEY_RIGHT = 1 shl 4
        const val KEY_LEFT = 1 shl 5
        const val KEY_UP = 1 shl 6
        const val KEY_DOWN = 1 shl 7
        const val KEY_R = 1 shl 8
        const val KEY_L = 1 shl 9
    }

    interface SioListener {
        fun onSioMasterTransferInitiated(masterData: Short)
    }

    var sioListener: SioListener? = null

    // JNI Native Methods
    external fun nativeInit()
    external fun nativeLoadRom(romBytes: ByteArray): Boolean
    external fun nativeReset()
    external fun nativeRunFrame(bitmap: Bitmap?, audioOut: ShortArray?, keyMask: Int): Int
    external fun nativeSetKeypad(keyMask: Int)
    external fun nativeSetLinkRole(role: Int)
    external fun nativeOnSioPacketReceived(masterData: Short, slaveData: Short)
    external fun nativeGetSaveData(): ByteArray?
    external fun nativeLoadSaveData(sramBytes: ByteArray)
    external fun nativeSaveState(): ByteArray?
    external fun nativeLoadState(stateBytes: ByteArray): Boolean

    /**
     * Called from C++ JNI when Master initiates an SIO transfer
     */
    fun onSioMasterTransfer(masterData: Short) {
        Log.d(TAG, "SIO Master transfer initiated: 0x${(masterData.toInt() and 0xFFFF).toString(16).uppercase()}")
        sioListener?.onSioMasterTransferInitiated(masterData)
    }
}
