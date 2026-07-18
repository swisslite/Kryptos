package com.kryptos.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kryptos.android.R
import com.kryptos.android.pgp.PgpAlgo
import com.kryptos.android.pgp.PgpService
import com.kryptos.android.pgp.PgpVerification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PgpScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { runCatching { PgpService.ensureInitialized() } } }
    val identities by PgpService.identities.collectAsState()
    val currentID by PgpService.currentID.collectAsState()
    val recipients by PgpService.recipients.collectAsState()
    val busy by PgpService.busy.collectAsState()
    val current = identities.firstOrNull { it.id == currentID }

    var encrypting by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    var selectedRecipient by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("") }
    var verification by remember { mutableStateOf<PgpVerification?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoCopied by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showRecipients by remember { mutableStateOf(false) }

    KScreen(stringResource(R.string.tab_pgp), modifier) {
        if (busy && identities.isEmpty()) {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = K.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.pgp_busy), fontSize = 14.sp, color = K.textSecondary)
                }
            }
            return@KScreen
        }

        GlassCard(onClick = { showKeys = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, null, Modifier.size(24.dp), tint = K.accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    FieldLabel(stringResource(R.string.pgp_your_key_manage))
                    Text(
                        current?.name ?: stringResource(R.string.my_key),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = K.textPrimary,
                    )
                    Text(
                        current?.fingerprint ?: "",
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = K.textSecondary, maxLines = 1,
                    )
                }
                Text("›", fontSize = 20.sp, color = K.accent)
            }
        }

        Row {
            KeyTile(stringResource(R.string.pgp_identities), Icons.Default.Key, Modifier.weight(1f)) { showKeys = true }
            Spacer(Modifier.width(10.dp))
            KeyTile(stringResource(R.string.share), Icons.Default.Share, Modifier.weight(1f)) { showShare = true }
            Spacer(Modifier.width(10.dp))
            KeyTile(stringResource(R.string.pgp_recipients), Icons.Default.People, Modifier.weight(1f)) { showRecipients = true }
        }

        KSegmented(
            listOf(stringResource(R.string.encrypt), stringResource(R.string.decrypt)),
            selected = if (encrypting) 0 else 1,
            onSelect = { encrypting = it == 0; result = ""; error = null; verification = null; autoCopied = false },
        )

        GlassCard(spacing = 14.dp) {
            if (encrypting) {
                FieldLabel(stringResource(R.string.label_recipient))
                RecipientMenuField(
                    recipients = recipients.map { it.id to it.name },
                    selected = selectedRecipient,
                    onPick = { selectedRecipient = it },
                )
            }
            FieldLabel(stringResource(if (encrypting) R.string.label_message_text else R.string.label_pgp_message))
            KTextField(text, { text = it }, mono = !encrypting, minLines = 5, maxLines = 10)
            Row {
                SecondaryButton(
                    stringResource(R.string.paste),
                    Modifier.weight(1f),
                    icon = Icons.Default.ContentPaste,
                ) { text = clipboardText(context) }
                Spacer(Modifier.width(12.dp))
                PrimaryButton(
                    stringResource(if (encrypting) R.string.encrypt else R.string.decrypt),
                    Modifier.weight(1f),
                    icon = if (encrypting) Icons.Default.Lock else Icons.Default.LockOpen,
                    enabled = text.isNotBlank() && !busy && (!encrypting || selectedRecipient != null),
                ) {
                    error = null; verification = null; autoCopied = false
                    scope.launch(Dispatchers.Default) {
                        try {
                            if (encrypting) {
                                val recipient = recipients.firstOrNull { it.id == selectedRecipient }
                                    ?: throw Exception(context.getString(R.string.pgp_choose_recipient))
                                result = PgpService.encrypt(text, recipient)
                                copySensitive(context, result, null)
                                autoCopied = true
                            } else {
                                val (plain, ver) = PgpService.decrypt(text)
                                result = plain
                                verification = ver
                            }
                        } catch (e: Exception) {
                            result = ""
                            error = e.message
                        }
                    }
                }
            }
        }

        error?.let { Banner(it, BannerKind.Error) }
        if (!encrypting && result.isNotEmpty()) {
            verification?.let {
                if (it == PgpVerification.VERIFIED) {
                    Banner(stringResource(R.string.pgp_verified_banner), BannerKind.Success)
                } else {
                    Banner(stringResource(R.string.pgp_unverified_banner), BannerKind.Warning)
                }
            }
        }
        if (autoCopied) Banner(stringResource(R.string.copied_banner), BannerKind.Success)

        if (result.isNotEmpty()) {
            GlassCard {
                FieldLabel(stringResource(if (encrypting) R.string.label_encrypted_send else R.string.label_decrypted))
                Text(
                    result,
                    fontSize = if (encrypting) 13.sp else 16.sp,
                    fontFamily = if (encrypting) FontFamily.Monospace else null,
                    color = K.textPrimary,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Row {
                    SecondaryButton(
                        stringResource(R.string.copy),
                        Modifier.weight(1f),
                        icon = Icons.Outlined.FileCopy,
                    ) { copySensitive(context, result, context.getString(R.string.copied)) }
                    Spacer(Modifier.width(12.dp))
                    PrimaryButton(
                        stringResource(R.string.share),
                        Modifier.weight(1f),
                        icon = Icons.Default.Share,
                    ) { shareText(context, result) }
                }
            }
        }
    }

    if (showKeys) PgpKeysSheet(onDismiss = { showKeys = false })
    if (showShare) PgpShareSheet(onDismiss = { showShare = false })
    if (showRecipients) PgpRecipientsSheet(onDismiss = { showRecipients = false })
}

@Composable
private fun KeyTile(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(KShape.cornerSmall)
    Column(
        modifier
            .clip(shape)
            .background(K.card)
            .border(1.dp, K.hairline, shape)
            .quietClickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = K.accent)
        Spacer(Modifier.size(6.dp))
        Text(
            title,
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp, color = K.textPrimary, maxLines = 1,
        )
    }
}

@Composable
private fun RecipientMenuField(
    recipients: List<Pair<String, String>>,
    selected: String?,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(KShape.cornerSmall)
    val selectedName = recipients.firstOrNull { it.first == selected }?.second
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(K.fieldFill)
                .border(1.dp, K.hairline, shape)
                .quietClickable(enabled = recipients.isNotEmpty()) { open = true }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selectedName
                    ?: stringResource(if (recipients.isEmpty()) R.string.pgp_add_first else R.string.pgp_choose_recipient),
                fontSize = 16.sp,
                color = if (selectedName == null) K.textSecondary else K.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.UnfoldMore, null, Modifier.size(18.dp), tint = K.textSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            recipients.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, color = K.textPrimary) },
                    onClick = { open = false; onPick(id) },
                )
            }
        }
    }
}

@Composable
private fun PgpKeysSheet(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val identities by PgpService.identities.collectAsState()
    val currentID by PgpService.currentID.collectAsState()
    val busy by PgpService.busy.collectAsState()

    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var algo by remember { mutableStateOf(PgpAlgo.CURVE25519) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }

    KSheet(stringResource(R.string.pgp_keys_title), onDismiss) {
        GlassCard {
            FieldLabel(stringResource(R.string.pgp_create_new))
            KTextField(name, { name = it }, placeholder = stringResource(R.string.pgp_name), maxLines = 1)
            KTextField(email, { email = it }, placeholder = stringResource(R.string.pgp_email), maxLines = 1)
            MenuRow(
                stringResource(R.string.pgp_algo),
                options = PgpAlgo.entries.map { it.label },
                selected = PgpAlgo.entries.indexOf(algo),
                onPick = { algo = PgpAlgo.entries[it] },
            )
            PrimaryButton(
                stringResource(if (busy) R.string.pgp_busy else R.string.pgp_generate),
                Modifier.fillMaxWidth(),
                icon = Icons.Default.Add,
                enabled = name.isNotBlank() && !busy,
                busy = busy,
            ) {
                val n = name.trim(); val e = email.trim()
                name = ""; email = ""
                scope.launch(Dispatchers.Default) { PgpService.generateBlocking(n, e, algo) }
            }
        }

        identities.forEach { ident ->
            GlassCard(spacing = 8.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (ident.id == currentID) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        null, Modifier.size(22.dp),
                        tint = if (ident.id == currentID) K.accent else K.textSecondary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ident.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = K.textPrimary)
                        FieldLabel(ident.algo)
                    }
                    if (ident.id != currentID) {
                        SecondaryButton(stringResource(R.string.pgp_use)) { PgpService.switchTo(ident.id) }
                    }
                }
                Text(
                    ident.fingerprint,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = K.textSecondary, maxLines = 2,
                )
                if (identities.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier.quietClickable(enabled = !busy) { confirmDeleteId = ident.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = K.danger)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.delete), fontSize = 14.sp, color = K.danger)
                        }
                    }
                }
            }
        }

        confirmDeleteId?.let { id ->
            ConfirmDialog(
                title = stringResource(R.string.confirm_delete_pgp_title),
                text = stringResource(R.string.confirm_delete_pgp_text),
                confirmLabel = stringResource(R.string.delete),
                onConfirm = { scope.launch(Dispatchers.Default) { PgpService.deleteIdentity(id) } },
                onDismiss = { confirmDeleteId = null },
            )
        }
    }
}

@Composable
private fun PgpShareSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val key = PgpService.myPublicKey
    val name = PgpService.currentIdentity?.name ?: stringResource(R.string.my_key)

    KSheet(name, onDismiss) {
        Text(stringResource(R.string.pgp_share_hint), fontSize = 14.sp, lineHeight = 19.sp, color = K.textSecondary)
        val shape = RoundedCornerShape(KShape.cornerSmall)
        Text(
            key,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = K.textPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(K.fieldFill)
                .border(1.dp, K.hairline, shape)
                .padding(12.dp)
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState()),
        )
        Row {
            SecondaryButton(
                stringResource(R.string.copy),
                Modifier.weight(1f),
                icon = Icons.Outlined.FileCopy,
                enabled = key.isNotEmpty(),
            ) { copySensitive(context, key, context.getString(R.string.copied)) }
            Spacer(Modifier.width(12.dp))
            PrimaryButton(
                stringResource(R.string.share),
                Modifier.weight(1f),
                icon = Icons.Default.Share,
                enabled = key.isNotEmpty(),
            ) { shareText(context, key) }
        }
    }
}

@Composable
private fun PgpRecipientsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val recipients by PgpService.recipients.collectAsState()
    var name by remember { mutableStateOf("") }
    var keyText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    KSheet(stringResource(R.string.pgp_recipients), onDismiss) {
        GlassCard {
            FieldLabel(stringResource(R.string.pgp_name))
            KTextField(name, { name = it }, maxLines = 1)
            FieldLabel(stringResource(R.string.pgp_their_key))
            KTextField(keyText, { keyText = it }, mono = true, minLines = 4, maxLines = 7)
            Row {
                SecondaryButton(
                    stringResource(R.string.paste),
                    Modifier.weight(1f),
                    icon = Icons.Default.ContentPaste,
                ) { keyText = clipboardText(context) }
                Spacer(Modifier.width(12.dp))
                PrimaryButton(
                    stringResource(R.string.add),
                    Modifier.weight(1f),
                    icon = Icons.Default.Add,
                    enabled = name.isNotBlank() && keyText.isNotBlank(),
                ) {
                    error = null
                    try {
                        PgpService.addRecipient(name.trim(), keyText.trim())
                        name = ""; keyText = ""
                    } catch (e: Exception) {
                        error = e.message ?: context.getString(R.string.pgp_invalid_key)
                    }
                }
            }
            error?.let { Banner(it, BannerKind.Error) }
        }

        recipients.forEach { r ->
            GlassCard(spacing = 4.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.name,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = K.textPrimary, modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.Delete, stringResource(R.string.delete),
                        Modifier
                            .size(20.dp)
                            .quietClickable { PgpService.removeRecipient(r) },
                        tint = K.danger,
                    )
                }
                if (r.fingerprint.isNotEmpty()) {
                    Text(
                        r.fingerprint,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = K.textSecondary, maxLines = 1,
                    )
                }
            }
        }
    }
}
