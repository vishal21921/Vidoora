package com.example.vidoora.core.network

object ApiConfig {
    /**
     * Development API Base URL.
     * Note: '10.0.2.2' is the Android Emulator special alias to host loopback ('127.0.0.1').
     * For physical Android devices on the same Wi-Fi, change to your local machine IP (e.g. 'http://192.168.x.x:8080/').
     */
    val BASE_URL = com.example.vidoora.BuildConfig.API_BASE_URL
}
