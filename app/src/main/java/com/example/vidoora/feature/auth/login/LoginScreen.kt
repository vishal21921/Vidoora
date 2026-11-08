package com.example.vidoora.feature.auth.login

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.vidoora.R
import com.example.vidoora.feature.auth.*
import com.example.vidoora.ui.theme.*

@Composable
fun LoginScreen(uiState: AuthUiState,onLoginClick: (String,String)->Unit,onNavigateToRegister: ()->Unit,
    onLoginSuccess: ()->Unit,onClearState: ()->Unit,onBack: (() -> Unit)?=null,onForgotPassword: ()->Unit={}) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val busy=uiState is AuthUiState.Loading
    LaunchedEffect(uiState) { if(uiState is AuthUiState.Success) onLoginSuccess() }
    AuthPage(onBack,footer="Just watching? Open a shared link from Home.") {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Welcome back",style=MaterialTheme.typography.headlineSmall)
            Text("Sign in to upload and manage your videos.",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        AuthField("Email",email,{ email=it; if(uiState is AuthUiState.Error) onClearState() },R.drawable.ic_ui_email,keyboardType=KeyboardType.Email,enabled=!busy)
        AuthField("Password",password,{ password=it; if(uiState is AuthUiState.Error) onClearState() },R.drawable.ic_ui_lock,password=true,keyboardType=KeyboardType.Password,enabled=!busy)
        if(uiState is AuthUiState.Error) FormError(uiState.message)
        Spacer(Modifier.height(4.dp))
        TextButton(onClick=onForgotPassword,enabled=!busy) {Text("Forgot password?")}
        PrimaryAction("Log in",{ onLoginClick(email,password) },enabled=!busy,loading=busy)
        TextButton(onClick=onNavigateToRegister,enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Create account") }
    }
}

@Preview(showBackground=true)
@Composable
private fun LoginScreenPreview() { VidooraTheme { LoginScreen(AuthUiState.Idle,{_,_->},{},{},{}) } }
