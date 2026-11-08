package com.example.vidoora.feature.profile

import androidx.compose.runtime.*
import com.example.vidoora.feature.auth.AuthField
import androidx.compose.material3.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.vidoora.core.data.repository.AuthRepository
import com.example.vidoora.core.common.Resource
import com.example.vidoora.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel class CreatorInviteViewModel @Inject constructor(private val auth: AuthRepository): ViewModel() {
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    fun redeem(code: String) { if(busy)return; busy=true
        viewModelScope.launch {try {
            message=when(val result=auth.redeemCreator(code)) {is Resource.Success->"Creator access enabled";is Resource.Error->result.message;else->null}
        } finally {busy=false} }
    }
}
@Composable fun CreatorInvitePanel(model: CreatorInviteViewModel=hiltViewModel()) {
    var code by remember {mutableStateOf("")}
    Text("Become a creator",style=MaterialTheme.typography.titleMedium)
    AuthField("Creator invite code",code,{code=it},com.example.vidoora.R.drawable.ic_ui_lock,enabled=!model.busy)
    PrimaryAction("Activate creator access",{model.redeem(code)},enabled=code.isNotBlank() && !model.busy,loading=model.busy)
    model.message?.let {Text(it)}
}
