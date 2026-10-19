package com.tkjone.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.darkColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tkjone.app.R
import kotlinx.coroutines.launch

private val BrandBlue = Color(0xFF0A84FF)
private val ScreenBackground = Color(0xFF17191D)
private val PanelBackground = Color(0xFF22262C)
private val SecondaryText = Color(0xFFA8AFB8)

@Composable
fun ComposeLoginScreen(onLogin: suspend (String) -> String?) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(false) }
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) { isVisible = true }

    MaterialTheme(colors = darkColors(primary = BrandBlue, background = ScreenBackground, surface = PanelBackground)) {
        Box(
            modifier = Modifier.fillMaxSize().background(ScreenBackground),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.background),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0x990B0D10))
                    .pointerInput(cardBounds) {
                        detectTapGestures { tap ->
                            if (!cardBounds.contains(tap)) focusManager.clearFocus()
                        }
                    }
            )
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 12 }),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                        .onGloballyPositioned { cardBounds = it.boundsInRoot() },
                    color = PanelBackground,
                    shape = RoundedCornerShape(18.dp),
                    elevation = 8.dp
                ) {
                    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)) {
                        Text(
                            text = stringResource(R.string.login_title),
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.login_subtitle), color = SecondaryText, fontSize = 14.sp)
                        Spacer(Modifier.height(24.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = {
                                name = it
                                if (error.isNotEmpty()) error = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isLoading,
                            label = { Text(stringResource(R.string.login_hint)) },
                            isError = error.isNotEmpty(),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                if (!isLoading) submitLogin(name, { error = it }, { isLoading = it }, onLogin, scope)
                            }),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                textColor = Color.White,
                                focusedBorderColor = BrandBlue,
                                focusedLabelColor = BrandBlue,
                                cursorColor = BrandBlue
                            )
                        )
                        AnimatedVisibility(visible = error.isNotEmpty()) {
                            Text(error, color = MaterialTheme.colors.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = { submitLogin(name, { error = it }, { isLoading = it }, onLogin, scope) },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            enabled = !isLoading,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = BrandBlue, contentColor = Color.White)
                        ) {
                            if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text(stringResource(R.string.login_button), fontWeight = FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = SecondaryText)
                            Text(stringResource(R.string.login_hint_admin), color = SecondaryText, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun submitLogin(
    name: String,
    setError: (String) -> Unit,
    setLoading: (Boolean) -> Unit,
    onLogin: suspend (String) -> String?,
    scope: kotlinx.coroutines.CoroutineScope
) {
    setLoading(true)
    setError("")
    scope.launch {
        try {
            setError(onLogin(name.trim()).orEmpty())
        } catch (exception: Exception) {
            setError(exception.message ?: "Login gagal. Coba lagi.")
        } finally {
            setLoading(false)
        }
    }
}
