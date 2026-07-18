package com.kryptos.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kryptos.android.R
import com.kryptos.android.core.CipherException
import com.kryptos.android.core.ImageBridge
import com.kryptos.android.core.LsbStego
import com.kryptos.android.core.PasswordCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StegoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var hiding by remember { mutableStateOf(true) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var message by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf("") }
    var pngToSave by remember { mutableStateOf<ByteArray?>(null) }

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        pickedUri = uri
        status = null
        revealed = ""
        pngToSave = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { onPicked(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { onPicked(it) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bytes = pngToSave
        if (uri != null && bytes != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
            }.getOrDefault(false)
            status = context.getString(if (ok) R.string.saved_as_file else R.string.save_failed)
            isError = !ok
            if (ok) pngToSave = null
        }
    }

    LaunchedEffect(pickedUri) {
        val uri = pickedUri
        preview = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) > 900) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    ?.rotated(exifRotation(context, uri))
            }.getOrNull()
        }
    }

    KScreen(
        stringResource(R.string.tab_stego),
        modifier,
        subtitle = stringResource(R.string.stego_hint),
    ) {
        KSegmented(
            listOf(stringResource(R.string.stego_hide), stringResource(R.string.stego_reveal)),
            selected = if (hiding) 0 else 1,
            onSelect = { hiding = it == 0; status = null; revealed = ""; pngToSave = null },
        )

        GlassCard {
            FieldLabel(stringResource(if (hiding) R.string.label_cover_photo else R.string.label_stego_photo))
            val shape = RoundedCornerShape(KShape.cornerSmall)
            val bmp = preview
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), null,
                    Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(shape)
                        .quietClickable { picker.launch("image/*") },
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .clip(shape)
                        .background(K.fieldFill)
                        .border(1.dp, K.hairline, shape)
                        .quietClickable { picker.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AddPhotoAlternate, null, tint = K.accent)
                        Spacer(Modifier.height(0.dp))
                        Text(
                            "  " + stringResource(R.string.pick_photo),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = K.accent,
                        )
                    }
                }
            }
            SecondaryButton(
                stringResource(R.string.pick_file),
                Modifier.fillMaxWidth(),
                icon = Icons.Default.FolderOpen,
            ) { filePicker.launch(arrayOf("image/*")) }
        }

        GlassCard(spacing = 14.dp) {
            FieldLabel(stringResource(R.string.password))
            KTextField(
                password, { password = it },
                placeholder = stringResource(R.string.shared_secret),
                password = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (hiding) {
                FieldLabel(stringResource(R.string.label_secret_message))
                KTextField(message, { message = it }, minLines = 4, maxLines = 8)
            }
            PrimaryButton(
                stringResource(if (hiding) R.string.stego_hide_action else R.string.stego_reveal_action),
                Modifier.fillMaxWidth(),
                icon = if (hiding) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                enabled = pickedUri != null && password.isNotEmpty() && (!hiding || message.isNotBlank()),
                busy = busy,
            ) {
                status = null
                val uri = pickedUri ?: return@PrimaryButton
                busy = true
                scope.launch(Dispatchers.Default) {
                    try {
                        val degrees = if (hiding) exifRotation(context, uri) else 0f
                        val pixels = (
                            if (degrees != 0f) {
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    BitmapFactory.decodeStream(stream)?.rotated(degrees)?.let { ImageBridge.rgba(it) }
                                }
                            } else {
                                context.contentResolver.openInputStream(uri)?.use { ImageBridge.rgba(it) }
                            }
                        ) ?: throw CipherException(CipherException.Kind.INVALID_INPUT)
                        if (hiding) {
                            val blob = PasswordCipher.encrypt(message.toByteArray(Charsets.UTF_8), password)
                            if (blob.size > LsbStego.capacity(pixels.rgba.size / 4)) {
                                throw CipherException(CipherException.Kind.STEGO_CAPACITY_EXCEEDED)
                            }
                            val stego = LsbStego.embed(blob, pixels.rgba)
                            pngToSave = ImageBridge.pngData(stego, pixels.width, pixels.height)
                            withContext(Dispatchers.Main) { saver.launch("kryptos.png") }
                        } else {
                            val blob = LsbStego.extract(pixels.rgba)
                            revealed = String(PasswordCipher.decrypt(blob, password), Charsets.UTF_8)
                            isError = false
                        }
                    } catch (e: CipherException) {
                        isError = true
                        status = when (e.kind) {
                            CipherException.Kind.STEGO_CAPACITY_EXCEEDED -> context.getString(R.string.capacity_exceeded)
                            CipherException.Kind.NO_HIDDEN_DATA -> context.getString(R.string.no_hidden_data)
                            CipherException.Kind.DECRYPTION_FAILED -> context.getString(R.string.wrong_password)
                            else -> e.kind.name
                        }
                    } catch (e: OutOfMemoryError) {
                        isError = true
                        status = context.getString(R.string.capacity_exceeded)
                    } catch (e: Exception) {
                        isError = true
                        status = e.message
                    } finally {
                        busy = false
                    }
                }
            }
            if (hiding) {
                Text(
                    stringResource(R.string.stego_file_hint),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = K.textSecondary,
                )
            }
        }

        status?.let { Banner(it, if (isError) BannerKind.Error else BannerKind.Success) }

        if (revealed.isNotEmpty()) {
            GlassCard {
                FieldLabel(stringResource(R.string.label_hidden_message))
                Text(revealed, fontSize = 16.sp, color = K.textPrimary)
                SecondaryButton(
                    stringResource(R.string.copy),
                    Modifier.fillMaxWidth(),
                ) { copySensitive(context, revealed, context.getString(R.string.copied)) }
            }
        }
    }
}

private fun exifRotation(context: Context, uri: Uri): Float = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
}.getOrDefault(0f)

private fun Bitmap.rotated(degrees: Float): Bitmap =
    if (degrees == 0f) this
    else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees) }, true)
