package com.kryptos.android.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object KShape {
    val corner = 20.dp
    val cornerSmall = 14.dp
}

@Composable
fun ScreenBackground(modifier: Modifier = Modifier) {
    val accent = K.accent
    val bg = K.bg
    Box(
        modifier
            .fillMaxSize()
            .background(bg)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width, 0f),
                        radius = size.width * 1.05f,
                    )
                )
            }
    )
}

@Composable
fun KScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(2.dp))
        if (onBack != null) {
            Row(
                Modifier.quietClickable(onClick = onBack),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBackIos, null,
                    Modifier.size(15.dp), tint = K.accent,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    backLabel ?: "",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = K.accent,
                )
            }
        }
        Text(
            title,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = K.textPrimary,
        )
        if (subtitle != null) {
            Text(subtitle, fontSize = 14.sp, lineHeight = 19.sp, color = K.textSecondary)
        }
        content()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    spacing: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(KShape.corner)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(6.dp, shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(shape)
            .background(K.card)
            .border(1.dp, K.hairline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) { content() }
}

@Composable
fun CardDivider() {
    HorizontalDivider(color = K.hairline, thickness = 1.dp)
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = K.textSecondary,
    )
}

@Composable
fun SectionHeader(text: String) {
    FieldLabel(text, Modifier.padding(start = 4.dp, top = 6.dp))
}

@Composable
fun FooterText(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = K.textSecondary,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
fun KTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    mono: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val shape = RoundedCornerShape(KShape.cornerSmall)
    val singleLine = minLines == 1 && (password || maxLines == 1)
    val style = TextStyle(
        fontSize = if (mono) 13.sp else 16.sp,
        fontFamily = if (mono) FontFamily.Monospace else null,
        color = K.textPrimary,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(K.fieldFill)
            .border(1.dp, K.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        textStyle = style,
        cursorBrush = SolidColor(K.accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else maxLines,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, style = style.copy(color = K.textSecondary))
                }
                inner()
            }
        },
    )
}

@Composable
fun KSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(track)
            .background(K.fieldFill)
            .border(1.dp, K.hairline, track)
            .padding(3.dp)
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            val chip = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .weight(1f)
                    .then(if (sel) Modifier.shadow(3.dp, chip) else Modifier)
                    .clip(chip)
                    .background(if (sel) K.segment else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (sel) K.textPrimary else K.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(KShape.cornerSmall)
    val active = enabled && !busy
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, active)
    val dim by animateFloatAsState(if (active) 1f else 0.45f, tween(200), label = "dim")
    val gradient = Brush.verticalGradient(
        listOf(K.accentBright.copy(alpha = dim), K.accent.copy(alpha = dim))
    )
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .heightIn(min = 50.dp)
            .then(
                if (active) Modifier.shadow(
                    8.dp, shape,
                    ambientColor = K.accent.copy(alpha = 0.55f),
                    spotColor = K.accent.copy(alpha = 0.55f),
                ) else Modifier
            )
            .clip(shape)
            .background(gradient)
            .clickable(interactionSource = interaction, indication = null, enabled = active, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(KShape.cornerSmall)
    val tint = if (danger) K.danger else K.textPrimary
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, enabled)
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .heightIn(min = 50.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(K.card)
            .border(1.dp, K.hairline, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = K.accent,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, enabled, pressedScale = 0.9f)
    val dim by animateFloatAsState(if (enabled) 1f else 0.55f, tween(200), label = "dim")
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(dim)
            .then(
                if (filled) Modifier.shadow(
                    8.dp, CircleShape,
                    ambientColor = K.accent.copy(alpha = 0.5f),
                    spotColor = K.accent.copy(alpha = 0.5f),
                ) else Modifier
            )
            .clip(CircleShape)
            .background(
                if (filled) Brush.verticalGradient(listOf(K.accentBright, K.accent))
                else SolidColor(K.card)
            )
            .border(1.dp, if (filled) Color.Transparent else K.hairline, CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(size * 0.45f), tint = if (filled) Color.White else tint)
    }
}

enum class BannerKind { Error, Success, Warning }

@Composable
fun Banner(text: String, kind: BannerKind, modifier: Modifier = Modifier) {
    val color = when (kind) {
        BannerKind.Error -> K.danger
        BannerKind.Success -> K.success
        BannerKind.Warning -> Color(0xFFD9940F)
    }
    val icon = when (kind) {
        BannerKind.Error, BannerKind.Warning -> Icons.Default.Warning
        BannerKind.Success -> Icons.Default.CheckCircle
    }
    val shape = RoundedCornerShape(KShape.cornerSmall)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.sp, lineHeight = 19.sp, color = K.textPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Avatar(name: String, size: Dp = 44.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(K.accent.copy(alpha = 0.26f), K.accent.copy(alpha = 0.12f))
                )
            )
            .border(1.dp, K.accent.copy(alpha = 0.25f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "•" },
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = K.accent,
        )
    }
}

@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = K.textPrimary, lineHeight = 20.sp)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = K.textSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = K.accent,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = K.textPrimary.copy(alpha = 0.12f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    value: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = K.accent)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            title,
            fontSize = 15.sp,
            color = K.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                value,
                fontSize = 14.sp,
                color = K.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 6.dp),
            )
        }
        Text("›", fontSize = 17.sp, color = K.textSecondary)
    }
}

@Composable
fun MenuRow(
    title: String,
    options: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { open = true }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 15.sp, color = K.textPrimary, modifier = Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(options.getOrNull(selected) ?: "", fontSize = 14.sp, color = K.textSecondary)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Default.UnfoldMore, null, Modifier.size(16.dp), tint = K.textSecondary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, label ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                (if (i == selected) "✓ " else "") + label,
                                color = if (i == selected) K.accent else K.textPrimary,
                            )
                        },
                        onClick = { open = false; onPick(i) },
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = K.surface,
        titleContentColor = K.textPrimary,
        textContentColor = K.textSecondary,
        title = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = { Text(text, fontSize = 14.sp, lineHeight = 19.sp) },
        confirmButton = {
            Text(
                confirmLabel,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = K.danger,
                modifier = Modifier
                    .quietClickable { onDismiss(); onConfirm() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        },
        dismissButton = {
            Text(
                stringResource(com.kryptos.android.R.string.cancel),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = K.accent,
                modifier = Modifier
                    .quietClickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = K.surface,
        contentColor = K.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                title,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = K.textPrimary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            content()
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
fun Modifier.quietClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )

@Composable
fun Modifier.bouncyClickable(enabled: Boolean = true, pressedScale: Float = 0.94f, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun rememberPressScale(interaction: MutableInteractionSource, enabled: Boolean = true, pressedScale: Float = 0.97f): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale",
    )
    return scale
}
