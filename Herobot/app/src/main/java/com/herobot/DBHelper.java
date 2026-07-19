package com.herobot;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;
import android.database.Cursor;

public class DBHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "chatbot.db";
    private static final int DB_VERSION = 2;

    public static final String TABLE = "chatbot";
    public static final String COL_Q = "question";
    public static final String COL_A = "answer";

    public static final String PARAM_TABLE = "chatbot_parameters";
    public static final String COL_KEY = "parameter_key";
    public static final String COL_VALUE = "parameter_value";

    public DBHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE + " (" +
            COL_Q + " TEXT PRIMARY KEY, " +
            COL_A + " TEXT NOT NULL)"
        );

        db.execSQL(
            "CREATE TABLE " + PARAM_TABLE + " (" +
            COL_KEY + " TEXT PRIMARY KEY, " +
            COL_VALUE + " TEXT NOT NULL)"
        );

        seedDefaultParameters(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + PARAM_TABLE + " (" +
            COL_KEY + " TEXT PRIMARY KEY, " +
            COL_VALUE + " TEXT NOT NULL)"
        );
        seedDefaultParameters(db);
    }

    private void seedDefaultParameters(SQLiteDatabase db) {
        insertOrUpdateParameter(db, "model", "llama3.2:1b");
        insertOrUpdateParameter(db, "temperature", "0.7");
        insertOrUpdateParameter(db, "top_p", "0.9");
        insertOrUpdateParameter(db, "stream", "false");
    }

    private void insertOrUpdateParameter(SQLiteDatabase db, String key, String value) {
        ContentValues values = new ContentValues();
        values.put(COL_KEY, key);
        values.put(COL_VALUE, value);
        db.insertWithOnConflict(PARAM_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // INSERT / UPDATE
    public void insertQA(String q, String a) {
        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put(COL_Q, q.toLowerCase());
        values.put(COL_A, a);

        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // GET ALL DATA
    public Cursor getAll() {
        SQLiteDatabase db = getReadableDatabase();
        return db.rawQuery("SELECT * FROM " + TABLE, null);
    }

    // GET ANSWER (exact match fallback)
    public String getAnswer(String question) {
        SQLiteDatabase db = getReadableDatabase();

        Cursor c = db.rawQuery(
            "SELECT answer FROM " + TABLE + " WHERE question=?",
            new String[]{question.toLowerCase()}
        );

        if (c.moveToFirst()) {
            String ans = c.getString(0);
            c.close();
            return ans;
        }

        c.close();
        return null;
    }

    public void setParameter(String key, String value) {
        SQLiteDatabase db = getWritableDatabase();
        insertOrUpdateParameter(db, key, value);
    }

    public String getParameter(String key, String defaultValue) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
            "SELECT parameter_value FROM " + PARAM_TABLE + " WHERE parameter_key=?",
            new String[]{key}
        );

        try {
            if (c.moveToFirst()) {
                return c.getString(0);
            }
            return defaultValue;
        } finally {
            c.close();
        }
    }
}
