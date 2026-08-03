package com.kryptos.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.kryptos.android.R
import com.kryptos.android.security.AppLock
import com.kryptos.android.signal.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LockGate(activity: FragmentActivity, content: @Composable () -> Unit) {
    val locked by AppLock.locked.collectAsState()
    val shielded by AppLock.shielded.collectAsState()

    Box(Modifier.fillMaxSize()) {
        if (!locked) content()

        if (locked) {
            LockScreen(activity)
        } else if (shielded) {
            Box(Modifier.fillMaxSize().background(K.bg))
        }
    }
}

@Composable
private fun LockScreen(activity: FragmentActivity) {
    var code by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { AppLock.prompt(activity) }

    Box(Modifier.fillMaxSize()) {
        ScreenBackground()
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Box(
                Modifier
                    .shadow(
                        12.dp, RoundedCornerShape(18.dp),
                        ambientColor = K.accent.copy(alpha = 0.6f),
                        spotColor = K.accent.copy(alpha = 0.6f),
                    )
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.verticalGradient(listOf(K.accentBright, K.accent)))
                    .size(78.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Lock, null, Modifier.size(36.dp), tint = Color.White)
            }
            Spacer(Modifier.height(18.dp))
            Text("Kryptos", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = K.textPrimary)
            Spacer(Modifier.height(28.dp))
            PrimaryButton(
                stringResource(R.string.lock_unlock),
                Modifier.widthIn(min = 220.dp),
                icon = Icons.Default.LockOpen,
            ) { AppLock.prompt(activity) }

            run {
                Spacer(Modifier.height(28.dp))
                Column(Modifier.widthIn(max = 280.dp)) {
                    FieldLabel(stringResource(R.string.lock_code))
                    Spacer(Modifier.height(6.dp))
                    KTextField(
                        code,
                        { code = it; wrong = false },
                        password = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    if (wrong) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.lock_code_wrong),
                            fontSize = 13.sp, color = K.danger,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(
                        stringResource(R.string.lock_code_submit),
                        Modifier.fillMaxWidth(),
                        enabled = code.length >= AppSettingsStore.CODE_MIN_LENGTH && !checking,
                    ) {
                        val entered = code
                        code = ""
                        wrong = false
                        checking = true
                        scope.launch {
                            kotlinx.coroutines.delay(AppLock.codeThrottleMillis())
                            val outcome = withContext(Dispatchers.Default) {
                                AppLock.submitCode(activity.applicationContext, entered)
                            }
                            checking = false
                            wrong = outcome == AppLock.CodeOutcome.REJECTED
                            if (!wrong) AppLock.locked.value = false
                        }
                    }
                }
            }
        }
    }
}
