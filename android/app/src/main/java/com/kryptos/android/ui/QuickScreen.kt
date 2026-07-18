package com.kryptos.android.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kryptos.android.R
import com.kryptos.android.core.KryptosCore
import com.kryptos.android.signal.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun QuickScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var encrypting by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var autoCopied by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun reset() {
        result = ""; error = null; autoCopied = false
    }

    KScreen(
        stringResource(R.string.tab_quick),
        modifier,
        subtitle = stringResource(R.string.quick_subtitle),
    ) {
        KSegmented(
            listOf(stringResource(R.string.encrypt), stringResource(R.string.decrypt)),
            selected = if (encrypting) 0 else 1,
            onSelect = { encrypting = it == 0; reset() },
        )

        GlassCard {
            FieldLabel(stringResource(R.string.password))
            KTextField(
                password, { password = it },
                placeholder = stringResource(R.string.shared_secret),
                password = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            FieldLabel(stringResource(if (encrypting) R.string.label_message_text else R.string.label_encrypted_block))
            KTextField(
                text,
                {
                    text = it
                    autoCopied = false
                    if (encrypting && KryptosCore.containsMessage(it)) { encrypting = false; result = "" }
                },
                mono = !encrypting,
                minLines = 5,
                maxLines = 9,
            )
            Row {
                SecondaryButton(
                    stringResource(R.string.paste),
                    Modifier.weight(1f),
                    icon = Icons.Default.ContentPaste,
                ) {
                    text = clipboardText(context)
                    autoCopied = false
                    if (encrypting && KryptosCore.containsMessage(text)) { encrypting = false; result = "" }
                }
                Spacer(Modifier.width(12.dp))
                PrimaryButton(
                    stringResource(if (encrypting) R.string.encrypt else R.string.decrypt),
                    Modifier.weight(1f),
                    icon = if (encrypting) Icons.Default.Lock else Icons.Default.LockOpen,
                    enabled = text.isNotBlank() && password.isNotEmpty(),
                    busy = busy,
                ) {
                    error = null
                    autoCopied = false
                    busy = true
                    scope.launch(Dispatchers.Default) {
                        try {
                            result = if (encrypting) {
                                KryptosCore.encrypt(text, password, AppSettingsStore.lengthPadding).also {
                                    copySensitive(context, it, null)
                                    autoCopied = true
                                }
                            } else {
                                KryptosCore.decrypt(text, password)
                            }
                        } catch (e: Exception) {
                            result = ""
                            error = context.getString(
                                if (encrypting) R.string.not_a_kryptos_message else R.string.wrong_password
                            )
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        }

        error?.let { Banner(it, BannerKind.Error) }
        if (autoCopied) Banner(stringResource(R.string.copied_banner), BannerKind.Success)

        if (result.isNotEmpty()) {
            GlassCard {
                FieldLabel(stringResource(if (encrypting) R.string.label_ready else R.string.label_decrypted))
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
}
