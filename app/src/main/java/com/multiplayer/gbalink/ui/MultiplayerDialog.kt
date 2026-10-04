package com.multiplayer.gbalink.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import com.multiplayer.gbalink.R
import com.multiplayer.gbalink.network.P2PConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MultiplayerDialog(
    private val context: Context,
    private val p2pManager: P2PConnectionManager
) {

    fun show() {
        val builder = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_multiplayer, null)

        val txtStatus = view.findViewById<TextView>(R.id.txtStatus)
        val txtRoomCode = view.findViewById<TextView>(R.id.txtRoomCode)
        val editJoinCode = view.findViewById<EditText>(R.id.editJoinCode)
        val btnCreate = view.findViewById<Button>(R.id.btnCreateRoom)
        val btnCopy = view.findViewById<Button>(R.id.btnCopyCode)
        val btnJoin = view.findViewById<Button>(R.id.btnJoinRoom)
        val btnDisconnect = view.findViewById<Button>(R.id.btnDisconnect)
        val layoutRoomCode = view.findViewById<LinearLayout>(R.id.layoutRoomCode)

        val dialog = builder.setView(view).create()

        p2pManager.onStateChanged = { state, msg ->
            txtStatus.text = msg
            if (state == P2PConnectionManager.STATE_CONNECTED) {
                btnDisconnect.visibility = View.VISIBLE
            }
        }

        btnCreate.setOnClickListener {
            txtStatus.text = "Contactando STUN de Google..."
            CoroutineScope(Dispatchers.Main).launch {
                val code = p2pManager.createRoom()
                if (code != null) {
                    layoutRoomCode.visibility = View.VISIBLE
                    txtRoomCode.text = code
                    btnDisconnect.visibility = View.VISIBLE
                }
            }
        }

        btnCopy.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("GBA Room Code", txtRoomCode.text))
            Toast.makeText(context, "Código copiado al portapapeles", Toast.LENGTH_SHORT).show()
        }

        btnJoin.setOnClickListener {
            val code = editJoinCode.text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(context, "Ingresa un código de sala", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            txtStatus.text = "Conectando P2P vía STUN..."
            CoroutineScope(Dispatchers.Main).launch {
                val ok = p2pManager.joinRoom(code)
                if (ok) {
                    btnDisconnect.visibility = View.VISIBLE
                }
            }
        }

        btnDisconnect.setOnClickListener {
            p2pManager.disconnect()
            layoutRoomCode.visibility = View.GONE
            btnDisconnect.visibility = View.GONE
            txtStatus.text = "Desconectado"
        }

        dialog.show()
    }
}
