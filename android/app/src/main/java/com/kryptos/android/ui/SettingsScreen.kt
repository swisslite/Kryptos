package com.kryptos.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kryptos.android.R
import com.kryptos.android.security.AppLock
import com.kryptos.android.security.launchFromApp
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.kryptos.android.core.KeyQr
import com.kryptos.android.core.LetterStego
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.StegoLanguage
import com.kryptos.android.core.StegoMode
import com.kryptos.android.core.TextStego
import com.kryptos.android.keyboard.VoiceInput
import com.kryptos.android.pgp.PgpService
import com.kryptos.android.screen.ScreenDecryptService
import com.kryptos.android.security.ClipboardGuard
import com.kryptos.android.signal.AppSettingsStore
import com.kryptos.android.signal.SignalService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SettingsPage {
    Interface, Root, Privacy, AppCode, Panic, Stego, Keyboard, KeyboardLangs, KeyboardApps,
    KeyboardAppsPick, KeyBackup, Donate,
    HowTo, HowToKeys, HowToSetup, Faq, About, Developer, Danger }

private val kbLanguageCatalog =
    listOf(
        "en" to R.string.lang_en, "ru" to R.string.lang_ru,
        "de" to R.string.lang_de, "zh" to R.string.lang_zh,
        "fa" to R.string.lang_fa,
    )

private val fieldSizeOptions = listOf(
    AppSettingsStore.FieldSize.SMALL to R.string.field_size_small,
    AppSettingsStore.FieldSize.MEDIUM to R.string.field_size_medium,
    AppSettingsStore.FieldSize.LARGE to R.string.field_size_large,
)

private val voiceEngineOptions = listOf(
    AppSettingsStore.VoiceEngine.SYSTEM to R.string.kb_voice_engine_system,
)

private fun pageDepth(page: SettingsPage): Int = when (page) {
    SettingsPage.Root -> 0
    SettingsPage.KeyboardLangs, SettingsPage.KeyboardApps, SettingsPage.Panic, SettingsPage.AppCode,
    SettingsPage.HowToKeys, SettingsPage.HowToSetup -> 2
    SettingsPage.KeyboardAppsPick -> 3
    else -> 1
}

private fun parentPage(page: SettingsPage): SettingsPage = when (page) {
    SettingsPage.KeyboardLangs, SettingsPage.KeyboardApps -> SettingsPage.Keyboard
    SettingsPage.KeyboardAppsPick -> SettingsPage.KeyboardApps
    SettingsPage.Panic, SettingsPage.AppCode -> SettingsPage.Privacy
    SettingsPage.HowToKeys, SettingsPage.HowToSetup -> SettingsPage.HowTo
    else -> SettingsPage.Root
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, homeSignal: Int = 0, onShieldChanged: () -> Unit) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    LaunchedEffect(Unit) {
        if (page == SettingsPage.Panic || page == SettingsPage.AppCode) page = SettingsPage.Privacy
    }
    var seenHomeSignal by remember { mutableIntStateOf(homeSignal) }
    LaunchedEffect(homeSignal) {
        if (homeSignal != seenHomeSignal) {
            seenHomeSignal = homeSignal
            page = SettingsPage.Root
        }
    }
    var panicSet by remember { mutableStateOf(false) }
    var appCodeSet by remember { mutableStateOf(false) }
    var codesLoaded by remember { mutableStateOf(false) }
    var canSystemLock by remember { mutableStateOf<Boolean?>(null) }
    var lockState by remember { mutableStateOf(AppLock.lockState()) }
    var donateFromDev by rememberSaveable { mutableStateOf(false) }
    val lockScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.Default) {
            Triple(
                AppSettingsStore.hasPanicPassword,
                AppSettingsStore.hasAppCode,
                AppLock.canUseLock(context),
            )
        }
        panicSet = loaded.first
        appCodeSet = loaded.second
        canSystemLock = loaded.third
        codesLoaded = true
    }
    LaunchedEffect(codesLoaded, canSystemLock, appCodeSet) {
        if (!codesLoaded || canSystemLock == null) return@LaunchedEffect
        lockState = withContext(Dispatchers.Default + NonCancellable) {
            AppLock.syncLockState(context, appCodeSet)
        }
    }
    val lockReady = (canSystemLock ?: false) || appCodeSet
    val lockControlsReady = codesLoaded && canSystemLock != null
    val onLockEnabled: (Boolean) -> Unit = { on ->
        lockState = lockState.copy(enabled = on)
        lockScope.launch {
            lockState = withContext(Dispatchers.Default + NonCancellable) {
                AppLock.setLockEnabled(context, on, appCodeSet)
            }
        }
    }
    val onLockCodeOnly: (Boolean) -> Unit = { only ->
        lockState = lockState.copy(codeOnly = only)
        lockScope.launch {
            lockState = withContext(Dispatchers.Default + NonCancellable) {
                AppLock.setLockCodeOnly(context, only, appCodeSet)
            }
        }
    }
    BackHandler(enabled = page != SettingsPage.Root) {
        page = if (page == SettingsPage.Donate && donateFromDev) SettingsPage.Developer else parentPage(page)
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val depth = { p: SettingsPage ->
                if (p == SettingsPage.Donate && donateFromDev) 2 else pageDepth(p)
            }
            if (depth(targetState) > depth(initialState)) {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 3 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "settings-nav",
    ) { current ->
        val back = { page = SettingsPage.Root }
        when (current) {
            SettingsPage.Root -> RootSettings(modifier) {
                if (it == SettingsPage.Donate) donateFromDev = false
                page = it
            }
            SettingsPage.Interface -> InterfaceSettings(modifier, back)
            SettingsPage.Privacy -> PrivacySettings(
                modifier, onShieldChanged, back,
                appCodeSet = appCodeSet,
                panicSet = panicSet,
                canSystemLock = canSystemLock == true,
                lockState = lockState,
                lockReady = lockReady,
                lockControlsReady = lockControlsReady,
                onLockEnabled = onLockEnabled,
                onLockCodeOnly = onLockCodeOnly,
                openAppCode = { page = SettingsPage.AppCode },
                openPanic = { page = SettingsPage.Panic },
            )
            SettingsPage.AppCode -> CodeSettings(
                modifier,
                title = stringResource(R.string.appcode_title),
                description = stringResource(R.string.appcode_desc),
                newLabel = stringResource(R.string.appcode_new),
                repeatLabel = stringResource(R.string.appcode_repeat),
                saveLabel = stringResource(R.string.appcode_save),
                setHeader = stringResource(R.string.appcode_set_header),
                changeHeader = stringResource(R.string.appcode_change_header),
                setFooter = stringResource(R.string.appcode_set_footer),
                removeLabel = stringResource(R.string.appcode_remove),
                removeConfirm = stringResource(R.string.appcode_remove_confirm),
                savedMessage = stringResource(R.string.appcode_saved),
                removedMessage = stringResource(R.string.appcode_removed),
                isSet = appCodeSet,
                lockEnabled = lockState.enabled,
                lockReady = lockReady,
                lockControlsReady = lockControlsReady,
                onLockEnabled = onLockEnabled,
                onSave = { AppSettingsStore.setAppCode(it) },
                onRemove = { AppSettingsStore.clearAppCode() },
                onChanged = { appCodeSet = it },
                onBack = { page = SettingsPage.Privacy },
            )
            SettingsPage.Panic -> CodeSettings(
                modifier,
                title = stringResource(R.string.panic_title),
                description = stringResource(R.string.panic_desc),
                newLabel = stringResource(R.string.panic_new),
                repeatLabel = stringResource(R.string.panic_repeat),
                saveLabel = stringResource(R.string.panic_save),
                setHeader = stringResource(R.string.panic_set_header),
                changeHeader = stringResource(R.string.panic_change_header),
                setFooter = stringResource(R.string.panic_set_footer),
                removeLabel = stringResource(R.string.panic_remove),
                removeConfirm = stringResource(R.string.panic_remove_confirm),
                savedMessage = stringResource(R.string.panic_saved),
                removedMessage = stringResource(R.string.panic_removed),
                isSet = panicSet,
                lockEnabled = lockState.enabled,
                lockReady = lockReady,
                lockControlsReady = lockControlsReady,
                onLockEnabled = onLockEnabled,
                onSave = { AppSettingsStore.setPanicPassword(it) },
                onRemove = { AppSettingsStore.clearPanicPassword() },
                onChanged = { panicSet = it },
                onBack = { page = SettingsPage.Privacy },
            )
            SettingsPage.Stego -> StegoSettings(modifier, back)
            SettingsPage.Keyboard -> KeyboardSettings(
                modifier, back,
                openLanguages = { page = SettingsPage.KeyboardLangs },
                openComposeApps = { page = SettingsPage.KeyboardApps },
            )
            SettingsPage.KeyboardLangs -> KeyboardLanguagesSettings(modifier, onBack = { page = SettingsPage.Keyboard })
            SettingsPage.KeyboardApps -> ComposeAppsSettings(
                modifier,
                onBack = { page = SettingsPage.Keyboard },
                onAdd = { page = SettingsPage.KeyboardAppsPick },
            )
            SettingsPage.KeyboardAppsPick -> ComposeAppsPicker(modifier) { page = SettingsPage.KeyboardApps }
            SettingsPage.KeyBackup -> KeyBackupSettings(modifier, back)
            SettingsPage.Donate -> DonateSettings(
                modifier,
                backLabel = stringResource(if (donateFromDev) R.string.about_dev else R.string.tab_settings),
                onBack = { page = if (donateFromDev) SettingsPage.Developer else SettingsPage.Root },
            )
            SettingsPage.HowTo -> HowToSettings(
                modifier,
                backLabel = stringResource(R.string.tab_settings),
                onBack = back,
                openKeys = { page = SettingsPage.HowToKeys },
                openSetup = { page = SettingsPage.HowToSetup },
            )
            SettingsPage.HowToKeys -> HowToKeysSettings(modifier) { page = SettingsPage.HowTo }
            SettingsPage.HowToSetup -> HowToSetupSettings(modifier) { page = SettingsPage.HowTo }
            SettingsPage.Faq -> FaqSettings(modifier, back)
            SettingsPage.About -> AboutSettings(modifier, back)
            SettingsPage.Developer -> DeveloperSettings(modifier, back) {
                donateFromDev = true
                page = SettingsPage.Donate
            }
            SettingsPage.Danger -> DangerSettings(modifier, back)
        }
    }
}

@Composable
private fun RootSettings(modifier: Modifier, open: (SettingsPage) -> Unit) {
    KScreen(stringResource(R.string.tab_settings), modifier) {
        GlassCard(padding = 14.dp, spacing = 2.dp) {
            SettingsTile(stringResource(R.string.settings_interface), Icons.Default.Palette) {
                open(SettingsPage.Interface)
            }
            CardDivider()
            SettingsTile(stringResource(R.string.settings_privacy), Icons.Default.Shield) {
                open(SettingsPage.Privacy)
            }
            CardDivider()
            SettingsTile(stringResource(R.string.set_section_stego), Icons.AutoMirrored.Filled.Notes) {
                open(SettingsPage.Stego)
            }
            CardDivider()
            SettingsTile(stringResource(R.string.settings_keyboard), Icons.Default.Keyboard) {
                open(SettingsPage.Keyboard)
            }
            CardDivider()
            SettingsTile(stringResource(R.string.backup_title), Icons.Default.SwapVert) {
                open(SettingsPage.KeyBackup)
            }
        }

        GlassCard(padding = 14.dp, spacing = 2.dp) {
            SettingsTile(
                stringResource(R.string.donate_title), Icons.Default.Favorite,
                tile = DONATE_TILE,
            ) { open(SettingsPage.Donate) }
        }

        GlassCard(padding = 14.dp, spacing = 2.dp) {
            SettingsTile(
                stringResource(R.string.help_howto), Icons.AutoMirrored.Filled.MenuBook,
                tile = Color(0xFF8E8E93),
            ) { open(SettingsPage.HowTo) }
            CardDivider()
            SettingsTile(
                stringResource(R.string.help_faq), Icons.AutoMirrored.Filled.HelpOutline,
                tile = Color(0xFF8E8E93),
            ) { open(SettingsPage.Faq) }
            CardDivider()
            SettingsTile(
                stringResource(R.string.settings_about_row), Icons.Default.Info,
                tile = Color(0xFF8E8E93),
            ) { open(SettingsPage.About) }
            CardDivider()
            SettingsTile(
                stringResource(R.string.about_dev), Icons.Default.AccountCircle,
                tile = Color(0xFF8E8E93),
            ) { open(SettingsPage.Developer) }
        }

        GlassCard(padding = 14.dp, spacing = 2.dp) {
            SettingsTile(
                stringResource(R.string.settings_erase_row), Icons.Default.DeleteForever,
                tile = K.danger, textColor = K.danger,
            ) { open(SettingsPage.Danger) }
        }
        Spacer(Modifier.height(8.dp))

    }
}

@Composable
private fun InterfaceSettings(modifier: Modifier, onBack: () -> Unit) {
    val ui by AppSettingsStore.ui.collectAsState()
    val context = LocalContext.current
    var kbShield by remember { mutableStateOf(AppSettingsStore.keyboardShield) }

    KScreen(
        stringResource(R.string.settings_interface), modifier,
        backLabel = stringResource(R.string.tab_settings), onBack = onBack,
    ) {
        SectionHeader(stringResource(R.string.ui_appearance))
        GlassCard(spacing = 4.dp) {
            val themes = listOf(
                "auto" to R.string.ui_auto, "light" to R.string.ui_light, "dark" to R.string.ui_dark,
            )
            MenuRow(
                stringResource(R.string.ui_theme),
                options = themes.map { stringResource(it.second) },
                selected = themes.indexOfFirst { it.first == ui.theme }.coerceAtLeast(0),
                onPick = { AppSettingsStore.uiTheme = themes[it].first },
            )
            CardDivider()
            val langs = listOf(
                "auto" to R.string.ui_auto, "en" to R.string.lang_en,
                "ru" to R.string.lang_ru, "de" to R.string.lang_de,
                "zh" to R.string.lang_zh, "fa" to R.string.lang_fa,
            )
            MenuRow(
                stringResource(R.string.ui_language),
                options = langs.map { stringResource(it.second) },
                selected = langs.indexOfFirst { it.first == ui.language }.coerceAtLeast(0),
                onPick = {
                    if (langs[it].first != ui.language) {
                        AppSettingsStore.uiLanguage = langs[it].first
                        (context as? android.app.Activity)?.recreate()
                    }
                },
            )
        }
        FooterText(stringResource(R.string.ui_appearance_footer))

        SectionHeader(stringResource(R.string.ui_tabs))
        GlassCard(spacing = 4.dp) {
            val hideable = AppSettingsStore.AppTab.entries.filter { it.canHide }
            hideable.forEachIndexed { i, tab ->
                if (i > 0) CardDivider()
                val on = tab !in ui.hiddenTabs
                val last = on && ui.visibleTabs.count { it.canHide } <= 1
                ToggleRow(
                    stringResource(tabSpecOf(tab)),
                    on,
                    enabled = !last,
                    onChange = { AppSettingsStore.setTabHidden(tab, !it) },
                )
            }
        }
        FooterText(stringResource(R.string.ui_tabs_footer))

        SectionHeader(stringResource(R.string.settings_keyboard))
        GlassCard(spacing = 4.dp) {
            ToggleRow(stringResource(R.string.settings_kb_shield), kbShield, onChange = {
                kbShield = it; AppSettingsStore.keyboardShield = it
            })
        }
        FooterText(stringResource(R.string.ui_keyboard_footer))
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DangerSettings(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<DangerAction?>(null) }

    KScreen(
        stringResource(R.string.settings_danger), modifier,
        backLabel = stringResource(R.string.tab_settings), onBack = onBack,
    ) {
        GlassCard(padding = 14.dp, spacing = 2.dp) {
            SettingsTile(
                stringResource(R.string.settings_wipe_chats), Icons.Default.Delete,
                tile = K.danger, textColor = K.danger,
            ) { pending = pending.toggled(DangerAction.CHATS) }
            DangerConfirm(
                visible = pending == DangerAction.CHATS,
                warning = stringResource(R.string.confirm_wipe_chats_text),
                label = stringResource(R.string.settings_wipe_chats),
            ) {
                pending = null
                scope.launch(Dispatchers.Default + NonCancellable) { SignalService.wipeAllChats() }
            }
            CardDivider()
            SettingsTile(
                stringResource(R.string.settings_wipe_contacts), Icons.Default.DeleteSweep,
                tile = K.danger, textColor = K.danger,
            ) { pending = pending.toggled(DangerAction.CONTACTS) }
            DangerConfirm(
                visible = pending == DangerAction.CONTACTS,
                warning = stringResource(R.string.confirm_wipe_contacts_text),
                label = stringResource(R.string.settings_wipe_contacts),
            ) {
                pending = null
                scope.launch(Dispatchers.Default + NonCancellable) { SignalService.wipeContactsAndChats() }
            }
            CardDivider()
            SettingsTile(
                stringResource(R.string.settings_wipe_all), Icons.Default.DeleteForever,
                tile = K.danger, textColor = K.danger,
            ) { pending = pending.toggled(DangerAction.ALL) }
            DangerConfirm(
                visible = pending == DangerAction.ALL,
                warning = stringResource(R.string.wipe_all_warning),
                label = stringResource(R.string.settings_wipe_all),
            ) {
                pending = null
                scope.launch(Dispatchers.Default + NonCancellable) {
                    com.kryptos.android.security.DataWipe.wipe(context)
                }
            }
        }
        FooterText(stringResource(R.string.settings_danger_footer))
        Spacer(Modifier.height(8.dp))
    }
}

private enum class DangerAction { CHATS, CONTACTS, ALL }

private fun DangerAction?.toggled(target: DangerAction): DangerAction? =
    if (this == target) null else target

@Composable
private fun DangerConfirm(visible: Boolean, warning: String, label: String, onConfirm: () -> Unit) {
    if (!visible) return
    Spacer(Modifier.height(6.dp))
    Banner(warning, BannerKind.Error)
    Spacer(Modifier.height(4.dp))
    SecondaryButton(label, Modifier.fillMaxWidth(), danger = true, onClick = onConfirm)
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun SettingsTile(
    title: String,
    icon: ImageVector,
    tile: Color = K.accent,
    textColor: Color = K.textPrimary,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .quietClickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(17.dp), tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 16.sp, color = textColor, modifier = Modifier.weight(1f))
        Text("›", fontSize = 17.sp, color = K.textSecondary)
    }
}

@Composable
private fun PrivacySettings(
    modifier: Modifier,
    onShieldChanged: () -> Unit,
    onBack: () -> Unit,
    appCodeSet: Boolean,
    panicSet: Boolean,
    canSystemLock: Boolean,
    lockState: AppLock.LockState,
    lockReady: Boolean,
    lockControlsReady: Boolean,
    onLockEnabled: (Boolean) -> Unit,
    onLockCodeOnly: (Boolean) -> Unit,
    openAppCode: () -> Unit,
    openPanic: () -> Unit,
) {
    val context = LocalContext.current

    var autoLock by remember { mutableStateOf(AppSettingsStore.autoLockGraceSeconds) }
    var shield by remember { mutableStateOf(AppSettingsStore.privacyShield) }
    var integrity by remember { mutableStateOf(AppSettingsStore.integrityWarnings) }
    var clipAuto by remember { mutableStateOf(AppSettingsStore.clipboardAutoDecrypt) }
    var clipClear by remember { mutableStateOf(AppSettingsStore.clipboardClearSeconds) }
    var clipOnDecrypt by remember { mutableStateOf(AppSettingsStore.clearClipboardOnDecrypt) }
    var lengthPad by remember { mutableStateOf(AppSettingsStore.lengthPadding) }

    KScreen(
        stringResource(R.string.settings_privacy), modifier,
        backLabel = stringResource(R.string.tab_settings), onBack = onBack,
    ) {
        val report by produceState<com.kryptos.android.security.DeviceIntegrity.Report?>(null, integrity) {
            value = if (!integrity) null else {
                withContext(Dispatchers.Default) { com.kryptos.android.security.DeviceIntegrity.check(context) }
            }
        }
        if (integrity && report != null) {
            val report = report!!
            if (report.tampered) {
                Banner(stringResource(R.string.sec_integrity_tampered), BannerKind.Error)
            }
            if (report.foreignAccessibility.isNotEmpty()) {
                Banner(
                    stringResource(
                        R.string.sec_integrity_accessibility,
                        report.foreignAccessibility.joinToString(", "),
                    ),
                    BannerKind.Warning,
                )
            }
        }

        SectionHeader(stringResource(R.string.sec_section))
        GlassCard(spacing = 4.dp) {
            ToggleRow(
                stringResource(R.string.sec_app_lock),
                lockState.enabled,
                subtitle = if (lockReady) null else stringResource(R.string.sec_app_lock_unavailable),
                enabled = lockReady && lockControlsReady,
                onChange = onLockEnabled,
            )

            if (lockState.enabled) {
                CardDivider()
                val methods = listOf(
                    false to R.string.sec_lock_method_system,
                    true to R.string.sec_lock_method_code,
                )
                MenuRow(
                    stringResource(R.string.sec_lock_method),
                    options = methods.map { stringResource(it.second) },
                    selected = methods.indexOfFirst { it.first == lockState.codeOnly }.coerceAtLeast(0),
                    onPick = { onLockCodeOnly(methods[it].first) },
                    optionEnabled = { if (methods[it].first) appCodeSet else canSystemLock },
                )
                CardDivider()
                val graceOptions = listOf(
                    0 to R.string.sec_autolock_immediate, 10 to R.string.sec_autolock_10s,
                    60 to R.string.sec_autolock_1m, 300 to R.string.sec_autolock_5m,
                )
                MenuRow(
                    stringResource(R.string.sec_autolock),
                    options = graceOptions.map { stringResource(it.second) },
                    selected = graceOptions.indexOfFirst { it.first == autoLock }.coerceAtLeast(0),
                    onPick = { autoLock = graceOptions[it].first; AppSettingsStore.autoLockGraceSeconds = graceOptions[it].first },
                )
            }

            CardDivider()
            ToggleRow(stringResource(R.string.settings_shield), shield, onChange = {
                shield = it; AppSettingsStore.privacyShield = it; onShieldChanged()
            })

            CardDivider()
            ToggleRow(stringResource(R.string.sec_integrity), integrity, onChange = {
                integrity = it; AppSettingsStore.integrityWarnings = it
            })
        }
        if (lockState.enabled) {
            when {
                !canSystemLock -> FooterText(stringResource(R.string.sec_lock_method_no_system))
                !appCodeSet -> FooterText(stringResource(R.string.sec_lock_method_no_code))
                else -> Unit
            }
        }

        SectionHeader(stringResource(R.string.codes_section))
        GlassCard(spacing = 4.dp) {
            NavRow(
                stringResource(R.string.appcode_title),
                icon = Icons.Default.Password,
                value = stringResource(if (appCodeSet) R.string.panic_status_on else R.string.panic_status_off),
                onClick = openAppCode,
            )
            CardDivider()
            NavRow(
                stringResource(R.string.panic_title),
                icon = Icons.Default.DeleteForever,
                value = stringResource(if (panicSet) R.string.panic_status_on else R.string.panic_status_off),
                onClick = openPanic,
            )
        }
        FooterText(stringResource(R.string.codes_row_footer))

        ScreenDecryptSection()

        SectionHeader(stringResource(R.string.clip_section))
        GlassCard(spacing = 4.dp) {
            ToggleRow(stringResource(R.string.settings_clip_autodecrypt), clipAuto, onChange = {
                clipAuto = it; AppSettingsStore.clipboardAutoDecrypt = it
            })

            CardDivider()
            ToggleRow(stringResource(R.string.sec_clip_on_decrypt), clipOnDecrypt, onChange = {
                clipOnDecrypt = it; AppSettingsStore.clearClipboardOnDecrypt = it
            })

            CardDivider()
            val clearOptions = listOf(
                0 to R.string.sec_clip_never, 15 to R.string.sec_clip_15s, 30 to R.string.sec_clip_30s,
                60 to R.string.sec_clip_1m, 300 to R.string.sec_clip_5m,
            )
            MenuRow(
                stringResource(R.string.sec_clip_clear),
                options = clearOptions.map { stringResource(it.second) },
                selected = clearOptions.indexOfFirst { it.first == clipClear }.coerceAtLeast(0),
                onPick = { clipClear = clearOptions[it].first; AppSettingsStore.clipboardClearSeconds = clearOptions[it].first },
            )
        }
        FooterText(stringResource(R.string.clip_footer))

        SectionHeader(stringResource(R.string.meta_section))
        GlassCard(spacing = 4.dp) {
            ToggleRow(stringResource(R.string.settings_length_padding), lengthPad, onChange = {
                lengthPad = it; AppSettingsStore.lengthPadding = it
            })
        }
        FooterText(stringResource(R.string.length_padding_footer))
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CodeSettings(
    modifier: Modifier,
    title: String,
    description: String,
    newLabel: String,
    repeatLabel: String,
    saveLabel: String,
    setHeader: String,
    changeHeader: String,
    setFooter: String,
    removeLabel: String,
    removeConfirm: String,
    savedMessage: String,
    removedMessage: String,
    isSet: Boolean,
    lockEnabled: Boolean,
    lockReady: Boolean,
    lockControlsReady: Boolean,
    onLockEnabled: (Boolean) -> Unit,
    onSave: (String) -> AppSettingsStore.CodeResult,
    onRemove: () -> Unit,
    onChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var code by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    KScreen(
        title, modifier,
        backLabel = stringResource(R.string.settings_privacy), onBack = onBack,
    ) {
        GlassCard(spacing = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 15.sp, color = K.textPrimary, modifier = Modifier.weight(1f))
                Text(
                    stringResource(if (isSet) R.string.panic_status_on else R.string.panic_status_off),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSet) K.accent else K.textSecondary,
                )
            }
            CardDivider()
            Text(description, fontSize = 13.sp, lineHeight = 18.sp, color = K.textSecondary)
        }

        GlassCard(spacing = 4.dp) {
            ToggleRow(
                stringResource(R.string.sec_app_lock),
                lockEnabled,
                subtitle = if (lockReady) null else stringResource(R.string.sec_app_lock_unavailable),
                enabled = lockReady && lockControlsReady,
                onChange = onLockEnabled,
            )
        }
        FooterText(stringResource(R.string.code_needs_lock))

        SectionHeader(if (isSet) changeHeader else setHeader)
        GlassCard(spacing = 12.dp) {
            FieldLabel(newLabel)
            KTextField(
                code,
                { code = it; message = null },
                password = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            FieldLabel(repeatLabel)
            KTextField(
                repeat,
                { repeat = it; message = null },
                password = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            PrimaryButton(
                saveLabel,
                Modifier.fillMaxWidth(),
                enabled = code.isNotEmpty() && repeat.isNotEmpty(),
                busy = busy,
            ) {
                if (code != repeat) {
                    failed = true
                    repeat = ""
                    message = context.getString(R.string.panic_mismatch)
                } else {
                    val entered = code
                    busy = true
                    message = null
                    scope.launch {
                        val result = withContext(Dispatchers.Default + NonCancellable) { onSave(entered) }
                        busy = false
                        failed = result != AppSettingsStore.CodeResult.OK
                        message = when (result) {
                            AppSettingsStore.CodeResult.OK -> {
                                code = ""
                                repeat = ""
                                onChanged(true)
                                savedMessage
                            }
                            AppSettingsStore.CodeResult.TOO_SHORT -> context.getString(R.string.panic_too_short)
                            AppSettingsStore.CodeResult.DUPLICATE -> {
                                code = ""
                                repeat = ""
                                context.getString(R.string.code_duplicate)
                            }
                            AppSettingsStore.CodeResult.FAILED -> {
                                code = ""
                                repeat = ""
                                context.getString(R.string.code_save_failed)
                            }
                        }
                    }
                }
            }
            message?.let { Banner(it, if (failed) BannerKind.Error else BannerKind.Success) }
        }
        FooterText(setFooter)

        if (isSet) {
            SecondaryButton(
                removeLabel,
                Modifier.fillMaxWidth(),
                icon = Icons.Default.Delete,
                danger = true,
                enabled = !busy,
            ) { confirmRemove = true }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = removeLabel,
            text = removeConfirm,
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.Default + NonCancellable) { onRemove() }
                    onChanged(false)
                    code = ""
                    repeat = ""
                    failed = false
                    message = removedMessage
                }
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun KeyBackupSettings(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var exportPassword by remember { mutableStateOf("") }
    var exportRepeat by remember { mutableStateOf("") }
    var pendingFile by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf<String?>(null) }
    var importPassword by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<com.kryptos.android.core.KeyArchive?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exportFailed by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importFailed by remember { mutableStateOf(false) }

    fun failExport(text: String) { exportFailed = true; exportMessage = text }
    fun failImport(text: String) { importFailed = true; importMessage = text }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingFile
        pendingFile = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO + NonCancellable) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)
                        ?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
            }
            busy = false
            if (ok) {
                exportPassword = ""; exportRepeat = ""
                exportFailed = false
                exportMessage = context.getString(R.string.backup_created)
            } else {
                failExport(context.getString(R.string.backup_write_failed))
            }
        }
    }

    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importMessage = null
        pending = null
        importPassword = ""
        importText = null
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        readBounded(stream, com.kryptos.android.core.KeyArchive.MAX_FILE_BYTES)
                            ?.let { String(it, Charsets.UTF_8) }
                    }
                }.getOrNull()
            }
            busy = false
            if (text == null) failImport(context.getString(R.string.backup_read_failed)) else importText = text
        }
    }

    KScreen(
        stringResource(R.string.backup_title), modifier,
        backLabel = stringResource(R.string.tab_settings), onBack = onBack,
    ) {
        GlassCard(spacing = 10.dp) {
            Text(
                stringResource(R.string.backup_intro),
                fontSize = 13.sp, lineHeight = 18.sp, color = K.textSecondary,
            )
        }
        Banner(stringResource(R.string.backup_warning), BannerKind.Warning)

        SectionHeader(stringResource(R.string.backup_export_header))
        GlassCard(spacing = 12.dp) {
            FieldLabel(stringResource(R.string.backup_password))
            KTextField(
                exportPassword, { exportPassword = it; exportMessage = null },
                password = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            FieldLabel(stringResource(R.string.backup_repeat))
            KTextField(
                exportRepeat, { exportRepeat = it; exportMessage = null },
                password = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            PrimaryButton(
                stringResource(R.string.backup_create),
                Modifier.fillMaxWidth(),
                enabled = exportPassword.isNotEmpty() && exportRepeat.isNotEmpty(),
                busy = busy,
            ) {
                if (exportPassword != exportRepeat) {
                    exportRepeat = ""
                    failExport(context.getString(R.string.panic_mismatch))
                } else {
                    val secret = exportPassword
                    busy = true
                    exportMessage = null
                    scope.launch {
                        val text = withContext(Dispatchers.Default) {
                            runCatching {
                                val profiles = SignalService.archivedProfiles()
                                    ?: throw com.kryptos.android.core.ArchiveException(
                                        com.kryptos.android.core.ArchiveException.Kind.WRITE_FAILED
                                    )
                                val pgpKeys = PgpService.archivedIdentities()
                                    ?: throw com.kryptos.android.core.ArchiveException(
                                        com.kryptos.android.core.ArchiveException.Kind.WRITE_FAILED
                                    )
                                com.kryptos.android.core.KeyArchive.seal(
                                    com.kryptos.android.core.KeyArchive(
                                        profiles = profiles,
                                        pgpIdentities = pgpKeys,
                                        pgpRecipients = PgpService.archivedRecipients(),
                                    ),
                                    secret,
                                )
                            }
                        }
                        busy = false
                        text.onSuccess {
                            pendingFile = it
                            saver.launchFromApp("kryptos-keys.txt")
                        }.onFailure { error ->
                            val kind = (error as? com.kryptos.android.core.ArchiveException)?.kind
                            if (kind != com.kryptos.android.core.ArchiveException.Kind.PASSWORD_TOO_SHORT) {
                                exportPassword = ""
                                exportRepeat = ""
                            }
                            failExport(
                                when (kind) {
                                    com.kryptos.android.core.ArchiveException.Kind.PASSWORD_TOO_SHORT ->
                                        context.getString(R.string.backup_too_short)
                                    com.kryptos.android.core.ArchiveException.Kind.NOTHING_TO_EXPORT ->
                                        context.getString(R.string.backup_nothing)
                                    else -> context.getString(R.string.backup_write_failed)
                                }
                            )
                        }
                    }
                }
            }
            exportMessage?.let { Banner(it, if (exportFailed) BannerKind.Error else BannerKind.Success) }
        }
        FooterText(stringResource(R.string.backup_export_footer))

        SectionHeader(stringResource(R.string.backup_import_header))
        GlassCard(spacing = 12.dp) {
            SecondaryButton(
                stringResource(R.string.backup_choose),
                Modifier.fillMaxWidth(),
                icon = Icons.Default.FolderOpen,
                enabled = !busy,
            ) { opener.launchFromApp(arrayOf("*/*")) }

            if (importText != null) {
                FieldLabel(stringResource(R.string.backup_password))
                KTextField(
                    importPassword, { importPassword = it; importMessage = null },
                    password = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                PrimaryButton(
                    stringResource(R.string.backup_restore),
                    Modifier.fillMaxWidth(),
                    enabled = importPassword.isNotEmpty(),
                    busy = busy,
                ) {
                    val text = importText ?: return@PrimaryButton
                    val secret = importPassword
                    busy = true
                    importMessage = null
                    scope.launch {
                        val archive = withContext(Dispatchers.Default) {
                            runCatching { com.kryptos.android.core.KeyArchive.open(text, secret) }
                        }
                        busy = false
                        archive.onSuccess { pending = it; confirmRestore = true }
                            .onFailure {
                                importPassword = ""
                                failImport(context.getString(R.string.backup_unreadable))
                            }
                    }
                }
            }
            importMessage?.let { Banner(it, if (importFailed) BannerKind.Error else BannerKind.Success) }
        }
        FooterText(stringResource(R.string.backup_import_footer))
        Spacer(Modifier.height(8.dp))
    }

    if (confirmRestore) {
        val archive = pending
        ConfirmDialog(
            title = stringResource(R.string.backup_confirm_title),
            text = stringResource(
                R.string.backup_confirm_text,
                archive?.profiles?.size ?: 0,
                archive?.contactCount ?: 0,
                archive?.pgpIdentities?.size ?: 0,
            ),
            confirmLabel = stringResource(R.string.backup_restore),
            onConfirm = {
                val data = archive ?: return@ConfirmDialog
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.Default + NonCancellable) {
                        val signalOk = data.profiles.isEmpty() ||
                            runCatching { SignalService.restoreProfiles(data.profiles) }.getOrDefault(false)
                        val pgpOk = runCatching {
                            PgpService.restore(data.pgpIdentities, data.pgpRecipients)
                        }.getOrDefault(false)
                        signalOk && pgpOk
                    }
                    busy = false
                    pending = null
                    importText = null
                    importPassword = ""
                    if (ok) {
                        importFailed = false
                        importMessage = context.getString(R.string.backup_restored)
                    } else {
                        failImport(context.getString(R.string.backup_restore_failed))
                    }
                }
            },
            onDismiss = { confirmRestore = false },
        )
    }
}

@Composable
private fun ScreenDecryptSection() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(AppSettingsStore.screenDecrypt) }
    var blockShots by remember { mutableStateOf(AppSettingsStore.screenDecryptSecure) }
    var serviceOn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        serviceOn = withContext(Dispatchers.Default) { ScreenDecryptService.isSystemEnabled(context) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) serviceOn = ScreenDecryptService.isSystemEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val needsService = enabled && !serviceOn
    val pausable by produceState(false, needsService) {
        value = needsService && withContext(Dispatchers.Default) { appPausingAllowed(context) }
    }

    SectionHeader(stringResource(R.string.screen_section))
    GlassCard(spacing = 4.dp) {
        ToggleRow(stringResource(R.string.screen_decrypt_toggle), enabled, onChange = {
            enabled = it
            AppSettingsStore.screenDecrypt = it
            if (it) {
                if (!serviceOn) openAccessibilitySettings(context)
            } else {
                ScreenDecryptService.turnOff()
                serviceOn = false
            }
        })
        if (enabled) {
            CardDivider()
            ToggleRow(
                stringResource(R.string.screen_block_screenshots),
                blockShots,
                subtitle = stringResource(R.string.screen_block_screenshots_desc),
                onChange = { blockShots = it; AppSettingsStore.screenDecryptSecure = it },
            )
        }
        if (needsService) {
            CardDivider()
            NavRow(
                stringResource(R.string.screen_decrypt_enable_service),
                icon = Icons.Default.Accessibility,
                onClick = { openAccessibilitySettings(context) },
            )
        }
        if (pausable) {
            CardDivider()
            NavRow(
                stringResource(R.string.screen_decrypt_keep_running),
                icon = Icons.Default.Apps,
                onClick = { openAppDetails(context) },
            )
        }
    }
    FooterText(
        stringResource(
            if (needsService) R.string.screen_decrypt_service_off else R.string.screen_decrypt_footer
        )
    )
}

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        AppLock.onOwnScreen()
    }
}

private fun openAppDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
        AppLock.onOwnScreen()
    }
}

private fun appPausingAllowed(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT >= 30 &&
        runCatching { !context.packageManager.isAutoRevokeWhitelisted }.getOrDefault(false)

private fun readBounded(stream: java.io.InputStream, limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        if (out.size() + n > limit) return null
        out.write(buf, 0, n)
    }
    return if (out.size() == 0) null else out.toByteArray()
}

@Composable
private fun StegoSettings(modifier: Modifier, onBack: () -> Unit) {
    var stegoEnabled by remember { mutableStateOf(AppSettingsStore.chatStegoEnabled) }
    var stegoLang by remember { mutableStateOf(AppSettingsStore.chatStegoLanguage) }
    var stegoMode by remember { mutableStateOf(AppSettingsStore.chatStegoMode) }

    KScreen(
        stringResource(R.string.set_section_stego), modifier,
        backLabel = stringResource(R.string.tab_settings), onBack = onBack,
    ) {
        GlassCard(spacing = 4.dp) {
            ToggleRow(
                stringResource(R.string.settings_stego),
                stegoEnabled,
                onChange = { stegoEnabled = it; AppSettingsStore.chatStegoEnabled = it },
            )
            if (stegoEnabled) {
                CardDivider()
                val langs = listOf(
                    "auto" to R.string.lang_auto, "english" to R.string.lang_en,
                    "russian" to R.string.lang_ru, "german" to R.string.lang_de,
                    "chinese" to R.string.lang_zh, "persian" to R.string.lang_fa,
                )
                MenuRow(
                    stringResource(R.string.settings_stego_lang),
                    options = langs.map { stringResource(it.second) },
                    selected = langs.indexOfFirst { it.first == stegoLang }.coerceAtLeast(0),
                    onPick = { stegoLang = langs[it].first; AppSettingsStore.chatStegoLanguage = langs[it].first },
                )
                CardDivider()
                val modes = listOf(
                    StegoMode.WORDS to R.string.stego_mode_words,
                    StegoMode.SMART to R.string.stego_mode_smart,
                    StegoMode.LETTERS to R.string.stego_mode_letters,
                )
                MenuRow(
                    stringResource(R.string.settings_stego_mode),
                    options = modes.map { stringResource(it.second) },
                    selected = modes.indexOfFirst { it.first == stegoMode }.coerceAtLeast(0),
                    onPick = { stegoMode = modes[it].first; AppSettingsStore.chatStegoMode = stegoMode },
                )
            }
        }
        FooterText(stringResource(R.string.settings_stego_desc))

        if (stegoEnabled) {
            SectionHeader(stringResource(R.string.stego_example_header))
            GlassCard {
                val sample by produceState("", stegoLang, stegoMode) {
                    value = withContext(Dispatchers.Default) { stegoSample(stegoLang, stegoMode) }
                }
                Text(
                    sample,
                    fontSize = 13.sp, lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    color = K.textPrimary,
                )
            }
            FooterText(
                stringResource(
                    when (stegoMode) {
                        StegoMode.WORDS -> R.string.stego_example_footer
                        StegoMode.SMART -> R.string.stego_smart_footer
                        StegoMode.LETTERS ->
                            if (stegoLanguageOf(stegoLang).isHan) R.string.stego_letters_footer_han
                            else R.string.stego_letters_footer
                    },
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun stegoLanguageOf(langKey: String): StegoLanguage = when (langKey) {
    "english" -> StegoLanguage.ENGLISH
    "russian" -> StegoLanguage.RUSSIAN
    "german" -> StegoLanguage.GERMAN
    "chinese" -> StegoLanguage.CHINESE
    "persian" -> StegoLanguage.PERSIAN
    else -> StegoLanguage.forSystem()
}

private fun stegoSample(langKey: String, mode: StegoMode): String {
    val bytes = byteArrayOf(
        0x03, 0x02, 0x41, 0x9C.toByte(), 0x2A, 0xF7.toByte(),
        0x10, 0x88.toByte(), 0x3D, 0x6B, 0xE0.toByte(), 0x54,
    )
    val language = stegoLanguageOf(langKey)
    return when (mode) {
        StegoMode.WORDS -> TextStego.encode(bytes, language).orEmpty()
        StegoMode.SMART -> SmartTextStego.encode(bytes, language).orEmpty()
        StegoMode.LETTERS -> LetterStego.encode(bytes, language).orEmpty()
    }
}

@Composable
private fun KeyboardSettings(
    modifier: Modifier,
    onBack: () -> Unit,
    openLanguages: () -> Unit,
    openComposeApps: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val forgetScope = rememberCoroutineScope()
    var serviceOn by remember { mutableStateOf(false) }
    var voiceSupported by remember { mutableStateOf(true) }
    var micGranted by remember { mutableStateOf(VoiceInput.hasPermission(context)) }
    var micBlocked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        serviceOn = withContext(Dispatchers.Default) { ScreenDecryptService.isSystemEnabled(context) }
        voiceSupported = withContext(Dispatchers.Default) { VoiceInput.isSupported(context) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceOn = ScreenDecryptService.isSystemEnabled(context)
                micGranted = VoiceInput.hasPermission(context)
                if (micGranted) micBlocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var kbAutoDecrypt by remember { mutableStateOf(AppSettingsStore.keyboardAutoDecrypt) }
    var kbSendAfter by remember { mutableStateOf(AppSettingsStore.keyboardSendAfterEncrypt) }
    var kbHaptics by remember { mutableStateOf(AppSettingsStore.keyboardHaptics) }
    var kbSounds by remember { mutableStateOf(AppSettingsStore.keyboardSounds) }
    var kbCompose by remember { mutableStateOf(AppSettingsStore.keyboardCompose) }
    var kbComposeToggle by remember { mutableStateOf(AppSettingsStore.keyboardComposeToggle) }
    var kbFieldSize by remember { mutableStateOf(AppSettingsStore.keyboardFieldSize) }
    val kbComposeAuto = AppSettingsStore.keyboardComposeAuto
    var secureKb by remember { mutableStateOf(AppSettingsStore.secureKeyboard) }
    var kbSuggestions by remember { mutableStateOf(AppSettingsStore.keyboardSuggestions) }
    var kbAutocorrect by remember { mutableStateOf(AppSettingsStore.keyboardAutocorrect) }
    var kbEmoji by remember { mutableStateOf(AppSettingsStore.keyboardEmoji) }
    var kbPunct by remember { mutableStateOf(AppSettingsStore.keyboardPunctKey) }
    var kbPunctDouble by remember { mutableStateOf(AppSettingsStore.keyboardPunctDouble) }
    var kbVoice by remember { mutableStateOf(AppSettingsStore.keyboardVoice) }
    var kbVoiceEngine by remember { mutableStateOf(AppSettingsStore.keyboardVoiceEngine) }
    var confirmForget by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        micBlocked = !granted
        kbVoice = granted
        AppSettingsStore.keyboardVoice = granted
    }
    val askMic = {
        if (micBlocked) openAppSettings(context) else micLauncher.launchFromApp(Manifest.permission.RECORD_AUDIO)
    }
    val langSummary = kbLanguageCatalog
        .filter { AppSettingsStore.keyboardLangEnabled(it.first) }
        .map { stringResource(it.second) }
        .joinToString(", ")

    KScreen(
        stringResource(R.string.settings_keyboard), modifier,
        backLabel = stringResource(R.string.tab_settings), onBack = onBack,
    ) {
        GlassCard(spacing = 4.dp) {
            NavRow(
                stringResource(R.string.settings_enable_keyboard),
                icon = Icons.Default.Keyboard,
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        AppLock.onOwnScreen()
                    }
                },
            )
            CardDivider()
            NavRow(
                stringResource(R.string.kb_languages),
                icon = Icons.Default.Language,
                value = langSummary,
                onClick = openLanguages,
            )
            CardDivider()
            ToggleRow(stringResource(R.string.settings_kb_autodecrypt), kbAutoDecrypt, onChange = {
                kbAutoDecrypt = it; AppSettingsStore.keyboardAutoDecrypt = it
            })
        }
        FooterText(stringResource(R.string.kb_autodecrypt_footer))

        val sendNeedsService = kbSendAfter && !serviceOn
        GlassCard(spacing = 4.dp) {
            ToggleRow(stringResource(R.string.settings_kb_sendafter), kbSendAfter, onChange = {
                kbSendAfter = it
                AppSettingsStore.keyboardSendAfterEncrypt = it
                if (it && !serviceOn) openAccessibilitySettings(context)
            })
            if (sendNeedsService) {
                CardDivider()
                NavRow(
                    stringResource(R.string.screen_decrypt_enable_service),
                    icon = Icons.Default.Accessibility,
                    onClick = { openAccessibilitySettings(context) },
                )
            }
        }
        FooterText(
            stringResource(
                if (sendNeedsService) R.string.settings_kb_sendafter_service
                else R.string.settings_kb_sendafter_desc
            )
        )

        SectionHeader(stringResource(R.string.kb_typing))
        GlassCard(spacing = 4.dp) {
            ToggleRow(stringResource(R.string.settings_kb_suggestions), kbSuggestions, onChange = {
                kbSuggestions = it; AppSettingsStore.keyboardSuggestions = it
            })
            CardDivider()
            ToggleRow(stringResource(R.string.settings_kb_autocorrect), kbAutocorrect, onChange = {
                kbAutocorrect = it; AppSettingsStore.keyboardAutocorrect = it
            })
            CardDivider()
            ToggleRow(stringResource(R.string.settings_kb_emoji), kbEmoji, onChange = {
                kbEmoji = it; AppSettingsStore.keyboardEmoji = it
            })
            CardDivider()
            ToggleRow(stringResource(R.string.settings_kb_punct), kbPunct, onChange = {
                kbPunct = it; AppSettingsStore.keyboardPunctKey = it
            })
            CardDivider()
            ToggleRow(
                stringResource(R.string.settings_kb_punct_double), kbPunctDouble,
                onChange = { kbPunctDouble = it; AppSettingsStore.keyboardPunctDouble = it },
                enabled = kbPunct,
            )
            CardDivider()
            NavRow(stringResource(R.string.kb_forget_words), onClick = { confirmForget = true })
        }
        FooterText(stringResource(R.string.settings_kb_suggestions_desc))

        if (confirmForget) {
            ConfirmDialog(
                title = stringResource(R.string.confirm_forget_words_title),
                text = stringResource(R.string.confirm_forget_words_text),
                confirmLabel = stringResource(R.string.kb_forget_words),
                onConfirm = {
                    forgetScope.launch(Dispatchers.Default + NonCancellable) {
                        com.kryptos.android.keyboard.TypingMemory.forgetAll()
                    }
                },
                onDismiss = { confirmForget = false },
            )
        }

        SectionHeader(stringResource(R.string.kb_voice_section))
        GlassCard(spacing = 4.dp) {
            ToggleRow(
                stringResource(R.string.settings_kb_voice),
                kbVoice,
                enabled = voiceSupported,
                onChange = { on ->
                    when {
                        !on -> { kbVoice = false; AppSettingsStore.keyboardVoice = false }
                        micGranted -> { kbVoice = true; AppSettingsStore.keyboardVoice = true }
                        else -> askMic()
                    }
                },
            )
            CardDivider()
            MenuRow(
                stringResource(R.string.settings_kb_voice_engine),
                voiceEngineOptions.map { stringResource(it.second) },
                voiceEngineOptions.indexOfFirst { it.first == kbVoiceEngine }.coerceAtLeast(0),
                onPick = { i ->
                    kbVoiceEngine = voiceEngineOptions[i].first
                    AppSettingsStore.keyboardVoiceEngine = kbVoiceEngine
                },
            )
            if (kbVoice && !micGranted) {
                CardDivider()
                NavRow(
                    stringResource(R.string.settings_kb_voice_permission),
                    icon = Icons.Default.Mic,
                    onClick = { askMic() },
                )
            }
        }
        FooterText(
            stringResource(
                when {
                    !voiceSupported -> R.string.settings_kb_voice_unsupported
                    kbVoice -> R.string.settings_kb_voice_download_footer
                    else -> R.string.settings_kb_voice_footer
                }
            )
        )

        SectionHeader(stringResource(R.string.kb_feedback))
        GlassCard(spacing = 4.dp) {
            ToggleRow(stringResource(R.string.settings_kb_haptics), kbHaptics, onChange = {
                kbHaptics = it; AppSettingsStore.keyboardHaptics = it
            })
            CardDivider()
            ToggleRow(stringResource(R.string.settings_kb_sounds), kbSounds, onChange = {
                kbSounds = it; AppSettingsStore.keyboardSounds = it
            })
        }
        FooterText(stringResource(R.string.kb_feedback_footer))

        SectionHeader(stringResource(R.string.kb_field_section))
        GlassCard(spacing = 4.dp) {
            ToggleRow(stringResource(R.string.settings_kb_compose), kbCompose, onChange = {
                kbCompose = it; AppSettingsStore.keyboardCompose = it
            })
            CardDivider()
            ToggleRow(stringResource(R.string.settings_kb_compose_toggle), kbComposeToggle, onChange = {
                kbComposeToggle = it; AppSettingsStore.keyboardComposeToggle = it
            })
            CardDivider()
            MenuRow(
                stringResource(R.string.settings_kb_fieldsize),
                fieldSizeOptions.map { stringResource(it.second) },
                fieldSizeOptions.indexOfFirst { it.first == kbFieldSize }.coerceAtLeast(0),
                onPick = { i ->
                    kbFieldSize = fieldSizeOptions[i].first
                    AppSettingsStore.keyboardFieldSize = kbFieldSize
                },
            )
            CardDivider()
            NavRow(
                stringResource(R.string.settings_kb_composeapps),
                icon = Icons.Default.Apps,
                value = if (kbComposeAuto) null else stringResource(R.string.off),
                onClick = openComposeApps,
            )
        }
        FooterText(stringResource(R.string.settings_kb_compose_desc))

        SectionHeader(stringResource(R.string.sec_section))
        GlassCard(spacing = 4.dp) {
            ToggleRow(stringResource(R.string.sec_secure_keyboard), secureKb, onChange = {
                secureKb = it; AppSettingsStore.secureKeyboard = it
            })
        }
        Spacer(Modifier.height(8.dp))
    }
}


private class AppEntry(val pkg: String, val label: String)

private fun appOrder(): Comparator<AppEntry> {
    val collator = java.text.Collator.getInstance()
    return Comparator { a, b -> collator.compare(a.label, b.label) }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        AppLock.onOwnScreen()
    }
}

private fun launchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedWith(appOrder())
    }.getOrDefault(emptyList())
}

private fun resolveApps(context: Context, packages: Set<String>): List<AppEntry> {
    val pm = context.packageManager
    return packages.mapNotNull { pkg ->
        runCatching { AppEntry(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()) }.getOrNull()
    }.sortedWith(appOrder())
}

@Composable
private fun AppIcon(pkg: String, size: Dp = 34.dp) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val pixels = with(density) { size.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, pkg, pixels) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(pkg)
                val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, pixels, pixels)
                drawable.draw(AndroidCanvas(bitmap))
                bitmap.asImageBitmap()
            }.getOrNull()
        }
    }
    val painted = icon
    if (painted != null) {
        Image(painted, null, Modifier.size(size).clip(RoundedCornerShape(8.dp)))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(K.hairline))
    }
}

@Composable
private fun ComposeAppsSettings(modifier: Modifier, onBack: () -> Unit, onAdd: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(AppSettingsStore.keyboardComposeAuto) }
    var packages by remember { mutableStateOf(AppSettingsStore.composeAutoApps()) }
    var entries by remember { mutableStateOf<List<AppEntry>?>(null) }

    LaunchedEffect(packages) {
        val resolved = withContext(Dispatchers.IO) { resolveApps(context, packages) }
        val alive = resolved.map { it.pkg }.toSet()
        if (alive != packages) {
            withContext(Dispatchers.IO) { AppSettingsStore.setComposeAutoApps(alive) }
            packages = alive
        }
        entries = resolved
    }

    KScreen(
        stringResource(R.string.settings_kb_composeapps), modifier,
        backLabel = stringResource(R.string.settings_keyboard), onBack = onBack,
    ) {
        GlassCard(spacing = 4.dp) {
            ToggleRow(stringResource(R.string.settings_kb_composeapps_enable), enabled, onChange = {
                enabled = it
                AppSettingsStore.keyboardComposeAuto = it
            })
        }
        FooterText(stringResource(R.string.settings_kb_composeapps_footer))

        if (enabled) {
            val list = entries
            GlassCard(spacing = 4.dp) {
                if (list.isNullOrEmpty()) {
                    Text(
                        stringResource(R.string.settings_kb_composeapps_empty),
                        fontSize = 14.sp, color = K.textSecondary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    list.forEachIndexed { i, entry ->
                        if (i > 0) CardDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(entry.pkg)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                entry.label,
                                fontSize = 15.sp, color = K.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            GlassIconButton(
                                Icons.Default.Close,
                                stringResource(R.string.delete),
                                size = 34.dp, tint = K.danger,
                            ) {
                                val next = packages - entry.pkg
                                packages = next
                                scope.launch(Dispatchers.IO + NonCancellable) {
                                    AppSettingsStore.setComposeAutoApps(next)
                                }
                            }
                        }
                    }
                }
            }
            SecondaryButton(
                stringResource(R.string.settings_kb_composeapps_add),
                Modifier.fillMaxWidth(),
                icon = Icons.Default.Add,
                onClick = onAdd,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ComposeAppsPicker(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var selected by remember { mutableStateOf(AppSettingsStore.composeAutoApps()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { launchableApps(context) }
    }

    KLazyScreen(
        stringResource(R.string.settings_kb_composeapps_add), modifier,
        backLabel = stringResource(R.string.settings_kb_composeapps), onBack = onBack,
    ) {
        val list = apps
        if (list == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = K.accent, strokeWidth = 2.dp)
                }
            }
        } else {
            items(list, key = { it.pkg }) { entry ->
                val checked = entry.pkg in selected
                GlassCard(spacing = 0.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .quietClickable {
                                val next = if (checked) selected - entry.pkg else selected + entry.pkg
                                selected = next
                                scope.launch(Dispatchers.IO + NonCancellable) {
                                    AppSettingsStore.setComposeAutoApps(next)
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(entry.pkg)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            entry.label,
                            fontSize = 15.sp, color = K.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (checked) {
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.Default.Check, null, Modifier.size(20.dp), tint = K.accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyboardLanguagesSettings(modifier: Modifier, onBack: () -> Unit) {
    var active by remember {
        mutableStateOf(
            kbLanguageCatalog.map { it.first }.filter { AppSettingsStore.keyboardLangEnabled(it) }.toSet(),
        )
    }

    KScreen(
        stringResource(R.string.kb_languages), modifier,
        backLabel = stringResource(R.string.settings_keyboard), onBack = onBack,
    ) {
        GlassCard(spacing = 4.dp) {
            kbLanguageCatalog.forEachIndexed { i, (code, nameRes) ->
                if (i > 0) CardDivider()
                val on = code in active
                ToggleRow(
                    stringResource(nameRes), on,
                    enabled = !(on && active.size == 1),
                    onChange = { v ->
                        active = if (v) active + code else active - code
                        AppSettingsStore.setKeyboardLang(code, v)
                    },
                )
            }
        }
        FooterText(stringResource(R.string.kb_languages_footer))
        Spacer(Modifier.height(8.dp))
    }
}

private val howToSteps = listOf(
    Triple(R.string.howto_s1_title, R.string.howto_s1_text, R.drawable.howto_1),
    Triple(R.string.howto_s2_title, R.string.howto_s2_text, R.drawable.howto_2),
    Triple(R.string.howto_s3_title, R.string.howto_s3_text, R.drawable.howto_3),
    Triple(R.string.howto_s4_title, R.string.howto_s4_text, R.drawable.howto_4),
)

private val howToSetupSteps = listOf(
    Triple(R.string.howto_setup_s1_title, R.string.howto_setup_s1_text, R.drawable.howto_setup_1),
    Triple(R.string.howto_setup_s2_title, R.string.howto_setup_s2_text, R.drawable.howto_setup_2),
    Triple(R.string.howto_setup_s3_title, R.string.howto_setup_s3_text, R.drawable.howto_setup_3),
    Triple(R.string.howto_setup_s4_title, R.string.howto_setup_s4_text, R.drawable.howto_setup_4),
    Triple(R.string.howto_setup_s5_title, R.string.howto_setup_s5_text, R.drawable.howto_setup_5),
    Triple(R.string.howto_setup_s6_title, R.string.howto_setup_s6_text, R.drawable.howto_setup_6),
    Triple(R.string.howto_setup_s7_title, R.string.howto_setup_s7_text, R.drawable.howto_setup_7),
)

private val howToTopics = listOf(
    R.string.howto_chat_title to R.string.howto_chat_text,
    R.string.howto_kbd_title to R.string.howto_kbd_text,
    R.string.howto_pwd_title to R.string.howto_pwd_text,
    R.string.howto_photo_title to R.string.howto_photo_text,
    R.string.howto_screen_title to R.string.howto_screen_text,
    R.string.howto_pgp_title to R.string.howto_pgp_text,
)

private val howToNotes = listOf(
    R.string.howto_note1, R.string.howto_note2, R.string.howto_note3, R.string.howto_note4,
)

private val faqItems = listOf(
    R.string.faq_q13 to R.string.faq_a13,
    R.string.faq_q1 to R.string.faq_a1,
    R.string.faq_q2 to R.string.faq_a2,
    R.string.faq_q14 to R.string.faq_a14,
    R.string.faq_q3 to R.string.faq_a3,
    R.string.faq_q22 to R.string.faq_a22,
    R.string.faq_q20 to R.string.faq_a20,
    R.string.faq_q21 to R.string.faq_a21,
    R.string.faq_q4 to R.string.faq_a4,
    R.string.faq_q5 to R.string.faq_a5,
    R.string.faq_q23 to R.string.faq_a23,
    R.string.faq_q15 to R.string.faq_a15,
    R.string.faq_q6 to R.string.faq_a6,
    R.string.faq_q7 to R.string.faq_a7,
    R.string.faq_q16 to R.string.faq_a16,
    R.string.faq_q17 to R.string.faq_a17,
    R.string.faq_q8 to R.string.faq_a8,
    R.string.faq_q18 to R.string.faq_a18,
    R.string.faq_q19 to R.string.faq_a19,
    R.string.faq_q9 to R.string.faq_a9,
    R.string.faq_q10 to R.string.faq_a10,
    R.string.faq_q11 to R.string.faq_a11,
    R.string.faq_q12 to R.string.faq_a12,
)

private fun LazyListScope.howToWalkthrough(intro: Int, steps: List<Triple<Int, Int, Int>>) {
    item {
        GlassCard {
            Text(
                stringResource(intro),
                fontSize = 14.sp, lineHeight = 20.sp, color = K.textPrimary,
            )
        }
    }
    itemsIndexed(steps) { index, step ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader(stringResource(R.string.howto_step, index + 1))
            GlassCard(spacing = 10.dp) {
                Text(
                    stringResource(step.first),
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = K.textPrimary,
                )
                Text(
                    stringResource(step.second),
                    fontSize = 14.sp, lineHeight = 20.sp, color = K.textSecondary,
                )
                Image(
                    painterResource(step.third),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .heightIn(max = 380.dp)
                        .clip(RoundedCornerShape(KShape.cornerSmall))
                        .border(1.dp, K.hairline, RoundedCornerShape(KShape.cornerSmall)),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
internal fun HowToKeysSettings(modifier: Modifier, onBack: () -> Unit) {
    KLazyScreen(
        stringResource(R.string.howto_keys_title), modifier,
        backLabel = stringResource(R.string.help_howto), onBack = onBack,
    ) {
        howToWalkthrough(R.string.howto_keys_intro, howToSteps)
    }
}

@Composable
internal fun HowToSetupSettings(modifier: Modifier, onBack: () -> Unit) {
    KLazyScreen(
        stringResource(R.string.howto_setup_title), modifier,
        backLabel = stringResource(R.string.help_howto), onBack = onBack,
    ) {
        howToWalkthrough(R.string.howto_setup_intro, howToSetupSteps)
    }
}

@Composable
internal fun HowToSettings(
    modifier: Modifier,
    backLabel: String,
    onBack: () -> Unit,
    openKeys: () -> Unit,
    openSetup: () -> Unit,
) {
    KScreen(
        stringResource(R.string.help_howto), modifier,
        backLabel = backLabel, onBack = onBack,
    ) {
        GlassCard {
            Text(
                stringResource(R.string.howto_intro),
                fontSize = 14.sp, lineHeight = 20.sp, color = K.textPrimary,
            )
        }

        GlassCard(spacing = 0.dp) {
            NavRow(stringResource(R.string.howto_keys_title), openKeys)
            CardDivider()
            NavRow(stringResource(R.string.howto_setup_title), openSetup)
        }

        howToTopics.forEach { (title, text) ->
            SectionHeader(stringResource(title))
            GlassCard {
                Text(
                    stringResource(text),
                    fontSize = 14.sp, lineHeight = 20.sp, color = K.textSecondary,
                )
            }
        }

        SectionHeader(stringResource(R.string.howto_notes_title))
        GlassCard(spacing = 14.dp) {
            howToNotes.forEach { note ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.CheckCircle, null,
                        Modifier.size(18.dp).padding(top = 2.dp), tint = K.accent,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(note),
                        fontSize = 14.sp, lineHeight = 20.sp, color = K.textSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FaqSettings(modifier: Modifier, onBack: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(-1) }

    KScreen(
        stringResource(R.string.help_faq), modifier,
        backLabel = stringResource(R.string.tab_settings), onBack = onBack,
    ) {
        GlassCard(padding = 4.dp, spacing = 0.dp) {
            faqItems.forEachIndexed { index, (question, answer) ->
                if (index > 0) CardDivider()
                val open = expanded == index
                Column(
                    Modifier
                        .fillMaxWidth()
                        .quietClickable { expanded = if (open) -1 else index }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(question),
                            fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            color = K.textPrimary, modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, Modifier.size(20.dp), tint = K.textSecondary,
                        )
                    }
                    AnimatedVisibility(open) {
                        Text(
                            stringResource(answer),
                            fontSize = 14.sp, lineHeight = 20.sp, color = K.textSecondary,
                            modifier = Modifier.padding(top = 10.dp, end = 30.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private val DONATE_TILE = Color(0xFFE23D6D)

private val DONATE_QR_PADDING = 6.dp

private val DONATE_QR_MAX = 260.dp

private val DONATE_BADGE = 26.dp

private class DonateQrRender(val bitmap: Bitmap?)

@Composable
private fun CoinBadge(coin: DonationCoin) {
    val asset = when (coin.id) {
        "xmr" -> R.drawable.ic_coin_xmr
        "ton" -> R.drawable.ic_coin_ton
        else -> R.drawable.ic_coin_btc
    }
    Image(painterResource(asset), null, Modifier.size(DONATE_BADGE))
}

@Composable
private fun DonateSettings(modifier: Modifier, backLabel: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copied) {
        if (copied == null) return@LaunchedEffect
        delay(1500)
        copied = null
    }

    KScreen(
        stringResource(R.string.donate_title), modifier,
        backLabel = backLabel, onBack = onBack,
    ) {
        GlassCard {
            Text(
                stringResource(R.string.donate_intro),
                fontSize = 14.sp, lineHeight = 20.sp, color = K.textPrimary,
            )
        }
        FooterText(stringResource(R.string.donate_verify))

        Donations.coins.forEach { coin ->
            GlassCard(spacing = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoinBadge(coin)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        coin.name,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = K.textPrimary, modifier = Modifier.weight(1f),
                    )
                    Text(
                        coin.ticker,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = K.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(K.fieldFill)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
                CardDivider()
                Text(
                    coin.grouped,
                    fontSize = 13.sp, lineHeight = 19.sp,
                    fontFamily = FontFamily.Monospace, color = K.textPrimary,
                    modifier = Modifier.semantics { contentDescription = coin.address },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val done = copied == coin.id
                    SecondaryButton(
                        stringResource(if (done) R.string.copied else R.string.donate_copy),
                        Modifier.weight(1f),
                        icon = if (done) Icons.Default.Check else Icons.Default.ContentCopy,
                        accent = true,
                    ) {
                        ClipboardGuard.copyPlain(context, coin.address)
                        copied = coin.id
                    }
                    val open = expanded == coin.id
                    GlassIconButton(
                        Icons.Default.QrCode2,
                        stringResource(if (open) R.string.hide_qr else R.string.show_qr),
                        size = 50.dp,
                        filled = open,
                    ) {
                        expanded = if (open) null else coin.id
                    }
                }
                if (expanded == coin.id) DonateQr(coin.address)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DonateQr(address: String) {
    KeepScreenBright()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val availablePx = with(density) { (minOf(maxWidth, DONATE_QR_MAX) - DONATE_QR_PADDING * 2).roundToPx() }
        val rendered by produceState<DonateQrRender?>(null, address, availablePx) {
            value = DonateQrRender(
                withContext(Dispatchers.Default) {
                    KeyQr.bitmap(
                        address.toByteArray(Charsets.ISO_8859_1),
                        availablePx,
                        ErrorCorrectionLevel.M,
                    )
                },
            )
        }
        val qr = rendered?.bitmap
        if (qr != null) {
            Box(
                Modifier
                    .shadow(10.dp, RoundedCornerShape(KShape.cornerSmall))
                    .clip(RoundedCornerShape(KShape.cornerSmall))
                    .background(Color.White)
                    .padding(DONATE_QR_PADDING),
            ) {
                Image(
                    qr.asImageBitmap(),
                    null,
                    Modifier.size(with(density) { qr.width.toDp() }),
                    filterQuality = FilterQuality.None,
                )
            }
        } else if (rendered != null) {
            Banner(stringResource(R.string.donate_qr_unavailable), BannerKind.Warning)
        }
    }
}

@Composable
private fun AboutSettings(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }

    KScreen(
        stringResource(R.string.settings_about_row), modifier,
        backLabel = stringResource(R.string.tab_settings), onBack = onBack,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                Modifier
                    .shadow(
                        12.dp, RoundedCornerShape(18.dp),
                        ambientColor = K.accent.copy(alpha = 0.45f),
                        spotColor = K.accent.copy(alpha = 0.45f),
                    )
                    .size(78.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.verticalGradient(listOf(K.accentBright, K.accent))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Lock, null, Modifier.size(36.dp), tint = Color.White)
            }
            Spacer(Modifier.height(9.dp))
            Text("Kryptos", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = K.textPrimary)
            Text(
                stringResource(R.string.about_tagline),
                fontSize = 14.sp, color = K.textSecondary, textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.about_version, version),
                fontSize = 12.sp, color = K.textSecondary.copy(alpha = 0.7f),
            )
        }

        GlassCard {
            Text(
                stringResource(R.string.about_desc),
                fontSize = 14.sp, lineHeight = 20.sp, color = K.textPrimary,
            )
        }
        FooterText(stringResource(R.string.about_offline))

        SectionHeader(stringResource(R.string.about_under_hood))
        GlassCard(spacing = 14.dp) {
            AboutInfoRow(Icons.Default.VerifiedUser, stringResource(R.string.about_signal_t), stringResource(R.string.about_signal_d))
            AboutInfoRow(Icons.Default.Email, stringResource(R.string.about_pgp_t), stringResource(R.string.about_pgp_d))
            AboutInfoRow(Icons.Default.Key, stringResource(R.string.about_pw_t), stringResource(R.string.about_pw_d))
            AboutInfoRow(Icons.Default.Image, stringResource(R.string.about_stego_t), stringResource(R.string.about_stego_d))
        }

        SectionHeader(stringResource(R.string.about_source))
        GlassCard(spacing = 4.dp) {
            AboutLinkRow(R.drawable.ic_brand_github, "GitHub", "swisslite/Kryptos") {
                openLink(context, "https://github.com/swisslite/Kryptos")
            }
        }
        FooterText(stringResource(R.string.about_source_footer))

        SectionHeader(stringResource(R.string.about_website))
        GlassCard(spacing = 4.dp) {
            AboutLinkRow(Icons.Default.Public, "Kryptos", "datakeeper.pages.dev/kryptos") {
                openLink(context, "https://datakeeper.pages.dev/kryptos")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DeveloperSettings(modifier: Modifier, onBack: () -> Unit, openDonate: () -> Unit) {
    val context = LocalContext.current

    KScreen(
        stringResource(R.string.about_dev), modifier,
        backLabel = stringResource(R.string.tab_settings), onBack = onBack,
    ) {
        GlassCard(spacing = 4.dp) {
            AboutLinkRow(Icons.Default.Public, stringResource(R.string.about_website), "datakeeper.pages.dev") {
                openLink(context, "https://datakeeper.pages.dev")
            }
            CardDivider()
            AboutLinkRow(Icons.Default.Email, "Email", "datakeepers@proton.me") {
                openLink(context, "mailto:datakeepers@proton.me")
            }
            CardDivider()
            AboutLinkRow(R.drawable.ic_brand_telegram, "Telegram", "@datakeeper") {
                openLink(context, "https://t.me/datakeeper")
            }
            CardDivider()
            AboutLinkRow(Icons.Default.Campaign, stringResource(R.string.about_tg_channel), "@KryptosApp") {
                openLink(context, "https://t.me/KryptosApp")
            }
            CardDivider()
            AboutLinkRow(R.drawable.ic_brand_github, stringResource(R.string.about_gh_profile), "@swisslite") {
                openLink(context, "https://github.com/swisslite")
            }
        }
        FooterText(stringResource(R.string.about_dev_footer))

        GlassCard(padding = 14.dp, spacing = 2.dp) {
            SettingsTile(
                stringResource(R.string.donate_title), Icons.Default.Favorite,
                tile = DONATE_TILE,
            ) { openDonate() }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AboutInfoRow(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(20.dp).padding(top = 1.dp), tint = K.accent)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = K.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = K.textSecondary)
        }
    }
}

private val ABOUT_ICON = 28.dp

@Composable
private fun AboutLinkRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    AboutLinkRow(label, value, onClick) {
        Icon(icon, null, Modifier.size(ABOUT_ICON), tint = K.accent)
    }
}

@Composable
private fun AboutLinkRow(@DrawableRes icon: Int, label: String, value: String, onClick: () -> Unit) {
    AboutLinkRow(label, value, onClick) {
        Icon(painterResource(icon), null, Modifier.size(ABOUT_ICON), tint = K.accent)
    }
}

@Composable
private fun AboutLinkRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .quietClickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = K.textSecondary)
            Text(value, fontSize = 17.sp, lineHeight = 22.sp, color = K.textPrimary)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp), tint = K.textSecondary)
    }
}

private fun appVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    "${info.versionName} (${info.versionCode})"
}.getOrDefault("1.0")

private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
