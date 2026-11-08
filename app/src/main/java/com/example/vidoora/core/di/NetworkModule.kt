package com.example.vidoora.core.di

import com.example.vidoora.core.network.KtorClientFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import javax.inject.Singleton
import javax.inject.Qualifier
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StorageHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    @StorageHttpClient
    fun provideStorageClient(): HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 60_000
        }
    }

    @Provides
    @Singleton
    fun provideHttpClient(factory: KtorClientFactory): HttpClient {
        return factory.createClient()
    }
}
