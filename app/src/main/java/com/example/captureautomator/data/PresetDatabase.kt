package com.example.captureautomator.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "presets")
data class PresetRow(
    @PrimaryKey val id: String,
    val name: String,
    val folder: String,
    val json: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "executions")
data class ExecutionRow(
    @PrimaryKey val id: String,
    val presetId: String,
    val presetName: String,
    val outcome: String,
    val startedAt: Long,
    val reportJson: String
)

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY updatedAt DESC")
    fun observePresets(): Flow<List<PresetRow>>

    @Query("SELECT * FROM presets WHERE id = :id LIMIT 1")
    suspend fun getPreset(id: String): PresetRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePreset(row: PresetRow)

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun deletePreset(id: String)

    @Query("SELECT * FROM executions ORDER BY startedAt DESC LIMIT 250")
    fun observeExecutions(): Flow<List<ExecutionRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveExecution(row: ExecutionRow)
}

@Database(entities = [PresetRow::class, ExecutionRow::class], version = 1, exportSchema = false)
abstract class PresetDatabase : RoomDatabase() {
    abstract fun dao(): PresetDao
    companion object {
        @Volatile private var cached: PresetDatabase? = null
        fun instance(context: Context): PresetDatabase = cached ?: synchronized(this) {
            cached ?: Room.databaseBuilder(context.applicationContext, PresetDatabase::class.java,
                "capture_presets.db").build().also { cached = it }
        }
    }
}
