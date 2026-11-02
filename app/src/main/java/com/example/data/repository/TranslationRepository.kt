package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.RomSessionEntity
import com.example.data.local.TranslationDao
import com.example.data.local.TranslationDatabase
import com.example.data.local.TranslationEntryEntity
import kotlinx.coroutines.flow.Flow

class TranslationRepository(
    private val database: TranslationDatabase,
    private val dao: TranslationDao = database.translationDao()
) {
    val allSessions: Flow<List<RomSessionEntity>> = dao.observeAllSessions()

    fun observeSession(romId: String): Flow<RomSessionEntity?> = dao.observeSession(romId)

    fun observeEntries(romId: String): Flow<List<TranslationEntryEntity>> =
        dao.observeEntriesForRom(romId)

    suspend fun getSession(romId: String): RomSessionEntity? = dao.getSessionById(romId)

    suspend fun upsertSession(session: RomSessionEntity) = dao.upsertSession(session)

    suspend fun getAllEntries(romId: String): List<TranslationEntryEntity> =
        dao.getAllEntriesForRom(romId)

    suspend fun getPendingEntries(romId: String): List<TranslationEntryEntity> =
        dao.getPendingEntriesForRom(romId)

    suspend fun saveInitialExtractedEntries(
        session: RomSessionEntity,
        entries: List<TranslationEntryEntity>
    ) {
        database.withTransaction {
            dao.upsertSession(session)
            dao.insertEntries(entries)
        }
    }

    suspend fun updateMaskingBatch(
        romId: String,
        maskedItems: List<Triple<Int, String, String>>,
        rulesJson: String
    ) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            for ((index, maskedText, placeholderJson) in maskedItems) {
                dao.updateMaskingForEntry(
                    romId = romId,
                    stringIndex = index,
                    maskedText = maskedText,
                    placeholderMapJson = placeholderJson,
                    updatedAt = now
                )
            }
            val current = dao.getSessionById(romId)
            if (current != null) {
                dao.upsertSession(
                    current.copy(
                        translationRulesJson = rulesJson,
                        updatedAt = now
                    )
                )
            }
        }
    }

    /**
     * Immediately commits a translated batch of 100-150 lines to SQLite/Room in a single transaction
     * so if daily API quota runs out mid-ROM, resuming tomorrow picks up at the exact next batch.
     */
    suspend fun saveTranslatedBatchCheckpoint(
        romId: String,
        batchNumber: Int,
        translatedPairs: List<Pair<Int, String>>
    ): Int {
        return database.withTransaction {
            val now = System.currentTimeMillis()
            for ((stringIndex, vietnameseText) in translatedPairs) {
                dao.updateTranslationResult(
                    romId = romId,
                    stringIndex = stringIndex,
                    translatedText = vietnameseText,
                    status = TranslationEntryEntity.STATUS_TRANSLATED,
                    batchNumber = batchNumber,
                    updatedAt = now
                )
            }
            val translatedCount = dao.getTranslatedCountForRom(romId)
            val session = dao.getSessionById(romId)
            if (session != null) {
                dao.upsertSession(
                    session.copy(
                        translatedStrings = translatedCount,
                        updatedAt = now
                    )
                )
            }
            translatedCount
        }
    }

    suspend fun updateManualTranslation(
        entry: TranslationEntryEntity,
        newVietnameseText: String
    ) {
        database.withTransaction {
            dao.updateEntry(
                entry.copy(
                    translatedText = newVietnameseText,
                    status = TranslationEntryEntity.STATUS_TRANSLATED,
                    updatedAt = System.currentTimeMillis()
                )
            )
            val translatedCount = dao.getTranslatedCountForRom(entry.romId)
            val session = dao.getSessionById(entry.romId)
            if (session != null) {
                dao.upsertSession(
                    session.copy(
                        translatedStrings = translatedCount,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    suspend fun markRomPatched(romId: String, stageName: String) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            dao.markAllTranslatedAsPatched(romId, now)
            val session = dao.getSessionById(romId)
            if (session != null) {
                val count = dao.getTranslatedCountForRom(romId)
                dao.upsertSession(
                    session.copy(
                        patchedStrings = count,
                        currentStage = stageName,
                        updatedAt = now
                    )
                )
            }
        }
    }

    suspend fun resetSession(romId: String) {
        database.withTransaction {
            dao.deleteEntriesForRom(romId)
            dao.deleteSession(romId)
        }
    }
}
