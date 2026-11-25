package com.floodsos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SosDao {

    /**
     * IGNORE: if a row with this id already exists, do nothing and return -1.
     * Dedup happens in one atomic SQL statement, so two Bluetooth peers delivering
     * the same message at the same moment can't both insert it (no check-then-insert race).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: SosEntity): Long

    /** Room re-runs this query and emits a new list whenever the table changes. */
    @Query("SELECT * FROM messages ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SosEntity>>

    /** Oldest first: the person who has waited longest gets uploaded first. */
    @Query("SELECT * FROM messages WHERE uploaded = 0 ORDER BY createdAt ASC")
    suspend fun pendingUploads(): List<SosEntity>

    @Query("UPDATE messages SET uploaded = 1 WHERE id = :id")
    suspend fun markUploaded(id: String)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): SosEntity?

    @Query("UPDATE messages SET smsSent = 1 WHERE id = :id")
    suspend fun markSmsSent(id: String)

    @Query("SELECT * FROM messages")
    suspend fun getAll(): List<SosEntity>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE uploaded = 0")
    suspend fun countNotUploaded(): Int

    /** Which of these ids do we already hold? (A packet has at most ~100 ids, well under SQLite's 999-parameter limit.) */
    @Query("SELECT id FROM messages WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>
}
