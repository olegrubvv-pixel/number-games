package com.yt3hunter.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public class Db extends SQLiteOpenHelper {
    public static final int FREE = 1;
    public static final int UNKNOWN = 2;

    public static class Row {
        public final String handle;
        public final int status;
        public final String reason;
        public final int attempts;
        Row(String h, int s, String r, int a) {
            handle=h; status=s; reason=r; attempts=a;
        }
    }

    public Db(Context c) {
        super(c, "yt3.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE results(handle TEXT PRIMARY KEY,status INTEGER NOT NULL,reason TEXT,attempts INTEGER NOT NULL DEFAULT 1,updated INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_status ON results(status)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {}

    public synchronized void putUnknown(String h, String reason) {
        SQLiteDatabase db=getWritableDatabase();
        Cursor c=db.rawQuery("SELECT status,attempts FROM results WHERE handle=?", new String[]{h});
        int attempts=1, existing=0;
        if(c.moveToFirst()){
            existing=c.getInt(0);
            attempts=c.getInt(1)+1;
        }
        c.close();
        if(existing==FREE) return;

        ContentValues v=new ContentValues();
        v.put("handle",h);
        v.put("status",UNKNOWN);
        v.put("reason",reason);
        v.put("attempts",attempts);
        v.put("updated",System.currentTimeMillis());
        db.insertWithOnConflict("results",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void putFree(String h, String reason) {
        SQLiteDatabase db=getWritableDatabase();
        Cursor c=db.rawQuery("SELECT attempts FROM results WHERE handle=?", new String[]{h});
        int attempts=1;
        if(c.moveToFirst()) attempts=c.getInt(0)+1;
        c.close();

        ContentValues v=new ContentValues();
        v.put("handle",h);
        v.put("status",FREE);
        v.put("reason",reason);
        v.put("attempts",attempts);
        v.put("updated",System.currentTimeMillis());
        db.insertWithOnConflict("results",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void remove(String h) {
        getWritableDatabase().delete("results","handle=?",new String[]{h});
    }

    public synchronized int count(int status) {
        Cursor c=getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM results WHERE status=?",
            new String[]{String.valueOf(status)}
        );
        int n=c.moveToFirst()?c.getInt(0):0;
        c.close();
        return n;
    }

    public synchronized List<String> unknownHandles() {
        ArrayList<String> out=new ArrayList<>();
        Cursor c=getReadableDatabase().rawQuery(
            "SELECT handle FROM results WHERE status=? ORDER BY updated ASC",
            new String[]{String.valueOf(UNKNOWN)}
        );
        while(c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }

    public synchronized List<Row> rows(int status, int limit) {
        ArrayList<Row> out=new ArrayList<>();
        Cursor c=getReadableDatabase().rawQuery(
            "SELECT handle,status,reason,attempts FROM results WHERE status=? ORDER BY updated DESC LIMIT "+limit,
            new String[]{String.valueOf(status)}
        );
        while(c.moveToNext()){
            out.add(new Row(c.getString(0),c.getInt(1),c.getString(2),c.getInt(3)));
        }
        c.close();
        return out;
    }

    public synchronized void clearAll() {
        getWritableDatabase().delete("results",null,null);
    }
}
