package com.hark.neokey;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android side of the clipboard library: SQLite file in app-private storage + one background thread. */
final class ClipStore extends SQLiteOpenHelper {

    private static ClipStore inst;
    private static final String LEGACY_SEP = "\u0001";

    final ClipRepo repo;
    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    static synchronized ClipStore get(Context c) {
        if (inst == null) inst = new ClipStore(c.getApplicationContext());
        return inst;
    }

    private ClipStore(Context c) {
        super(c, "clips.db", null, ClipRepo.SCHEMA);
        app = c;
        setWriteAheadLoggingEnabled(true);
        repo = new ClipRepo(new AndroidSql(null));
    }

    @Override public void onCreate(SQLiteDatabase db) { for (String s : ClipRepo.CREATE) db.execSQL(s); }

    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // schema changes are ALTER TABLE steps; user data is never dropped
        AndroidSql sql = new AndroidSql(db);
        if (oldV < 2) ClipRepo.upgradeTo2(sql);
        for (String s : ClipRepo.CREATE) db.execSQL(s);
    }

    /** Runs work on the clip thread; result delivered on the main thread. */
    interface Done<T> { void done(T r); }
    interface Job<T> { T run(ClipRepo r); }

    <T> void run(final Job<T> job, final Done<T> done) {
        io.execute(new Runnable() {
            @Override public void run() {
                T r;
                try { r = job.run(repo); } catch (Throwable t) { r = null; }
                final T fr = r;
                if (done != null) main.post(new Runnable() { @Override public void run() { done.done(fr); } });
            }
        });
    }

    /** One-time move of the old prefs history. Old keys are removed only after the DB holds every item. */
    void migrateIfNeeded() {
        final SharedPreferences p = Theme.prefs(app);
        final String clips = p.getString("clips", null), pins = p.getString("pins", null);
        if (clips == null && pins == null) return;
        run(new Job<Integer>() {
            @Override public Integer run(ClipRepo r) {
                List<String> c = clips == null || clips.isEmpty() ? new ArrayList<String>() : Arrays.asList(clips.split(LEGACY_SEP));
                HashSet<String> pn = new HashSet<>();
                if (pins != null && !pins.isEmpty()) pn.addAll(Arrays.asList(pins.split(LEGACY_SEP)));
                int n = r.migrateLegacy(c, pn, System.currentTimeMillis());
                if (n >= 0) p.edit().remove("clips").remove("pins").commit();
                return n;
            }
        }, null);
    }

    // ------------------------------------------------------------ SQL adapter

    private final class AndroidSql implements ClipRepo.Sql {
        private final SQLiteDatabase fixed;   // set only inside onUpgrade (no recursive open)
        AndroidSql(SQLiteDatabase fixed) { this.fixed = fixed; }
        private SQLiteDatabase w() { return fixed != null ? fixed : getWritableDatabase(); }

        @Override public void exec(String sql, Object... args) {
            if (args.length == 0) w().execSQL(sql); else w().execSQL(sql, args);
        }

        @Override public long insert(String sql, Object... args) {
            SQLiteStatement st = w().compileStatement(sql);
            try {
                bind(st, args);
                return st.executeInsert();
            } finally { st.close(); }
        }

        @Override public List<Object[]> query(String sql, Object... args) {
            String[] a = new String[args.length];
            for (int i = 0; i < args.length; i++) a[i] = String.valueOf(args[i]);
            List<Object[]> out = new ArrayList<>();
            Cursor c = w().rawQuery(sql, a);
            try {
                int n = c.getColumnCount();
                while (c.moveToNext()) {
                    Object[] row = new Object[n];
                    for (int i = 0; i < n; i++) {
                        switch (c.getType(i)) {
                            case Cursor.FIELD_TYPE_NULL: row[i] = null; break;
                            case Cursor.FIELD_TYPE_INTEGER: row[i] = c.getLong(i); break;
                            case Cursor.FIELD_TYPE_FLOAT: row[i] = c.getDouble(i); break;
                            default: row[i] = c.getString(i);
                        }
                    }
                    out.add(row);
                }
            } finally { c.close(); }
            return out;
        }

        @Override public void begin() { w().beginTransaction(); }
        @Override public void commit() { w().setTransactionSuccessful(); w().endTransaction(); }
        @Override public void rollback() { w().endTransaction(); }

        private void bind(SQLiteStatement st, Object[] args) {
            for (int i = 0; i < args.length; i++) {
                Object o = args[i];
                if (o == null) st.bindNull(i + 1);
                else if (o instanceof Number) st.bindLong(i + 1, ((Number) o).longValue());
                else st.bindString(i + 1, o.toString());
            }
        }
    }
}
