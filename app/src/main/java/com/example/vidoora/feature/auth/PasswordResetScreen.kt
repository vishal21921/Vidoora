package com.example.vidoora.feature.auth

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.vidoora.core.network.dto.ApiEnvelope
import com.example.vidoora.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*

@HiltViewModel class PasswordResetViewModel @Inject constructor(private val client: HttpClient): ViewModel() {
    var busy by mutableStateOf(false);private set
    var available by mutableStateOf(false);private set
    var checked by mutableStateOf(false);private set
    var message by mutableStateOf<String?>(null);private set
    init {viewModelScope.launch {try {available=client.get("api/v1/auth/password-reset/config").body<ApiEnvelope<Map<String,Boolean>>>().data?.get("available")==true}
        catch(e: CancellationException){throw e}catch(_: Exception){message="Unable to check recovery. Retry online."}finally{checked=true}}}
    fun submit(path: String,body: Map<String,String>) {if(busy)return;busy=true
        viewModelScope.launch {try {
            val response=client.post("api/v1/auth/password-reset/$path"){setBody(body)}
            val result=response.body<ApiEnvelope<Map<String,String>>>()
            message=result.data?.get("message") ?: result.error?.message ?: "Unable to complete recovery"
        }catch(e: CancellationException){throw e}catch(_: Exception){message="Unable to connect. Retry online."}finally{busy=false}}
    }
}
@Composable fun PasswordResetScreen(onBack: ()->Unit,model: PasswordResetViewModel=hiltViewModel()) {
    var email by remember {mutableStateOf("")};var code by remember {mutableStateOf("")};var password by remember {mutableStateOf("")}
    AuthPage(onBack) {
        Text("Forgot password",style=MaterialTheme.typography.headlineSmall)
        if(!model.checked) Text("Checking recovery…")
        else if(!model.available) Text("Email recovery is not available yet. Contact vidoora.support@gmail.com. Never send your password.")
        else {
            AuthField("Email",email,{email=it},com.example.vidoora.R.drawable.ic_ui_email,enabled=!model.busy)
            PrimaryAction("Send reset code",{model.submit("request",mapOf("email" to email))},enabled=!model.busy && email.contains('@'))
            Text("Paste the code from your email. It expires in 15 minutes.")
            AuthField("Reset code",code,{code=it},com.example.vidoora.R.drawable.ic_ui_lock,enabled=!model.busy)
            AuthField("New password",password,{password=it},com.example.vidoora.R.drawable.ic_ui_lock,password=true,enabled=!model.busy)
            PrimaryAction("Reset password",{model.submit("confirm",mapOf("code" to code.trim(),"password" to password))},enabled=!model.busy && code.isNotBlank() && password.length>=8)
        }
        model.message?.let {Text(it)}
    }
}
