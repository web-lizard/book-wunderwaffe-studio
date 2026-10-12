package com.templeoflizard.bookwunderwaffe.pocket.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.AudioFormat;
import android.net.Uri;

import com.templeoflizard.bookwunderwaffe.pocket.util.Hashing;
import com.templeoflizard.bookwunderwaffe.pocket.util.SmartTitleGenerator;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Process-wide persistence boundary for books, chapters, narration checkpoints and audio.
 * Public methods return immutable snapshots and never expose a database handle.
 */
public final class BookRepository {
    private static final long AUTOSAVE_DELAY_MS = 650L;
    private static final int DEFAULT_SAMPLE_RATE = 22_050;
    private static final int DEFAULT_CHANNEL_COUNT = 1;
    private static volatile BookRepository instance;

    private final Context context;
    private final BookDatabase helper;
    private final ScheduledExecutorService autosaveExecutor;
    private final Object autosaveLock = new Object();
    private final Map<String, PendingAutosave> pendingAutosaves = new HashMap<>();

    private BookRepository(Context context) {
        this.context = context.getApplicationContext();
        this.helper = new BookDatabase(this.context);
        this.autosaveExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chapter-autosave");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static BookRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (BookRepository.class) {
                if (instance == null) instance = new BookRepository(context);
            }
        }
        return instance;
    }

    public synchronized List<Book> listBooks() {
        ArrayList<Book> result = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(
                "books", null, null, null, null, null, "updated_at DESC, id")) {
            while (cursor.moveToNext()) result.add(readBook(cursor));
        }
        return result;
    }

    public synchronized Book getBook(String id) {
        if (isBlank(id)) return null;
        try (Cursor cursor = helper.getReadableDatabase().query(
                "books", null, "id = ?", new String[]{id}, null, null, null, "1")) {
            return cursor.moveToFirst() ? readBook(cursor) : null;
        }
    }

    public synchronized Book createBook(String title) {
        return createBook(title, "");
    }

    public synchronized Book createBook(String title, String author) {
        long now = System.currentTimeMillis();
        Book book = new Book(UUID.randomUUID().toString(), title, author, now, now, 1L);
        ContentValues values = bookValues(book);
        long row = helper.getWritableDatabase().insertOrThrow("books", null, values);
        if (row < 0) throw new IllegalStateException("Could not create book");
        return book;
    }

    /** Updates title and advances the stable book revision. */
    public synchronized Book saveBook(Book book) {
        if (book == null) throw new IllegalArgumentException("book == null");
        Book current = requireBook(book.getId());
        String normalized = Book.normalizeTitle(book.getTitle());
        String author = book.getAuthor() == null ? "" : book.getAuthor().trim();
        if (normalized.equals(current.getTitle()) && author.equals(current.getAuthor())) return current;
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("title", normalized);
        values.put("author", author);
        values.put("updated_at", now);
        values.put("revision", current.getRevision() + 1L);
        helper.getWritableDatabase().update("books", values, "id = ?", new String[]{book.getId()});
        return getBook(book.getId());
    }

    public synchronized boolean deleteBook(String bookId) {
        if (isBlank(bookId)) return false;
        List<String> ownedPaths = collectBookOwnedPaths(bookId);
        int rows = helper.getWritableDatabase().delete("books", "id = ?", new String[]{bookId});
        if (rows > 0) {
            for (String path : ownedPaths) safeDeleteOwnedFile(path);
        }
        return rows > 0;
    }

    public synchronized List<Chapter> listChapters(String bookId) {
        ArrayList<Chapter> result = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(
                "chapters", null, "book_id = ?", new String[]{bookId}, null, null,
                "position ASC, id ASC")) {
            while (cursor.moveToNext()) result.add(readChapter(cursor));
        }
        return result;
    }

    public synchronized Chapter getChapter(String id) {
        if (isBlank(id)) return null;
        try (Cursor cursor = helper.getReadableDatabase().query(
                "chapters", null, "id = ?", new String[]{id}, null, null, null, "1")) {
            return cursor.moveToFirst() ? readChapter(cursor) : null;
        }
    }

    public synchronized Chapter createChapter(String bookId, String title) {
        requireBook(bookId);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            int position = 0;
            try (Cursor cursor = db.rawQuery(
                    "SELECT COALESCE(MAX(position), -1) + 1 FROM chapters WHERE book_id = ?",
                    new String[]{bookId})) {
                if (cursor.moveToFirst()) position = cursor.getInt(0);
            }
            long now = System.currentTimeMillis();
            Chapter chapter = new Chapter(UUID.randomUUID().toString(), bookId, title, "", position,
                    now, now, 1L);
            db.insertOrThrow("chapters", null, chapterValues(chapter));
            touchBook(db, bookId, now);
            db.setTransactionSuccessful();
            return chapter;
        } finally {
            db.endTransaction();
        }
    }

    /** Saves title, text and requested position, incrementing chapter and parent revisions once. */
    public synchronized Chapter saveChapter(Chapter chapter) {
        if (chapter == null) throw new IllegalArgumentException("chapter == null");
        Chapter current = requireChapter(chapter.getId());
        if (!current.getBookId().equals(chapter.getBookId())) {
            throw new IllegalArgumentException("A chapter cannot move between books");
        }
        Chapter saved = autosaveChapter(chapter.getId(), chapter.getTitle(), chapter.getText());
        if (saved != null && saved.getPosition() != chapter.getPosition()) {
            moveChapter(saved.getId(), chapter.getPosition());
            saved = getChapter(saved.getId());
        }
        return saved;
    }

    /**
     * Immediate autosave primitive. It is change-aware, so repeated TextWatcher callbacks do not
     * generate spurious revisions. Use {@link #scheduleChapterAutosave} for debouncing.
     */
    public synchronized Chapter autosaveChapter(String chapterId, String title, String text) {
        Chapter current = requireChapter(chapterId);
        String normalizedTitle = isBlank(title) ? "Новая глава" : title.trim();
        String normalizedText = text == null ? "" : text;
        if (current.getTitle().equals(normalizedTitle) && current.getText().equals(normalizedText)) {
            return current;
        }
        long now = System.currentTimeMillis();
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("title", normalizedTitle);
            values.put("body", normalizedText);
            values.put("updated_at", now);
            values.put("revision", current.getRevision() + 1L);
            db.update("chapters", values, "id = ?", new String[]{chapterId});
            touchBook(db, current.getBookId(), now);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return getChapter(chapterId);
    }

    /** Queues a last-write-wins autosave on a dedicated worker thread. */
    public void scheduleChapterAutosave(String chapterId, String title, String text) {
        if (isBlank(chapterId)) return;
        final PendingAutosave pending = new PendingAutosave(chapterId, title, text);
        synchronized (autosaveLock) {
            PendingAutosave previous = pendingAutosaves.put(chapterId, pending);
            if (previous != null && previous.future != null) previous.future.cancel(false);
            pending.future = autosaveExecutor.schedule(() -> {
                try {
                    autosaveChapter(pending.chapterId, pending.title, pending.text);
                } catch (RuntimeException ignored) {
                    // A concurrent delete is legitimate. Explicit saves still surface errors.
                } finally {
                    synchronized (autosaveLock) {
                        if (pendingAutosaves.get(pending.chapterId) == pending) {
                            pendingAutosaves.remove(pending.chapterId);
                        }
                    }
                }
            }, AUTOSAVE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Flush this from onPause before the activity can be reclaimed. */
    public void flushPendingAutosaves() {
        List<PendingAutosave> pending;
        synchronized (autosaveLock) {
            pending = new ArrayList<>(pendingAutosaves.values());
            pendingAutosaves.clear();
            for (PendingAutosave item : pending) {
                if (item.future != null) item.future.cancel(false);
            }
        }
        for (PendingAutosave item : pending) {
            try {
                autosaveChapter(item.chapterId, item.title, item.text);
            } catch (RuntimeException ignored) {
                // The chapter may have been deleted between the edit and onPause.
            }
        }
    }

    public synchronized boolean deleteChapter(String chapterId) {
        Chapter chapter = getChapter(chapterId);
        if (chapter == null) return false;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            int rows = db.delete("chapters", "id = ?", new String[]{chapterId});
            if (rows > 0) {
                normalizeChapterPositions(db, chapter.getBookId(), System.currentTimeMillis());
                touchBook(db, chapter.getBookId(), System.currentTimeMillis());
            }
            db.setTransactionSuccessful();
            return rows > 0;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void moveChapter(String chapterId, int requestedPosition) {
        Chapter chapter = requireChapter(chapterId);
        int count = countChapters(chapter.getBookId());
        int newPosition = Math.max(0, Math.min(Math.max(0, count - 1), requestedPosition));
        int oldPosition = chapter.getPosition();
        if (oldPosition == newPosition) return;
        long now = System.currentTimeMillis();
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues shifted = new ContentValues();
            if (newPosition < oldPosition) {
                db.execSQL("UPDATE chapters SET position = position + 1, revision = revision + 1, "
                                + "updated_at = ? WHERE book_id = ? AND position >= ? AND position < ?",
                        new Object[]{now, chapter.getBookId(), newPosition, oldPosition});
            } else {
                db.execSQL("UPDATE chapters SET position = position - 1, revision = revision + 1, "
                                + "updated_at = ? WHERE book_id = ? AND position > ? AND position <= ?",
                        new Object[]{now, chapter.getBookId(), oldPosition, newPosition});
            }
            ContentValues moved = new ContentValues();
            moved.put("position", newPosition);
            moved.put("revision", chapter.getRevision() + 1L);
            moved.put("updated_at", now);
            db.update("chapters", moved, "id = ?", new String[]{chapterId});
            touchBook(db, chapter.getBookId(), now);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<AudioAsset> listAudio(String bookId) {
        ArrayList<AudioAsset> result = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(
                "audio_assets", null, "book_id = ?", new String[]{bookId}, null, null,
                "created_at DESC, id")) {
            while (cursor.moveToNext()) result.add(readAudio(cursor));
        }
        return result;
    }

    public synchronized AudioAsset getAudio(String audioId) {
        if (isBlank(audioId)) return null;
        try (Cursor cursor = helper.getReadableDatabase().query(
                "audio_assets", null, "id = ?", new String[]{audioId}, null, null, null, "1")) {
            return cursor.moveToFirst() ? readAudio(cursor) : null;
        }
    }

    public InputStream openAudio(AudioAsset audio) throws FileNotFoundException {
        if (audio == null) throw new IllegalArgumentException("audio == null");
        if (!isBlank(audio.getLocalPath())) return new FileInputStream(audio.getLocalPath());
        if (!isBlank(audio.getDocumentUri())) {
            InputStream stream = context.getContentResolver().openInputStream(Uri.parse(audio.getDocumentUri()));
            if (stream != null) return stream;
        }
        throw new FileNotFoundException(audio.getDisplayName());
    }

    /**
     * Atomically installs a fully validated portable snapshot. This imports rows, never a SQLite
     * file. Incoming revisions older than a local book are rejected to avoid silent data loss.
     */
    public synchronized Book replaceBookFromPackage(Book book, List<Chapter> chapters,
                                                     List<AudioAsset> audioAssets) {
        if (book == null) throw new IllegalArgumentException("book == null");
        List<Chapter> safeChapters = chapters == null ? Collections.emptyList() : chapters;
        List<AudioAsset> safeAudio = audioAssets == null ? Collections.emptyList() : audioAssets;
        validatePortableGraph(book, safeChapters, safeAudio);
        Book local = getBook(book.getId());
        if (local != null && local.getRevision() > book.getRevision()) {
            throw new IllegalStateException("Local book has a newer revision");
        }

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            if (local == null) {
                db.insertOrThrow("books", null, bookValues(book));
            } else {
                db.update("books", bookValues(book), "id = ?", new String[]{book.getId()});
                db.delete("audio_assets", "book_id = ?", new String[]{book.getId()});
                db.delete("chapters", "book_id = ?", new String[]{book.getId()});
            }
            for (Chapter chapter : safeChapters) {
                db.insertOrThrow("chapters", null, chapterValues(chapter));
            }
            for (AudioAsset audio : safeAudio) {
                db.insertOrThrow("audio_assets", null, audioValues(audio, false));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return getBook(book.getId());
    }

    public synchronized NarrationJob createWholeBookNarrationJob(
            String bookId, String languageTag, String voiceName, float speechRate, float pitch) {
        return createWholeBookNarrationJob(bookId, languageTag, voiceName, speechRate, pitch,
                DEFAULT_SAMPLE_RATE, DEFAULT_CHANNEL_COUNT, AudioFormat.ENCODING_PCM_16BIT);
    }

    public synchronized NarrationJob createWholeBookNarrationJob(
            String bookId, String languageTag, String voiceName, float speechRate, float pitch,
            int sampleRate, int channelCount, int audioFormat) {
        Book book = requireBook(bookId);
        List<Chapter> chapters = listChapters(bookId);
        StringBuilder snapshot = new StringBuilder();
        for (Chapter chapter : chapters) {
            if (isBlank(chapter.getText())) continue;
            if (snapshot.length() > 0) snapshot.append("\n\n");
            snapshot.append(chapter.getTitle()).append("\n\n").append(chapter.getText().trim());
        }
        if (snapshot.length() == 0) throw new IllegalStateException("В книге нет текста для озвучки");
        return createNarrationJob(book, null, NarrationJob.SOURCE_BOOK, book.getTitle(),
                snapshot.toString(), languageTag, voiceName, speechRate, pitch,
                sampleRate, channelCount, audioFormat);
    }

    public synchronized NarrationJob createChapterNarrationJob(
            String chapterId, String languageTag, String voiceName, float speechRate, float pitch) {
        return createChapterNarrationJob(chapterId, languageTag, voiceName, speechRate, pitch,
                DEFAULT_SAMPLE_RATE, DEFAULT_CHANNEL_COUNT, AudioFormat.ENCODING_PCM_16BIT);
    }

    public synchronized NarrationJob createChapterNarrationJob(
            String chapterId, String languageTag, String voiceName, float speechRate, float pitch,
            int sampleRate, int channelCount, int audioFormat) {
        Chapter chapter = requireChapter(chapterId);
        Book book = requireBook(chapter.getBookId());
        if (isBlank(chapter.getText())) throw new IllegalStateException("В главе нет текста для озвучки");
        return createNarrationJob(book, chapter, NarrationJob.SOURCE_CHAPTER,
                book.getTitle() + " — " + chapter.getTitle(), chapter.getText(), languageTag,
                voiceName, speechRate, pitch, sampleRate, channelCount, audioFormat);
    }

    /** Naming alias used by screens where the selected chapter is called the current chapter. */
    public NarrationJob createCurrentChapterNarrationJob(
            String chapterId, String languageTag, String voiceName, float speechRate, float pitch) {
        return createChapterNarrationJob(chapterId, languageTag, voiceName, speechRate, pitch);
    }

    public synchronized NarrationJob getNarrationJob(String id) {
        if (isBlank(id)) return null;
        try (Cursor cursor = helper.getReadableDatabase().query(
                "narration_jobs", null, "id = ?", new String[]{id}, null, null, null, "1")) {
            return cursor.moveToFirst() ? readNarrationJob(cursor) : null;
        }
    }

    /** Returns the most recently touched resumable job, including a cancellation request. */
    public synchronized NarrationJob getActiveNarrationJob() {
        String selection = "status IN (?, ?, ?)";
        String[] args = {NarrationJob.STATUS_RUNNING, NarrationJob.STATUS_QUEUED,
                NarrationJob.STATUS_CANCEL_REQUESTED};
        try (Cursor cursor = helper.getReadableDatabase().query(
                "narration_jobs", null, selection, args, null, null,
                "updated_at DESC, created_at DESC", "1")) {
            return cursor.moveToFirst() ? readNarrationJob(cursor) : null;
        }
    }

    public synchronized List<NarrationJob> listNarrationJobs(String bookId, int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        ArrayList<NarrationJob> result = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(
                "narration_jobs", null, "book_id = ?", new String[]{bookId}, null, null,
                "created_at DESC", Integer.toString(safeLimit))) {
            while (cursor.moveToNext()) result.add(readNarrationJob(cursor));
        }
        return result;
    }

    /**
     * Atomic durable checkpoint called only after the worker has fsynced its part file. Stale
     * callbacks cannot move the byte/chunk checkpoint or displayed percent backwards.
     */
    public synchronized void updateNarrationProgress(
            String id, String stage, String status, int percent, int resumeChunkIndex,
            int totalChunks, long committedPcmBytes) {
        requireNarrationJob(id);
        String safeStage = normalizeStage(stage);
        String safeStatus = normalizeActiveStatus(status);
        long now = System.currentTimeMillis();
        helper.getWritableDatabase().execSQL(
                "UPDATE narration_jobs SET "
                        + "stage = ?, "
                        + "status = CASE WHEN status = ? THEN status ELSE ? END, "
                        + "progress_percent = MAX(progress_percent, ?), "
                        + "resume_chunk_index = MAX(resume_chunk_index, ?), "
                        + "total_chunks = MAX(total_chunks, ?), "
                        + "committed_pcm_bytes = MAX(committed_pcm_bytes, ?), "
                        + "updated_at = ?, "
                        + "started_at = CASE WHEN started_at = 0 THEN ? ELSE started_at END "
                        + "WHERE id = ? AND status NOT IN (?, ?, ?)",
                new Object[]{safeStage, NarrationJob.STATUS_CANCEL_REQUESTED, safeStatus,
                        clampPercent(percent), Math.max(0, resumeChunkIndex), Math.max(0, totalChunks),
                        Math.max(0L, committedPcmBytes), now, now, id,
                        NarrationJob.STATUS_COMPLETED, NarrationJob.STATUS_FAILED,
                        NarrationJob.STATUS_CANCELLED});
    }

    public void checkpointNarrationJob(NarrationJob job) {
        if (job == null) throw new IllegalArgumentException("job == null");
        updateNarrationProgress(job.getId(), job.getStage(), job.getStatus(),
                job.getProgressPercent(), job.getResumeChunkIndex(), job.getTotalChunks(),
                job.getCommittedPcmBytes());
    }

    /** Persists the actual format reported by TextToSpeech.onBeginSynthesis. */
    public synchronized void updateNarrationAudioFormat(
            String id, int sampleRate, int channelCount, int audioFormat) {
        NarrationJob job = requireNarrationJob(id);
        if (job.isTerminal()) return;
        if (sampleRate <= 0 || channelCount <= 0 || audioFormat <= 0) {
            throw new IllegalArgumentException("Invalid PCM format");
        }
        ContentValues values = new ContentValues();
        values.put("sample_rate", sampleRate);
        values.put("channel_count", channelCount);
        values.put("audio_format", audioFormat);
        values.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update("narration_jobs", values, "id = ?", new String[]{id});
    }

    public synchronized void appendNarrationLog(String id, String level, String message) {
        requireNarrationJob(id);
        ContentValues values = new ContentValues();
        values.put("job_id", id);
        values.put("created_at", System.currentTimeMillis());
        values.put("level", normalizeLogLevel(level));
        values.put("message", truncate(message == null ? "" : message, 4_000));
        helper.getWritableDatabase().insertOrThrow("narration_logs", null, values);
    }

    /** Returns oldest-to-newest rows for direct display in the collapsible technical panel. */
    public synchronized List<NarrationLogEntry> getNarrationLogs(String id, int limit) {
        int safeLimit = Math.max(1, Math.min(BookDatabase.MAX_LOG_ROWS_PER_JOB, limit));
        ArrayList<NarrationLogEntry> newestFirst = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(
                "narration_logs", null, "job_id = ?", new String[]{id}, null, null,
                "sequence DESC", Integer.toString(safeLimit))) {
            while (cursor.moveToNext()) newestFirst.add(readNarrationLog(cursor));
        }
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    public synchronized void completeNarrationJob(
            String id, String audioPath, long durationMs, long sizeBytes) {
        NarrationJob job = requireNarrationJob(id);
        if (job.isTerminal()) return;
        if (NarrationJob.STATUS_CANCEL_REQUESTED.equals(job.getStatus())) {
            markNarrationCancelled(id);
            return;
        }
        if (isBlank(audioPath)) throw new IllegalArgumentException("audioPath is empty");
        long now = System.currentTimeMillis();
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues completed = new ContentValues();
            completed.put("status", NarrationJob.STATUS_COMPLETED);
            completed.put("stage", NarrationJob.STAGE_DONE);
            completed.put("progress_percent", 100);
            completed.put("error", (String) null);
            completed.put("updated_at", now);
            completed.put("completed_at", now);
            db.update("narration_jobs", completed, "id = ?", new String[]{id});

            boolean alreadyHasAudio;
            try (Cursor cursor = db.query("audio_assets", new String[]{"id"},
                    "narration_job_id = ?", new String[]{id}, null, null, null, "1")) {
                alreadyHasAudio = cursor.moveToFirst();
            }
            if (!alreadyHasAudio) {
                File file = new File(audioPath);
                AudioAsset asset = new AudioAsset(UUID.randomUUID().toString(), job.getBookId(),
                        job.getChapterId(), id, file.getName(), "audio/wav", audioPath, null,
                        durationMs, sizeBytes > 0 ? sizeBytes : file.length(), job.getSampleRate(),
                        job.getChannelCount(), job.getAudioFormat(), null, now);
                db.insertOrThrow("audio_assets", null, audioValues(asset, true));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void failNarrationJob(String id, String error) {
        NarrationJob job = requireNarrationJob(id);
        if (job.isTerminal()) return;
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("status", NarrationJob.STATUS_FAILED);
        values.put("stage", NarrationJob.STAGE_ERROR);
        values.put("error", truncate(isBlank(error) ? "Неизвестная ошибка синтеза" : error, 8_000));
        values.put("updated_at", now);
        values.put("completed_at", now);
        helper.getWritableDatabase().update("narration_jobs", values, "id = ?", new String[]{id});
    }

    /** UI command. The worker observes it through {@link #isCancellationRequested}. */
    public synchronized void cancelNarrationJob(String id) {
        NarrationJob job = requireNarrationJob(id);
        if (job.isTerminal() || NarrationJob.STATUS_CANCEL_REQUESTED.equals(job.getStatus())) return;
        ContentValues values = new ContentValues();
        values.put("status", NarrationJob.STATUS_CANCEL_REQUESTED);
        values.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update("narration_jobs", values, "id = ?", new String[]{id});
    }

    /** Worker acknowledgement after closing and removing the temporary PCM part. */
    public synchronized void markNarrationCancelled(String id) {
        NarrationJob job = requireNarrationJob(id);
        if (job.isTerminal()) return;
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("status", NarrationJob.STATUS_CANCELLED);
        values.put("stage", NarrationJob.STAGE_CANCELLED);
        values.put("updated_at", now);
        values.put("completed_at", now);
        helper.getWritableDatabase().update("narration_jobs", values, "id = ?", new String[]{id});
    }

    public synchronized boolean isCancellationRequested(String id) {
        NarrationJob job = getNarrationJob(id);
        return job != null && (NarrationJob.STATUS_CANCEL_REQUESTED.equals(job.getStatus())
                || NarrationJob.STATUS_CANCELLED.equals(job.getStatus()));
    }

    public synchronized AudioAsset getAudioForNarrationJob(String jobId) {
        try (Cursor cursor = helper.getReadableDatabase().query(
                "audio_assets", null, "narration_job_id = ?", new String[]{jobId}, null, null,
                "created_at DESC", "1")) {
            return cursor.moveToFirst() ? readAudio(cursor) : null;
        }
    }

    private NarrationJob createNarrationJob(
            Book book, Chapter chapter, String sourceType, String displayTitle, String text,
            String languageTag, String voiceName, float speechRate, float pitch, int sampleRate,
            int channelCount, int audioFormat) {
        String safeLanguage = isBlank(languageTag) ? "ru-RU" : languageTag.trim();
        float safeRate = clampFloat(speechRate, 0.25f, 3.0f, 1.0f);
        float safePitch = clampFloat(pitch, 0.25f, 2.0f, 1.0f);
        int safeSampleRate = sampleRate > 0 ? sampleRate : DEFAULT_SAMPLE_RATE;
        int safeChannels = channelCount > 0 ? channelCount : DEFAULT_CHANNEL_COUNT;
        int safeFormat = audioFormat > 0 ? audioFormat : AudioFormat.ENCODING_PCM_16BIT;
        String jobId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        File directory = narrationDirectory(book.getId());
        String suggestedName = SmartTitleGenerator.generateAudioFileName(book.getTitle(),
                chapter == null ? null : chapter.getTitle(), text, now);
        File output = new File(directory, suggestedName);
        if (output.exists()) {
            output = new File(directory, SmartTitleGenerator.withSuffix(
                    suggestedName, jobId.substring(0, 8)));
        }
        File part = new File(directory, jobId + ".pcm.part");

        JSONObject settings = new JSONObject();
        try {
            settings.put("schemaVersion", 1);
            settings.put("languageTag", safeLanguage);
            if (!isBlank(voiceName)) settings.put("voiceName", voiceName);
            settings.put("speechRate", safeRate);
            settings.put("pitch", safePitch);
            settings.put("sampleRate", safeSampleRate);
            settings.put("channelCount", safeChannels);
            settings.put("audioFormat", safeFormat);
        } catch (JSONException impossible) {
            throw new IllegalStateException("Could not serialize narration settings", impossible);
        }

        ContentValues values = new ContentValues();
        values.put("id", jobId);
        values.put("book_id", book.getId());
        if (chapter == null) values.putNull("chapter_id");
        else values.put("chapter_id", chapter.getId());
        values.put("source_type", sourceType);
        values.put("display_title", displayTitle);
        values.put("text_snapshot", text);
        values.put("text_hash", Hashing.sha256(text));
        values.put("settings_json", settings.toString());
        values.put("language_tag", safeLanguage);
        if (isBlank(voiceName)) values.putNull("voice_name");
        else values.put("voice_name", voiceName);
        values.put("speech_rate", safeRate);
        values.put("pitch", safePitch);
        values.put("stage", NarrationJob.STAGE_QUEUED);
        values.put("status", NarrationJob.STATUS_QUEUED);
        values.put("progress_percent", 0);
        values.put("resume_chunk_index", 0);
        values.put("total_chunks", 0);
        values.put("committed_pcm_bytes", 0L);
        values.put("sample_rate", safeSampleRate);
        values.put("channel_count", safeChannels);
        values.put("audio_format", safeFormat);
        values.put("output_path", output.getAbsolutePath());
        values.put("part_path", part.getAbsolutePath());
        values.putNull("error");
        values.put("created_at", now);
        values.put("updated_at", now);
        values.put("started_at", 0L);
        values.put("completed_at", 0L);
        helper.getWritableDatabase().insertOrThrow("narration_jobs", null, values);
        appendNarrationLog(jobId, "INFO", "Задание создано; текст: " + text.length()
                + " символов; SHA-256: " + Hashing.sha256(text).substring(0, 12));
        return getNarrationJob(jobId);
    }

    private File narrationDirectory(String bookId) {
        File root = new File(context.getFilesDir(), "narrations");
        File directory = new File(root, Hashing.sha256(bookId).substring(0, 24));
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory()) {
            throw new IllegalStateException("Could not create narration directory");
        }
        return directory;
    }

    private Book requireBook(String id) {
        Book book = getBook(id);
        if (book == null) throw new IllegalArgumentException("Unknown book: " + id);
        return book;
    }

    private Chapter requireChapter(String id) {
        Chapter chapter = getChapter(id);
        if (chapter == null) throw new IllegalArgumentException("Unknown chapter: " + id);
        return chapter;
    }

    private NarrationJob requireNarrationJob(String id) {
        NarrationJob job = getNarrationJob(id);
        if (job == null) throw new IllegalArgumentException("Unknown narration job: " + id);
        return job;
    }

    private static ContentValues bookValues(Book book) {
        ContentValues values = new ContentValues();
        values.put("id", book.getId());
        values.put("title", book.getTitle());
        values.put("author", book.getAuthor());
        values.put("created_at", book.getCreatedAt());
        values.put("updated_at", book.getUpdatedAt());
        values.put("revision", book.getRevision());
        return values;
    }

    private static ContentValues chapterValues(Chapter chapter) {
        ContentValues values = new ContentValues();
        values.put("id", chapter.getId());
        values.put("book_id", chapter.getBookId());
        values.put("title", chapter.getTitle());
        values.put("body", chapter.getText());
        values.put("position", chapter.getPosition());
        values.put("created_at", chapter.getCreatedAt());
        values.put("updated_at", chapter.getUpdatedAt());
        values.put("revision", chapter.getRevision());
        return values;
    }

    private static ContentValues audioValues(AudioAsset audio, boolean includeJobReference) {
        ContentValues values = new ContentValues();
        values.put("id", audio.getId());
        values.put("book_id", audio.getBookId());
        putNullable(values, "chapter_id", audio.getChapterId());
        putNullable(values, "narration_job_id",
                includeJobReference ? audio.getNarrationJobId() : null);
        values.put("display_name", audio.getDisplayName());
        values.put("mime_type", audio.getMimeType());
        putNullable(values, "local_path", audio.getLocalPath());
        putNullable(values, "document_uri", audio.getDocumentUri());
        values.put("duration_ms", audio.getDurationMs());
        values.put("size_bytes", audio.getSizeBytes());
        values.put("sample_rate", audio.getSampleRate());
        values.put("channel_count", audio.getChannelCount());
        values.put("audio_format", audio.getAudioFormat());
        putNullable(values, "content_hash", audio.getContentHash());
        values.put("created_at", audio.getCreatedAt());
        return values;
    }

    private static void putNullable(ContentValues values, String key, String value) {
        if (isBlank(value)) values.putNull(key);
        else values.put(key, value);
    }

    private static Book readBook(Cursor cursor) {
        return new Book(text(cursor, "id"), text(cursor, "title"), text(cursor, "author"),
                number(cursor, "created_at"), number(cursor, "updated_at"),
                number(cursor, "revision"));
    }

    private static Chapter readChapter(Cursor cursor) {
        return new Chapter(text(cursor, "id"), text(cursor, "book_id"), text(cursor, "title"),
                text(cursor, "body"), integer(cursor, "position"), number(cursor, "created_at"),
                number(cursor, "updated_at"), number(cursor, "revision"));
    }

    private static AudioAsset readAudio(Cursor cursor) {
        return new AudioAsset(text(cursor, "id"), text(cursor, "book_id"),
                nullableText(cursor, "chapter_id"), nullableText(cursor, "narration_job_id"),
                text(cursor, "display_name"), text(cursor, "mime_type"),
                nullableText(cursor, "local_path"), nullableText(cursor, "document_uri"),
                number(cursor, "duration_ms"), number(cursor, "size_bytes"),
                integer(cursor, "sample_rate"), integer(cursor, "channel_count"),
                integer(cursor, "audio_format"), nullableText(cursor, "content_hash"),
                number(cursor, "created_at"));
    }

    private static NarrationJob readNarrationJob(Cursor cursor) {
        return new NarrationJob(text(cursor, "id"), text(cursor, "book_id"),
                nullableText(cursor, "chapter_id"), text(cursor, "source_type"),
                text(cursor, "display_title"), text(cursor, "text_snapshot"),
                text(cursor, "text_hash"), text(cursor, "settings_json"),
                text(cursor, "language_tag"), nullableText(cursor, "voice_name"),
                cursor.getFloat(cursor.getColumnIndexOrThrow("speech_rate")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("pitch")), text(cursor, "stage"),
                text(cursor, "status"), integer(cursor, "progress_percent"),
                integer(cursor, "resume_chunk_index"), integer(cursor, "total_chunks"),
                number(cursor, "committed_pcm_bytes"), integer(cursor, "sample_rate"),
                integer(cursor, "channel_count"), integer(cursor, "audio_format"),
                text(cursor, "output_path"), text(cursor, "part_path"),
                nullableText(cursor, "error"), number(cursor, "created_at"),
                number(cursor, "updated_at"), number(cursor, "started_at"),
                number(cursor, "completed_at"));
    }

    private static NarrationLogEntry readNarrationLog(Cursor cursor) {
        return new NarrationLogEntry(number(cursor, "sequence"), text(cursor, "job_id"),
                number(cursor, "created_at"), text(cursor, "level"), text(cursor, "message"));
    }

    private static String text(Cursor cursor, String column) {
        String value = cursor.getString(cursor.getColumnIndexOrThrow(column));
        return value == null ? "" : value;
    }

    private static String nullableText(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? null : cursor.getString(index);
    }

    private static int integer(Cursor cursor, String column) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(column));
    }

    private static long number(Cursor cursor, String column) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(column));
    }

    private static void touchBook(SQLiteDatabase db, String bookId, long now) {
        db.execSQL("UPDATE books SET revision = revision + 1, updated_at = ? WHERE id = ?",
                new Object[]{now, bookId});
    }

    private int countChapters(String bookId) {
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM chapters WHERE book_id = ?", new String[]{bookId})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private static void normalizeChapterPositions(SQLiteDatabase db, String bookId, long now) {
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<Integer> positions = new ArrayList<>();
        try (Cursor cursor = db.query("chapters", new String[]{"id", "position"},
                "book_id = ?", new String[]{bookId}, null, null, "position ASC, id ASC")) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(0));
                positions.add(cursor.getInt(1));
            }
        }
        for (int index = 0; index < ids.size(); index++) {
            if (positions.get(index) == index) continue;
            db.execSQL("UPDATE chapters SET position = ?, revision = revision + 1, updated_at = ? "
                    + "WHERE id = ?", new Object[]{index, now, ids.get(index)});
        }
    }

    private static void validatePortableGraph(Book book, List<Chapter> chapters,
                                              List<AudioAsset> audioAssets) {
        Set<String> chapterIds = new HashSet<>();
        Set<Integer> positions = new HashSet<>();
        for (Chapter chapter : chapters) {
            if (!book.getId().equals(chapter.getBookId())) {
                throw new IllegalArgumentException("Chapter belongs to another book");
            }
            if (!chapterIds.add(chapter.getId())) throw new IllegalArgumentException("Duplicate chapter id");
            if (!positions.add(chapter.getPosition())) throw new IllegalArgumentException("Duplicate position");
        }
        Set<String> audioIds = new HashSet<>();
        for (AudioAsset audio : audioAssets) {
            if (!book.getId().equals(audio.getBookId())) {
                throw new IllegalArgumentException("Audio belongs to another book");
            }
            if (!audioIds.add(audio.getId())) throw new IllegalArgumentException("Duplicate audio id");
            if (audio.getChapterId() != null && !chapterIds.contains(audio.getChapterId())) {
                throw new IllegalArgumentException("Audio refers to an unknown chapter");
            }
        }
    }

    private List<String> collectBookOwnedPaths(String bookId) {
        ArrayList<String> result = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query("audio_assets", new String[]{"local_path"}, "book_id = ?",
                new String[]{bookId}, null, null, null)) {
            while (cursor.moveToNext()) if (!cursor.isNull(0)) result.add(cursor.getString(0));
        }
        try (Cursor cursor = db.query("narration_jobs", new String[]{"output_path", "part_path"},
                "book_id = ?", new String[]{bookId}, null, null, null)) {
            while (cursor.moveToNext()) {
                if (!cursor.isNull(0)) result.add(cursor.getString(0));
                if (!cursor.isNull(1)) result.add(cursor.getString(1));
            }
        }
        return result;
    }

    private void safeDeleteOwnedFile(String path) {
        if (isBlank(path)) return;
        try {
            File filesRoot = context.getFilesDir().getCanonicalFile();
            File target = new File(path).getCanonicalFile();
            String prefix = filesRoot.getPath() + File.separator;
            if (target.isFile() && target.getPath().startsWith(prefix)) {
                // Best effort: the DB deletion remains authoritative if media cleanup fails.
                target.delete();
            }
        } catch (Exception ignored) {
            // Never follow an unresolvable or external path during cleanup.
        }
    }

    private static String normalizeStage(String value) {
        if (NarrationJob.STAGE_QUEUED.equals(value) || NarrationJob.STAGE_PREPARING.equals(value)
                || NarrationJob.STAGE_SYNTHESIZING.equals(value)
                || NarrationJob.STAGE_FINALIZING.equals(value)) return value;
        throw new IllegalArgumentException("Invalid active stage: " + value);
    }

    private static String normalizeActiveStatus(String value) {
        if (NarrationJob.STATUS_QUEUED.equals(value) || NarrationJob.STATUS_RUNNING.equals(value)
                || NarrationJob.STATUS_CANCEL_REQUESTED.equals(value)) return value;
        throw new IllegalArgumentException("Invalid active status: " + value);
    }

    private static String normalizeLogLevel(String value) {
        String level = value == null ? "INFO" : value.trim().toUpperCase(Locale.ROOT);
        if ("DEBUG".equals(level) || "INFO".equals(level) || "WARN".equals(level)
                || "ERROR".equals(level)) return level;
        return "INFO";
    }

    private static float clampFloat(float value, float minimum, float maximum, float fallback) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return fallback;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class PendingAutosave {
        final String chapterId;
        final String title;
        final String text;
        volatile ScheduledFuture<?> future;

        PendingAutosave(String chapterId, String title, String text) {
            this.chapterId = chapterId;
            this.title = title;
            this.text = text;
        }
    }
}
