package com.echocover

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var auth: Auth
    private lateinit var api: SpotifyApi
    private lateinit var root: View
    private lateinit var player: View
    private lateinit var cover: ImageView
    private lateinit var status: TextView
    private lateinit var clockBox: View
    private lateinit var connect: Button
    private lateinit var webContainer: FrameLayout

    private lateinit var home: View
    private lateinit var lastPlayedBox: View
    private lateinit var lastArt: ImageView
    private lateinit var lastLabel: TextView
    private lateinit var lastTitle: TextView
    private lateinit var lastArtist: TextView
    private lateinit var nextSession: View
    private lateinit var nextLabel: TextView
    private lateinit var nextName: TextView
    private lateinit var nextCountdown: TextView

    private lateinit var f1Api: F1Api
    private lateinit var f1: View
    private lateinit var f1Map: TrackMapView
    private lateinit var f1Board: LeaderboardView
    private lateinit var f1Event: TextView
    private lateinit var f1Lap: TextView
    private lateinit var f1Status: TextView

    private val io = Executors.newFixedThreadPool(2)
    private val f1Io = Executors.newSingleThreadExecutor() // so a slow F1 server never holds up Spotify
    private val ui = Handler(Looper.getMainLooper())
    private val pollTask = Runnable { poll() }
    private val f1PollTask = Runnable { pollF1() }
    private val countdownTask = Runnable { updateCountdown() }

    // The last song seen playing, kept across restarts for the home screen.
    private val homePrefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    private var homeArtUrl: String? = null
    private val fadeTargets = HashMap<View, Boolean>()   // where each screen is fading to
    private var artOutline: GradientDrawable? = null   // empty-state placeholder for the art
    private var lastPlayedFetchedAt = 0L
    private var needsReconnect = false
    private var f1Next: F1Next? = null

    // Home screen text: translucent white stays readable on black and on a song's colour.
    private val labelText = Color.argb(179, 255, 255, 255)
    private val secondaryText = Color.argb(217, 255, 255, 255)

    private var f1SessionKey = 0L
    private var f1DismissedSession = -1L  // session the dashboard was long-pressed away for
    private var f1LastLive = 0L

    // Background gradient that takes its colour from the album art.
    private val bgDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.BLACK, Color.BLACK)
    )
    private var bgColor = Color.BLACK
    private var bgAnimator: ValueAnimator? = null

    private var coverUrl: String? = null
    private var playing = true          // what the cover is currently showing (playing vs paused)
    private var lastToggleAt = 0L       // when the user last tapped the cover
    private var pausedSince = 0L        // 0 = not paused
    private var webView: WebView? = null
    private var active = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        auth = Auth(this)
        api = SpotifyApi(auth)
        root = findViewById(R.id.root)
        player = findViewById(R.id.player)
        cover = findViewById(R.id.cover)
        status = findViewById(R.id.status)
        clockBox = findViewById(R.id.clockBox)
        connect = findViewById(R.id.connect)
        webContainer = findViewById(R.id.webContainer)
        home = findViewById(R.id.home)
        lastPlayedBox = findViewById(R.id.lastPlayed)
        lastArt = findViewById(R.id.lastArt)
        lastLabel = findViewById(R.id.lastLabel)
        lastTitle = findViewById(R.id.lastTitle)
        lastArtist = findViewById(R.id.lastArtist)
        nextSession = findViewById(R.id.nextSession)
        nextLabel = findViewById(R.id.nextLabel)
        nextName = findViewById(R.id.nextName)
        nextCountdown = findViewById(R.id.nextCountdown)
        f1Api = F1Api()
        f1 = findViewById(R.id.f1)
        f1Map = findViewById(R.id.f1Map)
        f1Board = findViewById(R.id.f1Board)
        f1Event = findViewById(R.id.f1Event)
        f1Lap = findViewById(R.id.f1Lap)
        f1Status = findViewById(R.id.f1Status)

        root.background = bgDrawable

        // Rounded album cover with a soft shadow.
        val density = resources.displayMetrics.density
        val radius = 20 * density
        cover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        cover.clipToOutline = true
        cover.elevation = 24 * density

        // Home screen art: rounded, with an outline that shows while there's no image.
        val artRadius = 6 * density
        lastArt.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, artRadius)
            }
        }
        lastArt.clipToOutline = true
        artOutline = GradientDrawable().apply {
            cornerRadius = artRadius
            setStroke((1.5f * density).toInt(), Color.rgb(51, 51, 51))
        }

        // Invisible skip zones either side of the cover; tapping the cover plays/pauses.
        findViewById<View>(R.id.prevZone).setOnClickListener { command { api.skipPrevious() } }
        findViewById<View>(R.id.nextZone).setOnClickListener { command { api.skipNext() } }
        cover.setOnClickListener { togglePlayback() }

        connect.setOnClickListener { startLogin() }
        // Long-press the clock to disconnect your Spotify account from this device.
        clockBox.setOnLongClickListener {
            auth.logout()
            showConnect("Disconnected")
            true
        }
        // Long-press the F1 dashboard to go back to the cover for the rest of that session.
        f1.setOnLongClickListener {
            f1DismissedSession = f1SessionKey
            hideF1()
            true
        }

        if (!handleRedirect(intent?.data)) {
            if (auth.isLoggedIn) showClock() else showConnect()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        active = true
        if (auth.isLoggedIn) schedule(0)
        if (f1Api.enabled) scheduleF1(0)
        if (isShowing(home)) updateHome()
    }

    override fun onPause() {
        active = false
        ui.removeCallbacks(pollTask)
        ui.removeCallbacks(f1PollTask)
        ui.removeCallbacks(countdownTask)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRedirect(intent.data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView != null) hideWebLogin() else super.onBackPressed()
    }

    // ---------- Polling & rendering ----------

    private fun schedule(delayMs: Long) {
        ui.removeCallbacks(pollTask)
        if (active) ui.postDelayed(pollTask, delayMs)
    }

    private fun poll() {
        if (!active || !auth.isLoggedIn) return
        io.execute {
            val result = api.current()
            ui.post { render(result) }
        }
    }

    private fun render(result: Playback) {
        when (result) {
            is Playback.Playing -> {
                val now = System.currentTimeMillis()
                rememberLastPlayed(result)
                if (result.isPlaying) pausedSince = 0L else if (pausedSince == 0L) pausedSince = now
                val showCover = result.isPlaying || now - pausedSince < Config.PAUSED_CLOCK_AFTER_MS
                if (showCover) {
                    fade(home, false)
                    fade(player, true)
                    fade(status, false)
                    fade(connect, false)
                    result.imageUrl?.let { loadCover(it) }
                    // Don't let a slightly stale poll undo a tap the user just made.
                    if (now - lastToggleAt > 2500) applyPlayState(result.isPlaying)
                } else {
                    showClock()
                }
                schedule(Config.POLL_MS)
            }
            Playback.Idle -> {
                // Nothing playing anywhere: give it the same grace as a pause before going home.
                val now = System.currentTimeMillis()
                if (pausedSince == 0L) pausedSince = now
                if (isShowing(player) && now - pausedSince < Config.PAUSED_CLOCK_AFTER_MS) {
                    applyPlayState(false)
                    schedule(Config.POLL_MS)
                } else {
                    showClock()
                    refreshLastPlayed()
                    schedule(Config.POLL_MS * 2)
                }
            }
            Playback.AuthRequired -> showConnect("Please reconnect Spotify")
            is Playback.Error -> schedule(result.retryAfterMs)
        }
    }

    private fun showClock() {
        fade(player, false) { resetCover() }   // keep the cover until it has faded out
        fade(status, false)
        fade(connect, false)
        fade(home, true)
        updateHome()
        // Keep the last song's colour behind the clock rather than going to black.
        val tinted = homePrefs.getString("title", null) != null && homePrefs.contains("color")
        fadeBackgroundTo(if (tinted) homePrefs.getInt("color", Color.BLACK) else Color.BLACK)
    }

    private fun showConnect(message: String = "Connect your Spotify account") {
        ui.removeCallbacks(pollTask)
        fade(player, false) { resetCover() }
        fade(home, false)
        status.text = message
        fade(status, true)
        fade(connect, true)
        fadeBackgroundTo(Color.BLACK)
    }

    private fun isShowing(view: View) = fadeTargets[view] ?: (view.visibility == View.VISIBLE)

    /**
     * Fades a screen in or out instead of switching it instantly. [onHidden] runs once it has
     * fully faded out; if the screen is brought back before then, it doesn't run at all.
     */
    private fun fade(view: View, show: Boolean, onHidden: (() -> Unit)? = null) {
        if (isShowing(view) == show) return
        fadeTargets[view] = show
        view.animate().cancel()
        if (show) {
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.visibility = View.VISIBLE
            }
            view.animate().alpha(1f).setDuration(Config.FADE_MS).start()
        } else {
            view.animate().alpha(0f).setDuration(Config.FADE_MS).withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
                onHidden?.invoke()
            }.start()
        }
    }

    private fun resetCover() {
        coverUrl = null
        cover.animate().cancel()
        cover.scaleX = 1f
        cover.scaleY = 1f
        cover.setImageDrawable(null)
        playing = true
    }

    // ---------- Album art, colour and play/pause ----------

    private fun loadCover(url: String) {
        if (url == coverUrl) return
        coverUrl = url
        io.execute {
            val bitmap = try {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
            val color = bitmap?.let { dominantColor(it) }
            ui.post {
                if (url != coverUrl) return@post
                if (bitmap != null && color != null) {
                    swapCover(bitmap)
                    fadeBackgroundTo(color)
                    homePrefs.edit().putInt("color", color).apply()
                } else {
                    coverUrl = null // retry on the next poll
                }
            }
        }
    }

    /** Cross-fades from the old cover to the new one. */
    private fun swapCover(bitmap: Bitmap) {
        val next = BitmapDrawable(resources, bitmap)
        val current = cover.drawable
        if (current == null) {
            cover.setImageDrawable(next)
            return
        }
        // Unwrap so we never build an ever-growing chain of transitions.
        val from = (current as? TransitionDrawable)?.getDrawable(1) ?: current
        val transition = TransitionDrawable(arrayOf(from, next))
        transition.isCrossFadeEnabled = true
        cover.setImageDrawable(transition)
        transition.startTransition(500)
    }

    /** Smoothly fades the background gradient to a new colour. */
    private fun fadeBackgroundTo(target: Int) {
        if (target == bgColor && bgAnimator == null) return
        bgAnimator?.cancel()
        bgAnimator = ValueAnimator.ofObject(ArgbEvaluator(), bgColor, target).apply {
            duration = 1200
            addUpdateListener { applyBackground(it.animatedValue as Int) }
            start()
        }
    }

    private fun applyBackground(color: Int) {
        bgColor = color
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] *= 0.35f
        bgDrawable.colors = intArrayOf(color, Color.HSVToColor(hsv))
    }

    /**
     * Picks the dominant vivid colour of the artwork: groups pixels by hue, favours saturated
     * and bright ones, then keeps the winning hue and tones it so it works as a background.
     */
    private fun dominantColor(src: Bitmap): Int {
        val size = 24
        val small = Bitmap.createScaledBitmap(src, size, size, true)
        val w = DoubleArray(12)
        val rs = DoubleArray(12)
        val gs = DoubleArray(12)
        val bs = DoubleArray(12)
        val hsv = FloatArray(3)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val p = small.getPixel(x, y)
                Color.colorToHSV(p, hsv)
                val weight = hsv[1] * hsv[2] + 0.02
                val k = (hsv[0] / 30f).toInt().coerceIn(0, 11)
                w[k] += weight
                rs[k] += Color.red(p) * weight
                gs[k] += Color.green(p) * weight
                bs[k] += Color.blue(p) * weight
            }
        }
        var best = 0
        for (k in 1 until 12) if (w[k] > w[best]) best = k
        val avg = Color.rgb(
            (rs[best] / w[best]).toInt(),
            (gs[best] / w[best]).toInt(),
            (bs[best] / w[best]).toInt()
        )
        Color.colorToHSV(avg, hsv)
        hsv[1] = (hsv[1] * 1.1f).coerceAtMost(1f)
        hsv[2] = hsv[2].coerceIn(0.35f, 0.7f)
        return Color.HSVToColor(hsv)
    }

    /** Tap on the cover: pause or resume. The cover shrinks slightly while paused. */
    private fun togglePlayback() {
        val nowPlaying = !playing
        lastToggleAt = System.currentTimeMillis()
        applyPlayState(nowPlaying)
        io.execute {
            if (nowPlaying) api.play() else api.pause()
            ui.post { schedule(1500) }
        }
    }

    private fun applyPlayState(isPlaying: Boolean) {
        if (isPlaying == playing) return
        playing = isPlaying
        val scale = if (isPlaying) 1f else 0.88f
        cover.animate().scaleX(scale).scaleY(scale).setDuration(300).start()
    }

    private fun command(block: () -> Unit) {
        io.execute {
            block()
            ui.post { schedule(500) } // give Spotify a moment, then refresh the cover
        }
    }

    // ---------- Home screen ----------

    /** Notes what's playing (or paused), so the home screen can show it once playback stops. */
    private fun rememberLastPlayed(p: Playback.Playing) {
        p.title?.let { saveLastPlayed(it, p.artist, p.imageUrl) }
    }

    /** With nothing active, asks Spotify's listening history for the last track played. */
    private fun refreshLastPlayed() {
        val now = System.currentTimeMillis()
        if (now - lastPlayedFetchedAt < 2 * 60_000) return
        lastPlayedFetchedAt = now
        io.execute {
            val result = api.lastPlayed()
            ui.post {
                when (result) {
                    is LastPlayed.Track -> {
                        needsReconnect = false
                        saveLastPlayed(result.title, result.artist, result.imageUrl)
                    }
                    LastPlayed.NeedsReconnect -> needsReconnect = true
                    LastPlayed.Unavailable -> Unit
                }
                if (isShowing(home)) updateHome()
            }
        }
    }

    private fun saveLastPlayed(title: String, artist: String?, imageUrl: String?) {
        if (title == homePrefs.getString("title", null) && artist == homePrefs.getString("artist", null)) return
        homePrefs.edit()
            .putString("title", title)
            .putString("artist", artist)
            .putString("image", imageUrl)
            .apply()
    }

    /** Fills in the last played song and the countdown to the next F1 session. */
    private fun updateHome() {
        val title = homePrefs.getString("title", null)
        // Signed in before the listening-history permission existed: tapping here reconnects.
        val reconnect = title == null && needsReconnect
        lastPlayedBox.setOnClickListener(if (reconnect) View.OnClickListener { startLogin() } else null)
        lastPlayedBox.isClickable = reconnect
        if (title == null) {
            // An empty art outline and a hint rather than a blank corner.
            lastLabel.visibility = View.GONE
            lastTitle.text = if (reconnect) "Reconnect Spotify" else "Nothing played recently"
            lastTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            lastTitle.setTextColor(secondaryText)
            lastArtist.text = if (reconnect) "Tap here to show your last song" else "Play on Spotify to see it here"
            lastArtist.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            lastArtist.setTextColor(labelText)
            lastArt.background = artOutline
            loadHomeArt(null)
        } else {
            lastLabel.visibility = View.VISIBLE
            lastTitle.text = title
            lastTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 27f)
            lastTitle.setTextColor(Color.WHITE)
            lastArtist.text = homePrefs.getString("artist", null).orEmpty()
            lastArtist.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            lastArtist.setTextColor(secondaryText)
            lastArt.background = null
            loadHomeArt(homePrefs.getString("image", null))
        }
        updateCountdown()
    }

    private fun loadHomeArt(url: String?) {
        if (url == homeArtUrl) return
        homeArtUrl = url
        lastArt.setImageDrawable(null)
        if (url == null) return
        io.execute {
            val bitmap = try {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
            val color = bitmap?.let { dominantColor(it) }
            ui.post {
                if (url != homeArtUrl) return@post
                if (bitmap == null || color == null) {
                    homeArtUrl = null // retry next time
                    return@post
                }
                lastArt.setImageBitmap(bitmap)
                // A song from the listening history may not have played here: take its colour too.
                homePrefs.edit().putInt("color", color).apply()
                if (isShowing(home)) fadeBackgroundTo(color)
            }
        }
    }

    /** Counts down to the next session; hidden when there's none (or no F1 server). */
    private fun updateCountdown() {
        ui.removeCallbacks(countdownTask)
        val next = f1Next
        val left = (next?.startMs ?: 0L) - System.currentTimeMillis()
        if (next == null || left <= 0) {
            nextSession.visibility = View.GONE
            return
        }
        val soon = left < 60 * 60_000
        nextSession.visibility = View.VISIBLE
        nextLabel.text = if (soon) "Starting soon" else "Next session"
        nextLabel.setTextColor(if (soon) Color.WHITE else labelText)
        nextName.text = "${next.event.replace("Grand Prix", "GP")} · ${next.session}"
        nextCountdown.text = countdown(left)
        if (active && isShowing(home)) ui.postDelayed(countdownTask, 15_000)
    }

    /** "4d 17h 02m", "17h 02m" or "42 min", rounded up to the next minute. */
    private fun countdown(ms: Long): String {
        val mins = (ms + 59_999) / 60_000
        val days = mins / (24 * 60)
        val hours = mins / 60 % 24
        return when {
            days > 0 -> "%dd %dh %02dm".format(days, hours, mins % 60)
            hours > 0 -> "%dh %02dm".format(hours, mins % 60)
            else -> "$mins min"
        }
    }

    // ---------- F1 dashboard ----------

    private fun scheduleF1(delayMs: Long) {
        ui.removeCallbacks(f1PollTask)
        if (active) ui.postDelayed(f1PollTask, delayMs)
    }

    private fun pollF1() {
        if (!active) return
        val knownTrack = f1Map.trackId
        f1Io.execute {
            val state = f1Api.state()
            val track = state?.trackId?.takeIf { it != knownTrack }?.let { f1Api.track() }
            ui.post { renderF1(state, track) }
        }
    }

    private fun renderF1(state: F1State?, track: F1Track?) {
        val now = System.currentTimeMillis()
        if (state != null && !state.live) {
            f1Next = state.next
            updateCountdown()
        }
        if (state == null || !state.live || state.sessionKey == f1DismissedSession) {
            // Ride out short gaps (server restart, feed reconnect) rather than flashing away.
            if (isShowing(f1) && now - f1LastLive < 60_000) {
                scheduleF1(Config.F1_POLL_MS * 3)
                return
            }
            hideF1()
            scheduleF1(Config.F1_IDLE_POLL_MS)
            return
        }
        f1LastLive = now
        f1SessionKey = state.sessionKey
        track?.let { f1Map.setTrack(it) }
        fade(f1, true)
        f1Event.text = "${state.event}  ·  ${state.session}".uppercase()
        f1Lap.text = headline(state)
        showTrackStatus(state)
        f1Board.setRows(state.drivers, state.race)
        // Slightly longer than the poll interval, so cars keep moving until the next update.
        f1Map.setDrivers(state.drivers, Config.F1_POLL_MS + 250)
        scheduleF1(Config.F1_POLL_MS)
    }

    private fun hideF1() {
        fade(f1, false) { f1Map.clear() }
    }

    /** "LAP 46/71" in a race; otherwise the time left, e.g. "Q2  12:41". */
    private fun headline(s: F1State): CharSequence {
        if (s.race && s.totalLaps != null) {
            val text = SpannableString("LAP ${s.lap ?: "–"}/${s.totalLaps}")
            text.setSpan(
                ForegroundColorSpan(Color.rgb(110, 110, 110)),
                text.indexOf('/'), text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            return text
        }
        val sec = s.remainingSec ?: return ""
        val time = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
        else "%d:%02d".format(sec / 60, sec % 60)
        val q = if (s.session.startsWith("Sprint")) "SQ" else "Q"
        return if (s.part != null) "$q${s.part}  $time" else time
    }

    private fun showTrackStatus(s: F1State) {
        val red = Color.rgb(225, 6, 0)
        val yellow = Color.rgb(255, 205, 0)
        val (label, bg, fg) = when {
            s.status == "Finished" -> Triple("FINISHED", Color.WHITE, Color.BLACK)
            s.status == "Inactive" -> Triple("NOT STARTED", Color.rgb(70, 70, 70), Color.WHITE)
            s.status == "Aborted" || s.trackStatus == "5" -> Triple("RED FLAG", red, Color.WHITE)
            s.trackStatus == "4" -> Triple("SAFETY CAR", yellow, Color.BLACK)
            s.trackStatus == "6" || s.trackStatus == "7" -> Triple("VSC", yellow, Color.BLACK)
            s.trackStatus == "2" -> Triple("YELLOW FLAG", yellow, Color.BLACK)
            else -> Triple("GREEN", Color.rgb(0, 190, 80), Color.BLACK)
        }
        f1Status.text = label
        f1Status.setTextColor(fg)
        val pill = f1Status.background as? GradientDrawable ?: GradientDrawable().also {
            it.cornerRadius = 6 * resources.displayMetrics.density
            f1Status.background = it
        }
        pill.setColor(bg)
    }

    // ---------- Login ----------

    private fun startLogin() {
        val url = auth.buildAuthUrl()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            showWebLogin(url) // no browser installed: log in inside the app
        }
    }

    private fun handleRedirect(uri: Uri?): Boolean {
        if (uri == null || !uri.toString().startsWith(Config.REDIRECT_URI)) return false
        val code = uri.getQueryParameter("code")
        hideWebLogin()
        if (code == null) {
            showConnect("Login cancelled")
            return true
        }
        status.text = "Connecting…"
        fade(status, true)
        fade(connect, false)
        io.execute {
            val ok = auth.exchangeCode(code)
            ui.post {
                if (ok) {
                    fade(status, false)
                    // New sign-in may have granted the listening-history permission: check again now.
                    needsReconnect = false
                    lastPlayedFetchedAt = 0L
                    schedule(0)
                } else {
                    showConnect("Login failed. Try again")
                }
            }
        }
        return true
    }

    private fun showWebLogin(url: String) {
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.toString().startsWith(Config.REDIRECT_URI)) {
                    handleRedirect(request.url)
                    return true
                }
                return false
            }
        }
        webContainer.addView(wv)
        webContainer.visibility = View.VISIBLE
        webView = wv
        wv.loadUrl(url)
    }

    private fun hideWebLogin() {
        webView?.let {
            webContainer.removeView(it)
            it.destroy()
        }
        webView = null
        webContainer.visibility = View.GONE
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}
