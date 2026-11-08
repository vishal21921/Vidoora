package com.example.vidoora.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.vidoora.feature.home.HomeScreen
import com.example.vidoora.feature.auth.AuthUiState
import com.example.vidoora.feature.auth.login.LoginScreen

// Synthetic, signed-out previews: never preview a real share capability or account.
@Preview(name="Small phone",widthDp=320,heightDp=568,showBackground=true)
@Preview(name="Phone",widthDp=411,heightDp=891,showBackground=true)
@Preview(name="Landscape",widthDp=800,heightDp=360,showBackground=true)
@Preview(name="Tablet",widthDp=840,heightDp=1100,showBackground=true)
@Preview(name="Large text",widthDp=360,heightDp=800,fontScale=2f,showBackground=true)
@Composable
private fun HomeSizesPreview() { VidooraTheme { HomeScreen {} } }

@Preview(name="Dark phone",widthDp=360,heightDp=800,showBackground=true)
@Composable
private fun HomeDarkPreview() { VidooraTheme(darkTheme=true) { HomeScreen {} } }

@Preview(name="Login small",widthDp=320,heightDp=568,showBackground=true)
@Preview(name="Login large text",widthDp=360,heightDp=640,fontScale=2f,showBackground=true)
@Composable
private fun LoginSizesPreview() {
    VidooraTheme { LoginScreen(AuthUiState.Idle,{_,_->},{},{},{}) }
}
