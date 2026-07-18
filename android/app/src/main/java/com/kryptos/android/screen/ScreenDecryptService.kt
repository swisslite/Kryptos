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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kryptos.android.R
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.TextStego
import com.kryptos.android.security.AppLock
import com.kryptos.android.security.ClipboardGuard
import com.kryptos.android.signal.AppSettingsStore
import com.kryptos.android.signal.SignalService
import java.util.concurrent.Executors

class ScreenDecryptService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val scan = Runnable { doScan() }
    @Volatile private var generation = 0

    private var windowManager: WindowManager? = null
    private var overlay: OverlayView? = null
    private val expandButtons = ArrayList<View>()
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
            }
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        syncOverlaySecure()
        if (!active()) {
            handler.removeCallbacks(scan)
            clearOverlay()
            return
        }
        handler.removeCallbacks(scan)
        handler.postDelayed(scan, 220)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(scan)
        removeOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(scan)
        removeOverlay()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun active(): Boolean =
        AppSettingsStore.screenDecrypt && !AppLock.isCryptoSessionLocked(this)

    private var overlaySecure = false

    private fun secureFlag(): Int =
        if (AppSettingsStore.screenDecryptSecure) WindowManager.LayoutParams.FLAG_SECURE else 0

    private fun syncOverlaySecure() {
        val secure = AppSettingsStore.screenDecryptSecure
        if (secure == overlaySecure) return
        overlaySecure = secure
        val view = overlay ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = if (secure) {
            params.flags or WindowManager.LayoutParams.FLAG_SECURE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun doScan() {
        if (!active()) { clearOverlay(); return }
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() == packageName) { clearOverlay(); return }

        val candidates = ArrayList<Pair<Rect, String>>()
        collect(root, candidates, 0)

        val myGen = ++generation
        worker.execute {
            val found = ArrayList<OverlayItem>()
            for ((rect, text) in candidates) {
                if (generation != myGen) return@execute
                val r = ScreenDecryptor.decryptIfPresent(text) ?: continue
                found.add(OverlayItem(rect, r.name, r.text, r.mine))
            }
            val items = ArrayList<OverlayItem>()
            for (item in found.sortedBy { it.bounds.width().toLong() * it.bounds.height() }) {
                if (items.size >= MAX_CHIPS) break
                if (items.none { it.text == item.text && Rect.intersects(it.bounds, item.bounds) }) {
                    items.add(item)
                }
            }
            handler.post { if (generation == myGen && active()) showItems(items) }
        }
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<Pair<Rect, String>>, depth: Int) {
        if (node == null || depth > 60 || out.size >= 60) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length >= 8 && ScreenDecryptor.quickCheck(text)) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) out.add(r to text)
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }

    private fun addOverlay() {
        if (overlay != null) return
        val view = OverlayView(this)
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
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
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { windowManager?.addView(view, params); overlay = view }
    }

    private fun showItems(items: List<OverlayItem>) {
        if (items == lastItems) return
        lastItems = items
        overlay?.setItems(items)
        rebuildExpandButtons(items)
    }

    private fun clearOverlay() {
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

    private fun expandable(item: OverlayItem): Boolean =
        item.text.length > 220 || item.text.count { it == '\n' } >= 7

    private fun removeExpandButtons() {
        for (b in expandButtons) runCatching { windowManager?.removeView(b) }
        expandButtons.clear()
    }

    private fun rebuildExpandButtons(items: List<OverlayItem>) {
        removeExpandButtons()
        val wm = windowManager ?: return
        for (item in items.filter(::expandable)) {
            val size = dp(28)
            val button = TextView(this).apply {
                text = "⤢"
                contentDescription = getString(R.string.screen_show_full)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(if (dark) "#6B85FA" else "#3749C2"))
                }
                elevation = dp(4).toFloat()
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
                x = (item.bounds.right - size + dp(6)).coerceAtLeast(0)
                y = (item.bounds.top - dp(6)).coerceAtLeast(0)
            }
            runCatching { wm.addView(button, params); expandButtons.add(button) }
        }
    }

    private fun showReader(item: OverlayItem) {
        dismissReader()
        val wm = windowManager ?: return

        val ink = Color.parseColor(if (dark) "#F2F5FA" else "#12141A")
        val sub = Color.parseColor(if (dark) "#94FFFFFF" else "#8712141A")
        val accent = Color.parseColor(if (dark) "#6B85FA" else "#3749C2")
        val panelBg = Color.parseColor(if (dark) "#1B2030" else "#FFFFFF")

        val title = TextView(this).apply {
            text = if (item.mine) getString(R.string.screen_you_to, item.name) else item.name
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
            text = getString(R.string.copy)
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
            setOnClickListener { ClipboardGuard.copy(this@ScreenDecryptService, item.text, context.getString(R.string.copied)) }
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
        )
        runCatching { wm.addView(dim, params); reader = dim }
    }

    private fun dismissReader() {
        reader?.let { runCatching { windowManager?.removeView(it) } }
        reader = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_CHIPS = 12
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

data class OverlayItem(val bounds: Rect, val name: String, val text: String, val mine: Boolean)

private class OverlayView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val fill = if (dark) Color.parseColor("#F21B2030") else Color.parseColor("#FAEDF0FF")
    private val stroke = if (dark) Color.parseColor("#6B85FA") else Color.parseColor("#3749C2")
    private val ink = if (dark) Color.parseColor("#F2F5FA") else Color.parseColor("#12141A")
    private val accent = stroke

    private var current: List<OverlayItem> = emptyList()

    fun setItems(items: List<OverlayItem>) {
        if (items == current) return
        current = items
        removeAllViews()
        for (item in items) addView(chip(item, textWidthCap(item)), layoutFor(item))
    }

    private fun textWidthCap(item: OverlayItem): Int {
        val screenW = resources.displayMetrics.widthPixels
        val left = item.bounds.left.coerceAtLeast(dp(4))
        return minOf(item.bounds.width(), screenW - left - dp(4))
            .coerceAtLeast(dp(96)) - dp(24)
    }

    private fun chip(item: OverlayItem, textCap: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(7))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }
        elevation = dp(3).toFloat()
        addView(TextView(context).apply {
            text = if (item.mine) context.getString(R.string.screen_you_to, item.name) else item.name
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = textCap
        })
        addView(TextView(context).apply {
            text = item.text
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.05f)
            maxLines = 8
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = textCap
        })
    }

    private fun layoutFor(item: OverlayItem): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = item.bounds.left.coerceAtLeast(dp(4))
            topMargin = item.bounds.top.coerceAtLeast(0)
        }

    private fun dp(v: Int): Int = (v * density).toInt()
}
