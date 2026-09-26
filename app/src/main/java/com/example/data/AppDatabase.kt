package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add group fields to conversations table
                db.execSQL("ALTER TABLE conversations ADD COLUMN isGroup INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN groupOwnerId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE conversations ADD COLUMN groupMembers TEXT DEFAULT NULL")

                // Add attachment and reply fields to messages table
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToMessageId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentPath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentType TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentSize INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nearby_chat_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
