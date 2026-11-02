package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationDao {

    @Query("SELECT * FROM rom_sessions ORDER BY updatedAt DESC")
    fun observeAllSessions(): Flow<List<RomSessionEntity>>

    @Query("SELECT * FROM rom_sessions WHERE romId = :romId LIMIT 1")
    fun observeSession(romId: String): Flow<RomSessionEntity?>

    @Query("SELECT * FROM rom_sessions WHERE romId = :romId LIMIT 1")
    suspend fun getSessionById(romId: String): RomSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: RomSessionEntity)

    @Query("SELECT * FROM translation_entries WHERE romId = :romId ORDER BY stringIndex ASC")
    fun observeEntriesForRom(romId: String): Flow<List<TranslationEntryEntity>>

    @Query("SELECT * FROM translation_entries WHERE romId = :romId ORDER BY stringIndex ASC")
    suspend fun getAllEntriesForRom(romId: String): List<TranslationEntryEntity>

    @Query("SELECT * FROM translation_entries WHERE romId = :romId AND status = 'PENDING' ORDER BY stringIndex ASC")
    suspend fun getPendingEntriesForRom(romId: String): List<TranslationEntryEntity>

    @Query("SELECT COUNT(*) FROM translation_entries WHERE romId = :romId")
    suspend fun getTotalCountForRom(romId: String): Int

    @Query("SELECT COUNT(*) FROM translation_entries WHERE romId = :romId AND (status = 'TRANSLATED' OR status = 'PATCHED')")
    suspend fun getTranslatedCountForRom(romId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<TranslationEntryEntity>)

    @Update
    suspend fun updateEntry(entry: TranslationEntryEntity)

    @Query(
        """
        UPDATE translation_entries 
        SET translatedText = :translatedText, status = :status, batchNumber = :batchNumber, updatedAt = :updatedAt 
        WHERE romId = :romId AND stringIndex = :stringIndex
        """
    )
    suspend fun updateTranslationResult(
        romId: String,
        stringIndex: Int,
        translatedText: String,
        status: String,
        batchNumber: Int,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE translation_entries 
        SET maskedText = :maskedText, placeholderMapJson = :placeholderMapJson, updatedAt = :updatedAt
        WHERE romId = :romId AND stringIndex = :stringIndex
        """
    )
    suspend fun updateMaskingForEntry(
        romId: String,
        stringIndex: Int,
        maskedText: String,
        placeholderMapJson: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE translation_entries SET status = 'PATCHED', updatedAt = :updatedAt WHERE romId = :romId AND status = 'TRANSLATED'")
    suspend fun markAllTranslatedAsPatched(romId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM translation_entries WHERE romId = :romId")
    suspend fun deleteEntriesForRom(romId: String)

    @Query("DELETE FROM rom_sessions WHERE romId = :romId")
    suspend fun deleteSession(romId: String)
}
