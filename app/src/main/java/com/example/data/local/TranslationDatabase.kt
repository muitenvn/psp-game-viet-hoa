package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RomSessionEntity::class, TranslationEntryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TranslationDatabase : RoomDatabase() {
    abstract fun translationDao(): TranslationDao

    companion object {
        @Volatile
        private var INSTANCE: TranslationDatabase? = null

        fun getInstance(context: Context): TranslationDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TranslationDatabase::class.java,
                    "psp_localization_checkpoint.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
