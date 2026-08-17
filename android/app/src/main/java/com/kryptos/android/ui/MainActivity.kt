package com.kryptos.android.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.kryptos.android.R
import com.kryptos.android.AppLanguage
import com.kryptos.android.KryptosApp
import com.kryptos.android.core.CachePurge
import com.kryptos.android.pgp.PgpService
import com.kryptos.android.security.AppLock
import com.kryptos.android.security.ClipboardGuard
import com.kryptos.android.core.LetterStego
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.TextStego
import com.kryptos.android.core.WireFormat
import com.kryptos.android.core.sha256Hex
import com.kryptos.android.signal.AppSettingsStore
import com.kryptos.android.signal.OwnCipherMarker
import com.kryptos.android.signal.SignalService
import com.kryptos.android.store.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    override fun attachBaseContext(newBase: Context) {
        SecureStore.init(newBase)
        AppSettingsStore.loadUiState()
        super.attachBaseContext(runCatching { AppLanguage.wrap(newBase) }.getOrDefault(newBase))
    }

    private var appliedLanguage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedLanguage = AppSettingsStore.storedLanguage()
        ClipScanMemory.hook()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
            ),
        )
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        applyShield()
        hardenWindow()
        if (savedInstanceState == null || !AppLock.hasLaunched) AppLock.onLaunch(this)
        val boot = mutableStateOf(if (SignalService.isReady) BootState.Ready else BootState.Loading)
        if (boot.value != BootState.Ready) {
            KryptosApp.scope.launch {
                val ok = runCatching { SignalService.ensureInitialized() }.isSuccess
                withContext(Dispatchers.Main) {
                    boot.value = if (ok) BootState.Ready else BootState.Broken
                }
            }
        }
        KryptosApp.scope.launch { runCatching { PgpService.ensureInitialized() } }

        setContent {
            KryptosTheme {
                NoKeyboardLearning {
                    LockGate(activity = this) {
                    when (boot.value) {
                        BootState.Loading -> BootScreen()
                        BootState.Broken -> StorageRecoveryScreen {
                            runCatching { com.kryptos.android.security.DataWipe.wipe(applicationContext) }
                            boot.value = if (SignalService.isReady) BootState.Ready else BootState.Broken
                        }
                        BootState.Ready -> {
                        val ui by AppSettingsStore.ui.collectAsState()
                        val visible = ui.visibleTabs
                        var tabKey by rememberSaveable { mutableStateOf(AppSettingsStore.AppTab.CHATS.key) }
                        var chatOpen by remember { mutableStateOf(false) }
                        var homeSignal by remember { mutableIntStateOf(0) }
                        val tab = AppSettingsStore.AppTab.of(tabKey)
                            ?.takeIf { it in visible }
                            ?: visible.first()
                        if (tab.key != tabKey) tabKey = tab.key
                        Box {
                            ScreenBackground()
                            Scaffold(
                                containerColor = Color.Transparent,
                                bottomBar = {
                                    if (!chatOpen) BottomDock(visible, tab) { entry ->
                                        if (entry == tab) homeSignal++ else tabKey = entry.key
                                    }
                                },
                            ) { padding ->
                                val top = padding.calculateTopPadding()
                                val mod = Modifier
                                    .padding(top = top)
                                    .consumeWindowInsets(PaddingValues(top = top))
                                CompositionLocalProvider(
                                    LocalTabBarInset provides padding.calculateBottomPadding(),
                                ) {
                                    AnimatedContent(
                                        targetState = tab,
                                        transitionSpec = {
                                            fadeIn(tween(130)) togetherWith fadeOut(tween(90))
                                        },
                                        label = "tabContent",
                                    ) { t ->
                                        when (t) {
                                            AppSettingsStore.AppTab.CHATS ->
                                                SessionsScreen(mod, homeSignal, onChatOpenChanged = { chatOpen = it })
                                            AppSettingsStore.AppTab.PGP -> PgpScreen(mod)
                                            AppSettingsStore.AppTab.QUICK -> QuickScreen(mod)
                                            AppSettingsStore.AppTab.STEGO -> StegoScreen(mod)
                                            AppSettingsStore.AppTab.SETTINGS ->
                                                SettingsScreen(mod, homeSignal, onShieldChanged = { applyShield() })
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (appliedLanguage != null && appliedLanguage != AppSettingsStore.storedLanguage()) {
            recreate()
            return
        }
        AppLock.onForeground(this)
        KryptosApp.scope.launch { runCatching { SignalService.purgeExpiredMessages() } }
    }

    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations) AppLock.onBackground()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || AppLock.locked.value || !AppSettingsStore.clipboardAutoDecrypt) return
        if (!SignalService.isReady) return
        val clip = clipboardText(this)
        if (clip.isBlank() || !ClipScanMemory.markSeen(clip)) return
        val appContext = applicationContext
        KryptosApp.scope.launch {
            val stegoSized = clip.length in 40..64_000
            if (!WireFormat.isToken(clip) &&
                !(stegoSized && (TextStego.looksLikeStego(clip) || SmartTextStego.looksLikeStego(clip) ||
                    LetterStego.looksLikeStego(clip)))
            ) return@launch
            if (OwnCipherMarker.matches(clip)) return@launch
            for (contact in SignalService.contacts.value) {
                try {
                    SignalService.decrypt(clip, contact)
                    if (AppSettingsStore.clearClipboardOnDecrypt) {
                        ClipboardGuard.clearIfOurs(appContext, clip)
                    }
                    break
                } catch (_: Exception) {}
            }
        }
    }

    fun applyShield() {
        if (AppSettingsStore.privacyShield) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun hardenWindow() {
        window.decorView.filterTouchesWhenObscured = true
        if (android.os.Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
    }

}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NoKeyboardLearning(content: @Composable () -> Unit) {
    val interceptor = remember {
        PlatformTextInputInterceptor { request, nextHandler ->
            val guarded = PlatformTextInputMethodRequest { outAttrs ->
                val connection = request.createInputConnection(outAttrs)
                outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                connection
            }
            nextHandler.startInputMethod(guarded)
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

object ClipScanMemory {
    @Volatile private var lastSeen: String? = null
    @Volatile private var hooked = false

    @Synchronized fun hook() {
        if (hooked) return
        hooked = true
        CachePurge.register { lastSeen = null }
    }

    fun markSeen(clip: String): Boolean {
        val key = sha256Hex(clip.toByteArray(Charsets.UTF_8))
        if (key == lastSeen) return false
        lastSeen = key
        return true
    }
}

private data class TabSpec(val outlined: ImageVector, val filled: ImageVector, val label: Int)

fun tabSpecOf(tab: AppSettingsStore.AppTab): Int = when (tab) {
    AppSettingsStore.AppTab.CHATS -> R.string.tab_chats
    AppSettingsStore.AppTab.PGP -> R.string.tab_pgp
    AppSettingsStore.AppTab.QUICK -> R.string.tab_quick
    AppSettingsStore.AppTab.STEGO -> R.string.tab_stego
    AppSettingsStore.AppTab.SETTINGS -> R.string.tab_settings
}

private val tabSpecs: Map<AppSettingsStore.AppTab, TabSpec> by lazy {
    mapOf(
        AppSettingsStore.AppTab.CHATS to
            TabSpec(Icons.AutoMirrored.Outlined.Chat, Icons.AutoMirrored.Filled.Chat, R.string.tab_chats),
        AppSettingsStore.AppTab.PGP to
            TabSpec(Icons.Outlined.Email, Icons.Filled.Email, R.string.tab_pgp),
        AppSettingsStore.AppTab.QUICK to
            TabSpec(Icons.Outlined.Lock, Icons.Filled.Lock, R.string.tab_quick),
        AppSettingsStore.AppTab.STEGO to
            TabSpec(Icons.Outlined.Image, Icons.Filled.Image, R.string.tab_stego),
        AppSettingsStore.AppTab.SETTINGS to
            TabSpec(Icons.Outlined.Settings, Icons.Filled.Settings, R.string.tab_settings),
    )
}

private fun specFor(tab: AppSettingsStore.AppTab): TabSpec = tabSpecs.getValue(tab)

private class TabAnimSpecs(
    val labelSize: FiniteAnimationSpec<IntSize>,
    val labelFade: FiniteAnimationSpec<Float>,
    val tint: FiniteAnimationSpec<Color>,
)

private val dockFadeHeight = 28.dp

@Composable
private fun BottomDock(
    visible: List<AppSettingsStore.AppTab>,
    selected: AppSettingsStore.AppTab,
    onSelect: (AppSettingsStore.AppTab) -> Unit,
) {
    val bg = K.bg
    val fade = remember(bg) { Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg)) }
    Column(Modifier.fillMaxWidth()) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(dockFadeHeight)
                .background(fade),
        )
        Box(Modifier.fillMaxWidth().background(bg)) {
            KTabBar(visible, selected, onSelect)
        }
    }
}

@Composable
private fun KTabBar(
    visible: List<AppSettingsStore.AppTab>,
    selected: AppSettingsStore.AppTab,
    onSelect: (AppSettingsStore.AppTab) -> Unit,
) {
    val dock = CircleShape
    val specs = remember {
        TabAnimSpecs(
            labelSize = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f),
            labelFade = tween(140),
            tint = tween(150),
        )
    }
    val glassTop = K.glassTop
    val glassBottom = K.glassBottom
    val rimTop = K.glassRimTop
    val rimBottom = K.glassRimBottom
    val glass = remember(glassTop, glassBottom) {
        Brush.verticalGradient(listOf(glassTop, glassBottom))
    }
    val rim = remember(rimTop, rimBottom) {
        Brush.verticalGradient(listOf(rimTop, rimBottom))
    }

    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(6.dp, dock, ambientColor = K.glassShadow, spotColor = K.glassShadow)
                .clip(dock)
                .background(glass)
                .border(1.dp, rim, dock)
                .padding(horizontal = 6.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visible.forEach { entry ->
                key(entry) {
                    TabItem(
                        entry = entry,
                        active = entry == selected,
                        shape = dock,
                        specs = specs,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabItem(
    entry: AppSettingsStore.AppTab,
    active: Boolean,
    shape: Shape,
    specs: TabAnimSpecs,
    onSelect: (AppSettingsStore.AppTab) -> Unit,
) {
    val tab = specFor(entry)
    val label = stringResource(tab.label)
    val accent = K.accent
    val tint by animateColorAsState(
        if (active) accent else K.textSecondary,
        specs.tint, label = "tabTint",
    )
    val pillBg by animateColorAsState(
        if (active) accent.copy(alpha = 0.16f) else Color.Transparent,
        specs.tint, label = "tabPill",
    )
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .clip(shape)
            .background(pillBg)
            .selectable(
                selected = active,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = { onSelect(entry) },
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (active) tab.filled else tab.outlined,
            null,
            Modifier.size(22.dp),
            tint = tint,
        )
        AnimatedVisibility(
            visible = active,
            enter = expandHorizontally(specs.labelSize) + fadeIn(specs.labelFade),
            exit = shrinkHorizontally(specs.labelSize) + fadeOut(specs.labelFade),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

fun copySensitive(context: Context, text: String, toast: String? = null) {
    ClipboardGuard.copy(context, text, toast)
}

fun copyCipher(context: Context, text: String, toast: String? = null) {
    OwnCipherMarker.mark(text)
    ClipboardGuard.copy(context, text, toast)
}

private enum class BootState { Loading, Ready, Broken }

@Composable
private fun BootScreen() {
    Box(Modifier.fillMaxSize()) {
        ScreenBackground()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = K.accent, strokeWidth = 2.dp)
        }
    }
}

fun clipboardText(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip?.takeIf { it.itemCount > 0 } ?: return ""
    if (clip.description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) != true &&
        clip.description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) != true
    ) {
        return ""
    }
    return clip.getItemAt(0)?.text?.toString() ?: ""
}

fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

@Composable
private fun StorageRecoveryScreen(onReset: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        ScreenBackground()
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Warning, null, Modifier.size(52.dp), tint = K.danger)
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.storage_broken_title),
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = K.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.storage_broken_text),
                fontSize = 14.sp, lineHeight = 20.sp, color = K.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))
            SecondaryButton(
                stringResource(R.string.storage_broken_reset),
                Modifier.fillMaxWidth(),
                danger = true,
                enabled = !busy,
            ) {
                busy = true
                scope.launch(Dispatchers.Default) {
                    onReset()
                    withContext(Dispatchers.Main) { busy = false }
                }
            }
        }
    }
}
