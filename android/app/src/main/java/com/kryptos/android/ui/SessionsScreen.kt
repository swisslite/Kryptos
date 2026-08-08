package com.kryptos.android.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.kryptos.android.R
import com.kryptos.android.core.KeyQr
import com.kryptos.android.core.LetterStego
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.TextStego
import com.kryptos.android.signal.Contact
import com.kryptos.android.signal.SignalService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SessionsScreen(
    modifier: Modifier = Modifier,
    homeSignal: Int = 0,
    onChatOpenChanged: (Boolean) -> Unit = {},
) {
    val bgScope = rememberCoroutineScope()
    val contacts by SignalService.contacts.collectAsState()
    val profiles by SignalService.profiles.collectAsState()
    val currentID by SignalService.currentID.collectAsState()
    var openContact by remember { mutableStateOf<Contact?>(null) }
    var showMyKey by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showProfiles by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Contact?>(null) }

    val selected = openContact
    BackHandler(enabled = selected != null) { openContact = null }

    LaunchedEffect(selected) { onChatOpenChanged(selected != null) }
    DisposableEffect(Unit) { onDispose { onChatOpenChanged(false) } }

    var seenHomeSignal by remember { mutableIntStateOf(homeSignal) }
    LaunchedEffect(homeSignal) {
        if (homeSignal != seenHomeSignal) {
            seenHomeSignal = homeSignal
            openContact = null
        }
    }

    AnimatedContent(
        targetState = selected,
        modifier = modifier,
        transitionSpec = {
            val push = targetState != null
            val spec = tween<IntOffset>(durationMillis = 340, easing = FastOutSlowInEasing)
            val fade = tween<Float>(durationMillis = 340, easing = FastOutSlowInEasing)
            if (push) {
                (slideInHorizontally(spec) { it } togetherWith
                    slideOutHorizontally(spec) { -it / 3 } + fadeOut(fade))
                    .apply { targetContentZIndex = 1f }
            } else {
                (slideInHorizontally(spec) { -it / 3 } + fadeIn(fade) togetherWith
                    slideOutHorizontally(spec) { it })
                    .apply { targetContentZIndex = 0f }
            }
        },
        label = "chatNav",
    ) { contact ->
        if (contact != null) {
            ChatScreen(contact, Modifier.fillMaxSize()) { openContact = null }
        } else {
            SessionsList(
                contacts = contacts,
                profiles = profiles,
                currentID = currentID,
                onOpen = { openContact = it },
                onDelete = { confirmDelete = it },
                onMyKey = { showMyKey = true },
                onAdd = { showAdd = true },
                onProfiles = { showProfiles = true },
            )
        }
    }

    if (showMyKey) MyKeySheet { showMyKey = false }
    if (showAdd) AddContactSheet { showAdd = false }
    if (showProfiles) ProfilesSheet { showProfiles = false }
    confirmDelete?.let { contact ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_contact_title),
            text = stringResource(R.string.confirm_delete_contact_text),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                if (openContact?.fingerprint == contact.fingerprint) openContact = null
                bgScope.launch(Dispatchers.Default + NonCancellable) {
                    SignalService.removeContact(contact)
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun SessionsList(
    contacts: List<Contact>,
    profiles: List<com.kryptos.android.signal.Profile>,
    currentID: String,
    onOpen: (Contact) -> Unit,
    onDelete: (Contact) -> Unit,
    onMyKey: () -> Unit,
    onAdd: () -> Unit,
    onProfiles: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val autoDeleteMap by SignalService.autoDelete.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.quietClickable { menu = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        profiles.firstOrNull { it.id == currentID }?.name ?: stringResource(R.string.tab_chats),
                        fontSize = 30.sp, fontWeight = FontWeight.Bold, color = K.textPrimary, maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.ExpandMore, null, Modifier.size(22.dp), tint = K.textSecondary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    profiles.forEach { p ->
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    if (p.id == currentID) Icons.Default.Check else Icons.Default.Person,
                                    null, Modifier.size(18.dp),
                                    tint = if (p.id == currentID) K.accent else K.textSecondary,
                                )
                            },
                            text = { Text(p.name, color = K.textPrimary) },
                            onClick = {
                                menu = false
                                scope.launch(Dispatchers.Default + NonCancellable) {
                                    SignalService.switchTo(p.id)
                                }
                            },
                        )
                    }
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp), tint = K.accent) },
                        text = { Text(stringResource(R.string.manage_profiles), color = K.textPrimary) },
                        onClick = { menu = false; onProfiles() },
                    )
                }
            }
            GlassIconButton(Icons.Default.QrCode, stringResource(R.string.my_key), onClick = onMyKey)
            Spacer(Modifier.width(10.dp))
            GlassIconButton(Icons.Default.Add, stringResource(R.string.add_contact), onClick = onAdd)
        }

        if (contacts.isEmpty()) {
            GlassCard {
                Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(26.dp), tint = K.accent)
                Text(
                    stringResource(R.string.empty_title),
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = K.textPrimary,
                )
                Text(
                    stringResource(R.string.empty_hint),
                    fontSize = 14.sp, lineHeight = 20.sp, color = K.textSecondary,
                )
            }
        } else {
            contacts.forEach { contact ->
                ContactCard(
                    contact,
                    autoDelete = autoDeleteMap[contact.fingerprint]?.let { it > 0 } == true,
                    onOpen = { onOpen(contact) },
                    onDelete = { onDelete(contact) },
                )
            }
        }
        Spacer(Modifier.height(8.dp + LocalTabBarInset.current))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactCard(contact: Contact, autoDelete: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(KShape.corner)
    var menu by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.97f)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(6.dp, shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
                .clip(shape)
                .background(K.card)
                .border(1.dp, K.hairline, shape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onOpen,
                    onLongClick = { menu = true },
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(contact.displayName)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    contact.displayName,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = K.textPrimary, maxLines = 1,
                )
                Text(
                    contact.safetyNumber,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = K.textSecondary, maxLines = 1,
                )
            }
            if (autoDelete) {
                Icon(Icons.Default.Timer, null, Modifier.size(15.dp), tint = K.accent)
                Spacer(Modifier.width(6.dp))
            }
            Text("›", fontSize = 17.sp, color = K.textSecondary)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = K.danger) },
                text = { Text(stringResource(R.string.delete_contact), color = K.danger) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun ChatScreen(contact: Contact, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val messages by SignalService.messages.collectAsState()
    var lastCipher by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var autoDeleteOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var decrypting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val msgs = messages[contact.fingerprint] ?: emptyList()
    val autoDeleteMap by SignalService.autoDelete.collectAsState()
    val autoDeleteSecs = autoDeleteMap[contact.fingerprint]?.takeIf { it > 0 }

    LaunchedEffect(autoDeleteSecs) {
        if (autoDeleteSecs == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(5_000)
            withContext(Dispatchers.Default) { SignalService.purgeExpiredMessages() }
        }
    }

    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(
        modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(Icons.Default.ArrowBackIosNew, null, size = 40.dp) { onBack() }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    contact.displayName,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = K.textPrimary, maxLines = 1,
                )
                Text(
                    contact.safetyNumber,
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = K.textSecondary, maxLines = 1,
                )
            }
            Spacer(Modifier.width(10.dp))
            Box {
                GlassIconButton(Icons.Default.MoreVert, null, size = 40.dp, tint = K.textPrimary) { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Timer, null, Modifier.size(18.dp), tint = K.accent) },
                        text = { Text(stringResource(R.string.auto_delete), color = K.textPrimary) },
                        onClick = { menuOpen = false; autoDeleteOpen = true },
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = K.danger) },
                        text = { Text(stringResource(R.string.clear_chat), color = K.danger) },
                        onClick = { menuOpen = false; confirmClear = true },
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Default.Close, null, Modifier.size(18.dp), tint = K.danger) },
                        text = { Text(stringResource(R.string.delete_contact), color = K.danger) },
                        onClick = { menuOpen = false; confirmDelete = true },
                    )
                }
            }
        }

        LazyColumn(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            state = listState,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(msgs.asReversed(), key = { it.id }) { msg ->
                Bubble(msg.text, msg.mine, Modifier.animateItem())
            }
            if (autoDeleteSecs != null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Row(
                            Modifier
                                .clip(CircleShape)
                                .background(K.fieldFill)
                                .border(1.dp, K.hairline, CircleShape)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Timer, null, Modifier.size(12.dp), tint = K.textSecondary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.auto_delete_hint, autoDeleteLabel(autoDeleteSecs)),
                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = K.textSecondary,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.chat_hint),
                    fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
                    color = K.textSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }
        }

        val shownError = remember { mutableStateOf("") }
        LaunchedEffect(error) { error?.let { shownError.value = it } }
        AnimatedVisibility(
            visible = error != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                shownError.value, fontSize = 13.sp, color = K.danger,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        val shownCipher = remember { mutableStateOf("") }
        LaunchedEffect(lastCipher) { lastCipher?.let { shownCipher.value = it } }
        AnimatedVisibility(
            visible = lastCipher != null,
            enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val cipher = shownCipher.value
            val hidden = remember(cipher) {
                TextStego.looksLikeStego(cipher) || SmartTextStego.looksLikeStego(cipher) ||
                    LetterStego.looksLikeStego(cipher)
            }
            Row(
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(KShape.corner))
                    .background(K.card)
                    .border(1.dp, K.hairline, RoundedCornerShape(KShape.corner))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (hidden) Icons.Default.VisibilityOff else Icons.Default.Check,
                    null, Modifier.size(18.dp), tint = K.success,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(if (hidden) R.string.sent_banner_stego else R.string.sent_banner),
                    fontSize = 13.sp, lineHeight = 17.sp, color = K.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.Share, stringResource(R.string.share),
                    Modifier
                        .size(20.dp)
                        .quietClickable { shareText(context, cipher) },
                    tint = K.accent,
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Default.Close, null,
                    Modifier
                        .size(20.dp)
                        .quietClickable { lastCipher = null },
                    tint = K.textSecondary,
                )
            }
        }

        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            GlassIconButton(
                Icons.Default.Archive, stringResource(R.string.paste_and_decrypt),
                size = 48.dp, enabled = !decrypting,
            ) {
                error = null
                val clip = clipboardText(context)
                if (clip.isBlank()) {
                    error = context.getString(R.string.clipboard_empty)
                    return@GlassIconButton
                }
                decrypting = true
                scope.launch {
                    val outcome = withContext(Dispatchers.Default + NonCancellable) {
                        runCatching { SignalService.decrypt(clip, contact) }.also { result ->
                            if (result.isSuccess &&
                                com.kryptos.android.signal.AppSettingsStore.clearClipboardOnDecrypt
                            ) {
                                com.kryptos.android.security.ClipboardGuard.clearIfOurs(context, clip)
                            }
                        }
                    }
                    outcome.onFailure { e ->
                        val other = e as? com.kryptos.android.signal.DecryptedForOtherContactException
                        val newer = (e as? com.kryptos.android.core.CipherException)?.kind ==
                            com.kryptos.android.core.CipherException.Kind.UNSUPPORTED_FORMAT
                        error = when {
                            other != null -> context.getString(R.string.decrypted_other_contact, other.displayName)
                            newer -> context.getString(R.string.msg_newer_version)
                            e is com.kryptos.android.signal.NoSessionForContactException ->
                                context.getString(R.string.session_lost)
                            e is com.kryptos.android.signal.StorageUnavailableException ->
                                context.getString(R.string.storage_unavailable)
                            else -> context.getString(R.string.decrypt_failed)
                        }
                    }
                    decrypting = false
                }
            }
            ChatInput { text ->
                error = null
                val outcome = withContext(Dispatchers.Default + NonCancellable) {
                    runCatching {
                        SignalService.encrypt(text, contact).also { copyCipher(context, it, null) }
                    }
                }
                outcome.fold(
                    onSuccess = { armored ->
                        lastCipher = armored
                        true
                    },
                    onFailure = { e ->
                        error = context.getString(
                            when (e) {
                                is com.kryptos.android.signal.NoSessionForContactException -> R.string.session_lost
                                is com.kryptos.android.signal.StorageUnavailableException -> R.string.storage_unavailable
                                else -> R.string.encrypt_failed
                            }
                        )
                        false
                    },
                )
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_clear_chat_title),
            text = stringResource(R.string.confirm_clear_chat_text),
            confirmLabel = stringResource(R.string.clear_chat),
            onConfirm = {
                scope.launch(Dispatchers.Default + NonCancellable) { SignalService.clearChat(contact) }
            },
            onDismiss = { confirmClear = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_contact_title),
            text = stringResource(R.string.confirm_delete_contact_text),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                scope.launch(Dispatchers.Default + NonCancellable) { SignalService.removeContact(contact) }
                onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (autoDeleteOpen) {
        val presets = listOf(
            stringResource(R.string.off) to null,
            stringResource(R.string.s30) to 30.0,
            stringResource(R.string.m5) to 300.0,
            stringResource(R.string.h1) to 3600.0,
            stringResource(R.string.h8) to 8 * 3600.0,
            stringResource(R.string.d1) to 24 * 3600.0,
            stringResource(R.string.w1) to 7 * 24 * 3600.0,
        )
        KSheet(stringResource(R.string.auto_delete), onDismiss = { autoDeleteOpen = false }) {
            GlassCard(spacing = 0.dp) {
                presets.forEachIndexed { i, (label, secs) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .quietClickable {
                                autoDeleteOpen = false
                                scope.launch(Dispatchers.Default + NonCancellable) {
                                    SignalService.setAutoDelete(secs, contact)
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, fontSize = 15.sp, color = K.textPrimary, modifier = Modifier.weight(1f))
                        if (autoDeleteSecs == secs) {
                            Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = K.accent)
                        }
                    }
                    if (i < presets.lastIndex) CardDivider()
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    val bright = K.accentBright
    val accent = K.accent
    val incoming = K.incomingBubble
    val fill = remember(mine, bright, accent, incoming) {
        if (mine) Brush.verticalGradient(listOf(bright, accent)) else SolidColor(incoming)
    }
    Box(
        modifier.fillMaxWidth(),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text,
            fontSize = 16.sp,
            lineHeight = 21.sp,
            color = if (mine) Color.White else K.textPrimary,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(fill)
                .then(if (mine) Modifier else Modifier.border(1.dp, K.hairline, shape))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun autoDeleteLabel(secs: Double): String = when (secs) {
    30.0 -> stringResource(R.string.s30)
    300.0 -> stringResource(R.string.m5)
    3600.0 -> stringResource(R.string.h1)
    8 * 3600.0 -> stringResource(R.string.h8)
    24 * 3600.0 -> stringResource(R.string.d1)
    7 * 24 * 3600.0 -> stringResource(R.string.w1)
    else -> "${secs.toInt()} s"
}

private fun qrBitmap(payload: ByteArray, availablePx: Int): Bitmap? = runCatching {
    val matrix = KeyQr.matrix(payload)
    val modules = matrix.width
    val scale = KeyQr.scaleFor(modules, availablePx)
    val size = modules * scale
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val my = y / scale
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] =
                if (matrix[x / scale, my]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}.getOrNull()

private val QR_PLATE_PADDING = 6.dp

private class QrRender(val bitmap: Bitmap?)

private fun hostWindow(view: View): Window? {
    var node: Any? = view
    while (node is View) {
        if (node is DialogWindowProvider) return node.window
        node = node.parent
    }
    return (view.context as? Activity)?.window
}

@Composable
private fun KeepScreenBright() {
    val view = LocalView.current
    val window = remember(view) { hostWindow(view) }
    DisposableEffect(window) {
        val previous = window?.attributes?.screenBrightness
        window?.let { it.attributes = it.attributes.apply { screenBrightness = 1f } }
        onDispose {
            if (window != null && previous != null) {
                window.attributes = window.attributes.apply { screenBrightness = previous }
            }
        }
    }
}

@Composable
private fun MyKeySheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val share by produceState<SignalService.KeyShare?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { runCatching { SignalService.myKeyShare() }.getOrNull() }
    }
    val keyString = share?.text ?: ""
    val safety by SignalService.mySafetyNumber.collectAsState()
    val profiles by SignalService.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    val currentID by SignalService.currentID.collectAsState()
    var showQR by remember { mutableStateOf(false) }

    KSheet(stringResource(R.string.my_key), onDismiss) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(K.accent.copy(alpha = 0.12f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Person, null, Modifier.size(18.dp), tint = K.accent)
            Spacer(Modifier.width(8.dp))
            FieldLabel(stringResource(R.string.key_for_profile))
            Spacer(Modifier.width(6.dp))
            Text(
                profiles.firstOrNull { it.id == currentID }?.name ?: "",
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = K.textPrimary,
            )
        }

        if (showQR) {
            KeepScreenBright()
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    val availablePx = with(density) { (maxWidth - QR_PLATE_PADDING * 2).roundToPx() }
                    val rendered by produceState<QrRender?>(null, share, availablePx) {
                        val payload = share?.payload ?: return@produceState
                        value = QrRender(withContext(Dispatchers.Default) { qrBitmap(payload, availablePx) })
                    }
                    val qr = rendered?.bitmap
                    if (qr != null) {
                        Box(
                            Modifier
                                .shadow(10.dp, RoundedCornerShape(KShape.cornerSmall))
                                .clip(RoundedCornerShape(KShape.cornerSmall))
                                .background(Color.White)
                                .padding(QR_PLATE_PADDING)
                        ) {
                            Image(
                                qr.asImageBitmap(),
                                null,
                                Modifier.size(with(density) { qr.width.toDp() }),
                                filterQuality = FilterQuality.None,
                            )
                        }
                    } else if (rendered != null) {
                        Banner(stringResource(R.string.qr_unavailable), BannerKind.Warning)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.quietClickable { showQR = false },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.VisibilityOff, null, Modifier.size(16.dp), tint = K.accent)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.hide_qr), fontSize = 14.sp, color = K.accent)
                }
            }
        } else {
            GlassCard(onClick = { if (keyString.isNotEmpty()) showQR = true }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.QrCode, null, Modifier.size(44.dp), tint = K.accent)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.show_qr),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = K.textPrimary,
                    )
                    Text(
                        stringResource(R.string.qr_scan_hint),
                        fontSize = 14.sp, color = K.textSecondary, textAlign = TextAlign.Center,
                    )
                }
            }
        }

        GlassCard(spacing = 8.dp) {
            FieldLabel(stringResource(R.string.safety_number))
            Text(
                safety,
                fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold, color = K.accent,
            )
            Text(
                stringResource(R.string.key_public_hint) + " " + stringResource(R.string.key_hint),
                fontSize = 13.sp, lineHeight = 18.sp, color = K.textSecondary,
            )
        }

        Row {
            SecondaryButton(
                stringResource(R.string.copy_key),
                Modifier.weight(1f),
                icon = Icons.Outlined.FileCopy,
                accent = true,
                enabled = keyString.isNotEmpty(),
            ) { copySensitive(context, keyString, context.getString(R.string.copied)) }
            Spacer(Modifier.width(12.dp))
            PrimaryButton(
                stringResource(R.string.share),
                Modifier.weight(1f),
                icon = Icons.Default.Share,
                enabled = keyString.isNotEmpty(),
                busy = keyString.isEmpty(),
            ) { shareText(context, keyString) }
        }
    }
}

@Composable
private fun AddContactSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var scanned by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var cameraDenied by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let {
            scanned = it.toByteArray(Charsets.ISO_8859_1)
            key = ""
            error = null
        }
    }
    val startScan = {
        cameraDenied = false
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setBeepEnabled(false)
                .setCaptureActivity(PortraitCaptureActivity::class.java)
                .addExtra("TRY_HARDER", true)
                .addExtra("CHARACTER_SET", KeyQr.CHARSET)
        )
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startScan() else cameraDenied = true
    }

    KSheet(stringResource(R.string.add_contact), onDismiss) {
        GlassCard {
            FieldLabel(stringResource(R.string.contact_name))
            KTextField(name, { name = it }, maxLines = 1)
            FieldLabel(stringResource(R.string.paste_key))
            KTextField(key, { key = it; scanned = null }, mono = true, minLines = 3, maxLines = 6)
            if (scanned != null) Banner(stringResource(R.string.scan_captured), BannerKind.Success)
            SecondaryButton(
                stringResource(R.string.scan_qr),
                Modifier.fillMaxWidth(),
                icon = Icons.Default.QrCodeScanner,
                accent = true,
            ) {
                if (context.checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    startScan()
                } else {
                    cameraLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            SecondaryButton(
                stringResource(R.string.paste),
                Modifier.fillMaxWidth(),
                icon = Icons.Default.ContentPaste,
                accent = true,
            ) { key = clipboardText(context); scanned = null }
            PrimaryButton(
                stringResource(R.string.add),
                Modifier.fillMaxWidth(),
                icon = Icons.Default.Add,
                enabled = (key.isNotBlank() || scanned != null) && !adding,
                busy = adding,
            ) {
                val raw = key
                val payload = scanned
                val display = name.trim()
                error = null
                adding = true
                scope.launch {
                    val outcome = withContext(Dispatchers.Default + NonCancellable) {
                        runCatching {
                            if (payload != null) SignalService.addContactFromScan(payload, display)
                            else SignalService.addContact(raw, display)
                        }
                    }
                    adding = false
                    outcome.onSuccess { onDismiss() }.onFailure { e ->
                        error = context.getString(
                            when {
                                e is com.kryptos.android.signal.OwnKeyException -> R.string.add_own_key
                                e is com.kryptos.android.signal.StorageUnavailableException ||
                                    e is IllegalStateException -> R.string.storage_unavailable
                                payload != null -> R.string.scan_unreadable
                                else -> R.string.not_a_kryptos_message
                            }
                        )
                    }
                }
            }
            if (cameraDenied) {
                Banner(stringResource(R.string.camera_denied), BannerKind.Warning)
                SecondaryButton(
                    stringResource(R.string.open_app_settings),
                    Modifier.fillMaxWidth(),
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                ) { openAppSettings(context) }
            }
            error?.let { Banner(it, BannerKind.Error) }
        }
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
        )
    }
}

@Composable
private fun ProfilesSheet(onDismiss: () -> Unit) {
    val profiles by SignalService.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    val currentID by SignalService.currentID.collectAsState()
    var newName by remember { mutableStateOf("") }
    var confirmRegenerate by remember { mutableStateOf(false) }
    var confirmDeleteProfile by remember { mutableStateOf<com.kryptos.android.signal.Profile?>(null) }

    KSheet(stringResource(R.string.profiles), onDismiss) {
        GlassCard(spacing = 0.dp) {
            profiles.forEachIndexed { i, p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .quietClickable {
                            scope.launch(Dispatchers.Default + NonCancellable) { SignalService.switchTo(p.id) }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (p.id == currentID) Icons.Default.Check else Icons.Default.Person,
                        null, Modifier.size(18.dp),
                        tint = if (p.id == currentID) K.accent else K.textSecondary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(p.name, fontSize = 15.sp, color = K.textPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                    if (profiles.size > 1) {
                        Icon(
                            Icons.Default.Close, stringResource(R.string.delete),
                            Modifier
                                .size(18.dp)
                                .quietClickable { confirmDeleteProfile = p },
                            tint = K.danger,
                        )
                    }
                }
                if (i < profiles.lastIndex) CardDivider()
            }
        }

        GlassCard {
            FieldLabel(stringResource(R.string.new_profile))
            Row(verticalAlignment = Alignment.CenterVertically) {
                KTextField(
                    newName, { newName = it },
                    Modifier.weight(1f),
                    placeholder = stringResource(R.string.profile_name),
                    maxLines = 1,
                )
                Spacer(Modifier.width(12.dp))
                PrimaryButton(
                    stringResource(R.string.add),
                    icon = Icons.Default.Add,
                    enabled = newName.isNotBlank(),
                ) {
                    val name = newName
                    newName = ""
                    scope.launch(Dispatchers.Default + NonCancellable) { SignalService.createProfile(name) }
                }
            }
        }

        confirmDeleteProfile?.let { p ->
            ConfirmDialog(
                title = stringResource(R.string.confirm_delete_profile_title),
                text = stringResource(R.string.confirm_delete_profile_text),
                confirmLabel = stringResource(R.string.delete),
                onConfirm = {
                    scope.launch(Dispatchers.Default + NonCancellable) { SignalService.deleteProfile(p.id) }
                },
                onDismiss = { confirmDeleteProfile = null },
            )
        }

        if (confirmRegenerate) {
            Banner(stringResource(R.string.regenerate_warning), BannerKind.Warning)
            Row {
                SecondaryButton(stringResource(R.string.cancel), Modifier.weight(1f)) { confirmRegenerate = false }
                Spacer(Modifier.width(12.dp))
                SecondaryButton(
                    stringResource(R.string.regenerate_key),
                    Modifier.weight(1f),
                    danger = true,
                ) {
                    scope.launch(Dispatchers.Default + NonCancellable) {
                        SignalService.regenerateCurrentIdentity()
                    }
                    confirmRegenerate = false
                }
            }
        } else {
            SecondaryButton(
                stringResource(R.string.regenerate_key),
                Modifier.fillMaxWidth(),
            ) { confirmRegenerate = true }
        }
    }
}

@Composable
private fun RowScope.ChatInput(onSend: suspend (String) -> Boolean) {
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(24.dp)
    Spacer(Modifier.width(10.dp))
    BasicTextField(
        draft, { draft = it },
        modifier = Modifier
            .weight(1f)
            .clip(shape)
            .background(K.card)
            .border(1.dp, K.hairline, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        textStyle = TextStyle(fontSize = 16.sp, color = K.textPrimary),
        cursorBrush = SolidColor(K.accent),
        maxLines = 4,
        decorationBox = { inner ->
            Box {
                if (draft.isEmpty()) Text(stringResource(R.string.message), fontSize = 16.sp, color = K.textSecondary)
                inner()
            }
        },
    )
    Spacer(Modifier.width(10.dp))
    GlassIconButton(
        Icons.Default.Lock, stringResource(R.string.encrypt_and_copy),
        size = 48.dp, filled = true,
        enabled = draft.isNotBlank() && !sending,
    ) {
        if (sending) return@GlassIconButton
        val text = draft.trim()
        sending = true
        scope.launch {
            if (onSend(text)) draft = ""
            sending = false
        }
    }
}
