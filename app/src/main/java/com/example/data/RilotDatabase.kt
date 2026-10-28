package com.example.data

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
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "hashtag_targets")
data class HashtagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val tag: String,
    val category: String,
    val isEnabled: Boolean,
    val candidateLimitPerRun: Int,
    val minViewsThreshold: Long,
    val priorityWeight: Float
)

@Entity(tableName = "caption_templates")
data class CaptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val body: String,
    val categoryName: String,
    val isAbsurdCopypasta: Boolean,
    val hashtagTemplate: String,
    val usageCount: Int,
    val lastUsedEpochMs: Long?,
    val isFavorite: Boolean
)

@Entity(tableName = "activity_candidates")
data class ActivityRecordEntity(
    @PrimaryKey val reelPk: String,
    val shortCode: String,
    val reelUrl: String,
    val hashtag: String,
    val category: String,
    val creatorHandle: String,
    val viewCount: Long,
    val likeCount: Long,
    val commentCount: Long,
    val durationSeconds: Int,
    val qualityScore: Double,
    val selectionReason: String,
    val downloaderUsed: String,
    val outcomeStatusName: String,
    val failureReason: String?,
    val captionPreview: String,
    val timestampIst: String,
    val timestampEpochMs: Long,
    val retryCount: Int,
    val idempotencyToken: String
)

@Dao
interface RilotDao {
    @Query("SELECT * FROM hashtag_targets ORDER BY category ASC, tag ASC")
    fun observeHashtags(): Flow<List<HashtagEntity>>

    @Query("SELECT * FROM hashtag_targets ORDER BY category ASC, tag ASC")
    suspend fun getAllHashtags(): List<HashtagEntity>

    @Query("SELECT COUNT(*) FROM hashtag_targets")
    suspend fun countHashtags(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHashtag(entity: HashtagEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHashtags(entities: List<HashtagEntity>)

    @Update
    suspend fun updateHashtag(entity: HashtagEntity)

    @Query("DELETE FROM hashtag_targets WHERE id = :id")
    suspend fun deleteHashtagById(id: Long)

    @Query("SELECT * FROM caption_templates ORDER BY isFavorite DESC, id DESC")
    fun observeCaptions(): Flow<List<CaptionEntity>>

    @Query("SELECT * FROM caption_templates ORDER BY isFavorite DESC, id DESC")
    suspend fun getAllCaptions(): List<CaptionEntity>

    @Query("SELECT COUNT(*) FROM caption_templates")
    suspend fun countCaptions(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCaption(entity: CaptionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCaptions(entities: List<CaptionEntity>)

    @Update
    suspend fun updateCaption(entity: CaptionEntity)

    @Query("DELETE FROM caption_templates WHERE id = :id")
    suspend fun deleteCaptionById(id: Long)

    @Query("SELECT * FROM activity_candidates ORDER BY timestampEpochMs DESC")
    fun observeActivityRecords(): Flow<List<ActivityRecordEntity>>

    @Query("SELECT COUNT(*) FROM activity_candidates")
    suspend fun countActivityRecords(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivityRecords(entities: List<ActivityRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActivityRecord(entity: ActivityRecordEntity)

    @Query("SELECT * FROM activity_candidates WHERE reelPk = :reelPk LIMIT 1")
    suspend fun getActivityByPk(reelPk: String): ActivityRecordEntity?
}

@Database(
    entities = [HashtagEntity::class, CaptionEntity::class, ActivityRecordEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RilotDatabase : RoomDatabase() {
    abstract fun rilotDao(): RilotDao

    companion object {
        @Volatile
        private var INSTANCE: RilotDatabase? = null

        fun getInstance(context: Context): RilotDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RilotDatabase::class.java,
                    "rilot_control_panel.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
