package com.herobot.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

class DBHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "$COL_Q TEXT PRIMARY KEY, " +
                "$COL_A TEXT NOT NULL)"
        )

        db.execSQL(
            "CREATE TABLE $PARAM_TABLE (" +
                "$COL_KEY TEXT PRIMARY KEY, " +
                "$COL_VALUE TEXT NOT NULL)"
        )

        seedDefaultParameters(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $PARAM_TABLE (" +
                "$COL_KEY TEXT PRIMARY KEY, " +
                "$COL_VALUE TEXT NOT NULL)"
        )
        seedDefaultParameters(db)
    }

    private fun seedDefaultParameters(db: SQLiteDatabase) {
        insertOrUpdateParameter(db, KEY_MODEL, "llama3.2:1b")
        insertOrUpdateParameter(db, KEY_TEMPERATURE, "0.7")
        insertOrUpdateParameter(db, KEY_TOP_P, "0.9")
        insertOrUpdateParameter(db, KEY_STREAM, "false")
        insertOrUpdateParameter(db, KEY_WEB_SEARCH_ENABLED, "true")
        insertOrUpdateParameter(db, KEY_SEARCH_ENGINE, "duckduckgo")
        insertOrUpdateParameter(db, KEY_SEARCH_COUNTRY, "us")
        insertOrUpdateParameter(db, KEY_MAX_WEB_RESULTS, "3")
    }

    private fun insertOrUpdateParameter(db: SQLiteDatabase, key: String, value: String) {
        val values = ContentValues().apply {
            put(COL_KEY, key)
            put(COL_VALUE, value)
        }
        db.insertWithOnConflict(PARAM_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun insertQA(question: String, answer: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_Q, question.lowercase(Locale.ROOT))
            put(COL_A, answer)
        }
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun insertBatchQA(pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val statement = db.compileStatement(
                "INSERT OR REPLACE INTO $TABLE ($COL_Q, $COL_A) VALUES (?, ?)"
            )
            for ((q, a) in pairs) {
                statement.clearBindings()
                statement.bindString(1, q.lowercase(Locale.ROOT))
                statement.bindString(2, a)
                statement.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAllPairs(): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT $COL_Q, $COL_A FROM $TABLE", null).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0) to cursor.getString(1))
            }
        }
        return result
    }

    fun getCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getAnswer(question: String): String? {
        readableDatabase.rawQuery(
            "SELECT $COL_A FROM $TABLE WHERE $COL_Q=?",
            arrayOf(question.lowercase(Locale.ROOT))
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun setParameter(key: String, value: String) {
        insertOrUpdateParameter(writableDatabase, key, value)
    }

    fun getParameter(key: String, defaultValue: String): String {
        readableDatabase.rawQuery(
            "SELECT $COL_VALUE FROM $PARAM_TABLE WHERE $COL_KEY=?",
            arrayOf(key)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else defaultValue
        }
    }

    fun getAllParameters(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        readableDatabase.rawQuery(
            "SELECT $COL_KEY, $COL_VALUE FROM $PARAM_TABLE ORDER BY $COL_KEY ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                map[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return map
    }

    companion object {
        private const val DB_NAME = "chatbot.db"
        private const val DB_VERSION = 3

        const val TABLE = "chatbot"
        const val COL_Q = "question"
        const val COL_A = "answer"

        const val PARAM_TABLE = "chatbot_parameters"
        const val COL_KEY = "parameter_key"
        const val COL_VALUE = "parameter_value"

        const val KEY_MODEL = "model"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "top_p"
        const val KEY_STREAM = "stream"
        const val KEY_WEB_SEARCH_ENABLED = "web_search_enabled"
        const val KEY_SEARCH_ENGINE = "search_engine"
        const val KEY_SEARCH_COUNTRY = "search_country"
        const val KEY_MAX_WEB_RESULTS = "max_web_results"
    }
}
