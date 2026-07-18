package com.kryptos.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kryptos.android.R
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.StegoLanguage
import com.kryptos.android.core.TextStego
import com.kryptos.android.pgp.PgpService
import com.kryptos.android.screen.ScreenDecryptService
import com.kryptos.android.signal.AppSettingsStore
import com.kryptos.android.signal.SignalService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class SettingsPage { Root, Privacy, Stego, Keyboard, KeyboardLangs, About }

private val kbLanguageCatalog = listOf("en" to R.string.lang_en, "ru" to R.string.lang_ru)

private fun pageDepth(page: SettingsPage): Int = when (page) {
    SettingsPage.Root -> 0
    SettingsPage.KeyboardLangs -> 2
    else -> 1
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onShieldChanged: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    BackHandler(enabled = page != SettingsPage.Root) {
        page = if (page == SettingsPage.KeyboardLangs) SettingsPage.Keyboard else SettingsPage.Root
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (pageDepth(targetState) > pageDepth(initialState)) {
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
            SettingsPage.Root -> RootSettings(modifier) { page = it }
            SettingsPage.Privacy -> PrivacySettings(modifier, onShieldChanged, back)
            SettingsPage.Stego -> StegoSettings(modifier, back)
            SettingsPage.Keyboard -> KeyboardSettings(modifier, back, openLanguages = { page = SettingsPage.KeyboardLangs })
            SettingsPage.KeyboardLangs -> KeyboardLanguagesSettings(modifier, onBack = { page = SettingsPage.Keyboard })
            SettingsPage.About -> AboutSettings(modifier, back)
        }
    }
}

@Composable
private fun RootSettings(modifier: Modifier, open: (SettingsPage) -> Unit) {
    val scope = rememberCoroutineScope()

    KScreen(stringResource(R.string.tab_settings), modifier) {
        GlassCard(padding = 14.dp, spacing = 2.dp) {
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
        }

        GlassCard(padding = 14.dp, spacing = 2.dp) {
            SettingsTile(
                stringResource(R.string.settings_about_row), Icons.Default.Info,
                tile = Color(0xFF8E8E93),
            ) { open(SettingsPage.About) }
        }

        SectionHeader(stringResource(R.string.settings_danger))
        var confirmWipeChats by remember { mutableStateOf(false) }
        var confirmWipeContacts by remember { mutableStateOf(false) }
        GlassCard(padding = 14.dp, spacing = 2.dp) {
            SettingsTile(
                stringResource(R.string.settings_wipe_chats), Icons.Default.Delete,
                tile = K.danger, textColor = K.danger,
            ) { confirmWipeChats = true }
            CardDivider()
            SettingsTile(
                stringResource(R.string.settings_wipe_contacts), Icons.Default.DeleteSweep,
                tile = K.danger, textColor = K.danger,
            ) { confirmWipeContacts = true }
            CardDivider()
            var confirmWipe by remember { mutableStateOf(false) }
            SettingsTile(
                stringResource(R.string.settings_wipe_all), Icons.Default.DeleteForever,
                tile = K.danger, textColor = K.danger,
            ) { confirmWipe = !confirmWipe }
            if (confirmWipe) {
                Banner(stringResource(R.string.wipe_all_warning), BannerKind.Error)
                Spacer(Modifier.height(4.dp))
                SecondaryButton(
                    stringResource(R.string.settings_wipe_all),
                    Modifier.fillMaxWidth(),
                    danger = true,
                ) {
                    SignalService.eraseEverything()
                    PgpService.eraseAllStorage()
                    scope.launch(Dispatchers.Default) { runCatching { PgpService.ensureInitialized() } }
                    confirmWipe = false
                }
            }
        }
        FooterText(stringResource(R.string.settings_danger_footer))
        Spacer(Modifier.height(8.dp))

        if (confirmWipeChats) {
            ConfirmDialog(
                title = stringResource(R.string.confirm_wipe_chats_title),
                text = stringResource(R.string.confirm_wipe_chats_text),
                confirmLabel = stringResource(R.string.settings_wipe_chats),
                onConfirm = { SignalService.wipeAllChats() },
                onDismiss = { confirmWipeChats = false },
            )
        }
        if (confirmWipeContacts) {
            ConfirmDialog(
                title = stringResource(R.string.confirm_wipe_contacts_title),
                text = stringResource(R.string.confirm_wipe_contacts_text),
                confirmLabel = stringResource(R.string.settings_wipe_contacts),
                onConfirm = { SignalService.wipeContactsAndChats() },
                onDismiss = { confirmWipeContacts = false },
            )
        }
    }
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
private fun PrivacySettings(modifier: Modifier, onShieldChanged: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val canLock = remember { com.kryptos.android.security.AppLock.canUseLock(context) }

    var appLock by remember { mutableStateOf(AppSettingsStore.appLock) }
    var autoLock by remember { mutableStateOf(AppSettingsStore.autoLockGraceSeconds) }
    var duress by remember { mutableStateOf("") }
    var duressSet by remember { mutableStateOf(AppSettingsStore.hasDuressPin) }
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
        if (integrity) {
            val report = remember { com.kryptos.android.security.DeviceIntegrity.check(context) }
            if (report.tampered) {
                Banner(stringResource(R.string.sec_integrity_tampered), BannerKind.Error)
            }
            if (report.hooked) {
                Banner(stringResource(R.string.sec_integrity_hooked), BannerKind.Error)
            }
            val warn = when {
                report.rooted -> stringResource(R.string.sec_integrity_rooted)
                report.debugged -> stringResource(R.string.sec_integrity_debugged)
                report.emulator -> stringResource(R.string.sec_integrity_emulator)
                else -> null
            }
            warn?.let { Banner(it, BannerKind.Warning) }
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
                appLock && canLock,
                subtitle = if (canLock) null else stringResource(R.string.sec_app_lock_unavailable),
                enabled = canLock,
                onChange = { if (canLock) { appLock = it; AppSettingsStore.appLock = it } },
            )

            if (appLock && canLock) {
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
                CardDivider()
                Spacer(Modifier.height(6.dp))
                FieldLabel(stringResource(R.string.sec_duress) + if (duressSet) " ✓" else "")
                KTextField(
                    duress,
                    {
                        duress = it.filter { c -> c.isDigit() }.take(12)
                        AppSettingsStore.setDuressPinAsync(duress)
                        duressSet = duress.isNotEmpty()
                    },
                    password = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Text(
                    stringResource(R.string.sec_duress_hint),
                    fontSize = 12.sp, lineHeight = 16.sp, color = K.textSecondary,
                )
                Spacer(Modifier.height(2.dp))
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
        FooterText(stringResource(R.string.privacy_footer))

        ScreenDecryptSection()

        SectionHeader(stringResource(R.string.clip_section))
        GlassCard(spacing = 4.dp) {
            ToggleRow(stringResource(R.string.settings_clip_autodecrypt), clipAuto, onChange = {
                clipAuto = it; AppSettingsStore.clipboardAutoDecrypt = it
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

            CardDivider()
            ToggleRow(stringResource(R.string.sec_clip_on_decrypt), clipOnDecrypt, onChange = {
                clipOnDecrypt = it; AppSettingsStore.clearClipboardOnDecrypt = it
            })
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
private fun ScreenDecryptSection() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(AppSettingsStore.screenDecrypt) }
    var blockShots by remember { mutableStateOf(AppSettingsStore.screenDecryptSecure) }
    var serviceOn by remember { mutableStateOf(ScreenDecryptService.isSystemEnabled(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) serviceOn = ScreenDecryptService.isSystemEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val needsService = enabled && !serviceOn

    SectionHeader(stringResource(R.string.screen_section))
    GlassCard(spacing = 4.dp) {
        ToggleRow(stringResource(R.string.screen_decrypt_toggle), enabled, onChange = {
            enabled = it
            AppSettingsStore.screenDecrypt = it
            if (it && !serviceOn) openAccessibilitySettings(context)
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
    }
    FooterText(
        stringResource(
            if (needsService) R.string.screen_decrypt_service_off else R.string.screen_decrypt_footer
        )
    )
}

private fun openAccessibilitySettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
}

@Composable
private fun StegoSettings(modifier: Modifier, onBack: () -> Unit) {
    var stegoEnabled by remember { mutableStateOf(AppSettingsStore.chatStegoEnabled) }
    var stegoLang by remember { mutableStateOf(AppSettingsStore.chatStegoLanguage) }
    var stegoSmart by remember { mutableStateOf(AppSettingsStore.chatStegoSmart) }

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
                val langs = listOf("auto" to R.string.lang_auto, "english" to R.string.lang_en, "russian" to R.string.lang_ru)
                MenuRow(
                    stringResource(R.string.settings_stego_lang),
                    options = langs.map { stringResource(it.second) },
                    selected = langs.indexOfFirst { it.first == stegoLang }.coerceAtLeast(0),
                    onPick = { stegoLang = langs[it].first; AppSettingsStore.chatStegoLanguage = langs[it].first },
                )
                CardDivider()
                ToggleRow(
                    stringResource(R.string.settings_stego_smart),
                    stegoSmart,
                    onChange = { stegoSmart = it; AppSettingsStore.chatStegoSmart = it },
                )
            }
        }
        FooterText(stringResource(R.string.settings_stego_desc))

        if (stegoEnabled) {
            SectionHeader(stringResource(R.string.stego_example_header))
            GlassCard {
                val sample = remember(stegoLang, stegoSmart) {
                    val bytes = byteArrayOf(0x03, 0x02, 0x41, 0x9C.toByte(), 0x2A, 0xF7.toByte(),
                        0x10, 0x88.toByte(), 0x3D, 0x6B, 0xE0.toByte(), 0x54)
                    val language = when (stegoLang) {
                        "english" -> StegoLanguage.ENGLISH
                        "russian" -> StegoLanguage.RUSSIAN
                        else -> StegoLanguage.forSystem()
                    }
                    if (stegoSmart) SmartTextStego.encode(bytes, language) else TextStego.encode(bytes, language)
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
                    if (stegoSmart) R.string.stego_smart_footer else R.string.stego_example_footer,
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun KeyboardSettings(modifier: Modifier, onBack: () -> Unit, openLanguages: () -> Unit) {
    val context = LocalContext.current
    var kbAutoDecrypt by remember { mutableStateOf(AppSettingsStore.keyboardAutoDecrypt) }
    var kbHaptics by remember { mutableStateOf(AppSettingsStore.keyboardHaptics) }
    var kbSounds by remember { mutableStateOf(AppSettingsStore.keyboardSounds) }
    var kbCompose by remember { mutableStateOf(AppSettingsStore.keyboardCompose) }
    var secureKb by remember { mutableStateOf(AppSettingsStore.secureKeyboard) }
    var kbSuggestions by remember { mutableStateOf(AppSettingsStore.keyboardSuggestions) }
    var kbAutocorrect by remember { mutableStateOf(AppSettingsStore.keyboardAutocorrect) }
    var kbEmoji by remember { mutableStateOf(AppSettingsStore.keyboardEmoji) }
    var confirmForget by remember { mutableStateOf(false) }
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
                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } },
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
            if (kbSuggestions || kbAutocorrect) {
                CardDivider()
                NavRow(stringResource(R.string.kb_forget_words), onClick = { confirmForget = true })
            }
        }
        FooterText(stringResource(R.string.settings_kb_suggestions_desc))

        if (confirmForget) {
            ConfirmDialog(
                title = stringResource(R.string.confirm_forget_words_title),
                text = stringResource(R.string.confirm_forget_words_text),
                confirmLabel = stringResource(R.string.kb_forget_words),
                onConfirm = { com.kryptos.android.keyboard.SuggestionEngine.clearPersonal() },
                onDismiss = { confirmForget = false },
            )
        }

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

        GlassCard(spacing = 4.dp) {
            ToggleRow(stringResource(R.string.settings_kb_compose), kbCompose, onChange = {
                kbCompose = it; AppSettingsStore.keyboardCompose = it
            })
            CardDivider()
            ToggleRow(stringResource(R.string.sec_secure_keyboard), secureKb, onChange = {
                secureKb = it; AppSettingsStore.secureKeyboard = it
            })
        }
        FooterText(stringResource(R.string.settings_kb_compose_desc))
        Spacer(Modifier.height(8.dp))
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

        SectionHeader(stringResource(R.string.about_dev))
        GlassCard(spacing = 4.dp) {
            AboutLinkRow(Icons.Default.Email, "Email", "datakeepers@proton.me") {
                openLink(context, "mailto:datakeepers@proton.me")
            }
            CardDivider()
            AboutLinkRow(Icons.AutoMirrored.Filled.Send, "Telegram", "@datakeeper") {
                openLink(context, "https://t.me/datakeeper")
            }
            CardDivider()
            AboutLinkRow(Icons.Default.Campaign, stringResource(R.string.about_tg_channel), "@KryptosApp") {
                openLink(context, "https://t.me/KryptosApp")
            }
        }
        FooterText(stringResource(R.string.about_dev_footer))
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

@Composable
private fun AboutLinkRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .quietClickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = K.accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, color = K.textSecondary)
            Text(value, fontSize = 15.sp, color = K.textPrimary)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(15.dp), tint = K.textSecondary)
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
