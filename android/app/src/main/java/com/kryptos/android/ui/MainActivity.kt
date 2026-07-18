package com.kryptos.android.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.kryptos.android.R
import com.kryptos.android.pgp.PgpService
import com.kryptos.android.security.AppLock
import com.kryptos.android.security.ClipboardGuard
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.TextStego
import com.kryptos.android.core.WireFormat
import com.kryptos.android.signal.AppSettingsStore
import com.kryptos.android.signal.OwnCipherMarker
import com.kryptos.android.signal.SignalService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyShield()
        hardenWindow()
        SignalService.ensureInitialized()
        if (savedInstanceState == null || !AppLock.hasLaunched) AppLock.onLaunch(this)
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.Default) { runCatching { PgpService.ensureInitialized() } }

        setContent {
            KryptosTheme {
                LockGate(activity = this) {
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    Box {
                        ScreenBackground()
                        Scaffold(
                            containerColor = Color.Transparent,
                            bottomBar = { KTabBar(tab) { tab = it } },
                        ) { padding ->
                            val mod = Modifier.padding(padding).consumeWindowInsets(padding)
                            AnimatedContent(
                                targetState = tab,
                                transitionSpec = {
                                    (fadeIn(tween(200, delayMillis = 40)) +
                                        scaleIn(initialScale = 0.985f, animationSpec = tween(240, delayMillis = 40)))
                                        .togetherWith(fadeOut(tween(110)))
                                },
                                label = "tabContent",
                            ) { t ->
                                when (t) {
                                    0 -> SessionsScreen(mod)
                                    1 -> PgpScreen(mod)
                                    2 -> QuickScreen(mod)
                                    3 -> StegoScreen(mod)
                                    4 -> SettingsScreen(mod, onShieldChanged = { applyShield() })
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
        AppLock.onForeground(this)
        SignalService.purgeExpiredMessages()
    }

    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations) AppLock.onBackground()
    }

    private var lastAutoDecryptedClip: String? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || AppLock.locked.value || !AppSettingsStore.clipboardAutoDecrypt) return
        val clip = clipboardText(this)
        if (clip.isBlank() || clip == lastAutoDecryptedClip) return
        lastAutoDecryptedClip = clip
        val stegoSized = clip.length in 40..64_000
        if (!WireFormat.isToken(clip) &&
            !(stegoSized && (TextStego.looksLikeStego(clip) || SmartTextStego.looksLikeStego(clip)))
        ) return
        if (OwnCipherMarker.matches(clip)) return
        val appContext = applicationContext
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.Default) {
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

private data class TabSpec(val outlined: ImageVector, val filled: ImageVector, val label: Int)

@Composable
private fun KTabBar(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf(
        TabSpec(Icons.AutoMirrored.Outlined.Chat, Icons.AutoMirrored.Filled.Chat, R.string.tab_chats),
        TabSpec(Icons.Outlined.Email, Icons.Filled.Email, R.string.tab_pgp),
        TabSpec(Icons.Outlined.Lock, Icons.Filled.Lock, R.string.tab_quick),
        TabSpec(Icons.Outlined.Image, Icons.Filled.Image, R.string.tab_stego),
        TabSpec(Icons.Outlined.Settings, Icons.Filled.Settings, R.string.tab_settings),
    )
    val dockShape = RoundedCornerShape(30.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(
                    18.dp, dockShape,
                    ambientColor = Color.Black.copy(alpha = 0.4f),
                    spotColor = Color.Black.copy(alpha = 0.4f),
                )
                .clip(dockShape)
                .background(K.surface.copy(alpha = 0.97f))
                .border(1.dp, K.hairline, dockShape)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { i, tab ->
                val active = i == selected
                val tint by animateColorAsState(
                    if (active) K.accent else K.textSecondary,
                    tween(220), label = "tabTint",
                )
                val pillBg by animateColorAsState(
                    if (active) K.accent.copy(alpha = 0.14f) else Color.Transparent,
                    tween(220), label = "tabPill",
                )
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(pillBg)
                        .bouncyClickable(pressedScale = 0.9f) { onSelect(i) }
                        .animateContentSize(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (active) tab.filled else tab.outlined,
                        stringResource(tab.label),
                        Modifier.size(22.dp),
                        tint = tint,
                    )
                    AnimatedVisibility(
                        visible = active,
                        enter = expandHorizontally(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(200)),
                        exit = shrinkHorizontally(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)) + fadeOut(tween(90)),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(7.dp))
                            Text(
                                stringResource(tab.label),
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
        }
    }
}

fun copySensitive(context: Context, text: String, toast: String? = null) {
    ClipboardGuard.copy(context, text, toast)
}

fun clipboardText(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
}

fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
