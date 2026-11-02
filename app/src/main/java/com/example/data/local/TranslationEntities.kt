package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "rom_sessions")
data class RomSessionEntity(
    @PrimaryKey val romId: String,
    val romTitle: String,
    val isoFileName: String,
    val isoUriString: String,
    val isoSizeBytes: Long,
    val targetFilePath: String,
    val targetFileLba: Long,
    val targetFileSize: Long,
    val translationRulesJson: String,
    val totalStrings: Int,
    val translatedStrings: Int,
    val patchedStrings: Int,
    val currentStage: String,
    val tier1Model: String,
    val tier2Model: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "translation_entries",
    indices = [
        Index(value = ["romId", "stringIndex"], unique = true),
        Index(value = ["romId", "status"])
    ]
)
data class TranslationEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val romId: String,
    val targetFilePath: String,
    val stringIndex: Int,
    val byteOffsetInIso: Long,
    val allocatedByteLength: Int,
    val originalText: String,
    val maskedText: String,
    val placeholderMapJson: String,
    val translatedText: String? = null,
    val status: String = STATUS_PENDING,
    val batchNumber: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_TRANSLATED = "TRANSLATED"
        const val STATUS_PATCHED = "PATCHED"
    }
}
