package com.example.linkpoint.cache

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "grid_configs")
data class GridConfigEntity(
    @PrimaryKey val gridId: String,
    val name: String,
    val loginUri: String,
    val gridType: String, // "SECOND_LIFE" or "OPENSIM"
    val isBuiltIn: Boolean = false,
    val isHttps: Boolean = true,
    val description: String = ""
)

@Entity(tableName = "saved_accounts")
data class SavedAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val firstName: String,
    val lastName: String,
    val gridId: String,
    val startLocation: String = "last",
    /** Optional protocol MD5 digest ($1$...) stored ONLY when user explicitly enables Remember Login. Never plaintext. */
    val rememberedDigest: String? = null,
    val lastUsedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "landmarks_cache")
data class LandmarkCacheEntity(
    @PrimaryKey val landmarkId: String,
    val name: String,
    val regionName: String,
    val gridId: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_logs")
data class ChatLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val speaker: String,
    val message: String,
    val channel: Int,
    val regionName: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface GridConfigDao {
    @Query("SELECT * FROM grid_configs ORDER BY isBuiltIn DESC, name ASC")
    fun observeGrids(): Flow<List<GridConfigEntity>>

    @Query("SELECT COUNT(*) FROM grid_configs")
    suspend fun countGrids(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(grids: List<GridConfigEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(grid: GridConfigEntity)

    @Query("DELETE FROM grid_configs WHERE gridId = :gridId AND isBuiltIn = 0")
    suspend fun deleteCustomGrid(gridId: String)
}

@Dao
interface SavedAccountDao {
    @Query("SELECT * FROM saved_accounts ORDER BY lastUsedAt DESC")
    fun observeAccounts(): Flow<List<SavedAccountEntity>>

    @Query("SELECT * FROM saved_accounts WHERE gridId = :gridId AND LOWER(firstName) = LOWER(:firstName) AND LOWER(lastName) = LOWER(:lastName) LIMIT 1")
    suspend fun findMatching(gridId: String, firstName: String, lastName: String): SavedAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: SavedAccountEntity)

    @Query("DELETE FROM saved_accounts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM saved_accounts")
    suspend fun clearAll()
}

@Dao
interface LandmarkDao {
    @Query("SELECT * FROM landmarks_cache ORDER BY createdAt DESC")
    fun observeLandmarks(): Flow<List<LandmarkCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(landmark: LandmarkCacheEntity)

    @Query("DELETE FROM landmarks_cache WHERE landmarkId = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ChatLogDao {
    @Query("SELECT * FROM chat_logs ORDER BY timestamp DESC LIMIT 120")
    fun observeRecentLogs(): Flow<List<ChatLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ChatLogEntity)

    @Query("DELETE FROM chat_logs")
    suspend fun clearAll()
}

@Database(
    entities = [
        GridConfigEntity::class,
        SavedAccountEntity::class,
        LandmarkCacheEntity::class,
        ChatLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class LinkpointDatabase : RoomDatabase() {
    abstract fun gridConfigDao(): GridConfigDao
    abstract fun savedAccountDao(): SavedAccountDao
    abstract fun landmarkDao(): LandmarkDao
    abstract fun chatLogDao(): ChatLogDao

    companion object {
        @Volatile private var INSTANCE: LinkpointDatabase? = null

        fun getInstance(context: Context): LinkpointDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LinkpointDatabase::class.java,
                    "linkpoint_cache.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { INSTANCE = it }
            }
    }
}

class LinkpointCacheRepository(private val db: LinkpointDatabase) {
    val grids: Flow<List<GridConfigEntity>> = db.gridConfigDao().observeGrids()
    val savedAccounts: Flow<List<SavedAccountEntity>> = db.savedAccountDao().observeAccounts()
    val landmarks: Flow<List<LandmarkCacheEntity>> = db.landmarkDao().observeLandmarks()

    suspend fun ensureBuiltInGrids() {
        if (db.gridConfigDao().countGrids() == 0) {
            db.gridConfigDao().upsertAll(DEFAULT_GRIDS)
        }
    }

    suspend fun upsertGrid(grid: GridConfigEntity) = db.gridConfigDao().upsert(grid)
    suspend fun deleteCustomGrid(gridId: String) = db.gridConfigDao().deleteCustomGrid(gridId)

    suspend fun saveAccount(account: SavedAccountEntity) {
        val resolvedId = if (account.id != 0L) {
            account.id
        } else {
            db.savedAccountDao().findMatching(account.gridId, account.firstName, account.lastName)?.id ?: 0L
        }
        db.savedAccountDao().upsert(account.copy(id = resolvedId))
    }
    suspend fun deleteAccount(id: Long) = db.savedAccountDao().deleteById(id)

    suspend fun saveLandmark(landmark: LandmarkCacheEntity) = db.landmarkDao().upsert(landmark)
    suspend fun deleteLandmark(id: String) = db.landmarkDao().deleteById(id)

    suspend fun logChat(speaker: String, message: String, channel: Int, regionName: String) {
        db.chatLogDao().insert(
            ChatLogEntity(speaker = speaker, message = message, channel = channel, regionName = regionName)
        )
    }

    companion object {
        val DEFAULT_GRIDS = listOf(
            GridConfigEntity(
                gridId = "sl_agni",
                name = "Second Life (Agni)",
                loginUri = "https://login.agni.lindenlab.com/cgi-bin/login.cgi",
                gridType = "SECOND_LIFE",
                isBuiltIn = true,
                isHttps = true,
                description = "Official Second Life Main Grid"
            ),
            GridConfigEntity(
                gridId = "sl_aditi",
                name = "Second Life Beta (Aditi)",
                loginUri = "https://login.aditi.lindenlab.com/cgi-bin/login.cgi",
                gridType = "SECOND_LIFE",
                isBuiltIn = true,
                isHttps = true,
                description = "Official Second Life Preview & Beta Grid"
            ),
            GridConfigEntity(
                gridId = "osgrid",
                name = "OSGrid",
                loginUri = "http://login.osgrid.org/",
                gridType = "OPENSIM",
                isBuiltIn = true,
                isHttps = false,
                description = "OpenSimulator Community Grid (Cleartext HTTP)"
            ),
            GridConfigEntity(
                gridId = "kitely",
                name = "Kitely",
                loginUri = "http://grid.kitely.com:8002/",
                gridType = "OPENSIM",
                isBuiltIn = true,
                isHttps = false,
                description = "Kitely Hosted OpenSimulator Worlds"
            )
        )
    }
}
