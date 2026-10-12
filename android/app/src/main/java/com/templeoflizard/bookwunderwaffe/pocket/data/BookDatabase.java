package com.templeoflizard.bookwunderwaffe.pocket.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Internal normalized store. Portable book packages never contain this database file. */
final class BookDatabase extends SQLiteOpenHelper {
    static final String DATABASE_NAME = "book_wunderwaffe_pocket.db";
    static final int DATABASE_VERSION = 1;
    static final int MAX_LOG_ROWS_PER_JOB = 500;

    BookDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        db.execSQL("PRAGMA busy_timeout=5000");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "title TEXT NOT NULL,"
                + "author TEXT NOT NULL DEFAULT '',"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "revision INTEGER NOT NULL DEFAULT 1 CHECK(revision >= 0)"
                + ")");

        db.execSQL("CREATE TABLE chapters ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,"
                + "title TEXT NOT NULL,"
                + "body TEXT NOT NULL DEFAULT '',"
                + "position INTEGER NOT NULL CHECK(position >= 0),"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "revision INTEGER NOT NULL DEFAULT 1 CHECK(revision >= 0)"
                + ")");
        db.execSQL("CREATE INDEX chapters_book_position_idx ON chapters(book_id, position, id)");

        db.execSQL("CREATE TABLE narration_jobs ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,"
                + "chapter_id TEXT REFERENCES chapters(id) ON DELETE SET NULL,"
                + "source_type TEXT NOT NULL,"
                + "display_title TEXT NOT NULL,"
                + "text_snapshot TEXT NOT NULL,"
                + "text_hash TEXT NOT NULL,"
                + "settings_json TEXT NOT NULL DEFAULT '{}',"
                + "language_tag TEXT NOT NULL DEFAULT 'ru-RU',"
                + "voice_name TEXT,"
                + "speech_rate REAL NOT NULL DEFAULT 1.0,"
                + "pitch REAL NOT NULL DEFAULT 1.0,"
                + "stage TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "progress_percent INTEGER NOT NULL DEFAULT 0 CHECK(progress_percent BETWEEN 0 AND 100),"
                + "resume_chunk_index INTEGER NOT NULL DEFAULT 0 CHECK(resume_chunk_index >= 0),"
                + "total_chunks INTEGER NOT NULL DEFAULT 0 CHECK(total_chunks >= 0),"
                + "committed_pcm_bytes INTEGER NOT NULL DEFAULT 0 CHECK(committed_pcm_bytes >= 0),"
                + "sample_rate INTEGER NOT NULL DEFAULT 0,"
                + "channel_count INTEGER NOT NULL DEFAULT 0,"
                + "audio_format INTEGER NOT NULL DEFAULT 0,"
                + "output_path TEXT NOT NULL,"
                + "part_path TEXT NOT NULL,"
                + "error TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "started_at INTEGER NOT NULL DEFAULT 0,"
                + "completed_at INTEGER NOT NULL DEFAULT 0"
                + ")");
        db.execSQL("CREATE INDEX narration_jobs_active_idx ON narration_jobs(status, updated_at DESC)");
        db.execSQL("CREATE INDEX narration_jobs_book_idx ON narration_jobs(book_id, created_at DESC)");

        db.execSQL("CREATE TABLE audio_assets ("
                + "id TEXT PRIMARY KEY NOT NULL,"
                + "book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,"
                + "chapter_id TEXT REFERENCES chapters(id) ON DELETE SET NULL,"
                + "narration_job_id TEXT REFERENCES narration_jobs(id) ON DELETE SET NULL,"
                + "display_name TEXT NOT NULL,"
                + "mime_type TEXT NOT NULL DEFAULT 'audio/wav',"
                + "local_path TEXT,"
                + "document_uri TEXT,"
                + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                + "size_bytes INTEGER NOT NULL DEFAULT 0,"
                + "sample_rate INTEGER NOT NULL DEFAULT 0,"
                + "channel_count INTEGER NOT NULL DEFAULT 0,"
                + "audio_format INTEGER NOT NULL DEFAULT 0,"
                + "content_hash TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "CHECK(local_path IS NOT NULL OR document_uri IS NOT NULL)"
                + ")");
        db.execSQL("CREATE INDEX audio_assets_book_idx ON audio_assets(book_id, created_at DESC)");

        db.execSQL("CREATE TABLE narration_logs ("
                + "sequence INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "job_id TEXT NOT NULL REFERENCES narration_jobs(id) ON DELETE CASCADE,"
                + "created_at INTEGER NOT NULL,"
                + "level TEXT NOT NULL,"
                + "message TEXT NOT NULL"
                + ")");
        db.execSQL("CREATE INDEX narration_logs_job_idx ON narration_logs(job_id, sequence DESC)");
        db.execSQL("CREATE TRIGGER narration_logs_ring AFTER INSERT ON narration_logs BEGIN "
                + "DELETE FROM narration_logs WHERE job_id = NEW.job_id AND sequence < "
                + "(SELECT sequence FROM narration_logs WHERE job_id = NEW.job_id "
                + "ORDER BY sequence DESC LIMIT 1 OFFSET " + (MAX_LOG_ROWS_PER_JOB - 1) + "); END");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the initial public schema. Future versions must migrate in place because
        // narration checkpoints are intentionally durable across process/app updates.
        if (oldVersion != newVersion) {
            throw new IllegalStateException("Missing DB migration " + oldVersion + " -> " + newVersion);
        }
    }
}
