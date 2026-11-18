package com.serein.chat.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;

/** 会话与消息的本地存储（SQLite，纯 framework）。 */
public final class Db extends SQLiteOpenHelper {

    private static final String NAME = "deepseek.db";
    private static final int VERSION = 2;

    public Db(Context c) {
        super(c.getApplicationContext(), NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions ("
                + "id TEXT PRIMARY KEY,"
                + "title TEXT,"
                + "created INTEGER,"
                + "updated INTEGER)");
        db.execSQL("CREATE TABLE messages ("
                + "id TEXT PRIMARY KEY,"
                + "sid TEXT,"
                + "created INTEGER,"
                + "role INTEGER,"
                + "data TEXT)");
        db.execSQL("CREATE INDEX idx_msg_sid ON messages(sid, created)");
        db.execSQL("CREATE INDEX idx_sess_upd ON sessions(updated)");
        db.execSQL("CREATE TABLE usage_log ("
                + "ts INTEGER,"
                + "model TEXT,"
                + "in_tok INTEGER,"
                + "out_tok INTEGER,"
                + "hit INTEGER,"
                + "miss INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS usage_log ("
                    + "ts INTEGER, model TEXT, in_tok INTEGER, out_tok INTEGER,"
                    + "hit INTEGER, miss INTEGER)");
        }
    }

    /** 记录一轮真实用量，供「近期用量」聚合。 */
    public void logUsage(String model, Usage u) {
        if (u == null || (u.inputTokens == 0 && u.outputTokens == 0)) return;
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("ts", System.currentTimeMillis());
        v.put("model", model == null ? "" : model);
        v.put("in_tok", u.inputTokens);
        v.put("out_tok", u.outputTokens);
        v.put("hit", u.cacheHit);
        v.put("miss", u.cacheMiss);
        getWritableDatabase().insert("usage_log", null, v);
    }

    /** 累计缓存命中 token 数与请求数。 */
    public long[] usageTotals() {
        android.database.Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), IFNULL(SUM(hit),0), IFNULL(SUM(in_tok),0), IFNULL(SUM(out_tok),0)"
                        + " FROM usage_log", null);
        try {
            if (c.moveToFirst()) {
                return new long[]{c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3)};
            }
        } finally {
            c.close();
        }
        return new long[]{0, 0, 0, 0};
    }

    /** 最近 N 天按天聚合：{day, calls, in, out, hit, miss}。 */
    public java.util.List<long[]> recentUsage(int days) {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        long since = System.currentTimeMillis() - days * 86400000L;
        android.database.Cursor c = getReadableDatabase().rawQuery(
                "SELECT strftime('%Y%m%d', ts/1000, 'unixepoch', 'localtime') d,"
                        + " COUNT(*), SUM(in_tok), SUM(out_tok), SUM(hit), SUM(miss)"
                        + " FROM usage_log WHERE ts >= ? GROUP BY d ORDER BY d DESC",
                new String[]{String.valueOf(since)});
        try {
            while (c.moveToNext()) {
                out.add(new long[]{c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3),
                        c.getLong(4), c.getLong(5)});
            }
        } finally {
            c.close();
        }
        return out;
    }

    // ---------------------------------------------------------------- 会话

    public List<Models.Session> listSessions() {
        List<Models.Session> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("sessions", null, null, null, null, null, "updated DESC");
        try {
            while (c.moveToNext()) {
                Models.Session s = new Models.Session();
                s.id = c.getString(c.getColumnIndexOrThrow("id"));
                s.title = c.getString(c.getColumnIndexOrThrow("title"));
                s.createdAt = c.getLong(c.getColumnIndexOrThrow("created"));
                s.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated"));
                out.add(s);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void upsertSession(Models.Session s) {
        ContentValues v = new ContentValues();
        v.put("id", s.id);
        v.put("title", s.title);
        v.put("created", s.createdAt);
        v.put("updated", s.updatedAt);
        getWritableDatabase().insertWithOnConflict("sessions", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void renameSession(String id, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        getWritableDatabase().update("sessions", v, "id=?", new String[]{id});
    }

    public void deleteSession(String id) {
        getWritableDatabase().delete("messages", "sid=?", new String[]{id});
        getWritableDatabase().delete("sessions", "id=?", new String[]{id});
    }

    public void deleteAll() {
        getWritableDatabase().delete("messages", null, null);
        getWritableDatabase().delete("sessions", null, null);
    }

    // ---------------------------------------------------------------- 消息

    public List<Models.Message> listMessages(String sid) {
        List<Models.Message> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("messages", new String[]{"data"}, "sid=?",
                new String[]{sid}, null, null, "created ASC");
        try {
            while (c.moveToNext()) {
                String data = c.getString(0);
                try {
                    out.add(Models.Message.from(new JSONObject(data)));
                } catch (Exception ignored) {
                }
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void saveMessage(String sid, Models.Message m) {
        ContentValues v = new ContentValues();
        v.put("id", m.id);
        v.put("sid", sid);
        v.put("created", m.createdAt);
        v.put("role", m.role);
        v.put("data", m.toJson().toString());
        getWritableDatabase().insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void deleteMessage(String id) {
        getWritableDatabase().delete("messages", "id=?", new String[]{id});
    }

    public int messageCount(String sid) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages WHERE sid=?",
                new String[]{sid});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }
}
