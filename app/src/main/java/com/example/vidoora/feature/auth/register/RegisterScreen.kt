package com.example.vidoora.feature.auth.register

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
fun RegisterScreen(uiState: AuthUiState,onRegisterClick: (String,String,String,String)->Unit,onNavigateToLogin: ()->Unit,
    onRegisterSuccess: ()->Unit,onClearState: ()->Unit,onBack: (() -> Unit)?=null) {
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    val busy=uiState is AuthUiState.Loading
    fun clearError() { if(uiState is AuthUiState.Error) onClearState() }
    LaunchedEffect(uiState) { if(uiState is AuthUiState.Success) onRegisterSuccess() }
    AuthPage(onBack) {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Create your account",style=MaterialTheme.typography.headlineSmall)
            Text("Watch links. Creators join by invitation.",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        AuthField("Creator invite code (optional)",username,{ username=it; clearError() },R.drawable.ic_ui_at,enabled=!busy)
        AuthField("Email",email,{ email=it; clearError() },R.drawable.ic_ui_email,keyboardType=KeyboardType.Email,enabled=!busy)
        AuthField("Password",password,{ password=it; clearError() },R.drawable.ic_ui_lock,password=true,
            keyboardType=KeyboardType.Password,helper="At least 8 characters",enabled=!busy)
        if(uiState is AuthUiState.Error) FormError(uiState.message)
        Spacer(Modifier.height(6.dp))
        PrimaryAction("Create account",{ onRegisterClick(username,email,password,displayName) },enabled=!busy,loading=busy)
        TextButton(onClick=onNavigateToLogin,enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Already have an account? Log in") }
    }
}

@Preview(showBackground=true)
@Composable
private fun RegisterScreenPreview() { VidooraTheme { RegisterScreen(AuthUiState.Idle,{_,_,_,_->},{},{},{}) } }
