package com.herobot.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationSessionEntity::class,
        ConversationMessageEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class HeroBotDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: HeroBotDatabase? = null

        fun getInstance(context: Context): HeroBotDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HeroBotDatabase::class.java,
                    "herobot_conversations.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
