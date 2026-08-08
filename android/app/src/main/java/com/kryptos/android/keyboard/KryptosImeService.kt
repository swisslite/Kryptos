package com.kryptos.android.keyboard

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.kryptos.android.R
import com.kryptos.android.core.CachePurge
import com.kryptos.android.core.LetterStego
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.TextStego
import com.kryptos.android.core.WireFormat
import com.kryptos.android.signal.AppSettingsStore
import com.kryptos.android.signal.Contact
import com.kryptos.android.signal.DecryptCacheKey
import com.kryptos.android.signal.OwnCipherMarker
import com.kryptos.android.signal.SignalService
import com.kryptos.android.ui.clipboardText
import kotlin.math.abs

class KryptosImeService : InputMethodService() {

    override fun attachBaseContext(newBase: Context) {
        com.kryptos.android.store.SecureStore.init(newBase)
        super.attachBaseContext(
            runCatching { com.kryptos.android.AppLanguage.wrap(newBase) }.getOrDefault(newBase),
        )
    }

    private var selectedFingerprint: String? = null
    private var selectedProfileId: String? = null
    private var shiftState = 1
    private var autoShifted = false
    private var lastShiftTap = 0L
    private var lastSpaceTap = 0L
    private var langCode = "en"
    private var enabledLangs = listOf("en")
    private val langOrder = listOf("en", "ru", "de")
    private var symbols = false
    private var symPage = 0

    private var composeOn = false
    private var draft = ""
    private var caret = 0

    private var haptics = true
    private var sounds = true
    private var secureKb = true
    private var autoDecrypt = true
    private var suggestionsOn = true
    private var autocorrectOn = true
    private var emojiOn = true
    private var sendAfterEncrypt = false

    private data class AutoFix(val original: String, val corrected: String, val separator: String, val at: Long)
    private var lastAutoFix: AutoFix? = null

    private var pendingFixTyped: String? = null

    private var suggestionsStamp: List<Any?>? = null

    private var emojiOpen = false
    private var passwordField = false
    private var noLearningField = false
    private val noLearning: Boolean get() = passwordField || noLearningField
    private var returnAction: Int? = null

    private val handler = Handler(Looper.getMainLooper())
    private val crypto = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "kryptos-ime-crypto").apply { isDaemon = true }
    }
    private var encryptInFlight = false
    private val contacts: List<Contact> get() = SignalService.contacts.value

    private lateinit var rootFrame: FrameLayout
    private lateinit var status: TextView
    private lateinit var composeRow: LinearLayout
    private var composeToggle: FrameLayout? = null
    private lateinit var draftView: TextView
    private lateinit var profileChip: LinearLayout
    private lateinit var profileChipText: TextView
    private lateinit var contactChip: LinearLayout
    private lateinit var contactChipText: TextView
    private lateinit var clipDot: View
    private var sendBadge: ImageView? = null
    private lateinit var keyGrid: KeyGridView
    private lateinit var keyArea: FrameLayout
    private lateinit var suggestionBar: LinearLayout
    private val suggestionSlots = arrayOfNulls<TextView>(3)
    private val suggestionDividers = arrayOfNulls<View>(2)
    private var emojiPanel: LinearLayout? = null
    private var emojiGrid: LinearLayout? = null
    private var emojiEmpty: TextView? = null
    private var emojiTabs: LinearLayout? = null
    private var emojiAbc: TextView? = null
    private lateinit var revealOverlay: FrameLayout
    private lateinit var menuOverlay: FrameLayout
    private lateinit var menuList: LinearLayout
    private lateinit var revealCard: LinearLayout
    private lateinit var revealTitle: TextView
    private lateinit var revealText: TextView
    private var keyPopup: PopupWindow? = null
    private var keyPopupText: TextView? = null

    private val clipboardManager get() = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        updateClipDot()
        if (autoDecrypt) autoDecryptClipboard(freshCopy = true)
    }

    private class Palette(dark: Boolean) {
        val bg = Color.parseColor(if (dark) "#14161C" else "#E8EAF1")
        val key = Color.parseColor(if (dark) "#3B4050" else "#FFFFFF")
        val keyFn = Color.parseColor(if (dark) "#262B37" else "#CBD0DB")
        val keyShadow = Color.parseColor(if (dark) "#66000000" else "#33000000")
        val text = Color.parseColor(if (dark) "#F2F5FA" else "#12141A")
        val textSecondary = Color.parseColor(if (dark) "#94FFFFFF" else "#8712141A")
        val accent = Color.parseColor(if (dark) "#6B85FA" else "#3749C2")
        val accentSoft = Color.parseColor(if (dark) "#336B85FA" else "#243749C2")
        val panel = Color.parseColor(if (dark) "#262B37" else "#FFFFFF")
        val ok = Color.parseColor(if (dark) "#33B873" else "#2BA467")
        val err = Color.parseColor(if (dark) "#FF6B75" else "#C72E38")
        val hairline = Color.parseColor(if (dark) "#1FFFFFFF" else "#14000000")
        val scrim = Color.parseColor(if (dark) "#6B000000" else "#33000000")
    }

    private lateinit var palette: Palette

    private fun enabledLanguages(): List<String> =
        SuggestionEngine.SUPPORTED_LANGUAGES
            .filter { AppSettingsStore.keyboardLangEnabled(it) }
            .sortedBy { langOrder.indexOf(it) }
            .ifEmpty { listOf("en") }

    private fun warmSuggestions() {
        SuggestionEngine.warmUp(
            this,
            languages = enabledLanguages().toSet(),
            typingAids = AppSettingsStore.keyboardSuggestions || AppSettingsStore.keyboardAutocorrect,
        )
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun keyFace(color: Int): LayerDrawable {
        val face = LayerDrawable(arrayOf(rounded(palette.keyShadow, 9f), rounded(color, 9f)))
        face.setLayerInset(1, 0, 0, 0, dp(1.5f))
        return face
    }

    private object DecryptCache {
        private val map = object : LinkedHashMap<String, Pair<String, String>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>) =
                size > 40
        }

        init {
            CachePurge.register { clear() }
        }

        @Synchronized fun get(cipher: String): Pair<String, String>? = map[cipher]
        @Synchronized fun put(cipher: String, value: Pair<String, String>) { map[cipher] = value }
        @Synchronized fun clear() = map.clear()
    }

    override fun onCreateInputView(): View {
        crypto.execute {
            runCatching { SignalService.ensureInitialized() }
            handler.post { updateChips() }
        }
        live = this
        if (!purgeHooked) {
            purgeHooked = true
            CachePurge.register { live?.let { service -> service.handler.post { service.dropSensitiveState() } } }
        }
        warmSuggestions()
        if (AppSettingsStore.keyboardEmoji) EmojiData.prefetch()
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        palette = Palette(dark = night == Configuration.UI_MODE_NIGHT_YES)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
        }

        status = TextView(this).apply {
            setTextColor(palette.text)
            textSize = 12.5f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), 0, dp(10f), dp(6f))
            maxLines = 3
            visibility = View.GONE
        }
        column.addView(status)
        column.addView(buildComposeRow())
        column.addView(buildCryptoBar())
        column.addView(buildSuggestionBar())

        keyGrid = KeyGridView(this)
        keyGrid.keys = buildKeys()
        keyArea = FrameLayout(this).apply {
            addView(keyGrid, FrameLayout.LayoutParams(MATCH, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        column.addView(
            keyArea,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keyRowsHeight + dp(6f)),
        )

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(palette.bg)
            filterTouchesWhenObscured = true
            addView(column)
            addView(buildRevealOverlay())
            addView(buildMenuOverlay())
        }
        return rootFrame
    }

    private fun buildComposeRow(): LinearLayout {
        draftView = TextView(this).apply {
            textSize = 15f
            setTextColor(palette.text)
            background = keyFace(palette.key)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.accent)
            background = rounded(palette.accentSoft, 8f)
            gravity = Gravity.CENTER
            setPadding(dp(8f), 0, dp(8f), 0)
            setOnClickListener { onClick() }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                chip(getString(R.string.paste)) {
                    insertDraft(clipboardText(this@KryptosImeService))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(23f)),
            )
            addView(
                chip(getString(R.string.kb_clear)) {
                    draft = ""; caret = 0
                    renderDraft(); updateAutoShift(); refreshKeys(); updateSuggestions()
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(23f)).apply {
                    topMargin = dp(4f)
                },
            )
        }

        composeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(dp(4f), 0, dp(4f), dp(8f))
            addView(draftView, LinearLayout.LayoutParams(0, dp(50f), 1f))
            addView(
                buttons,
                LinearLayout.LayoutParams(dp(84f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(6f)
                },
            )
        }
        return composeRow
    }

    private fun renderDraft() {
        if (!::draftView.isInitialized) return
        caret = caret.coerceIn(0, draft.length)
        if (draft.isEmpty()) {
            draftView.setTextColor(palette.textSecondary)
            draftView.text = getString(R.string.message)
        } else {
            draftView.setTextColor(palette.text)
            draftView.text = draft.substring(0, caret) + "▏" + draft.substring(caret)
            val pos = caret
            draftView.post { runCatching { draftView.bringPointIntoView(pos) } }
        }
    }

    private fun buildCryptoBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), 0, dp(4f), dp(8f))
        }

        val shield = ImageView(this).apply {
            setImageResource(R.drawable.ic_kb_shield)
            setColorFilter(palette.accent)
        }

        profileChipText = chipLabel(13f, palette.accent)
        profileChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(palette.accentSoft, CHIP_HEIGHT_DP / 2f)
            setPadding(dp(9f), 0, dp(9f), 0)
            isClickable = true
            addView(
                ImageView(this@KryptosImeService).apply {
                    setImageResource(R.drawable.ic_kb_people)
                    setColorFilter(palette.accent)
                },
                LinearLayout.LayoutParams(dp(13f), dp(13f)).apply { rightMargin = dp(4f) },
            )
            addView(profileChipText, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(
                ImageView(this@KryptosImeService).apply {
                    setImageResource(R.drawable.ic_kb_chevron)
                    setColorFilter(palette.accent)
                },
                LinearLayout.LayoutParams(dp(9f), dp(9f)).apply { leftMargin = dp(4f) },
            )
            setOnClickListener { showProfileMenu() }
        }

        contactChipText = chipLabel(14f, palette.text)
        contactChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), 0, dp(6f), 0)
            isClickable = true
            addView(contactChipText, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(
                ImageView(this@KryptosImeService).apply {
                    setImageResource(R.drawable.ic_kb_chevron)
                    setColorFilter(palette.textSecondary)
                },
                LinearLayout.LayoutParams(dp(9f), dp(9f)).apply { leftMargin = dp(4f) },
            )
            setOnClickListener { showContactMenu() }
        }

        val decrypt = barIconButton(R.drawable.ic_kb_lock_open, bg = palette.accentSoft, tint = palette.accent) {
            haptic(); manualDecrypt()
        }
        clipDot = View(this).apply {
            background = rounded(palette.ok, 5f)
            visibility = View.GONE
        }
        (decrypt as FrameLayout).addView(
            clipDot,
            FrameLayout.LayoutParams(dp(9f), dp(9f), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(5f); rightMargin = dp(6f)
            },
        )

        val encrypt = barIconButton(R.drawable.ic_kb_lock, bg = palette.accent, tint = Color.WHITE) {
            haptic(); encryptTapped()
        }
        val badge = ImageView(this).apply {
            setImageResource(R.drawable.ic_kb_send)
            setColorFilter(palette.accent)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        sendBadge = badge
        (encrypt as FrameLayout).addView(
            badge,
            FrameLayout.LayoutParams(dp(13f), dp(13f), Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = dp(3f); rightMargin = dp(3f)
            },
        )
        styleSendBadge()

        bar.addView(shield, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { rightMargin = dp(8f) })
        val toggle = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { haptic(); toggleComposeMode() }
        }
        toggle.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_kb_compose)
                id = COMPOSE_ICON_ID
            },
            FrameLayout.LayoutParams(dp(16f), dp(16f), Gravity.CENTER),
        )
        composeToggle = toggle
        styleComposeToggle()
        bar.addView(toggle, LinearLayout.LayoutParams(dp(34f), dp(26f)).apply { rightMargin = dp(8f) })
        bar.addView(profileChip, LinearLayout.LayoutParams(WRAP, dp(CHIP_HEIGHT_DP)))
        bar.addView(contactChip, LinearLayout.LayoutParams(WRAP, dp(CHIP_HEIGHT_DP)).apply { leftMargin = dp(2f) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        bar.addView(decrypt, LinearLayout.LayoutParams(dp(56f), dp(40f)).apply { rightMargin = dp(8f) })
        bar.addView(encrypt, LinearLayout.LayoutParams(dp(56f), dp(40f)))
        updateChips()
        return bar
    }


    private fun chipLabel(size: Float, color: Int): TextView = TextView(this).apply {
        textSize = size
        setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
        setTextColor(color)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun applyChipBudget(profileName: String, contactName: String) {
        if (!::profileChipText.isInitialized) return
        var fixed = dp(6f) * 2 + dp(4f) * 2
        fixed += dp(18f) + dp(8f)
        if (AppSettingsStore.keyboardComposeToggle) fixed += dp(34f) + dp(8f)
        fixed += dp(56f) + dp(8f) + dp(56f)
        fixed += dp(2f)
        fixed += dp(9f) * 2 + dp(13f) + dp(4f) + dp(9f) + dp(4f)
        fixed += dp(6f) * 2 + dp(9f) + dp(4f)
        val available = (resources.displayMetrics.widthPixels - fixed).coerceAtLeast(dp(64f))
        val wantProfile = profileChipText.paint.measureText(profileName).toInt() + 1
        val wantContact = contactChipText.paint.measureText(contactName).toInt() + 1
        val half = available / 2
        val forProfile: Int
        val forContact: Int
        when {
            wantProfile + wantContact <= available -> { forProfile = wantProfile; forContact = wantContact }
            wantProfile <= half -> { forProfile = wantProfile; forContact = available - wantProfile }
            wantContact <= half -> { forContact = wantContact; forProfile = available - wantContact }
            else -> { forProfile = half; forContact = available - half }
        }
        profileChipText.maxWidth = forProfile
        contactChipText.maxWidth = forContact
    }

    private fun styleComposeToggle() {
        val toggle = composeToggle ?: return
        toggle.visibility = if (AppSettingsStore.keyboardComposeToggle) View.VISIBLE else View.GONE
        val bg = if (composeOn) palette.accent else palette.accentSoft
        toggle.background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                rounded(ColorUtils.blendARGB(bg, Color.BLACK, 0.12f), 13f),
            )
            addState(intArrayOf(), rounded(bg, 13f))
        }
        (toggle.findViewById<ImageView>(COMPOSE_ICON_ID))
            ?.setColorFilter(if (composeOn) Color.WHITE else palette.accent)
    }

    private fun styleSendBadge() {
        val on = AppSettingsStore.keyboardSendAfterEncrypt
        sendAfterEncrypt = on
        sendBadge?.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun toggleComposeMode() {
        composeOn = !composeOn
        AppSettingsStore.keyboardCompose = composeOn
        composeRow.visibility = if (composeOn) View.VISIBLE else View.GONE
        styleComposeToggle()
        renderDraft()
        updateAutoShift()
        updateSuggestions()
    }

    private fun barIconButton(iconRes: Int, bg: Int, tint: Int, onClick: () -> Unit): View {
        val wrap = FrameLayout(this).apply {
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    rounded(ColorUtils.blendARGB(bg, Color.BLACK, 0.12f), 12f),
                )
                addState(intArrayOf(), rounded(bg, 12f))
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(tint)
        }
        wrap.addView(icon, FrameLayout.LayoutParams(dp(21f), dp(21f), Gravity.CENTER))
        return wrap
    }

    private fun buildMenuOverlay(): FrameLayout {
        menuList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(palette.panel)
                setStroke(dp(0.5f).coerceAtLeast(1), palette.hairline)
            }
            elevation = dp(10f).toFloat()
            setPadding(0, dp(6f), 0, dp(6f))
            isClickable = true
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(menuList, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        menuOverlay = FrameLayout(this).apply {
            setBackgroundColor(palette.scrim)
            visibility = View.GONE
            isClickable = true
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setOnClickListener { hideChipMenu() }
            addView(scroll, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        }
        return menuOverlay
    }

    private fun showChipMenu(labels: List<String>, checked: Int, onPick: (Int) -> Unit) {
        if (!::menuOverlay.isInitialized || labels.isEmpty()) return
        keyGrid.cancelTouches()
        hideKeyPopup()
        menuList.removeAllViews()
        labels.forEachIndexed { i, label ->
            menuList.addView(
                TextView(this).apply {
                    text = if (i == checked) "✓  $label" else "     $label"
                    textSize = 15f
                    setTextColor(if (i == checked) palette.accent else palette.text)
                    setPadding(dp(16f), dp(12f), dp(20f), dp(12f))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_pressed), rounded(palette.accentSoft, 0f))
                        addState(intArrayOf(), rounded(Color.TRANSPARENT, 0f))
                    }
                    isClickable = true
                    setOnClickListener { haptic(); hideChipMenu(); onPick(i) }
                },
                LinearLayout.LayoutParams(MATCH, WRAP),
            )
        }
        menuOverlay.visibility = View.VISIBLE
    }

    private fun hideChipMenu() {
        if (::menuOverlay.isInitialized) menuOverlay.visibility = View.GONE
        if (::menuList.isInitialized) menuList.removeAllViews()
    }

    private fun showProfileMenu() {
        val profiles = SignalService.profiles.value
        if (profiles.isEmpty()) return
        val currentID = SignalService.currentID.value
        showChipMenu(profiles.map { it.name }, profiles.indexOfFirst { it.id == currentID }) { index ->
            val target = profiles.getOrNull(index)?.id ?: return@showChipMenu
            crypto.execute {
                runCatching { SignalService.switchTo(target) }
                handler.post { restoreContactSelection(); updateChips() }
            }
        }
    }

    private fun showContactMenu() {
        val list = contacts
        if (list.isEmpty()) { flash(getString(R.string.kb_no_contacts), error = true); return }
        val current = currentContact()
        showChipMenu(list.map { it.displayName }, list.indexOfFirst { it.fingerprint == current?.fingerprint }) { index ->
            val c = list.getOrNull(index) ?: return@showChipMenu
            val profileId = SignalService.currentID.value
            selectedFingerprint = c.fingerprint
            selectedProfileId = profileId
            crypto.execute { runCatching { AppSettingsStore.setKeyboardContact(profileId, c.fingerprint) } }
            updateChips()
        }
    }

    private fun updateChips() {
        if (!::profileChip.isInitialized) return
        val currentID = SignalService.currentID.value
        val profile = SignalService.profiles.value.firstOrNull { it.id == currentID }
        val profileName = profile?.name ?: "Kryptos"
        val contactName = currentContact()?.displayName ?: getString(R.string.kb_select_contact)
        applyChipBudget(profileName, contactName)
        profileChipText.text = profileName
        contactChipText.text = contactName
    }

    private fun updateClipDot() {
        if (!::clipDot.isInitialized) return
        val has = clipboardManager.hasPrimaryClip() &&
            clipboardManager.primaryClipDescription?.hasMimeType("text/*") == true
        clipDot.visibility = if (has) View.VISIBLE else View.GONE
    }

    private fun buildSuggestionBar(): LinearLayout {
        suggestionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(2f), 0, dp(2f), dp(6f))
        }
        for (i in 0 until 3) {
            if (i > 0) {
                val divider = View(this).apply {
                    setBackgroundColor(ColorUtils.setAlphaComponent(palette.textSecondary, 0x2E))
                    visibility = View.INVISIBLE
                }
                suggestionDividers[i - 1] = divider
                suggestionBar.addView(divider, LinearLayout.LayoutParams(dp(1f), dp(16f)))
            }
            val slot = TextView(this).apply {
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(palette.text)
                setPadding(dp(6f), dp(7f), dp(6f), dp(7f))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), rounded(palette.accentSoft, 8f))
                    addState(intArrayOf(), rounded(Color.TRANSPARENT, 8f))
                }
                isClickable = true
                setOnClickListener { (tag as? String)?.let { w -> haptic(); applySuggestion(w) } }
            }
            suggestionSlots[i] = slot
            suggestionBar.addView(slot, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return suggestionBar
    }

    private fun updateSuggestions() {
        if (!::suggestionBar.isInitialized) return
        val show = suggestionsOn && !passwordField && !emojiOpen
        suggestionBar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) { suggestionsStamp = null; return }
        val (prefix, previous) = wordContext()
        val stamp = listOf(prefix, previous, langCode, autocorrectOn)
        if (stamp == suggestionsStamp) return
        suggestionsStamp = stamp
        var list = SuggestionEngine.suggest(prefix, previous, language = langCode).toMutableList()
        var pending: String? = null
        if (autocorrectOn && prefix.length >= 3) {
            pending = SuggestionEngine.autocorrect(prefix, previous, langCode, deep = false)
            if (pending != null) {
                val alt = list.firstOrNull { it != pending && it != prefix }
                list = mutableListOf(pending, prefix)
                if (alt != null) list.add(alt)
            }
        }
        pendingFixTyped = if (pending != null) prefix else null
        val order = intArrayOf(1, 0, 2)
        for (i in 0 until 3) {
            val slot = suggestionSlots[i] ?: continue
            val word = list.getOrNull(order[i])?.takeIf { it.isNotEmpty() }
            slot.text = when {
                word == null -> ""
                pending != null && i == 0 && word == prefix -> "«$word»"
                else -> word
            }
            slot.tag = word
            slot.setTypeface(null, if (i == 1) Typeface.BOLD else Typeface.NORMAL)
            slot.setTextColor(if (i == 1 && pending != null) palette.accent else palette.text)
        }
        suggestionDividers[0]?.visibility =
            if (list.getOrNull(1) != null) View.VISIBLE else View.INVISIBLE
        suggestionDividers[1]?.visibility =
            if (list.getOrNull(2) != null) View.VISIBLE else View.INVISIBLE
    }

    private fun isWordChar(c: Char) = c.isLetter() || c == '\'' || c == '’' || c == '-'

    private fun wordContext(): Pair<String, String?> {
        val before = textBeforeCaret(64)
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        val prefix = before.substring(i)
        if (prefix.length > 24) return "" to null
        var j = i
        while (j > 0 && !isWordChar(before[j - 1])) {
            if (before[j - 1] == '\n' || before[j - 1] in ".!?…") return prefix to null
            j--
        }
        var k = j
        while (k > 0 && isWordChar(before[k - 1])) k--
        val previous = before.substring(k, j)
        return prefix to previous.ifEmpty { null }
    }

    private fun applySuggestion(word: String) {
        lastAutoFix = null
        val (prefix, previous) = wordContext()
        if (!noLearning && word == prefix && word == pendingFixTyped) {
            SuggestionEngine.noteRejectedCorrection(word)
        }
        if (composeOn) {
            if (prefix.isNotEmpty()) {
                draft = draft.removeRange(caret - prefix.length, caret)
                caret -= prefix.length
            }
            insertDraft("$word ")
        } else {
            val ic = currentInputConnection ?: return
            ic.beginBatchEdit()
            if (prefix.isNotEmpty()) ic.deleteSurroundingText(prefix.length, 0)
            ic.commitText("$word ", 1)
            ic.endBatchEdit()
        }
        if (!noLearning) SuggestionEngine.learn(word, previous)
        if (shiftState == 1) { shiftState = 0; autoShifted = false }
        updateAutoShift()
        refreshKeys()
        updateSuggestions()
    }

    private fun learnFinishedWord() {
        if ((!suggestionsOn && !autocorrectOn) || noLearning) return
        val (word, previous) = wordContext()
        if (word.isNotEmpty()) SuggestionEngine.learn(word, previous)
    }

    private fun commitWordBeforeSeparator(separator: String) {
        lastAutoFix = null
        if (passwordField) return
        if (autocorrectOn) {
            val (word, previous) = wordContext()
            if (word.isNotEmpty()) {
                val fixed = SuggestionEngine.autocorrect(word, previous, langCode)
                if (fixed != null && fixed != word) {
                    replaceCurrentWord(word, fixed)
                    lastAutoFix = AutoFix(word, fixed, separator, System.currentTimeMillis())
                }
            }
        }
        learnFinishedWord()
    }

    private fun replaceCurrentWord(old: String, new: String) {
        if (composeOn) {
            if (caret < old.length) return
            draft = draft.substring(0, caret - old.length) + new + draft.substring(caret)
            caret += new.length - old.length
            renderDraft()
        } else {
            val ic = currentInputConnection ?: return
            ic.beginBatchEdit()
            ic.deleteSurroundingText(old.length, 0)
            ic.commitText(new, 1)
            ic.endBatchEdit()
        }
    }

    private fun undoAutoFix(fix: AutoFix): Boolean {
        val tail = fix.corrected + fix.separator
        val restored = fix.original + fix.separator
        if (composeOn) {
            if (!draft.substring(0, caret).endsWith(tail)) return false
            draft = draft.substring(0, caret - tail.length) + restored + draft.substring(caret)
            caret += restored.length - tail.length
            renderDraft()
        } else {
            val ic = currentInputConnection ?: return false
            if (!textBeforeCaret(tail.length + 2).endsWith(tail)) return false
            ic.beginBatchEdit()
            ic.deleteSurroundingText(tail.length, 0)
            ic.commitText(restored, 1)
            ic.endBatchEdit()
        }
        SuggestionEngine.noteUndoneCorrection(fix.original)
        return true
    }

    private fun isPasswordField(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun computeReturnAction(info: EditorInfo?): Int? {
        val ei = info ?: return null
        if (ei.inputType == 0) return null
        if (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        val isText = ei.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
        if (isText && ei.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return null
        return when (val action = ei.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_PREVIOUS,
            -> action
            else -> null
        }
    }

    private fun buildRevealOverlay(): FrameLayout {
        revealTitle = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.accent)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 15f
            setTextColor(palette.textSecondary)
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(2f), dp(2f), dp(2f))
            setOnClickListener { revealOverlay.visibility = View.GONE }
        }
        revealText = TextView(this).apply {
            textSize = 16f
            setTextColor(palette.text)
            setLineSpacing(0f, 1.15f)
            maxHeight = dp(120f)
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(18f))
            setTextIsSelectable(false)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(revealTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(close, LinearLayout.LayoutParams(WRAP, WRAP))
        }

        revealCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(palette.panel)
                setStroke(dp(0.5f).coerceAtLeast(1), palette.hairline)
            }
            elevation = dp(10f).toFloat()
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            addView(header)
            addView(revealText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10f) })
        }

        revealOverlay = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    palette.scrim and 0x00FFFFFF, palette.scrim, palette.scrim,
                    palette.scrim, palette.scrim, palette.scrim,
                ),
            )
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isClickable = true
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            setOnClickListener { visibility = View.GONE }
            addView(revealCard, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        }
        return revealOverlay
    }

    private val keyRowsHeight: Int get() = dp(4 * 46f + 3 * 6f)

    private fun showReveal(name: String, text: String) {
        revealTitle.text = getString(R.string.decrypted) + " · " + name
        revealText.text = text
        revealText.scrollTo(0, 0)
        revealOverlay.visibility = View.VISIBLE
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        crypto.execute {
            runCatching { SignalService.ensureInitialized() }
            handler.post { restoreContactSelection(); updateChips() }
        }
        restoreContactSelection()
        secureKb = AppSettingsStore.secureKeyboard
        window?.window?.let { w ->
            if (secureKb) {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        haptics = AppSettingsStore.keyboardHaptics
        sounds = AppSettingsStore.keyboardSounds
        autoDecrypt = AppSettingsStore.keyboardAutoDecrypt
        composeOn = AppSettingsStore.keyboardCompose
        suggestionsOn = AppSettingsStore.keyboardSuggestions
        autocorrectOn = AppSettingsStore.keyboardAutocorrect
        emojiOn = AppSettingsStore.keyboardEmoji
        enabledLangs = enabledLanguages()
        langCode = (AppSettingsStore.keyboardLastLang ?: AppSettingsStore.systemKeyboardLang)
            .takeIf { it in enabledLangs } ?: enabledLangs[0]
        lastAutoFix = null
        suggestionsStamp = null
        passwordField = isPasswordField(info)
        noLearningField =
            ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0 &&
                info?.packageName != packageName
        returnAction = computeReturnAction(info)
        warmSuggestions()

        status.text = ""
        status.visibility = View.GONE
        revealOverlay.visibility = View.GONE
        composeRow.visibility = if (composeOn) View.VISIBLE else View.GONE
        styleComposeToggle()
        styleSendBadge()
        renderDraft()
        hideChipMenu()
        closeEmojiPanel()
        hideKeyPopup()
        if (emojiOn) EmojiData.prefetch()

        if (!restarting) {
            shiftState = 0
            autoShifted = false
            val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
            symbols = cls == InputType.TYPE_CLASS_NUMBER ||
                cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME
            symPage = 0
        }
        updateAutoShift()
        refreshKeys()
        updateChips()
        updateClipDot()
        updateSuggestions()
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        clipboardManager.addPrimaryClipChangedListener(clipListener)
        if (autoDecrypt) autoDecryptClipboard()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        hideKeyPopup()
        keyGrid.cancelTouches()
        SuggestionEngine.persistAsync()
    }

    override fun onCurrentInputMethodSubtypeChanged(subtype: android.view.inputmethod.InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(subtype)
        val tag = subtype?.languageTag?.takeIf { it.isNotEmpty() } ?: return
        val code = tag.substringBefore('-').lowercase()
        if (code !in enabledLangs || code == langCode) return
        langCode = code
        AppSettingsStore.keyboardLastLang = code
        symbols = false
        updateAutoShift()
        refreshKeys()
        updateSuggestions()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!composeOn) {
            updateAutoShift()
            refreshKeys()
            updateSuggestions()
        }
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        handler.removeCallbacksAndMessages(null)
        hideKeyPopup()
        crypto.shutdown()
        if (live === this) live = null
        super.onDestroy()
    }

    private fun dropSensitiveState() {
        draft = ""
        caret = 0
        lastAutoFix = null
        selectedFingerprint = null
        selectedProfileId = null
        pendingFixTyped = null
        suggestionsStamp = null
        hideChipMenu()
        if (::revealOverlay.isInitialized) revealOverlay.visibility = View.GONE
        if (::revealText.isInitialized) revealText.text = ""
        if (::revealTitle.isInitialized) revealTitle.text = ""
        if (::status.isInitialized) {
            status.text = ""
            status.visibility = View.GONE
        }
        renderDraft()
        updateChips()
        updateSuggestions()
    }

    private fun restoreContactSelection() {
        val pid = SignalService.currentID.value
        if (selectedProfileId == pid) return
        selectedProfileId = pid
        selectedFingerprint = null
        crypto.execute {
            val saved = runCatching { AppSettingsStore.keyboardContact(pid) }.getOrNull()
            handler.post {
                if (selectedProfileId == pid && selectedFingerprint == null) {
                    selectedFingerprint = saved
                    updateChips()
                }
            }
        }
    }

    private fun currentContact(): Contact? =
        contacts.firstOrNull { it.fingerprint == selectedFingerprint } ?: contacts.firstOrNull()

    private fun fieldText(): String {
        val ic = currentInputConnection ?: return ""
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString() ?: ""
        val before = ic.getTextBeforeCursor(FIELD_READ_MAX, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(FIELD_READ_MAX, 0)?.toString() ?: ""
        val around = before + after
        return if (around.length > extracted.length) around else extracted
    }

    private fun replaceField(newText: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (ic.getSelectedText(0)?.isNotEmpty() == true) ic.commitText("", 1)
        var rounds = 0
        var cleared = false
        while (rounds < FIELD_CLEAR_ROUNDS) {
            val before = ic.getTextBeforeCursor(FIELD_CLEAR_CHUNK, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(FIELD_CLEAR_CHUNK, 0)?.length ?: 0
            if (before == 0 && after == 0) break
            ic.deleteSurroundingText(before, after)
            cleared = true
            rounds++
        }
        if (!cleared) {
            val length = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.length ?: 0
            if (length > 0) ic.deleteSurroundingText(length, length)
        }
        ic.commitText(newText, 1)
        ic.endBatchEdit()
    }

    private fun cryptoLocked(): Boolean =
        com.kryptos.android.security.AppLock.isCryptoSessionLocked(this)

    private fun encryptTapped() {
        if (cryptoLocked()) { flash(getString(R.string.kb_locked), error = true); return }
        val contact = currentContact() ?: run { flash(getString(R.string.kb_no_contacts), error = true); return }
        val plain = if (composeOn) draft else fieldText()
        if (plain.isBlank()) { flash(getString(R.string.kb_nothing_to_encrypt), error = true); return }
        if (encryptInFlight) return
        encryptInFlight = true
        val intoDraft = composeOn
        crypto.execute {
            val outcome = runCatching { SignalService.encrypt(plain, contact) }
            handler.post {
                encryptInFlight = false
                outcome.onSuccess { armored ->
                    if (intoDraft) {
                        currentInputConnection?.commitText(armored, 1)
                        draft = ""; caret = 0
                        renderDraft()
                    } else {
                        replaceField(armored)
                    }
                    sendEncrypted()
                    flash(getString(R.string.kb_encrypted), error = false)
                }.onFailure { e ->
                    flash(e.message ?: "error", error = true)
                }
            }
        }
    }

    private fun sendEncrypted() {
        if (!sendAfterEncrypt) return
        val declared = returnAction
        val action = when (declared) {
            EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_DONE,
            -> declared
            else -> EditorInfo.IME_ACTION_SEND
        }
        runCatching { currentInputConnection?.performEditorAction(action) }
        updateAutoShift()
        refreshKeys()
        updateSuggestions()
    }

    private fun revealFrom(candidates: List<String>, onMiss: (() -> Unit)?) {
        val wanted = candidates.filter { it.isNotBlank() }
        if (wanted.isEmpty()) { onMiss?.invoke(); return }
        for (candidate in wanted) {
            DecryptCache.get(candidate)?.let { (name, text) -> showReveal(name, text); return }
        }
        crypto.execute {
            for (candidate in wanted) {
                runCatching { SignalService.cachedDecrypt(candidate) }.getOrNull()?.let { (contact, text) ->
                    DecryptCache.put(candidate, contact.displayName to text)
                    handler.post { showReveal(contact.displayName, text) }
                    return@execute
                }
                for (contact in contacts) {
                    val plain = runCatching { SignalService.decrypt(candidate, contact) }.getOrNull() ?: continue
                    DecryptCache.put(candidate, contact.displayName to plain)
                    handler.post { showReveal(contact.displayName, plain) }
                    return@execute
                }
            }
            if (onMiss != null) handler.post(onMiss)
        }
    }

    private fun manualDecrypt() {
        if (cryptoLocked()) { flash(getString(R.string.kb_locked), error = true); return }
        val candidates = buildList {
            add(clipboardText(this@KryptosImeService))
            add(fieldText())
            if (composeOn) add(draft)
        }
        revealFrom(candidates) { flash(getString(R.string.decrypt_failed), error = true) }
    }

    private fun autoDecryptClipboard(freshCopy: Boolean = false) {
        if (revealOverlay.visibility == View.VISIBLE) return
        if (cryptoLocked()) return
        val clip = clipboardText(this)
        if (clip.isBlank()) return
        crypto.execute {
            val stegoSized = clip.length in 40..64_000
            if (!WireFormat.isToken(clip) &&
                !(stegoSized && (TextStego.looksLikeStego(clip) || SmartTextStego.looksLikeStego(clip) ||
                    LetterStego.looksLikeStego(clip)))
            ) return@execute
            if (OwnCipherMarker.matches(clip)) return@execute
            val key = DecryptCacheKey.of(clip)
            if (!freshCopy && key == AppSettingsStore.keyboardHandledClip) return@execute
            AppSettingsStore.keyboardHandledClip = key
            handler.post {
                if (revealOverlay.visibility != View.VISIBLE && !cryptoLocked()) {
                    revealFrom(listOf(clip), null)
                }
            }
        }
    }

    private var statusGen = 0

    private fun flash(message: String, error: Boolean) {
        status.text = message
        status.setTextColor(if (error) palette.err else palette.ok)
        status.visibility = View.VISIBLE
        val gen = ++statusGen
        handler.postDelayed({
            if (gen == statusGen && ::status.isInitialized) status.visibility = View.GONE
        }, 3000)
    }

    private enum class KeyIcon { SHIFT, SHIFT_FILL, CAPS, BACKSPACE, RETURN, SEARCH, SEND, GO, DONE, NEXT, PREV, EMOJI }

    private class Key(val id: String, val label: String, val weight: Float, val icon: KeyIcon? = null) {
        val visual = RectF()
        val cell = RectF()
    }

    private fun rows(): List<String> = when {
        symbols && symPage == 1 -> listOf("[]{}#%^*+=", "_\\|~<>€£₽•", ".,?!'")
        symbols -> listOf("1234567890", "-/:;()$&@\"", ".,?!'")
        langCode == "ru" -> listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
        langCode == "de" -> listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnmß")
        else -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    }

    private fun buildKeys(): List<List<Key>> {
        val rowStrings = rows()
        val out = mutableListOf<List<Key>>()
        rowStrings.forEachIndexed { i, row ->
            val line = mutableListOf<Key>()
            val sidePad = if (i == 1) (rowStrings[0].length - row.length) / 2f else 0f
            if (sidePad > 0) line.add(Key("pad", "", sidePad))
            if (i == 2) {
                if (symbols) {
                    line.add(Key("symtoggle", if (symPage == 0) "#+=" else "?123", 1.4f))
                } else {
                    val icon = when (shiftState) {
                        2 -> KeyIcon.CAPS
                        1 -> KeyIcon.SHIFT_FILL
                        else -> KeyIcon.SHIFT
                    }
                    line.add(Key("shift", "", 1.4f, icon))
                }
            }
            row.forEach { ch ->
                val label = if (shiftState > 0 && !symbols) ch.uppercaseChar().toString() else ch.toString()
                line.add(Key("char", label, 1f))
            }
            if (i == 2) line.add(Key("bs", "", 1.4f, KeyIcon.BACKSPACE))
            if (sidePad > 0) line.add(Key("pad", "", sidePad))
            out.add(line)
        }
        val returnIcon = when (returnAction) {
            EditorInfo.IME_ACTION_SEARCH -> KeyIcon.SEARCH
            EditorInfo.IME_ACTION_SEND -> KeyIcon.SEND
            EditorInfo.IME_ACTION_GO -> KeyIcon.GO
            EditorInfo.IME_ACTION_DONE -> KeyIcon.DONE
            EditorInfo.IME_ACTION_NEXT -> KeyIcon.NEXT
            EditorInfo.IME_ACTION_PREVIOUS -> KeyIcon.PREV
            else -> KeyIcon.RETURN
        }
        val twoLangs = enabledLangs.size > 1
        out.add(
            buildList {
                add(Key("sym", if (symbols) modeLabel() else "?123", 1.4f))
                if (twoLangs) add(Key("lang", shortLabel(nextLang()), 1.1f))
                if (emojiOn) add(Key("emoji", "", 1.1f, KeyIcon.EMOJI))
                var spaceWeight = if (emojiOn) 3.3f else 4.4f
                if (!twoLangs) spaceWeight += 1.1f
                add(Key("space", languageName(langCode), spaceWeight))
                add(Key("ret", "", 1.4f, returnIcon))
            }
        )
        return out
    }

    private var keysStamp: List<Any?> = emptyList()

    private fun refreshKeys() {
        if (!::keyGrid.isInitialized) return
        val stamp = listOf(shiftState, langCode, symbols, symPage, returnAction, emojiOn, enabledLangs)
        if (stamp == keysStamp) return
        keysStamp = stamp
        keyGrid.keys = buildKeys()
        keyGrid.relayout()
    }

    private fun textBeforeCaret(n: Int): String =
        if (composeOn) draft.substring(0, caret).takeLast(n)
        else currentInputConnection?.getTextBeforeCursor(n, 0)?.toString() ?: ""

    private fun typeChar(s: String) {
        val first = s.firstOrNull()
        if (first != null && !first.isLetterOrDigit() && !isWordChar(first)) commitWordBeforeSeparator(s)
        else lastAutoFix = null
        if (composeOn) insertDraft(s) else currentInputConnection?.commitText(s, 1)
        if (shiftState == 1) { shiftState = 0; autoShifted = false }
        updateAutoShift()
        refreshKeys()
        updateSuggestions()
    }

    private fun backspace() {
        lastAutoFix?.let { fix ->
            lastAutoFix = null
            if (System.currentTimeMillis() - fix.at < 15_000 && undoAutoFix(fix)) {
                updateAutoShift()
                refreshKeys()
                updateSuggestions()
                return
            }
        }
        if (composeOn) {
            if (caret > 0) {
                val n = graphemeBack(draft, caret)
                draft = draft.removeRange(caret - n, caret)
                caret -= n
                renderDraft()
            }
        } else {
            val ic = currentInputConnection ?: return
            if (ic.getSelectedText(0)?.isNotEmpty() == true) {
                ic.commitText("", 1)
            } else {
                val before = ic.getTextBeforeCursor(16, 0)?.toString() ?: ""
                ic.deleteSurroundingText(if (before.isEmpty()) 1 else graphemeBack(before, before.length), 0)
            }
        }
        updateAutoShift()
        refreshKeys()
        updateSuggestions()
    }

    private fun graphemeBack(text: String, at: Int): Int {
        if (at <= 0) return 0
        var i = at
        fun prevCodePoint(): Int {
            if (i >= 2 && Character.isLowSurrogate(text[i - 1]) && Character.isHighSurrogate(text[i - 2])) {
                i -= 2
                return Character.toCodePoint(text[i], text[i + 1])
            }
            i -= 1
            return text[i].code
        }
        var cp = prevCodePoint()
        while (i > 0 && (cp == 0xFE0F || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF)) cp = prevCodePoint()
        if (cp in 0x1F1E6..0x1F1FF && i > 0) {
            val save = i
            if (prevCodePoint() !in 0x1F1E6..0x1F1FF) i = save
        }
        while (i > 0 && text[i - 1].code == 0x200D) {
            i--
            if (i == 0) break
            cp = prevCodePoint()
            while (i > 0 && (cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF)) cp = prevCodePoint()
        }
        return at - i
    }

    private fun spaceTapped() {
        commitWordBeforeSeparator(" ")
        val now = System.currentTimeMillis()
        val before = textBeforeCaret(2)
        if (now - lastSpaceTap < 600 && before.length == 2 &&
            before[1] == ' ' && before[0].isLetterOrDigit()
        ) {
            if (composeOn) {
                draft = draft.removeRange(caret - 1, caret); caret--
                insertDraft(". ")
            } else {
                currentInputConnection?.deleteSurroundingText(1, 0)
                currentInputConnection?.commitText(". ", 1)
            }
            lastSpaceTap = 0
        } else {
            if (composeOn) insertDraft(" ") else currentInputConnection?.commitText(" ", 1)
            lastSpaceTap = now
        }
        if (symbols) symbols = false
        updateAutoShift()
        refreshKeys()
        updateSuggestions()
    }

    private fun returnTapped() {
        commitWordBeforeSeparator(if (!composeOn && returnAction != null) "" else "\n")
        if (composeOn) {
            insertDraft("\n")
        } else {
            val action = returnAction
            if (action != null) currentInputConnection?.performEditorAction(action)
            else currentInputConnection?.commitText("\n", 1)
        }
        if (symbols) symbols = false
        updateAutoShift()
        refreshKeys()
        updateSuggestions()
    }

    private fun shiftTapped() {
        val now = System.currentTimeMillis()
        shiftState = when {
            shiftState == 0 -> 1
            shiftState == 1 && now - lastShiftTap < 350 -> 2
            else -> 0
        }
        lastShiftTap = now
        autoShifted = false
        refreshKeys()
    }

    private fun symbolsTapped() {
        symbols = !symbols
        if (symbols) symPage = 0
        refreshKeys()
    }

    private fun symPageTapped() { symPage = if (symPage == 0) 1 else 0; refreshKeys() }

    private fun nextLang(): String {
        val i = enabledLangs.indexOf(langCode).coerceAtLeast(0)
        return enabledLangs[(i + 1) % enabledLangs.size]
    }

    private fun modeLabel() = if (langCode == "ru") "АБВ" else "ABC"

    private fun shortLabel(code: String) = when (code) {
        "ru" -> "РУ"
        "de" -> "DE"
        else -> "EN"
    }

    private fun languageName(code: String) = when (code) {
        "ru" -> "Русский"
        "de" -> "Deutsch"
        else -> "English"
    }

    private fun langTapped() {
        if (enabledLangs.size < 2) return
        langCode = nextLang()
        AppSettingsStore.keyboardLastLang = langCode
        symbols = false
        updateAutoShift()
        refreshKeys()
        updateSuggestions()
    }

    private fun insertDraft(s: String) {
        if (s.isEmpty()) return
        caret = caret.coerceIn(0, draft.length)
        draft = draft.substring(0, caret) + s + draft.substring(caret)
        caret += s.length
        renderDraft()
        updateAutoShift()
        refreshKeys()
        updateSuggestions()
    }

    private fun updateAutoShift() {
        if (symbols || shiftState == 2) return
        val caps = if (composeOn) draftWantsCaps() else hostWantsCaps()
        if (caps && shiftState == 0) { shiftState = 1; autoShifted = true }
        else if (!caps && shiftState == 1 && autoShifted) { shiftState = 0; autoShifted = false }
    }

    private fun hostWantsCaps(): Boolean {
        val ei = currentInputEditorInfo ?: return false
        if (ei.inputType == 0) return false
        val ic = currentInputConnection ?: return false
        return ic.getCursorCapsMode(ei.inputType) != 0
    }

    private fun draftWantsCaps(): Boolean {
        val before = draft.substring(0, caret.coerceIn(0, draft.length))
        if (before.isEmpty()) return true
        if (before.last() == '\n') return true
        val t = before.trimEnd(' ')
        return before.last() == ' ' && t.isNotEmpty() && t.last() in ".!?…"
    }

    private fun caretRequest(): ExtractedTextRequest = ExtractedTextRequest().apply {
        hintMaxChars = CARET_WINDOW_CHARS
        hintMaxLines = CARET_WINDOW_LINES
    }

    private fun moveCaretH(chars: Int) {
        if (chars == 0) return
        lastAutoFix = null
        if (composeOn) {
            caret = (caret + chars).coerceIn(0, draft.length)
            renderDraft()
        } else {
            val ic = currentInputConnection ?: return
            val et = ic.getExtractedText(caretRequest(), 0) ?: return
            val len = et.text?.length ?: return
            val pos = (et.selectionStart + chars).coerceIn(0, len)
            ic.setSelection(et.startOffset + pos, et.startOffset + pos)
        }
        updateAutoShift()
        refreshKeys()
        updateSuggestions()
    }

    private fun moveCaretV(lines: Int) {
        if (lines == 0) return
        lastAutoFix = null
        if (composeOn) {
            var pos = caret.coerceIn(0, draft.length)
            repeat(abs(lines)) { pos = lineStep(draft, pos, down = lines > 0) }
            caret = pos
            renderDraft()
        } else {
            val ic = currentInputConnection ?: return
            val et = ic.getExtractedText(caretRequest(), 0) ?: return
            val text = et.text?.toString() ?: return
            var pos = et.selectionStart.coerceIn(0, text.length)
            repeat(abs(lines)) { pos = lineStep(text, pos, down = lines > 0) }
            ic.setSelection(et.startOffset + pos, et.startOffset + pos)
        }
        updateAutoShift()
        refreshKeys()
        updateSuggestions()
    }

    private fun lineStep(text: String, pos: Int, down: Boolean): Int {
        val lineStart = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val column = pos - lineStart
        return if (down) {
            val lineEnd = text.indexOf('\n', pos).let { if (it < 0) return pos else it }
            val nextStart = lineEnd + 1
            val nextEnd = text.indexOf('\n', nextStart).let { if (it < 0) text.length else it }
            (nextStart + column).coerceAtMost(nextEnd)
        } else {
            if (lineStart == 0) return pos
            val prevStart = text.lastIndexOf('\n', lineStart - 2).let { if (it < 0) 0 else it + 1 }
            (prevStart + column).coerceAtMost(lineStart - 1)
        }
    }

    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconPath = Path()

    private fun drawKeyIcon(canvas: Canvas, icon: KeyIcon, cx: Float, cy: Float, size: Float, color: Int) {
        fun x(u: Float) = cx + (u - 0.5f) * size
        fun y(v: Float) = cy + (v - 0.5f) * size
        iconStroke.color = color
        iconStroke.strokeWidth = size * 0.09f
        iconFill.color = color
        val p = iconPath
        p.reset()
        when (icon) {
            KeyIcon.SHIFT, KeyIcon.SHIFT_FILL, KeyIcon.CAPS -> {
                val stemBottom = if (icon == KeyIcon.CAPS) 0.60f else 0.82f
                p.moveTo(x(0.50f), y(0.08f))
                p.lineTo(x(0.92f), y(0.48f))
                p.lineTo(x(0.67f), y(0.48f))
                p.lineTo(x(0.67f), y(stemBottom))
                p.lineTo(x(0.33f), y(stemBottom))
                p.lineTo(x(0.33f), y(0.48f))
                p.lineTo(x(0.08f), y(0.48f))
                p.close()
                if (icon != KeyIcon.SHIFT) canvas.drawPath(p, iconFill)
                canvas.drawPath(p, iconStroke)
                if (icon == KeyIcon.CAPS) canvas.drawLine(x(0.33f), y(0.82f), x(0.67f), y(0.82f), iconStroke)
            }
            KeyIcon.BACKSPACE -> {
                p.moveTo(x(0.04f), y(0.50f))
                p.lineTo(x(0.32f), y(0.16f))
                p.lineTo(x(0.88f), y(0.16f))
                p.quadTo(x(0.96f), y(0.16f), x(0.96f), y(0.26f))
                p.lineTo(x(0.96f), y(0.74f))
                p.quadTo(x(0.96f), y(0.84f), x(0.88f), y(0.84f))
                p.lineTo(x(0.32f), y(0.84f))
                p.close()
                canvas.drawPath(p, iconStroke)
                canvas.drawLine(x(0.48f), y(0.38f), x(0.72f), y(0.62f), iconStroke)
                canvas.drawLine(x(0.48f), y(0.62f), x(0.72f), y(0.38f), iconStroke)
            }
            KeyIcon.RETURN -> {
                p.moveTo(x(0.84f), y(0.18f))
                p.lineTo(x(0.84f), y(0.60f))
                p.lineTo(x(0.20f), y(0.60f))
                canvas.drawPath(p, iconStroke)
                canvas.drawLine(x(0.20f), y(0.60f), x(0.38f), y(0.42f), iconStroke)
                canvas.drawLine(x(0.20f), y(0.60f), x(0.38f), y(0.78f), iconStroke)
            }
            KeyIcon.SEARCH -> {
                canvas.drawCircle(x(0.44f), y(0.44f), size * 0.26f, iconStroke)
                canvas.drawLine(x(0.64f), y(0.64f), x(0.88f), y(0.88f), iconStroke)
            }
            KeyIcon.SEND -> {
                canvas.drawLine(x(0.50f), y(0.86f), x(0.50f), y(0.16f), iconStroke)
                canvas.drawLine(x(0.50f), y(0.16f), x(0.24f), y(0.42f), iconStroke)
                canvas.drawLine(x(0.50f), y(0.16f), x(0.76f), y(0.42f), iconStroke)
            }
            KeyIcon.GO -> {
                canvas.drawLine(x(0.14f), y(0.50f), x(0.86f), y(0.50f), iconStroke)
                canvas.drawLine(x(0.86f), y(0.50f), x(0.60f), y(0.24f), iconStroke)
                canvas.drawLine(x(0.86f), y(0.50f), x(0.60f), y(0.76f), iconStroke)
            }
            KeyIcon.DONE -> {
                p.moveTo(x(0.14f), y(0.56f))
                p.lineTo(x(0.40f), y(0.80f))
                p.lineTo(x(0.86f), y(0.24f))
                canvas.drawPath(p, iconStroke)
            }
            KeyIcon.NEXT, KeyIcon.PREV -> {
                val m = if (icon == KeyIcon.NEXT) 1f else -1f
                fun mx(u: Float) = cx + m * (u - 0.5f) * size
                canvas.drawLine(mx(0.10f), y(0.50f), mx(0.72f), y(0.50f), iconStroke)
                canvas.drawLine(mx(0.72f), y(0.50f), mx(0.48f), y(0.26f), iconStroke)
                canvas.drawLine(mx(0.72f), y(0.50f), mx(0.48f), y(0.74f), iconStroke)
                canvas.drawLine(mx(0.88f), y(0.24f), mx(0.88f), y(0.76f), iconStroke)
            }
            KeyIcon.EMOJI -> {
                canvas.drawCircle(x(0.5f), y(0.5f), size * 0.40f, iconStroke)
                iconFill.color = color
                canvas.drawCircle(x(0.37f), y(0.40f), size * 0.05f, iconFill)
                canvas.drawCircle(x(0.63f), y(0.40f), size * 0.05f, iconFill)
                canvas.drawArc(
                    RectF(x(0.30f), y(0.28f), x(0.70f), y(0.68f)),
                    25f, 130f, false, iconStroke,
                )
            }
        }
    }

    private val vibrator: android.os.Vibrator? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    }

    private fun haptic() {
        if (!haptics) return
        val v = vibrator
        if (v != null && v.hasVibrator()) {
            val ok = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 26 && v.hasAmplitudeControl()) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(16, 200))
                } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                    v.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK))
                } else {
                    error("no effect API")
                }
            }.isSuccess
            if (ok) return
        }
        window?.window?.decorView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private val audioManager: AudioManager? by lazy {
        getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private fun sound(keyId: String) {
        if (!sounds) return
        val am = audioManager ?: return
        am.playSoundEffect(
            when (keyId) {
                "bs" -> AudioManager.FX_KEYPRESS_DELETE
                "space" -> AudioManager.FX_KEYPRESS_SPACEBAR
                "ret" -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
        )
    }

    private fun showKeyPopup(label: String, rectInWindow: RectF) {
        if (passwordField) return
        val text = keyPopupText ?: TextView(this).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(palette.text)
            background = keyFace(palette.key)
            elevation = dp(4f).toFloat()
        }.also { keyPopupText = it }
        text.text = label
        text.alpha = 1f
        text.animate().cancel()

        val w = rectInWindow.width().toInt() + dp(18f)
        val h = dp(56f)
        val x = rectInWindow.left.toInt() - dp(9f)
        val y = rectInWindow.top.toInt() - h - dp(6f)

        val popup = keyPopup ?: PopupWindow(text, w, h).apply {
            isClippingEnabled = false
            isTouchable = false
            isFocusable = false
        }.also { keyPopup = it }
        popup.contentView = text
        if (popup.isShowing) {
            popup.update(x, y, w, h)
        } else {
            popup.width = w
            popup.height = h
            popup.showAtLocation(rootFrame, Gravity.NO_GRAVITY, x, y)
            applyPopupSecure(popup)
        }
    }

    private fun applyPopupSecure(popup: PopupWindow) {
        runCatching {
            val root = popup.contentView?.rootView ?: return
            val params = root.layoutParams as? android.view.WindowManager.LayoutParams ?: return
            val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
            val next = if (secureKb) params.flags or flag else params.flags and flag.inv()
            if (next == params.flags) return
            params.flags = next
            (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .updateViewLayout(root, params)
        }
    }

    private fun fadeOutKeyPopup() {
        val text = keyPopupText ?: return hideKeyPopup()
        text.animate().alpha(0f).setDuration(100).withEndAction { hideKeyPopup() }.start()
    }

    private fun hideKeyPopup() {
        keyPopupText?.animate()?.cancel()
        keyPopup?.dismiss()
    }

    private fun openEmojiPanel() {
        if (emojiOpen) return
        val panel = emojiPanel ?: buildEmojiPanel().also {
            emojiPanel = it
            keyArea.addView(it, FrameLayout.LayoutParams(MATCH, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        keyGrid.cancelTouches()
        hideKeyPopup()
        emojiOpen = true
        emojiAbc?.text = modeLabel()
        showEmojiCategory(if (EmojiData.recents().isEmpty()) 0 else -1)
        keyGrid.visibility = View.GONE
        panel.visibility = View.VISIBLE
        updateSuggestions()
    }

    private fun closeEmojiPanel() {
        if (!emojiOpen && emojiPanel?.visibility != View.VISIBLE) { emojiOpen = false; return }
        emojiOpen = false
        emojiPanel?.visibility = View.GONE
        if (::keyGrid.isInitialized) keyGrid.visibility = View.VISIBLE
        updateSuggestions()
    }

    private fun buildEmojiPanel(): LinearLayout {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2f), dp(2f), dp(2f), dp(6f))
        }
        emojiGrid = grid
        emojiEmpty = TextView(this).apply {
            text = getString(R.string.kb_no_recents)
            textSize = 13f
            setTextColor(palette.textSecondary)
            gravity = Gravity.CENTER
            setPadding(0, dp(24f), 0, 0)
            visibility = View.GONE
        }
        grid.addView(emojiEmpty, LinearLayout.LayoutParams(MATCH, WRAP))
        val gridScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(grid, FrameLayout.LayoutParams(MATCH, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        emojiTabs = tabs
        val tabIcons = listOf("🕘") + EmojiData.categories.map { it.icon }
        tabIcons.forEachIndexed { i, icon ->
            tabs.addView(
                TextView(this).apply {
                    text = icon
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setOnClickListener { haptic(); showEmojiCategory(i - 1) }
                },
                LinearLayout.LayoutParams(dp(34f), dp(30f)).apply { rightMargin = dp(2f) },
            )
        }
        val tabsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }

        val abc = TextView(this).apply {
            text = "ABC"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.text)
            gravity = Gravity.CENTER
            background = keyFace(palette.keyFn)
            setOnClickListener { haptic(); closeEmojiPanel() }
        }
        emojiAbc = abc
        val del = IconKeyView(this, KeyIcon.BACKSPACE, repeatOnHold = true) { backspace() }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(abc, LinearLayout.LayoutParams(dp(52f), dp(34f)))
            addView(
                tabsScroll,
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8f); rightMargin = dp(8f) },
            )
            addView(del, LinearLayout.LayoutParams(dp(52f), dp(34f)))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(gridScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(MATCH, dp(42f)).apply { topMargin = dp(4f) })
        }
    }

    private fun emojiCell(): TextView = TextView(this).apply {
        textSize = 24f
        gravity = Gravity.CENTER
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(palette.accentSoft, 8f))
            addState(intArrayOf(), rounded(Color.TRANSPARENT, 8f))
        }
        isClickable = true
        setOnClickListener {
            val emoji = text.toString()
            if (emoji.isEmpty()) return@setOnClickListener
            haptic(); sound("char")
            typeChar(emoji)
            EmojiData.addRecent(emoji)
        }
    }

    private fun showEmojiCategory(index: Int) {
        val grid = emojiGrid ?: return
        val list = if (index < 0) EmojiData.recents() else EmojiData.categories[index].emoji
        emojiEmpty?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        val perRow = 8
        val rowsNeeded = (list.size + perRow - 1) / perRow
        val offset = if (emojiEmpty != null) 1 else 0
        while (grid.childCount - offset < rowsNeeded) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(perRow) {
                row.addView(emojiCell(), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH, dp(42f)))
        }
        for (r in 0 until grid.childCount - offset) {
            val row = grid.getChildAt(offset + r) as? LinearLayout ?: continue
            if (r >= rowsNeeded) {
                row.visibility = View.GONE
                continue
            }
            row.visibility = View.VISIBLE
            for (c in 0 until perRow) {
                val cell = row.getChildAt(c) as? TextView ?: continue
                val i = r * perRow + c
                if (i < list.size) {
                    cell.text = list[i]
                    cell.visibility = View.VISIBLE
                } else {
                    cell.text = ""
                    cell.visibility = View.INVISIBLE
                }
            }
        }

        emojiTabs?.let { bar ->
            for (i in 0 until bar.childCount) {
                bar.getChildAt(i).background =
                    if (i == index + 1) rounded(palette.accentSoft, 8f) else null
            }
        }
        (grid.parent as? ScrollView)?.scrollTo(0, 0)
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class IconKeyView(
        context: Context,
        private val icon: KeyIcon,
        private val repeatOnHold: Boolean = false,
        private val onTap: () -> Unit,
    ) : View(context) {
        private var repeat: Runnable? = null

        init {
            background = keyFace(palette.keyFn)
            isClickable = true
            isFocusable = true
            contentDescription = getString(
                if (icon == KeyIcon.BACKSPACE) R.string.kb_a11y_backspace else R.string.kb_a11y_enter,
            )
        }

        override fun performClick(): Boolean {
            super.performClick()
            onTap()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            drawKeyIcon(canvas, icon, width / 2f, height / 2f - dp(1f) / 2f, dp(17f).toFloat(), palette.text)
        }

        override fun onDetachedFromWindow() {
            repeat?.let { this@KryptosImeService.handler.removeCallbacks(it) }
            repeat = null
            super.onDetachedFromWindow()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    haptic(); sound("bs")
                    onTap()
                    if (repeatOnHold) {
                        val r = object : Runnable {
                            override fun run() {
                                onTap()
                                this@KryptosImeService.handler.postDelayed(this, 70)
                            }
                        }
                        repeat = r
                        this@KryptosImeService.handler.postDelayed(r, 380)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeat?.let { this@KryptosImeService.handler.removeCallbacks(it) }
                    repeat = null
                }
            }
            return true
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class KeyGridView(context: Context) : View(context) {
        var keys: List<List<Key>> = emptyList()

        private val keyH = dp(46f).toFloat()
        private val rowGap = dp(6f).toFloat()
        private val hGap = dp(5f).toFloat()
        private val radius = dp(9f).toFloat()
        private val edge = dp(1.5f).toFloat()
        private val yBias = dp(5f).toFloat()
        private val stepX = dp(8f).toFloat()
        private val stepY = dp(22f).toFloat()
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyShadow }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val faceRect = RectF()

        private inner class Touch(var key: Key, val row: Int, val col: Int, val downX: Float, val downY: Float) {
            var stepsX = 0
            var stepsY = 0
            var moved = false
            var holdRunnable: Runnable? = null
        }

        private val active = mutableMapOf<Int, Touch>()
        private val pressed = mutableSetOf<Key>()
        private var repeatRunnable: Runnable? = null

        private var trackpadActive = false
        private var labelAlpha = 1f
        private var labelAnimator: android.animation.ValueAnimator? = null

        private fun setTrackpad(on: Boolean) {
            if (trackpadActive == on) return
            trackpadActive = on
            labelAnimator?.cancel()
            labelAnimator = android.animation.ValueAnimator.ofFloat(labelAlpha, if (on) 0f else 1f).apply {
                duration = 160
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { labelAlpha = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        private fun engageTrackpad(t: Touch) {
            if (t.moved) return
            t.moved = true
            t.holdRunnable?.let { handler.removeCallbacks(it) }
            t.holdRunnable = null
            haptic()
            setTrackpad(true)
        }

        init { isClickable = true }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            ViewCompat.setAccessibilityDelegate(this, a11y)
        }

        override fun onDetachedFromWindow() {
            labelAnimator?.cancel()
            super.onDetachedFromWindow()
        }

        fun relayout() {
            remapTouches()
            if (width > 0) layoutKeys()
            a11y.invalidateRoot()
            invalidate()
        }

        private fun remapTouches() {
            if (active.isEmpty()) { pressed.clear(); return }
            pressed.clear()
            for (t in active.values) {
                val fresh = keys.getOrNull(t.row)?.getOrNull(t.col) ?: continue
                if (fresh.id != t.key.id) continue
                t.key = fresh
                pressed.add(fresh)
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layoutKeys() }

        private fun layoutKeys() {
            val w = width.toFloat()
            keys.forEachIndexed { i, row ->
                val top = i * (keyH + rowGap)
                val total = row.sumOf { it.weight.toDouble() }.toFloat()
                var x = 0f
                row.forEachIndexed { j, key ->
                    val kw = w * key.weight / total
                    key.visual.set(x + hGap / 2, top, x + kw - hGap / 2, top + keyH)
                    key.cell.set(
                        if (j == 0) 0f else x,
                        if (i == 0) -1000f else top - rowGap / 2,
                        if (j == row.lastIndex) w else x + kw,
                        if (i == keys.lastIndex) height + 1000f else top + keyH + rowGap / 2,
                    )
                    x += kw
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val la = (labelAlpha * 255).toInt().coerceIn(0, 255)
            fun faded(c: Int): Int = ColorUtils.setAlphaComponent(c, Color.alpha(c) * la / 255)
            keys.forEach { row ->
                row.forEach { key ->
                    if (key.id == "pad") return@forEach
                    val bg = when {
                        key.id == "ret" -> palette.accent
                        key.id == "shift" && shiftState > 0 -> palette.key
                        key.id == "char" || key.id == "space" -> palette.key
                        else -> palette.keyFn
                    }
                    val face = if (key in pressed && !trackpadActive) ColorUtils.blendARGB(bg, Color.BLACK, 0.10f) else bg
                    canvas.drawRoundRect(key.visual, radius, radius, shadowPaint)
                    facePaint.color = face
                    val r = faceRect.apply { set(key.visual); bottom -= edge }
                    canvas.drawRoundRect(r, radius, radius, facePaint)
                    if (la == 0) return@forEach

                    val iconColor = when (key.id) {
                        "ret" -> Color.WHITE
                        else -> palette.text
                    }
                    if (key.icon != null) {
                        drawKeyIcon(canvas, key.icon, r.centerX(), r.centerY(), dp(19f).toFloat(), faded(iconColor))
                    } else {
                        labelPaint.color = faded(when (key.id) {
                            "ret" -> Color.WHITE
                            "space" -> palette.textSecondary
                            else -> palette.text
                        })
                        labelPaint.textSize = when (key.id) {
                            "char" -> sp(20f)
                            "space" -> sp(14f)
                            "sym", "lang", "symtoggle" -> sp(13f)
                            else -> sp(18f)
                        }
                        labelPaint.typeface =
                            if (key.id == "sym" || key.id == "lang" || key.id == "symtoggle") Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        val cy = r.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2
                        canvas.drawText(key.label, r.centerX(), cy, labelPaint)
                    }
                }
            }
        }

        private fun positionOf(target: Key): Pair<Int, Int> {
            keys.forEachIndexed { r, row ->
                val c = row.indexOfFirst { it === target }
                if (c >= 0) return r to c
            }
            return -1 to -1
        }

        private fun dispatchKey(key: Key, fromTouch: Boolean) {
            when (key.id) {
                "char" -> typeChar(key.label)
                "bs" -> {
                    backspace()
                    if (fromTouch) startRepeat()
                }
                "shift" -> shiftTapped()
                "sym" -> symbolsTapped()
                "symtoggle" -> symPageTapped()
                "lang" -> langTapped()
                "emoji" -> openEmojiPanel()
                "ret" -> returnTapped()
                "space" -> if (!fromTouch) spaceTapped()
            }
        }

        private fun keyDescription(key: Key): String = when (key.id) {
            "char" -> if (passwordField) getString(R.string.kb_a11y_hidden) else key.label
            "space" -> getString(R.string.kb_a11y_space)
            "bs" -> getString(R.string.kb_a11y_backspace)
            "ret" -> getString(R.string.kb_a11y_enter)
            "shift" -> getString(R.string.kb_a11y_shift)
            "emoji" -> getString(R.string.kb_a11y_emoji)
            else -> key.label
        }

        private fun keyByIndex(index: Int): Key? {
            var i = 0
            keys.forEach { row ->
                row.forEach { k ->
                    if (i == index) return k
                    i++
                }
            }
            return null
        }

        private fun indexOf(target: Key): Int {
            var i = 0
            keys.forEach { row ->
                row.forEach { k ->
                    if (k === target) return i
                    i++
                }
            }
            return ExploreByTouchHelper.INVALID_ID
        }

        private val a11y = object : ExploreByTouchHelper(this) {
            override fun getVirtualViewAt(x: Float, y: Float): Int {
                val key = keyAt(x, y - yBias) ?: return HOST_ID
                if (key.id == "pad") return HOST_ID
                return indexOf(key)
            }

            override fun getVisibleVirtualViews(ids: MutableList<Int>) {
                var i = 0
                keys.forEach { row ->
                    row.forEach { k ->
                        if (k.id != "pad") ids.add(i)
                        i++
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
                val key = keyByIndex(id)
                if (key == null) {
                    node.contentDescription = ""
                    node.setBoundsInParent(Rect(0, 0, 1, 1))
                    return
                }
                node.contentDescription = keyDescription(key)
                node.className = "android.widget.Button"
                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                val r = Rect()
                key.visual.round(r)
                if (r.isEmpty) r.set(0, 0, 1, 1)
                node.setBoundsInParent(r)
            }

            override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
                if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
                val key = keyByIndex(id) ?: return false
                haptic()
                sound(key.id)
                dispatchKey(key, fromTouch = false)
                invalidate()
                return true
            }
        }

        override fun dispatchHoverEvent(event: MotionEvent): Boolean =
            a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

        private fun keyAt(x: Float, y: Float): Key? {
            var best: Key? = null
            var bestDist = Float.MAX_VALUE
            keys.forEach { row ->
                row.forEach { key ->
                    if (key.id == "pad") return@forEach
                    if (key.cell.contains(x, y)) return key
                    val dx = x - key.visual.centerX()
                    val dy = y - key.visual.centerY()
                    val d = dx * dx + dy * dy
                    if (d < bestDist) { bestDist = d; best = key }
                }
            }
            return best
        }

        fun cancelTouches() {
            active.values.forEach { t -> t.holdRunnable?.let { handler.removeCallbacks(it) } }
            active.clear()
            pressed.clear()
            stopRepeat()
            setTrackpad(false)
            invalidate()
        }

        private fun startRepeat() {
            stopRepeat()
            val r = object : Runnable {
                override fun run() {
                    backspace()
                    handler.postDelayed(this, 70)
                }
            }
            repeatRunnable = r
            handler.postDelayed(r, 380)
        }

        private fun stopRepeat() {
            repeatRunnable?.let { handler.removeCallbacks(it) }
            repeatRunnable = null
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val idx = e.actionIndex
                    val key = keyAt(e.getX(idx), e.getY(idx) - yBias) ?: return true
                    val (row, col) = positionOf(key)
                    val touch = Touch(key, row, col, e.getX(idx), e.getY(idx))
                    active[e.getPointerId(idx)] = touch
                    pressed.add(key)
                    haptic()
                    sound(key.id)
                    if (key.id == "space") {
                        val r = Runnable { if (active.containsValue(touch)) engageTrackpad(touch) }
                        touch.holdRunnable = r
                        handler.postDelayed(r, 400)
                    }
                    if (key.id == "char") showKeyPopup(key.label, rectInWindow(key))
                    dispatchKey(key, fromTouch = true)
                    invalidate()
                }

                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until e.pointerCount) {
                        val t = active[e.getPointerId(i)] ?: continue
                        if (t.key.id != "space") continue
                        val dx = e.getX(i) - t.downX
                        val dy = e.getY(i) - t.downY
                        if (abs(dx) > slop * 2 || abs(dy) > slop * 2) engageTrackpad(t)
                        val nx = (dx / stepX).toInt()
                        if (nx != t.stepsX) { engageTrackpad(t); moveCaretH(nx - t.stepsX); t.stepsX = nx }
                        val ny = (dy / stepY).toInt()
                        if (ny != t.stepsY) { engageTrackpad(t); moveCaretV(ny - t.stepsY); t.stepsY = ny }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val t = active.remove(e.getPointerId(e.actionIndex))
                    if (t != null) {
                        pressed.remove(t.key)
                        t.holdRunnable?.let { handler.removeCallbacks(it) }
                        when (t.key.id) {
                            "space" -> {
                                if (!t.moved) spaceTapped()
                                if (active.values.none { it.key.id == "space" }) setTrackpad(false)
                            }
                            "bs" -> stopRepeat()
                            "char" -> if (active.values.none { it.key.id == "char" }) fadeOutKeyPopup()
                        }
                        invalidate()
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelTouches()
                    fadeOutKeyPopup()
                }
            }
            return true
        }

        private fun rectInWindow(key: Key): RectF {
            val loc = IntArray(2)
            getLocationInWindow(loc)
            return RectF(
                key.visual.left + loc[0],
                key.visual.top + loc[1],
                key.visual.right + loc[0],
                key.visual.bottom + loc[1],
            )
        }
    }

    private companion object {
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val COMPOSE_ICON_ID = 0x4B430001
        const val CHIP_HEIGHT_DP = 30f
        const val FIELD_READ_MAX = 64 * 1024
        const val FIELD_CLEAR_CHUNK = 4096
        const val FIELD_CLEAR_ROUNDS = 64
        const val CARET_WINDOW_CHARS = 8192
        const val CARET_WINDOW_LINES = 64

        @Volatile private var live: KryptosImeService? = null
        private var purgeHooked = false
    }
}
