package com.example.vidoora.core.di

import android.content.Context
import androidx.room.Room
import com.example.vidoora.core.database.AppDatabase
import com.example.vidoora.core.database.dao.UploadPartDao
import com.example.vidoora.core.database.dao.UploadTaskDao
import com.example.vidoora.core.database.dao.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "vidoora_app_db"
        )
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
        .build()
    }

    @Provides
    @Singleton
    fun provideUserDao(db: AppDatabase): UserDao {
        return db.userDao()
    }

    @Provides
    @Singleton
    fun provideUploadTaskDao(db: AppDatabase): UploadTaskDao {
        return db.uploadTaskDao()
    }

    @Provides
    @Singleton
    fun provideUploadPartDao(db: AppDatabase): UploadPartDao {
        return db.uploadPartDao()
    }
}
