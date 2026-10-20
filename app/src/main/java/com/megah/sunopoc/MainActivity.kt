package com.megah.sunopoc

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Suno Driver — two screens switched by the segmented tab row:
 *  - WRITE screen: NVIDIA key card, song-idea card, channel profile,
 *    engine-state strip, manual description/style card, telemetry log,
 *    and the sticky amber "AI Write & Create" bar at the bottom.
 *  - SUNO PAGE screen: the WebView at FULL size (login, watching
 *    generation, listening to takes).
 *
 * The app opens on Write so it's obvious where the prompt goes. When a
 * Create fires, it auto-switches to the Suno Page so Megah sees it happen.
 * Cookies are accepted (incl. third-party) and flushed on pause so his
 * manual login persists across restarts.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var svPanel: ScrollView
    private lateinit var svSettings: ScrollView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var llCta: LinearLayout
    private lateinit var etApiKey: EditText
    private lateinit var etIdea: EditText
    private lateinit var etDescription: EditText
    private lateinit var etStyle: EditText
    private lateinit var btnTabWrite: TextView
    private lateinit var btnTabSuno: TextView
    private lateinit var btnAiCreate: TextView
    private lateinit var tvChannelDeep: TextView
    private lateinit var tvChannelStill: TextView
    private lateinit var tvAuthState: TextView
    private lateinit var tvIdeaCount: TextView
    private lateinit var tvEngineState: TextView
    private lateinit var tvLogCount: TextView
    private lateinit var tvLog: TextView
    private lateinit var meterStrip: MeterStripView

    private var selectedChannel = AiMind.Channel.DEEP_SOUND_REALM
    private var keyVisible = false
    private var logLines = 0

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var lastState: String? = null

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(SunoDriver.POLL_SCRIPT, null)
            }
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // Beat-follow state: which player signal is live, and an anchor clock
    // for the signals that report "playing" without a position.
    private var lastBeatKey = ""
    private var anchorStartNs = 0L
    private var anchorWasPlaying = false

    // Beat poller: asks the page's player where the music is, ~5x/second,
    // so the VU meter can sync to the real playback clock.
    private val beatHandler = Handler(Looper.getMainLooper())
    private val beatRunnable = object : Runnable {
        override fun run() {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(SunoDriver.BEAT_SCRIPT, null)
            }
            beatHandler.postDelayed(this, BEAT_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.sd_bg)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        svPanel = findViewById(R.id.svPanel)
        svSettings = findViewById(R.id.svSettings)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        llCta = findViewById(R.id.llCta)
        etApiKey = findViewById(R.id.etApiKey)
        etIdea = findViewById(R.id.etIdea)
        etDescription = findViewById(R.id.etDescription)
        etStyle = findViewById(R.id.etStyle)
        btnTabWrite = findViewById(R.id.btnTabWrite)
        btnTabSuno = findViewById(R.id.btnTabSuno)
        btnAiCreate = findViewById(R.id.btnAiCreate)
        tvChannelDeep = findViewById(R.id.tvChannelDeep)
        tvChannelStill = findViewById(R.id.tvChannelStill)
        tvAuthState = findViewById(R.id.tvAuthState)
        tvIdeaCount = findViewById(R.id.tvIdeaCount)
        tvEngineState = findViewById(R.id.tvEngineState)
        tvLogCount = findViewById(R.id.tvLogCount)
        tvLog = findViewById(R.id.tvLog)
        meterStrip = findViewById(R.id.meterStrip)

        // Restore the NVIDIA key he pasted on a previous run (this phone only).
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString(PREF_API_KEY, ""))
        updateAuthState()

        // Pre-filled manual test content (editable).
        etDescription.setText(DEFAULT_DESCRIPTION)
        etStyle.setText(DEFAULT_STYLE)

        // --- Tabs + refresh + settings ---
        btnTabWrite.setOnClickListener { showWrite() }
        btnTabSuno.setOnClickListener { showSuno() }
        findViewById<TextView>(R.id.btnRefresh).setOnClickListener {
            appendLog("Refresh tapped — reloading the Suno page…")
            webView.reload()
            showSuno()
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<TextView>(R.id.btnBackSettings).setOnClickListener { showWrite() }
        showWrite()

        // --- Settings: version facts + manual update check ---
        try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(packageName, 0)
            val versionText = "v${info.versionName} (build ${info.versionCode})"
            findViewById<TextView>(R.id.tvInfoVersion).text = versionText
            findViewById<TextView>(R.id.tvUpdateCurrent).text = versionText
        } catch (e: Exception) { /* version lines stay as placeholders */ }
        findViewById<TextView>(R.id.btnCheckUpdates).setOnClickListener {
            UpdateManager.manualCheck(this, tvUpdateStatus) { message -> appendLog(message) }
        }

        // --- Key show/hide + auth state ---
        findViewById<TextView>(R.id.btnToggleKey).setOnClickListener { toggle ->
            keyVisible = !keyVisible
            etApiKey.inputType = if (keyVisible)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            etApiKey.setSelection(etApiKey.text.length)
            (toggle as TextView).text = if (keyVisible) "HIDE" else "SHOW"
        }
        etApiKey.addTextChangedListener(simpleWatcher {
            // The key field lives in Settings now — persist as he types so
            // it is saved even if he never taps create in this session.
            prefs.edit().putString(PREF_API_KEY, etApiKey.text.toString().trim()).apply()
            updateAuthState()
        })

        // --- Idea char counter (design shows "n / 500 chars") ---
        etIdea.addTextChangedListener(simpleWatcher {
            tvIdeaCount.text = "${etIdea.text.length} / 500 chars"
        })

        // --- Channel profile pick ---
        tvChannelDeep.setOnClickListener {
            selectedChannel = AiMind.Channel.DEEP_SOUND_REALM
            meterStrip.setTempo(104.0)
            updateChannelUi()
        }
        tvChannelStill.setOnClickListener {
            selectedChannel = AiMind.Channel.STILL_MIND
            meterStrip.setTempo(60.0)
            updateChannelUi()
        }
        updateChannelUi()

        // --- Cookies: persist his Suno login across app restarts ---
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // --- WebView: JS + DOM storage on (Suno is a React app) ---
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
        }

        // --- Bridge: injected JS -> status log + engine state + beat ---
        val bridge = SunoDriver.PocBridge(
            onLog = { message -> appendLog(message) },
            onState = { state ->
                updateEngineState(state)
                if (state != lastState) {
                    lastState = state
                    appendLog("STATE -> $state")
                }
            },
            onBeat = { isPlaying, position, source, audioCount, mediaState ->
                runOnUiThread {
                    // Position: trust the audio element when it reports
                    // one; otherwise run an anchor clock from the moment
                    // playback was first seen (songs start on beat one).
                    val pos = when {
                        position >= 0 -> position
                        isPlaying -> {
                            if (!anchorWasPlaying) anchorStartNs = System.nanoTime()
                            (System.nanoTime() - anchorStartNs) / 1e9
                        }
                        else -> 0.0
                    }
                    anchorWasPlaying = isPlaying
                    meterStrip.setPlayback(isPlaying, pos)
                    // Log only when the picture changes, so the log shows
                    // which signal the player exposes without spamming.
                    val key = "$isPlaying|$source|$audioCount|$mediaState"
                    if (key != lastBeatKey) {
                        lastBeatKey = key
                        appendLog(
                            "Beat: playing=$isPlaying via=$source " +
                                "audioEls=$audioCount mediaSession=$mediaState"
                        )
                    }
                }
            }
        )
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                appendLog("Page loaded: $url")
            }
        }

        appendLog("Loading ${SunoDriver.SUNO_CREATE_URL} — tap \"Suno Page\" at the top to log in by hand; your login will be remembered.")
        webView.loadUrl(SunoDriver.SUNO_CREATE_URL)

        findViewById<TextView>(R.id.btnFillCreate).setOnClickListener {
            val description = etDescription.text.toString()
            val style = etStyle.text.toString()
            appendLog("Fill & Create tapped — driving the page…")
            SunoDriver.fillAndCreate(webView, description, style)
            showSuno()
        }

        findViewById<TextView>(R.id.btnCopyLogs).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Suno Driver logs", tvLog.text.toString()))
            Toast.makeText(this, "Logs copied — paste them to Ziggy", Toast.LENGTH_SHORT).show()
        }

        btnAiCreate.setOnClickListener { onAiCreateTapped() }

        // Start the page-state poller (3s) and the beat poller (~200ms).
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        beatHandler.postDelayed(beatRunnable, BEAT_INTERVAL_MS)

        // Forced-update check: the app always runs the latest release.
        UpdateManager.checkForForcedUpdate(this) { message -> appendLog(message) }
    }

    private fun onAiCreateTapped() {
        val apiKey = etApiKey.text.toString().trim()
        val idea = etIdea.text.toString().trim()
        if (apiKey.isEmpty()) {
            appendLog("AI: no NVIDIA API key — add it once in Settings (gear button, top right).")
            Toast.makeText(this, "Add your NVIDIA API key in Settings first", Toast.LENGTH_LONG).show()
            showSettings()
            return
        }
        if (idea.isEmpty()) {
            appendLog("AI: type a song idea first.")
            Toast.makeText(this, "Type a song idea first", Toast.LENGTH_LONG).show()
            return
        }

        // Remember the key on this phone only.
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_API_KEY, apiKey).apply()
        updateAuthState()

        btnAiCreate.isEnabled = false
        btnAiCreate.text = "AI working…"
        btnAiCreate.setBackgroundResource(R.drawable.bg_btn_primary_disabled)
        btnAiCreate.setTextColor(getColor(R.color.sd_text_dim))
        appendLog("AI Write & Create tapped — idea: \"$idea\"")

        AiMind.generate(
            apiKey = apiKey,
            idea = idea,
            channel = selectedChannel,
            onStatus = { message -> appendLog(message) },
            onDone = { prompt, error ->
                runOnUiThread {
                    btnAiCreate.isEnabled = true
                    btnAiCreate.text = "AI Write & Create"
                    btnAiCreate.setBackgroundResource(R.drawable.bg_btn_primary)
                    btnAiCreate.setTextColor(getColor(R.color.sd_on_amber))
                    if (error != null || prompt == null) {
                        appendLog("AI FAILED: ${error ?: "no prompt returned"}")
                        Toast.makeText(this, "AI failed — see the log", Toast.LENGTH_LONG).show()
                    } else {
                        appendLog("AI title: ${prompt.title.ifEmpty { "(none)" }}")
                        appendLog("AI description: ${prompt.description}")
                        appendLog("AI style: ${prompt.style}")
                        etDescription.setText(prompt.description)
                        etStyle.setText(prompt.style)
                        appendLog("AI prompt ready — driving Suno…")
                        SunoDriver.fillAndCreate(webView, prompt.description, prompt.style)
                        showSuno()
                    }
                }
            }
        )
    }

    private fun showWrite() {
        svPanel.visibility = View.VISIBLE
        llCta.visibility = View.VISIBLE
        webView.visibility = View.GONE
        svSettings.visibility = View.GONE
        btnTabWrite.setBackgroundResource(R.drawable.bg_tab_active)
        btnTabWrite.setTextColor(getColor(R.color.sd_on_amber))
        btnTabSuno.background = null
        btnTabSuno.setTextColor(getColor(R.color.sd_text_dim))
    }

    private fun showSuno() {
        svPanel.visibility = View.GONE
        llCta.visibility = View.GONE
        webView.visibility = View.VISIBLE
        svSettings.visibility = View.GONE
        btnTabSuno.setBackgroundResource(R.drawable.bg_tab_active)
        btnTabSuno.setTextColor(getColor(R.color.sd_on_amber))
        btnTabWrite.background = null
        btnTabWrite.setTextColor(getColor(R.color.sd_text_dim))
    }

    private fun showSettings() {
        svPanel.visibility = View.GONE
        llCta.visibility = View.GONE
        webView.visibility = View.GONE
        svSettings.visibility = View.VISIBLE
        btnTabWrite.background = null
        btnTabWrite.setTextColor(getColor(R.color.sd_text_dim))
        btnTabSuno.background = null
        btnTabSuno.setTextColor(getColor(R.color.sd_text_dim))
    }

    private fun updateChannelUi() {
        val deep = selectedChannel == AiMind.Channel.DEEP_SOUND_REALM
        tvChannelDeep.setBackgroundResource(
            if (deep) R.drawable.bg_segment_selected else 0
        )
        tvChannelDeep.setTextColor(
            getColor(if (deep) R.color.sd_text else R.color.sd_text_faint)
        )
        tvChannelStill.setBackgroundResource(
            if (!deep) R.drawable.bg_segment_selected else 0
        )
        tvChannelStill.setTextColor(
            getColor(if (!deep) R.color.sd_text else R.color.sd_text_faint)
        )
    }

    private fun updateAuthState() {
        val hasKey = etApiKey.text.toString().trim().isNotEmpty()
        tvAuthState.text = if (hasKey) "AUTH READY" else "NO KEY"
    }

    /** Maps the driver's raw page state onto the engine-state strip. */
    private fun updateEngineState(state: String) {
        runOnUiThread {
            val (label, colorRes) = when {
                state.startsWith("CHALLENGE") ->
                    "CLOUDFLARE CHECK — TAP IT ON THE SUNO PAGE" to R.color.sd_amber
                state.startsWith("FORM") ->
                    "READY FOR GENERATION" to R.color.sd_text
                state.startsWith("RESULTS") ->
                    "SONGS ON PAGE — GENERATING / READY" to R.color.sd_text
                state.startsWith("SIGNED_OUT") ->
                    "SIGNED OUT — LOG IN ON THE SUNO PAGE" to R.color.sd_amber
                else -> "SUNO LOADED" to R.color.sd_text
            }
            tvEngineState.text = label
            tvEngineState.setTextColor(getColor(colorRes))
        }
    }

    private fun appendLog(message: String) {
        // Bridge callbacks arrive off the UI thread — always hop.
        runOnUiThread {
            val line = "[${timeFmt.format(Date())}] $message\n"
            val warn = message.contains("WARN", ignoreCase = true) ||
                message.contains("Cloudflare", ignoreCase = true) ||
                message.contains("FAILED", ignoreCase = true) ||
                message.contains("error", ignoreCase = true)
            val spanned = SpannableString(line)
            spanned.setSpan(
                ForegroundColorSpan(
                    getColor(if (warn) R.color.sd_amber else R.color.sd_log_green)
                ),
                0, line.length, 0
            )
            tvLog.append(spanned)
            logLines++

            // Keep the buffer at the design's 100-line cap.
            if (logLines > MAX_LOG_LINES) {
                val text = tvLog.editableText
                var excess = logLines - MAX_LOG_LINES
                while (excess > 0) {
                    val nl = text.indexOf('\n')
                    if (nl < 0) break
                    text.delete(0, nl + 1)
                    excess--
                }
                logLines = MAX_LOG_LINES
            }
            tvLogCount.text = "LINES: $logLines/$MAX_LOG_LINES"
            svPanel.post { svPanel.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun simpleWatcher(after: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = after()
    }

    override fun onPause() {
        super.onPause()
        // Make sure cookies hit disk so the login survives an app kill.
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        pollHandler.removeCallbacks(pollRunnable)
        beatHandler.removeCallbacks(beatRunnable)
        super.onDestroy()
    }

    companion object {
        private const val BRIDGE_NAME = "PocBridge"
        private const val POLL_INTERVAL_MS = 3000L
        private const val BEAT_INTERVAL_MS = 200L
        private const val MAX_LOG_LINES = 100
        private const val PREFS_NAME = "sunopoc_prefs"
        private const val PREF_API_KEY = "nvidia_api_key"

        private const val DEFAULT_DESCRIPTION =
            "A romantic summer love song about holding the one you love close and " +
                "wishing the season would never end. Chorus comes in right at the " +
                "start, warm and catchy."

        private const val DEFAULT_STYLE =
            "tropical house, melodic edm pop, warm plucks, laid-back groove, " +
                "soft emotive male vocals, romantic feel-good summer vibe"
    }
}
