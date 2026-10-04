package com.multiplayer.gbalink.showdown

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Battle screen rendered by Pokémon Showdown's own battle engine (the one its replays use):
 * backgrounds, animated sprites, HP bars, weather, move animations, music and the battle log look
 * exactly like the website. Battle lines received by [ShowdownClient] are fed in as they arrive,
 * and the move / switch buttons are drawn in the page with Showdown's layout; choices come back
 * through [onChoice].
 *
 * If the engine can't be downloaded, [onFailed] fires and the caller falls back to the native view.
 */
class ShowdownBattleWeb(
    context: Context,
    private val container: FrameLayout,
    private val onChoice: (choice: String, rqid: String) -> Unit,
    private val onUndo: () -> Unit,
    private val onReady: () -> Unit,
    private val onFailed: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val web = WebView(context)
    private var pageLoaded = false
    private val pendingScripts = mutableListOf<String>()
    private val lineBuffer = mutableListOf<String>()
    private var flushPosted = false
    private var finished = false

    var isReady = false
        private set

    init {
        setup()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // battle music and cries
            textZoom = 100
        }
        web.setBackgroundColor(0xFF1B1E2B.toInt())
        web.addJavascriptInterface(Bridge(), "GbaBattle")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (pageLoaded) return
                pageLoaded = true
                pendingScripts.forEach { web.evaluateJavascript(it, null) }
                pendingScripts.clear()
            }
        }
        container.removeAllViews()
        container.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val html = web.context.assets.open("showdown/battle.html").bufferedReader().use { it.readText() }
        // Showdown's origin as base URL so the engine resolves its sprites, sounds and data normally
        web.loadDataWithBaseURL("https://play.pokemonshowdown.com/", html, "text/html", "utf-8", null)

        // Safety net: if nothing reports back, use the native view
        main.postDelayed({ if (!isReady && !finished) fail("Tiempo de espera agotado") }, 30_000)
    }

    private fun run(script: String) {
        if (finished) return
        if (pageLoaded) web.evaluateJavascript(script, null) else pendingScripts.add(script)
    }

    /** Raw protocol line of the battle room (e.g. "|move|p1a: Pikachu|Thunderbolt|p2a: Onix"). */
    fun feed(line: String) {
        lineBuffer.add(line)
        if (!flushPosted) {
            flushPosted = true
            main.postDelayed({
                flushPosted = false
                if (lineBuffer.isNotEmpty()) {
                    run("gbaFeed(" + JSONArray(lineBuffer.toList()).toString() + ")")
                    lineBuffer.clear()
                }
            }, 30)
        }
    }

    fun setPerspective(side: String) = run("gbaSetPerspective(" + JSONObject.quote(side) + ")")

    fun setRequest(json: String) = run("gbaSetRequest(" + JSONObject.quote(json) + ")")

    fun clearControls(message: String) = run("gbaClearControls(" + JSONObject.quote(message) + ")")

    fun destroy() {
        finished = true
        main.removeCallbacksAndMessages(null)
        container.removeView(web)
        web.stopLoading()
        web.destroy()
    }

    private fun fail(reason: String) {
        if (finished) return
        finished = true
        onFailed(reason)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun ready() {
            main.post {
                if (!finished) {
                    isReady = true
                    onReady()
                }
            }
        }

        @JavascriptInterface
        fun failed(reason: String) {
            main.post { fail(reason) }
        }

        @JavascriptInterface
        fun choose(choice: String, rqid: String) {
            main.post { if (!finished) onChoice(choice, rqid) }
        }

        @JavascriptInterface
        fun undo() {
            main.post { if (!finished) onUndo() }
        }
    }
}
