package com.kryptos.android.screen

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kryptos.android.R
import com.kryptos.android.core.CachePurge
import com.kryptos.android.core.LetterStego
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.TextStego
import com.kryptos.android.security.AppLock
import com.kryptos.android.security.ClipboardGuard
import com.kryptos.android.signal.AppSettingsStore
import com.kryptos.android.signal.SignalService
import java.util.concurrent.Executors

class ScreenDecryptService : AccessibilityService() {

    override fun attachBaseContext(newBase: Context) {
        com.kryptos.android.store.SecureStore.init(newBase)
        super.attachBaseContext(
            runCatching { com.kryptos.android.AppLanguage.wrap(newBase) }.getOrDefault(newBase),
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val scan = Runnable { doScan() }
    private val verify = Runnable { doScan() }
    private var verifyDelay = VERIFY_MIN_MS
    private var emptyTicks = 0
    @Volatile private var generation = 0

    private var windowManager: WindowManager? = null
    private var overlay: OverlayView? = null
    private val expandButtons = ArrayList<View>()
    private var expandTargets: List<Int> = emptyList()
    private var reader: View? = null
    private var lastItems: List<OverlayItem> = emptyList()

    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    override fun onServiceConnected() {
        super.onServiceConnected()
        worker.execute {
            runCatching { SignalService.ensureInitialized() }
            runCatching {
                TextStego.decode("warm")
                SmartTextStego.decode("warm")
                LetterStego.decode("warm")
            }
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayNight = dark
        overlayLang = runCatching { AppSettingsStore.storedLanguage() }.getOrDefault("auto")
        addOverlay()
        live = this
        if (!purgeHooked) {
            purgeHooked = true
            CachePurge.register {
                live?.let { service ->
                    service.handler.post {
                        service.generation++
                        service.clearOverlay()
                    }
                }
            }
        }
    }

    private var lastPackage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        syncOverlaySecure()
        syncOverlayLanguage()
        if (!active()) {
            handler.removeCallbacks(scan)
            generation++
            clearOverlay()
            return
        }
        val pkg = event?.packageName?.toString()
        if (pkg == packageName) return
        val switched = event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            (pkg != null && pkg != lastPackage)
        if (switched) {
            generation++
            clearOverlay()
        }
        handler.removeCallbacks(scan)
        val delay = when {
            switched -> SWITCH_SETTLE_MS
            lastItems.isNotEmpty() -> FAST_DEBOUNCE_MS
            else -> DEBOUNCE_MS
        }
        handler.postDelayed(scan, delay)
    }

    override fun onInterrupt() {}

    private var overlayNight = false
    private var overlayLang: String? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (night == overlayNight) return
        overlayNight = night
        generation++
        removeOverlay()
        addOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(scan)
        handler.removeCallbacks(verify)
        removeOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(scan)
        handler.removeCallbacks(verify)
        removeOverlay()
        worker.shutdownNow()
        if (live === this) live = null
        super.onDestroy()
    }

    private fun active(): Boolean =
        AppSettingsStore.screenDecrypt && !AppLock.isCryptoSessionLocked(this)

    private var overlaySecure = false

    private fun secureFlag(): Int =
        if (AppSettingsStore.screenDecryptSecure) WindowManager.LayoutParams.FLAG_SECURE else 0

    private fun WindowManager.LayoutParams.spanFullScreen(): WindowManager.LayoutParams = apply {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun syncOverlaySecure() {
        val secure = AppSettingsStore.screenDecryptSecure
        if (secure == overlaySecure) return
        overlaySecure = secure
        val windows = ArrayList<View>()
        overlay?.let { windows.add(it) }
        windows.addAll(expandButtons)
        reader?.let { windows.add(it) }
        for (view in windows) {
            val params = view.layoutParams as? WindowManager.LayoutParams ?: continue
            params.flags = if (secure) {
                params.flags or WindowManager.LayoutParams.FLAG_SECURE
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            }
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    private var stringsLang: String? = null
    private var stringsContext: Context? = null

    private fun localized(): Context {
        val lang = runCatching { AppSettingsStore.storedLanguage() }.getOrDefault("auto")
        stringsContext?.let { if (stringsLang == lang) return it }
        val ctx = runCatching { com.kryptos.android.AppLanguage.wrap(this) }.getOrDefault(this)
        stringsLang = lang
        stringsContext = ctx
        return ctx
    }

    private fun str(id: Int, vararg args: Any): String =
        if (args.isEmpty()) localized().getString(id) else localized().getString(id, *args)

    private fun syncOverlayLanguage() {
        val lang = runCatching { AppSettingsStore.storedLanguage() }.getOrDefault("auto")
        if (lang == overlayLang) return
        overlayLang = lang
        stringsLang = null
        stringsContext = null
        generation++
        removeOverlay()
        addOverlay()
    }

    private fun doScan() {
        handler.removeCallbacks(verify)
        if (!active()) { clearOverlay(); return }
        val root = rootInActiveWindow
        if (root == null) { generation++; clearOverlay(); return }
        lastPackage = root.packageName?.toString()
        if (lastPackage == packageName) { generation++; clearOverlay(); recycle(root); return }

        val candidates = ArrayList<Candidate>()
        val screen = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val chrome = intArrayOf(screen.top, screen.bottom)
        collect(root, candidates, 0, intArrayOf(MAX_NODES), screen, screen, chrome)
        recycle(root)

        val content = Rect(screen.left, chrome[0], screen.right, chrome[1])
        candidates.retainAll { candidate ->
            if (!candidate.clip.intersect(content)) return@retainAll false
            val visible = Rect(candidate.bounds)
            visible.intersect(candidate.clip) && visible.height() >= dp(MIN_PANEL_DP)
        }

        val myGen = ++generation
        worker.execute {
            purgeExpired()
            val found = ArrayList<OverlayItem>()
            for (candidate in candidates) {
                if (generation != myGen) return@execute
                val r = ScreenDecryptor.decryptIfPresent(candidate.text) ?: continue
                found.add(OverlayItem(candidate.bounds, candidate.clip, r.name, r.text, r.mine))
            }
            val items = ArrayList<OverlayItem>()
            for (item in found.sortedBy { it.bounds.width().toLong() * it.bounds.height() }) {
                if (items.size >= MAX_CHIPS) break
                if (items.none { it.text == item.text && Rect.intersects(it.bounds, item.bounds) }) {
                    items.add(item)
                }
            }
            handler.post {
                if (generation != myGen || !active()) return@post
                val moving = inMotion(items)
                lastScan = items
                if (moving) {
                    showItems(emptyList())
                    emptyTicks = 0
                    verifyDelay = VERIFY_MIN_MS
                    handler.removeCallbacks(verify)
                    handler.postDelayed(verify, MOTION_RECHECK_MS)
                    return@post
                }
                val unchanged = items == lastItems
                val hadItems = lastItems.isNotEmpty()
                readerText?.let { open -> if (items.none { it.text == open }) dismissReader() }
                showItems(items)
                handler.removeCallbacks(verify)
                if (items.isNotEmpty()) {
                    emptyTicks = 0
                    verifyDelay = if (unchanged) minOf(verifyDelay * 2, VERIFY_MAX_MS) else VERIFY_MIN_MS
                    handler.postDelayed(verify, verifyDelay)
                    return@post
                }
                verifyDelay = VERIFY_MIN_MS
                if (hadItems) emptyTicks = 0
                if (emptyTicks < EMPTY_RECHECKS) {
                    emptyTicks++
                    handler.postDelayed(verify, VERIFY_MIN_MS)
                }
            }
        }
    }

    private var lastPurge = 0L

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        if (now - lastPurge < PURGE_INTERVAL_MS) return
        lastPurge = now
        runCatching { SignalService.purgeExpiredMessages() }
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        out: MutableList<Candidate>,
        depth: Int,
        budget: IntArray,
        screen: Rect,
        clip: Rect,
        chrome: IntArray,
    ) {
        if (node == null || depth > 60 || out.size >= 60 || budget[0] <= 0) return
        budget[0]--
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val listLike = isListContainer(node)
        var inner = clip
        if (listLike && bounds.width() > 0 && bounds.height() > 0) {
            val narrowed = Rect(clip)
            if (narrowed.intersect(bounds)) inner = narrowed
        }
        val own = node.text?.toString()?.takeIf { it.isNotBlank() }
        val text = own ?: node.contentDescription?.toString()
        if (!listLike && text.isNullOrBlank() && bounds.width() >= screen.width() * 4 / 5 &&
            bounds.height() in 1 until screen.height() / 4
        ) {
            if (bounds.top <= screen.top + 2) chrome[0] = maxOf(chrome[0], bounds.bottom)
            if (bounds.bottom >= screen.bottom - 2) chrome[1] = minOf(chrome[1], bounds.top)
        }
        if (!text.isNullOrBlank() && text.length >= 8 && text.length <= ScreenDecryptor.MAX_SCAN_CHARS) {
            val visible = Rect(bounds)
            val inter = visible.intersect(clip)
            if (inter && visible.height() >= dp(MIN_PANEL_DP) &&
                rendersEnough(text, bounds, screen, own != null) && ScreenDecryptor.quickCheck(text)
            ) {
                out.add(Candidate(bounds, Rect(clip), text))
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, depth + 1, budget, screen, inner, chrome)
            recycle(child)
        }
    }

    private fun isListContainer(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable) return true
        val cls = node.className?.toString() ?: return false
        return cls.endsWith("RecyclerView") || cls.endsWith("ListView") ||
            cls.endsWith("ScrollView") || cls.endsWith("GridView")
    }

    private fun rendersEnough(text: String, r: Rect, screen: Rect, ownText: Boolean): Boolean {
        if (ownText && (r.top <= screen.top || r.bottom >= screen.bottom)) return true
        val need = if (ownText) MIN_DP_PER_CHAR else MIN_DP_PER_CHAR_LABEL
        return r.height() / resources.displayMetrics.density >= text.length * need
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        if (android.os.Build.VERSION.SDK_INT < 33) runCatching { node.recycle() }
    }

    private fun addOverlay() {
        if (overlay != null) return
        val view = OverlayView(localized())
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.onPanelsLaidOut = { frames, clipped ->
            handler.post { rebuildExpandButtons(frames, clipped) }
        }
        overlaySecure = AppSettingsStore.screenDecryptSecure
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                secureFlag(),
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }.spanFullScreen()
        runCatching { windowManager?.addView(view, params); overlay = view }
    }

    private var lastScan: List<OverlayItem> = emptyList()

    private fun inMotion(items: List<OverlayItem>): Boolean {
        if (items.isEmpty() || lastScan.isEmpty()) return false
        val slack = dp(MOTION_SLACK_DP)
        return items.any { item ->
            val before = lastScan.filter { it.text == item.text }
                .minByOrNull { kotlin.math.abs(it.bounds.centerY() - item.bounds.centerY()) }
                ?: return@any false
            kotlin.math.abs(before.bounds.centerY() - item.bounds.centerY()) > slack ||
                kotlin.math.abs(before.bounds.centerX() - item.bounds.centerX()) > slack
        }
    }

    private fun showItems(items: List<OverlayItem>) {
        if (items == lastItems) return
        lastItems = items
        overlay?.setItems(items)
    }

    private fun clearOverlay() {
        handler.removeCallbacks(verify)
        verifyDelay = VERIFY_MIN_MS
        emptyTicks = 0
        lastScan = emptyList()
        if (lastItems.isNotEmpty()) {
            lastItems = emptyList()
            overlay?.setItems(emptyList())
        }
        removeExpandButtons()
        dismissReader()
    }

    private fun removeOverlay() {
        clearOverlay()
        overlay?.let { runCatching { windowManager?.removeView(it) } }
        overlay = null
    }

    private fun removeExpandButtons() {
        for (b in expandButtons) runCatching { windowManager?.removeView(b) }
        expandButtons.clear()
        expandTargets = emptyList()
    }

    private fun buttonX(frame: Rect, size: Int, screenW: Int): Int =
        (frame.right - size / 2).coerceIn(0, (screenW - size).coerceAtLeast(0))

    private fun buttonY(frame: Rect, size: Int): Int = (frame.top - size / 2).coerceAtLeast(0)

    private fun rebuildExpandButtons(frames: List<Rect>, clipped: List<Boolean>) {
        val wm = windowManager ?: return
        val items = lastItems
        val screenW = resources.displayMetrics.widthPixels
        val size = dp(34)
        val targets = items.indices.filter { clipped.getOrNull(it) == true && frames.getOrNull(it) != null }

        if (targets == expandTargets && expandButtons.size == targets.size) {
            targets.forEachIndexed { slot, index ->
                val frame = frames[index]
                val view = expandButtons[slot]
                val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@forEachIndexed
                val x = buttonX(frame, size, screenW)
                val y = buttonY(frame, size)
                if (lp.x != x || lp.y != y) {
                    lp.x = x
                    lp.y = y
                    runCatching { wm.updateViewLayout(view, lp) }
                }
            }
            return
        }

        removeExpandButtons()
        expandTargets = targets
        for (index in targets) {
            val frame = frames[index]
            val item = items[index]
            val accent = Color.parseColor(if (dark) "#6B85FA" else "#3749C2")
            fun disc(color: Int) = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            val button = ImageView(this).apply {
                setImageResource(R.drawable.ic_screen_expand)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(9), dp(9), dp(9), dp(9))
                contentDescription = str(R.string.screen_show_full)
                background = StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        disc(ColorUtils.blendARGB(accent, Color.BLACK, 0.18f)),
                    )
                    addState(intArrayOf(), disc(accent))
                }
                elevation = dp(5).toFloat()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                filterTouchesWhenObscured = true
                setOnClickListener { showReader(item) }
            }
            val params = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    secureFlag(),
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = buttonX(frame, size, screenW)
                y = buttonY(frame, size)
            }.spanFullScreen()
            runCatching { wm.addView(button, params); expandButtons.add(button) }
        }
    }

    private var readerText: String? = null

    private fun showReader(item: OverlayItem) {
        dismissReader()
        val wm = windowManager ?: return
        readerText = item.text

        val ink = Color.parseColor(if (dark) "#F2F5FA" else "#12141A")
        val sub = Color.parseColor(if (dark) "#94FFFFFF" else "#8712141A")
        val accent = Color.parseColor(if (dark) "#6B85FA" else "#3749C2")
        val panelBg = Color.parseColor(if (dark) "#1B2030" else "#FFFFFF")

        val title = TextView(this).apply {
            text = if (item.mine) str(R.string.screen_you_to, item.name) else item.name
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
        }
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12), 0, 0, dp(4))
            filterTouchesWhenObscured = true
            setOnClickListener { dismissReader() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }

        val body = TextView(this).apply {
            text = item.text
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.1f)
        }
        val scroll = ScrollView(this).apply { addView(body) }

        val copy = TextView(this).apply {
            text = str(R.string.copy)
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (dark) 0x336B85FA else 0x243749C2)
            }
            filterTouchesWhenObscured = true
            setOnClickListener { ClipboardGuard.copy(this@ScreenDecryptService, item.text, str(R.string.copied)) }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(copy)
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(panelBg)
            }
            elevation = dp(10).toFloat()
            setPadding(dp(18), dp(14), dp(18), dp(14))
            isClickable = true
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(10) })
            addView(actions)
        }

        val dim = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            filterTouchesWhenObscured = true
            setOnClickListener { dismissReader() }
            addView(
                panel,
                FrameLayout.LayoutParams(MATCH, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                    leftMargin = dp(18); rightMargin = dp(18); topMargin = dp(60); bottomMargin = dp(60)
                },
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                secureFlag(),
            PixelFormat.TRANSLUCENT,
        ).spanFullScreen()
        runCatching { wm.addView(dim, params); reader = dim }
    }

    private fun dismissReader() {
        readerText = null
        reader?.let { runCatching { windowManager?.removeView(it) } }
        reader = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_CHIPS = 12
        private const val MAX_NODES = 1200
        private const val PURGE_INTERVAL_MS = 5_000L
        private const val MIN_DP_PER_CHAR = 0.30f
        private const val MIN_DP_PER_CHAR_LABEL = 0.50f
        private const val MIN_PANEL_DP = 28
        private const val DEBOUNCE_MS = 220L
        private const val FAST_DEBOUNCE_MS = 70L
        private const val MOTION_RECHECK_MS = 120L
        private const val MOTION_SLACK_DP = 20
        private const val SWITCH_SETTLE_MS = 300L
        private const val VERIFY_MIN_MS = 400L
        private const val VERIFY_MAX_MS = 1600L
        private const val EMPTY_RECHECKS = 3

        @Volatile private var live: ScreenDecryptService? = null
        private var purgeHooked = false
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT

        fun isSystemEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val target = ComponentName(context, ScreenDecryptService::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == target
            }
        }
    }
}

private class Candidate(val bounds: Rect, val clip: Rect, val text: String)

data class OverlayItem(
    val bounds: Rect,
    val clip: Rect,
    val name: String,
    val text: String,
    val mine: Boolean,
)

private class OverlayView(context: Context) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val fill = if (dark) Color.parseColor("#FF1B2030") else Color.parseColor("#FFEDF0FF")
    private val stroke = if (dark) Color.parseColor("#6B85FA") else Color.parseColor("#3749C2")
    private val ink = if (dark) Color.parseColor("#F2F5FA") else Color.parseColor("#12141A")
    private val accent = stroke

    private var current: List<OverlayItem> = emptyList()
    private var frames: List<Rect> = emptyList()
    private var reportedFrames: List<Rect> = emptyList()
    private var reportedClipped: List<Boolean> = emptyList()
    private val origin = IntArray(2)

    var onPanelsLaidOut: ((List<Rect>, List<Boolean>) -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false
    }

    fun setItems(items: List<OverlayItem>) {
        if (items == current) return
        val reusable = current.size == items.size && current.indices.all {
            current[it].text == items[it].text &&
                current[it].name == items[it].name &&
                current[it].mine == items[it].mine
        }
        current = items
        if (!reusable) {
            removeAllViews()
            for (item in items) addView(chip(item))
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentW = MeasureSpec.getSize(widthMeasureSpec)
        val parentH = MeasureSpec.getSize(heightMeasureSpec)
        getLocationOnScreen(origin)
        val margin = dp(4)
        val laid = ArrayList<Rect>(childCount)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val item = current.getOrNull(i)
            val bounds = item?.bounds ?: Rect()
            val screen = Rect(origin[0], origin[1], origin[0] + parentW, origin[1] + parentH)
            val area = Rect(item?.clip ?: screen)
            if (!area.intersect(screen)) area.set(screen)
            val visible = Rect(bounds)
            if (!visible.intersect(area)) visible.set(area)

            val edge = dp(8)
            val maxW = minOf(area.width() - edge * 2, visible.width() - edge * 2, dp(272))
                .coerceAtLeast(dp(96))
            child.measure(
                MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val width = child.measuredWidth.coerceIn(minOf(dp(150), maxW), maxW)
            child.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val height = child.measuredHeight
                .coerceAtMost((area.height() - margin * 2).coerceAtLeast(dp(48)))
            child.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
            val minLeft = area.left + margin
            val maxLeft = (area.right - margin - width).coerceAtLeast(minLeft)
            val minTop = area.top + margin
            val maxTop = (area.bottom - margin - height).coerceAtLeast(minTop)
            val spare = (visible.width() - width).coerceAtLeast(0)
            val side = minOf(dp(14), spare / 2)
            val anchored = when {
                spare == 0 -> visible.centerX() - width / 2
                item?.mine == true -> visible.right - width - side
                else -> visible.left + side
            }
            val left = anchored.coerceIn(minLeft, maxLeft)
            val top = (visible.centerY() - height / 2)
                .coerceIn(
                    visible.top.coerceIn(minTop, maxTop),
                    (visible.bottom - height).coerceIn(minTop, maxTop),
                )
            laid.add(Rect(left, top, left + width, top + height))
        }
        frames = laid
        setMeasuredDimension(parentW, parentH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        getLocationOnScreen(origin)
        for (i in 0 until childCount) {
            val frame = frames.getOrNull(i) ?: continue
            getChildAt(i).layout(
                frame.left - origin[0], frame.top - origin[1],
                frame.right - origin[0], frame.bottom - origin[1],
            )
        }
        val clipped = (0 until childCount).map { isClipped(getChildAt(it)) }
        if (frames != reportedFrames || clipped != reportedClipped) {
            reportedFrames = frames
            reportedClipped = clipped
            onPanelsLaidOut?.invoke(frames, clipped)
        }
    }

    private fun isClipped(chip: View): Boolean {
        val body = (chip as? ViewGroup)?.getChildAt(1) as? TextView ?: return false
        val layout = body.layout ?: return false
        val last = layout.lineCount - 1
        return last >= 0 && layout.getEllipsisCount(last) > 0
    }

    private fun chip(item: OverlayItem): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(11))
        background = GradientDrawable().apply {
            cornerRadius = dp(15).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }
        elevation = dp(4).toFloat()
        addView(TextView(context).apply {
            text = if (item.mine) context.getString(R.string.screen_you_to, item.name) else item.name
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(
            TextView(context).apply {
                text = item.text
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setLineSpacing(0f, 1.12f)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(3) },
        )
    }

    private fun dp(v: Int): Int = (v * density).toInt()
}
