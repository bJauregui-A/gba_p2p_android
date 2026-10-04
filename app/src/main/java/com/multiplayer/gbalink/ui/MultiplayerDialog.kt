package com.multiplayer.gbalink.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import com.multiplayer.gbalink.R
import com.multiplayer.gbalink.databinding.DialogMultiplayerBinding
import com.multiplayer.gbalink.network.P2PConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MultiplayerDialog(
    private val context: Context,
    private val p2pManager: P2PConnectionManager,
    private val onDismiss: (() -> Unit)? = null
) {

    fun show() {
        val b = DialogMultiplayerBinding.inflate(android.view.LayoutInflater.from(context))
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(b.root)
            window?.let { w ->
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                w.setDimAmount(0.65f)
                val dm = context.resources.displayMetrics
                w.setLayout(minOf((dm.widthPixels * 0.94f).toInt(), (460 * dm.density).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

        fun render(state: Int, msg: String) {
            b.txtStatus.text = msg
            val color = when (state) {
                P2PConnectionManager.STATE_CONNECTED -> context.getColor(R.color.gba_online_green)
                P2PConnectionManager.STATE_CONNECTING -> context.getColor(R.color.gba_warning_yellow)
                else -> context.getColor(R.color.text_secondary)
            }
            b.statusDot.backgroundTintList = ColorStateList.valueOf(color)
            b.progressLink.visibility = if (state == P2PConnectionManager.STATE_CONNECTING) View.VISIBLE else View.GONE
            b.btnDisconnect.visibility = if (state == P2PConnectionManager.STATE_DISCONNECTED) View.GONE else View.VISIBLE
            val idle = state == P2PConnectionManager.STATE_DISCONNECTED
            b.btnCreateRoom.isEnabled = idle
            b.btnJoinRoom.isEnabled = idle
            if (idle) b.layoutRoomCode.visibility = View.GONE
        }

        render(
            p2pManager.connectionState,
            when (p2pManager.connectionState) {
                P2PConnectionManager.STATE_CONNECTED -> "Conectado"
                P2PConnectionManager.STATE_CONNECTING -> "Esperando conexión..."
                else -> "Desconectado"
            }
        )
        p2pManager.onStateChanged = { state, msg -> render(state, msg) }

        b.btnCreateRoom.setOnClickListener {
            render(P2PConnectionManager.STATE_CONNECTING, "Creando sala...")
            CoroutineScope(Dispatchers.Main).launch {
                val code = p2pManager.createRoom()
                if (code != null) {
                    b.txtRoomCode.text = code
                    b.layoutRoomCode.visibility = View.VISIBLE
                }
            }
        }

        b.btnCopyCode.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Código GBA Link", b.txtRoomCode.text))
            Toast.makeText(context, "Código copiado", Toast.LENGTH_SHORT).show()
        }

        b.btnShareCode.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, b.txtRoomCode.text.toString())
            }
            context.startActivity(Intent.createChooser(send, "Enviar código de sala"))
        }

        b.btnPasteCode.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
            if (!text.isNullOrEmpty()) b.editJoinCode.setText(text)
        }

        b.btnJoinRoom.setOnClickListener {
            val code = b.editJoinCode.text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(context, "Pega el código que te envió el jugador 1", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            render(P2PConnectionManager.STATE_CONNECTING, "Conectando...")
            CoroutineScope(Dispatchers.Main).launch { p2pManager.joinRoom(code) }
        }

        b.btnDisconnect.setOnClickListener { p2pManager.disconnect() }
        b.btnCloseLink.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.show()
    }
}
