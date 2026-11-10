package com.hemanth.dictask.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun fromRec(r: Recurrence): String = r.name
    @TypeConverter fun toRec(s: String): Recurrence =
        runCatching { Recurrence.valueOf(s) }.getOrDefault(Recurrence.NONE)
}

@Dao
interface TaskDao {
    @Query(
        "SELECT * FROM tasks ORDER BY done, dueEpochDay IS NULL, dueEpochDay, " +
            "dueMinute IS NULL, dueMinute, priority DESC, createdAt"
    )
    fun all(): Flow<List<Task>>

    @Query(
        "SELECT * FROM tasks WHERE done = 0 ORDER BY dueEpochDay IS NULL, dueEpochDay, " +
            "dueMinute IS NULL, dueMinute, priority DESC, createdAt"
    )
    suspend fun pending(): List<Task>

    @Query(
        "SELECT * FROM tasks WHERE done = 0 ORDER BY dueEpochDay IS NULL, dueEpochDay, " +
            "dueMinute IS NULL, dueMinute, priority DESC, createdAt"
    )
    fun pendingFlow(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): Task?

    @Insert suspend fun insert(t: Task): Long
    @Update suspend fun update(t: Task)
    @Delete suspend fun delete(t: Task)

    @Query("SELECT * FROM tasks WHERE done = 1")
    suspend fun doneTasks(): List<Task>

    @Query("DELETE FROM tasks WHERE done = 1")
    suspend fun clearDone()

    @Query("SELECT * FROM tasks")
    suspend fun everything(): List<Task>

    @Query("SELECT * FROM tasks WHERE uid = :uid")
    suspend fun byUid(uid: String): Task?

    @Query("UPDATE tasks SET synced = 0")
    suspend fun markAllUnsynced()
}

@Database(entities = [Task::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun tasks(): TaskDao

    companion object {
        @Volatile private var inst: AppDb? = null

        /** v2 adds sync fields; existing tasks get a fresh shared id and are uploaded on first sign-in. */
        private val MIGRATE_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE tasks SET uid = lower(hex(randomblob(16))), updatedAt = createdAt")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tasks_uid ON tasks(uid)")
            }
        }
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "dictask.db")
                .addMigrations(MIGRATE_1_2)
                .build().also { inst = it }
        }
    }
}
