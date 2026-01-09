package com.bitnextechnologies.bitnexdial.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bitnextechnologies.bitnexdial.data.local.converter.Converters
import com.bitnextechnologies.bitnexdial.data.local.dao.*
import com.bitnextechnologies.bitnexdial.data.local.entity.*

/**
 * Room Database for BitNex Dial app
 */
@Database(
    entities = [
        ContactEntity::class,
        CallEntity::class,
        MessageEntity::class,
        ConversationEntity::class,
        VoicemailEntity::class,
        UserEntity::class,
        PhoneNumberEntity::class,
        BlockedNumberEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun callDao(): CallDao
    abstract fun messageDao(): MessageDao
    abstract fun voicemailDao(): VoicemailDao
    abstract fun userDao(): UserDao
    abstract fun phoneNumberDao(): PhoneNumberDao
    abstract fun blockedNumberDao(): BlockedNumberDao

    companion object {
        private const val DATABASE_NAME = "bitnex_dial_db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from version 1 to 2: Add notes column to call_history table
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE call_history ADD COLUMN notes TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from version 2 to 3: Add contentSignature for professional deduplication
         *
         * PROFESSIONAL DEDUPLICATION:
         * - Adds contentSignature column computed from normalized(from)|normalized(to)|body|timeBucket
         * - Creates UNIQUE index to make duplicates PHYSICALLY IMPOSSIBLE
         * - Existing messages get unique signatures based on their ID + content
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Step 1: Add contentSignature column with temporary default
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN contentSignature TEXT NOT NULL DEFAULT ''"
                )

                // Step 2: Update existing messages with unique signatures
                // Using a combination of id + fromNumber + toNumber + body + createdAt/120000
                // This ensures existing data gets unique but consistent signatures
                database.execSQL("""
                    UPDATE messages SET contentSignature =
                        id || '_' ||
                        REPLACE(REPLACE(REPLACE(fromNumber, '+', ''), '-', ''), ' ', '') || '|' ||
                        REPLACE(REPLACE(REPLACE(toNumber, '+', ''), '-', ''), ' ', '') || '|' ||
                        SUBSTR(body, 1, 50) || '|' ||
                        (createdAt / 120000)
                """)

                // Step 3: Create UNIQUE index - this is the key to preventing duplicates
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_messages_contentSignature ON messages(contentSignature)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
