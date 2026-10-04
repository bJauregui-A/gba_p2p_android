package com.multiplayer.gbalink.showdown

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.multiplayer.gbalink.R
import com.multiplayer.gbalink.databinding.ActivityShowdownWebBinding

/**
 * The official Pokémon Showdown client (play.pokemonshowdown.com) inside the app: ladder,
 * teambuilder, challenges, chat rooms, replays, tournaments... exactly like the website.
 *
 * Extras over a plain browser: native notifications/vibration for challenges and PMs (the page's
 * Notification API is bridged to Android), the connection stays alive in the background, and
 * login cookies persist between sessions.
 */
class ShowdownWebActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://play.pokemonshowdown.com/"
        private const val CHANNEL_ID = "showdown_events"
        private val INTERNAL_HOSTS = listOf("pokemonshowdown.com", "psim.us", "smogon.com")

        /** Replaces window.Notification so Showdown's desktop notifications reach Android. */
        private const val NOTIFICATION_BRIDGE_JS = """
            (function() {
              if (window.__gbaBridge) return; window.__gbaBridge = true;
              function N(title, opts) {
                opts = opts || {};
                try { GbaShowdown.notify(String(title || ''), String(opts.body || ''), String(opts.tag || '')); } catch (e) {}
                this.close = function() {};
                this.onclick = null; this.onclose = null; this.onshow = null;
              }
              N.permission = 'granted';
              N.requestPermission = function(cb) { if (cb) cb('granted'); return Promise.resolve('granted'); };
              window.Notification = N;
            })();
        """
    }

    private lateinit var binding: ActivityShowdownWebBinding
    private var isInForeground = false
    private var notificationId = 1000
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        filePathCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        filePathCallback = null
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShowdownWebBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val web = binding.webShowdown
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false // battle music and cries
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false) // target=_blank opens in the same view
        }
        web.setBackgroundColor(0xFF1B1E2B.toInt())
        web.addJavascriptInterface(Bridge(), "GbaShowdown")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host ?: return false
                if (INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }) return false
                // Outside links (YouTube, Smogon forums in another domain...) go to the browser
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                binding.layoutWebError.visibility = View.GONE
                view.evaluateJavascript(NOTIFICATION_BRIDGE_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(NOTIFICATION_BRIDGE_JS, null)
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    binding.txtWebError.text = "No se pudo conectar con Pokémon Showdown\n(${error.description})"
                    binding.layoutWebError.visibility = View.VISIBLE
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progressWeb.setProgressCompat(newProgress, true)
                binding.progressWeb.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        binding.btnWebRetry.setOnClickListener { web.reload() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(HOME_URL)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webShowdown.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        binding.webShowdown.onResume()
        NotificationManagerCompat.from(this).cancelAll()
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
        // Note: timers are NOT paused, so the battle/challenge connection keeps running
        binding.webShowdown.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        binding.webShowdown.apply {
            stopLoading()
            destroy()
        }
        super.onDestroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun notify(title: String, body: String, tag: String) {
            runOnUiThread { onShowdownNotification(title, body) }
        }
    }

    private fun onShowdownNotification(title: String, body: String) {
        vibrate()
        if (isInForeground) return // the page already shows it
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, ShowdownWebActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pokeball)
            .setContentTitle(title.ifBlank { "Pokémon Showdown" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(notificationId++, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Pokémon Showdown", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Retos, mensajes privados y turnos de batalla"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(200)
            }
        } catch (_: Exception) {
        }
    }
}
