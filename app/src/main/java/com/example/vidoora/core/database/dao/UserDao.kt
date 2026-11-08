package com.example.vidoora.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.vidoora.core.database.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM cached_users LIMIT 1")
    fun getUser(): Flow<UserEntity?>

    @Query("SELECT * FROM cached_users LIMIT 1")
    suspend fun getUserOnce(): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Query("DELETE FROM cached_users")
    suspend fun clearUser()
}
