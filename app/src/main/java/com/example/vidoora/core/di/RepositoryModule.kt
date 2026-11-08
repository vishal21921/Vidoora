package com.example.vidoora.core.di

import com.example.vidoora.core.data.repository.AuthRepository
import com.example.vidoora.core.data.repository.AuthRepositoryImpl
import com.example.vidoora.core.data.repository.UploadRepository
import com.example.vidoora.core.data.repository.UploadRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindUploadRepository(impl: UploadRepositoryImpl): UploadRepository
}
