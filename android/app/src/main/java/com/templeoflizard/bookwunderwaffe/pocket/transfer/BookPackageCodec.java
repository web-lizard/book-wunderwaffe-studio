package com.templeoflizard.bookwunderwaffe.pocket.transfer;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.templeoflizard.bookwunderwaffe.pocket.data.AudioAsset;
import com.templeoflizard.bookwunderwaffe.pocket.data.Book;
import com.templeoflizard.bookwunderwaffe.pocket.data.BookRepository;
import com.templeoflizard.bookwunderwaffe.pocket.data.Chapter;
import com.templeoflizard.bookwunderwaffe.pocket.util.Hashing;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Versioned portable .bwlbook stream codec. The archive contains JSON/text/audio only; it never
 * copies the app's SQLite database, which keeps the format usable by a future desktop sync layer.
 */
public final class BookPackageCodec {
    public static final String MIME_TYPE = "application/vnd.book-wunderwaffe.book+zip";
    public static final String FILE_EXTENSION = ".bwlbook";
    public static final String FORMAT_ID = "book-wunderwaffe-book";
    public static final int SCHEMA_VERSION = 1;

    private static final String MANIFEST_PATH = "manifest.json";
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final int MAX_ENTRIES = 4_096;
    private static final int MAX_CHAPTERS = 2_048;
    private static final int MAX_AUDIO_ASSETS = 2_047;
    private static final long MAX_MANIFEST_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_CHAPTER_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_AUDIO_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final long MAX_COMPRESSION_RATIO = 500L;

    private final Context context;
    private final ContentResolver resolver;
    private final BookRepository repository;

    public BookPackageCodec(Context context) {
        this(context, BookRepository.getInstance(context));
    }

    public BookPackageCodec(Context context, BookRepository repository) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.repository = repository;
    }

    public void exportBook(String bookId, Uri destination) throws IOException {
        if (destination == null) throw new IllegalArgumentException("destination == null");
        Book book = repository.getBook(bookId);
        if (book == null) throw new IllegalArgumentException("Unknown book: " + bookId);
        requireUuid(book.getId(), "book.uuid");

        List<Chapter> chapters = repository.listChapters(bookId);
        if (chapters.size() > MAX_CHAPTERS) throw new IOException("Too many chapters");
        List<ChapterExport> chapterExports = new ArrayList<>(chapters.size());
        for (Chapter chapter : chapters) {
            requireUuid(chapter.getId(), "chapter.uuid");
            byte[] text = chapter.getText().getBytes(StandardCharsets.UTF_8);
            if (text.length > MAX_CHAPTER_BYTES) {
                throw new IOException("Chapter is too large: " + chapter.getTitle());
            }
            chapterExports.add(new ChapterExport(chapter,
                    String.format(Locale.ROOT, "chapters/%04d-%s.txt", chapter.getPosition(),
                            chapter.getId()), text, Hashing.sha256(text)));
        }

        List<AudioAsset> audio = repository.listAudio(bookId);
        if (audio.size() > MAX_AUDIO_ASSETS) throw new IOException("Too many audio assets");
        List<AudioExport> audioExports = new ArrayList<>(audio.size());
        long totalBytes = 0L;
        for (AudioAsset asset : audio) {
            requireUuid(asset.getId(), "audio.uuid");
            String relativePath = "audio/" + asset.getId() + safeAudioExtension(asset.getDisplayName());
            HashAndSize scan;
            try (InputStream input = repository.openAudio(asset)) {
                scan = hashAndCount(input, MAX_AUDIO_BYTES);
            }
            totalBytes = checkedAdd(totalBytes, scan.size);
            if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) throw new IOException("Book audio is too large");
            audioExports.add(new AudioExport(asset, relativePath, scan.hash, scan.size));
        }

        JSONObject manifest = buildManifest(book, chapterExports, audioExports);
        byte[] manifestBytes;
        try {
            manifestBytes = manifest.toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException error) {
            throw new IOException("Could not serialize manifest", error);
        }
        if (manifestBytes.length > MAX_MANIFEST_BYTES) throw new IOException("Manifest is too large");

        OutputStream raw = resolver.openOutputStream(destination, "w");
        if (raw == null) throw new IOException("ContentResolver returned no output stream");
        try (OutputStream output = raw; ZipOutputStream zip = new ZipOutputStream(output)) {
            putBytes(zip, MANIFEST_PATH, manifestBytes);
            for (ChapterExport chapter : chapterExports) {
                putBytes(zip, chapter.relativePath, chapter.textBytes);
            }
            for (AudioExport item : audioExports) {
                ZipEntry entry = new ZipEntry(item.relativePath);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                try (InputStream input = repository.openAudio(item.asset)) {
                    copy(input, zip, item.size);
                }
                zip.closeEntry();
            }
            zip.finish();
        }
    }

    /** Validates and imports a complete snapshot, preserving stable UUIDs and revisions. */
    public Book importBook(Uri source) throws IOException {
        if (source == null) throw new IllegalArgumentException("source == null");
        File stagingRoot = new File(context.getCacheDir(), "bwlbook-import-" + UUID.randomUUID());
        if (!stagingRoot.mkdirs()) throw new IOException("Could not create import staging directory");
        ArrayList<File> newlyInstalled = new ArrayList<>();
        try {
            ParsedPackage parsed;
            InputStream raw = resolver.openInputStream(source);
            if (raw == null) throw new IOException("ContentResolver returned no input stream");
            try (InputStream input = raw; ZipInputStream zip = new ZipInputStream(input)) {
                parsed = readAndValidate(zip, stagingRoot);
            }

            File audioRoot = new File(new File(context.getFilesDir(), "portable-audio"),
                    Hashing.sha256(parsed.book.getId()).substring(0, 24));
            if ((!audioRoot.exists() && !audioRoot.mkdirs()) || !audioRoot.isDirectory()) {
                throw new IOException("Could not create imported audio directory");
            }
            ArrayList<AudioAsset> installedAudio = new ArrayList<>();
            for (AudioSpec spec : parsed.audioSpecs) {
                String extension = safeAudioExtension(spec.relativePath);
                File destination = new File(audioRoot,
                        spec.id + "-" + spec.contentHash.substring(0, 12) + extension);
                boolean existed = destination.isFile();
                if (existed) {
                    try (InputStream existing = new FileInputStream(destination)) {
                        if (!Hashing.constantTimeEquals(Hashing.sha256(existing), spec.contentHash)) {
                            destination = new File(audioRoot, spec.id + "-"
                                    + spec.contentHash.substring(0, 12) + "-" + UUID.randomUUID()
                                    + extension);
                            existed = false;
                        }
                    }
                }
                if (existed) {
                    if (!spec.stagedFile.delete()) throw new IOException("Could not clear staging audio");
                } else {
                    moveFile(spec.stagedFile, destination);
                    newlyInstalled.add(destination);
                }
                installedAudio.add(new AudioAsset(spec.id, parsed.book.getId(), spec.chapterId,
                        null, spec.displayName, spec.mimeType, destination.getAbsolutePath(), null,
                        spec.durationMs, spec.sizeBytes, spec.sampleRate, spec.channelCount,
                        spec.audioFormat, spec.contentHash, spec.createdAt));
            }
            Book result = repository.replaceBookFromPackage(
                    parsed.book, parsed.chapters, installedAudio);
            newlyInstalled.clear();
            return result;
        } catch (JSONException error) {
            throw new IOException("Invalid book manifest JSON", error);
        } catch (RuntimeException error) {
            throw new IOException("Book import rejected: " + error.getMessage(), error);
        } finally {
            for (File file : newlyInstalled) {
                if (file.isFile()) file.delete();
            }
            deleteTree(stagingRoot, stagingRoot);
        }
    }

    private ParsedPackage readAndValidate(ZipInputStream zip, File stagingRoot)
            throws IOException, JSONException {
        Budget budget = new Budget();
        ZipEntry manifestEntry = zip.getNextEntry();
        if (manifestEntry == null || manifestEntry.isDirectory()
                || !MANIFEST_PATH.equals(manifestEntry.getName())) {
            throw new IOException("The first ZIP entry must be manifest.json");
        }
        validateEntryMetadata(manifestEntry, MAX_MANIFEST_BYTES);
        byte[] manifestBytes = readBounded(zip, MAX_MANIFEST_BYTES, budget);
        zip.closeEntry();
        JSONObject root = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        ManifestSpec manifest = parseManifest(root);

        Map<String, ChapterSpec> chaptersByPath = new HashMap<>();
        for (ChapterSpec chapter : manifest.chapterSpecs) {
            if (chaptersByPath.put(chapter.relativePath, chapter) != null) {
                throw new IOException("Duplicate chapter path");
            }
        }
        Map<String, AudioSpec> audioByPath = new HashMap<>();
        for (AudioSpec audio : manifest.audioSpecs) {
            if (audioByPath.put(audio.relativePath, audio) != null) {
                throw new IOException("Duplicate audio path");
            }
        }

        Set<String> seen = new HashSet<>();
        int entryCount = 1;
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (++entryCount > MAX_ENTRIES) throw new IOException("Too many ZIP entries");
            if (entry.isDirectory()) throw new IOException("Directory entries are not allowed");
            String path = validateRelativePath(entry.getName());
            if (!seen.add(path)) throw new IOException("Duplicate ZIP entry: " + path);
            ChapterSpec chapter = chaptersByPath.get(path);
            AudioSpec audio = audioByPath.get(path);
            if (chapter == null && audio == null) throw new IOException("Unexpected ZIP entry: " + path);
            if (chapter != null) {
                validateEntryMetadata(entry, MAX_CHAPTER_BYTES);
                byte[] bytes = readBounded(zip, Math.min(MAX_CHAPTER_BYTES, chapter.sizeBytes), budget);
                if (bytes.length != chapter.sizeBytes) throw new IOException("Chapter size mismatch");
                String hash = Hashing.sha256(bytes);
                if (!Hashing.constantTimeEquals(hash, chapter.textHash)) {
                    throw new IOException("Chapter hash mismatch");
                }
                chapter.text = new String(bytes, StandardCharsets.UTF_8);
            } else {
                validateEntryMetadata(entry, Math.min(MAX_AUDIO_BYTES, audio.sizeBytes));
                File staged = new File(stagingRoot, audio.id + ".audio");
                assertDescendant(stagingRoot, staged);
                HashAndSize copied;
                try (OutputStream output = new FileOutputStream(staged)) {
                    copied = copyAndHash(zip, output, Math.min(MAX_AUDIO_BYTES, audio.sizeBytes), budget);
                }
                if (copied.size != audio.sizeBytes) throw new IOException("Audio size mismatch");
                if (!Hashing.constantTimeEquals(copied.hash, audio.contentHash)) {
                    throw new IOException("Audio hash mismatch");
                }
                audio.stagedFile = staged;
            }
            validateCompressionRatio(entry);
            zip.closeEntry();
        }
        if (seen.size() != chaptersByPath.size() + audioByPath.size()
                || !seen.containsAll(chaptersByPath.keySet()) || !seen.containsAll(audioByPath.keySet())) {
            throw new IOException("Book package is incomplete");
        }

        ArrayList<Chapter> chapters = new ArrayList<>();
        for (ChapterSpec spec : manifest.chapterSpecs) {
            if (spec.text == null) throw new IOException("Missing chapter text");
            chapters.add(new Chapter(spec.id, manifest.book.getId(), spec.title, spec.text,
                    spec.position, spec.createdAt, spec.updatedAt, spec.revision));
        }
        chapters.sort(Comparator.comparingInt(Chapter::getPosition));
        String actualSnapshotHash = snapshotHashFromSpecs(manifest.book, manifest.chapterSpecs,
                manifest.audioSpecs);
        if (!Hashing.constantTimeEquals(actualSnapshotHash, manifest.snapshotHash)) {
            throw new IOException("Snapshot hash mismatch");
        }
        return new ParsedPackage(manifest.book, chapters, manifest.audioSpecs);
    }

    private static JSONObject buildManifest(Book book, List<ChapterExport> chapters,
                                            List<AudioExport> audio) throws IOException {
        try {
            JSONObject root = new JSONObject();
            root.put("format", FORMAT_ID);
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("hashAlgorithm", "SHA-256");
            root.put("exportedAt", System.currentTimeMillis());

            JSONObject bookJson = new JSONObject();
            bookJson.put("uuid", book.getId());
            bookJson.put("revision", book.getRevision());
            bookJson.put("title", book.getTitle());
            bookJson.put("author", book.getAuthor());
            bookJson.put("createdAt", book.getCreatedAt());
            bookJson.put("updatedAt", book.getUpdatedAt());
            root.put("book", bookJson);

            JSONArray chapterArray = new JSONArray();
            for (ChapterExport item : chapters) {
                Chapter chapter = item.chapter;
                JSONObject json = new JSONObject();
                json.put("uuid", chapter.getId());
                json.put("revision", chapter.getRevision());
                json.put("title", chapter.getTitle());
                json.put("position", chapter.getPosition());
                json.put("createdAt", chapter.getCreatedAt());
                json.put("updatedAt", chapter.getUpdatedAt());
                json.put("relativePath", item.relativePath);
                json.put("hash", item.textHash);
                json.put("sizeBytes", item.textBytes.length);
                chapterArray.put(json);
            }
            root.put("chapters", chapterArray);

            JSONArray audioArray = new JSONArray();
            for (AudioExport item : audio) {
                AudioAsset asset = item.asset;
                JSONObject json = new JSONObject();
                json.put("uuid", asset.getId());
                if (asset.getChapterId() == null) json.put("chapterUuid", JSONObject.NULL);
                else json.put("chapterUuid", asset.getChapterId());
                json.put("displayName", asset.getDisplayName());
                json.put("mimeType", asset.getMimeType());
                json.put("durationMs", asset.getDurationMs());
                json.put("sizeBytes", item.size);
                json.put("sampleRate", asset.getSampleRate());
                json.put("channelCount", asset.getChannelCount());
                json.put("audioFormat", asset.getAudioFormat());
                json.put("createdAt", asset.getCreatedAt());
                json.put("relativePath", item.relativePath);
                json.put("hash", item.contentHash);
                audioArray.put(json);
            }
            root.put("audio", audioArray);
            root.put("contentHash", snapshotHash(book, chapters, audio));
            return root;
        } catch (JSONException error) {
            throw new IOException("Could not construct manifest", error);
        }
    }

    private static ManifestSpec parseManifest(JSONObject root) throws IOException, JSONException {
        if (!FORMAT_ID.equals(root.optString("format"))) throw new IOException("Unsupported book format");
        int version = root.getInt("schemaVersion");
        if (version != SCHEMA_VERSION) {
            throw new IOException("Unsupported schema version: " + version);
        }
        if (!"SHA-256".equalsIgnoreCase(root.optString("hashAlgorithm", "SHA-256"))) {
            throw new IOException("Unsupported hash algorithm");
        }

        JSONObject bookJson = root.getJSONObject("book");
        String bookId = requireUuid(bookJson.getString("uuid"), "book.uuid");
        long bookRevision = requireNonNegative(bookJson.getLong("revision"), "book.revision");
        String title = boundedString(bookJson.getString("title"), 1_000, "book.title");
        String author = boundedString(bookJson.optString("author", ""), 1_000, "book.author");
        long bookCreated = requireNonNegative(bookJson.optLong("createdAt", 0L), "book.createdAt");
        long bookUpdated = requireNonNegative(bookJson.optLong("updatedAt", bookCreated),
                "book.updatedAt");
        Book book = new Book(bookId, title, author, bookCreated, bookUpdated, bookRevision);

        JSONArray chapterArray = root.getJSONArray("chapters");
        if (chapterArray.length() > MAX_CHAPTERS) throw new IOException("Too many chapters");
        ArrayList<ChapterSpec> chapters = new ArrayList<>(chapterArray.length());
        Set<String> chapterIds = new HashSet<>();
        Set<Integer> positions = new HashSet<>();
        for (int index = 0; index < chapterArray.length(); index++) {
            JSONObject json = chapterArray.getJSONObject(index);
            String id = requireUuid(json.getString("uuid"), "chapter.uuid");
            if (!chapterIds.add(id)) throw new IOException("Duplicate chapter UUID");
            int position = json.getInt("position");
            if (position < 0 || !positions.add(position)) throw new IOException("Invalid chapter position");
            String path = validateRelativePath(json.getString("relativePath"));
            if (!path.startsWith("chapters/")) throw new IOException("Invalid chapter path");
            long size = requireRange(json.getLong("sizeBytes"), 0L, MAX_CHAPTER_BYTES,
                    "chapter.sizeBytes");
            chapters.add(new ChapterSpec(id,
                    requireNonNegative(json.getLong("revision"), "chapter.revision"),
                    boundedString(json.getString("title"), 1_000, "chapter.title"), position,
                    requireNonNegative(json.optLong("createdAt", 0L), "chapter.createdAt"),
                    requireNonNegative(json.optLong("updatedAt", 0L), "chapter.updatedAt"), path,
                    requireHash(json.getString("hash")), size));
        }
        for (int position = 0; position < chapters.size(); position++) {
            if (!positions.contains(position)) throw new IOException("Chapter positions must be contiguous");
        }

        JSONArray audioArray = root.optJSONArray("audio");
        if (audioArray == null) audioArray = new JSONArray();
        if (audioArray.length() > MAX_AUDIO_ASSETS) throw new IOException("Too many audio assets");
        ArrayList<AudioSpec> audio = new ArrayList<>(audioArray.length());
        Set<String> audioIds = new HashSet<>();
        long totalAudioSize = 0L;
        for (int index = 0; index < audioArray.length(); index++) {
            JSONObject json = audioArray.getJSONObject(index);
            String id = requireUuid(json.getString("uuid"), "audio.uuid");
            if (!audioIds.add(id)) throw new IOException("Duplicate audio UUID");
            String chapterId = json.isNull("chapterUuid") ? null
                    : requireUuid(json.getString("chapterUuid"), "audio.chapterUuid");
            if (chapterId != null && !chapterIds.contains(chapterId)) {
                throw new IOException("Audio refers to an unknown chapter");
            }
            long size = requireRange(json.getLong("sizeBytes"), 0L, MAX_AUDIO_BYTES,
                    "audio.sizeBytes");
            totalAudioSize = checkedAdd(totalAudioSize, size);
            if (totalAudioSize > MAX_TOTAL_UNCOMPRESSED_BYTES) throw new IOException("Audio is too large");
            String path = validateRelativePath(json.getString("relativePath"));
            if (!path.startsWith("audio/")) throw new IOException("Invalid audio path");
            audio.add(new AudioSpec(id, chapterId,
                    boundedString(json.optString("displayName", id + ".wav"), 1_000,
                            "audio.displayName"),
                    boundedString(json.optString("mimeType", "audio/wav"), 128,
                            "audio.mimeType"),
                    requireNonNegative(json.optLong("durationMs", 0L), "audio.durationMs"), size,
                    requireRangeInt(json.optInt("sampleRate", 0), 0, 768_000, "audio.sampleRate"),
                    requireRangeInt(json.optInt("channelCount", 0), 0, 32, "audio.channelCount"),
                    requireRangeInt(json.optInt("audioFormat", 0), 0, 1_000, "audio.audioFormat"),
                    requireNonNegative(json.optLong("createdAt", 0L), "audio.createdAt"), path,
                    requireHash(json.getString("hash"))));
        }
        if (chapters.size() + audio.size() + 1 > MAX_ENTRIES) throw new IOException("Too many entries");
        String snapshotHash = requireHash(root.getString("contentHash"));
        return new ManifestSpec(book, chapters, audio, snapshotHash);
    }

    private static String snapshotHash(Book book, List<ChapterExport> chapters,
                                       List<AudioExport> audio) {
        StringBuilder canonical = canonicalBook(book);
        for (ChapterExport item : chapters) {
            appendChapter(canonical, item.chapter.getId(), item.chapter.getRevision(),
                    item.chapter.getTitle(), item.chapter.getPosition(), item.chapter.getCreatedAt(),
                    item.chapter.getUpdatedAt(), item.relativePath, item.textHash,
                    item.textBytes.length);
        }
        for (AudioExport item : audio) appendAudio(canonical, item.asset, item.relativePath,
                item.contentHash, item.size);
        return Hashing.sha256(canonical.toString());
    }

    private static String snapshotHashFromSpecs(
            Book book, List<ChapterSpec> chapters, List<AudioSpec> audio) {
        StringBuilder canonical = canonicalBook(book);
        for (ChapterSpec item : chapters) {
            appendChapter(canonical, item.id, item.revision, item.title, item.position,
                    item.createdAt, item.updatedAt, item.relativePath, item.textHash, item.sizeBytes);
        }
        for (AudioSpec item : audio) {
            appendAudio(canonical, item.id, item.chapterId, item.displayName, item.mimeType,
                    item.durationMs, item.sizeBytes, item.sampleRate, item.channelCount,
                    item.audioFormat, item.createdAt, item.relativePath, item.contentHash);
        }
        return Hashing.sha256(canonical.toString());
    }

    private static StringBuilder canonicalBook(Book book) {
        StringBuilder value = new StringBuilder("bwlbook:").append(SCHEMA_VERSION).append(';');
        appendCanonical(value, book.getId());
        appendCanonical(value, book.getRevision());
        appendCanonical(value, book.getTitle());
        appendCanonical(value, book.getAuthor());
        appendCanonical(value, book.getCreatedAt());
        appendCanonical(value, book.getUpdatedAt());
        return value;
    }

    private static void appendChapter(StringBuilder value, String id, long revision, String title,
                                      int position, long createdAt, long updatedAt, String path,
                                      String hash, long size) {
        appendCanonical(value, "chapter");
        appendCanonical(value, id);
        appendCanonical(value, revision);
        appendCanonical(value, title);
        appendCanonical(value, position);
        appendCanonical(value, createdAt);
        appendCanonical(value, updatedAt);
        appendCanonical(value, path);
        appendCanonical(value, hash);
        appendCanonical(value, size);
    }

    private static void appendAudio(StringBuilder value, AudioAsset asset, String path,
                                    String hash, long size) {
        appendAudio(value, asset.getId(), asset.getChapterId(), asset.getDisplayName(),
                asset.getMimeType(), asset.getDurationMs(), size, asset.getSampleRate(),
                asset.getChannelCount(), asset.getAudioFormat(), asset.getCreatedAt(), path, hash);
    }

    private static void appendAudio(StringBuilder value, String id, String chapterId,
                                    String displayName, String mimeType, long durationMs, long size,
                                    int sampleRate, int channelCount, int audioFormat, long createdAt,
                                    String path, String hash) {
        appendCanonical(value, "audio");
        appendCanonical(value, id);
        appendCanonical(value, chapterId == null ? "" : chapterId);
        appendCanonical(value, displayName);
        appendCanonical(value, mimeType);
        appendCanonical(value, durationMs);
        appendCanonical(value, size);
        appendCanonical(value, sampleRate);
        appendCanonical(value, channelCount);
        appendCanonical(value, audioFormat);
        appendCanonical(value, createdAt);
        appendCanonical(value, path);
        appendCanonical(value, hash);
    }

    private static void appendCanonical(StringBuilder target, Object raw) {
        String value = String.valueOf(raw);
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static void putBytes(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void copy(InputStream input, OutputStream output, long expectedSize)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) continue;
            total = checkedAdd(total, read);
            if (total > expectedSize) throw new IOException("Source changed while exporting");
            output.write(buffer, 0, read);
        }
        if (total != expectedSize) throw new IOException("Source changed while exporting");
    }

    private static HashAndSize hashAndCount(InputStream input, long maximum) throws IOException {
        MessageDigest digest = Hashing.newSha256Digest();
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) continue;
            total = checkedAdd(total, read);
            if (total > maximum) throw new IOException("Entry exceeds size limit");
            digest.update(buffer, 0, read);
        }
        return new HashAndSize(Hashing.toHex(digest.digest()), total);
    }

    private static HashAndSize copyAndHash(ZipInputStream input, OutputStream output, long maximum,
                                           Budget budget) throws IOException {
        MessageDigest digest = Hashing.newSha256Digest();
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) continue;
            total = checkedAdd(total, read);
            if (total > maximum) throw new IOException("Entry exceeds declared/allowed size");
            budget.add(read);
            digest.update(buffer, 0, read);
            output.write(buffer, 0, read);
        }
        output.flush();
        if (output instanceof FileOutputStream) ((FileOutputStream) output).getFD().sync();
        return new HashAndSize(Hashing.toHex(digest.digest()), total);
    }

    private static byte[] readBounded(ZipInputStream input, long maximum, Budget budget)
            throws IOException {
        if (maximum > Integer.MAX_VALUE) throw new IOException("In-memory entry is too large");
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maximum, 64 * 1024));
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) continue;
            total = checkedAdd(total, read);
            if (total > maximum) throw new IOException("Entry exceeds declared/allowed size");
            budget.add(read);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void validateEntryMetadata(ZipEntry entry, long maximum) throws IOException {
        validateRelativePath(entry.getName());
        long declared = entry.getSize();
        if (declared > maximum) throw new IOException("ZIP entry exceeds size limit");
        if (declared < -1L) throw new IOException("Invalid ZIP entry size");
        validateCompressionRatio(entry);
    }

    private static void validateCompressionRatio(ZipEntry entry) throws IOException {
        long expanded = entry.getSize();
        long compressed = entry.getCompressedSize();
        if (expanded > 0L && compressed == 0L) throw new IOException("Invalid compressed entry");
        if (expanded > 0L && compressed > 0L && expanded / compressed > MAX_COMPRESSION_RATIO) {
            throw new IOException("Suspicious ZIP compression ratio");
        }
    }

    private static String validateRelativePath(String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 240 || value.startsWith("/")
                || value.startsWith("\\") || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0
                || value.indexOf('\u0000') >= 0 || new File(value).isAbsolute()) {
            throw new IOException("Unsafe ZIP path");
        }
        String[] pieces = value.split("/", -1);
        for (String piece : pieces) {
            if (piece.isEmpty() || ".".equals(piece) || "..".equals(piece)) {
                throw new IOException("Unsafe ZIP path segment");
            }
        }
        return value;
    }

    private static String requireUuid(String value, String field) throws IOException {
        try {
            UUID.fromString(value);
            return value;
        } catch (RuntimeException error) {
            throw new IOException("Invalid UUID in " + field, error);
        }
    }

    private static String requireHash(String value) throws IOException {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("Invalid SHA-256 hash");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String boundedString(String value, int maximum, String field) throws IOException {
        if (value == null || value.length() > maximum) throw new IOException("Invalid " + field);
        return value;
    }

    private static long requireNonNegative(long value, String field) throws IOException {
        return requireRange(value, 0L, Long.MAX_VALUE, field);
    }

    private static long requireRange(long value, long minimum, long maximum, String field)
            throws IOException {
        if (value < minimum || value > maximum) throw new IOException("Invalid " + field);
        return value;
    }

    private static int requireRangeInt(int value, int minimum, int maximum, String field)
            throws IOException {
        if (value < minimum || value > maximum) throw new IOException("Invalid " + field);
        return value;
    }

    private static long checkedAdd(long left, long right) throws IOException {
        if (right < 0L || left > Long.MAX_VALUE - right) throw new IOException("Size overflow");
        return left + right;
    }

    private static String safeAudioExtension(String fileName) {
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
                if (extension.matches("\\.(wav|wave|mp3|m4a|aac|ogg|flac)")) return extension;
            }
        }
        return ".wav";
    }

    private static void moveFile(File source, File destination) throws IOException {
        assertDescendant(destination.getParentFile(), destination);
        if (source.renameTo(destination)) return;
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 0) output.write(buffer, 0, read);
            }
            output.flush();
            output.getFD().sync();
        } catch (IOException error) {
            destination.delete();
            throw error;
        }
        if (!source.delete()) {
            destination.delete();
            throw new IOException("Could not remove staging file");
        }
    }

    private static void assertDescendant(File root, File target) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File canonicalTarget = target.getCanonicalFile();
        String prefix = canonicalRoot.getPath() + File.separator;
        if (!canonicalTarget.getPath().startsWith(prefix)) throw new IOException("Unsafe target path");
    }

    private static void deleteTree(File root, File target) {
        try {
            File canonicalRoot = root.getCanonicalFile();
            File canonicalTarget = target.getCanonicalFile();
            if (!canonicalTarget.equals(canonicalRoot)
                    && !canonicalTarget.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
                return;
            }
            File[] children = canonicalTarget.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(canonicalRoot, child);
            }
            canonicalTarget.delete();
        } catch (IOException ignored) {
            // Generated cache paths only; a failed cleanup is safe and recoverable by OS cache trim.
        }
    }

    private static final class Budget {
        long expandedBytes;

        void add(long amount) throws IOException {
            expandedBytes = checkedAdd(expandedBytes, amount);
            if (expandedBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw new IOException("ZIP expands beyond the package limit");
            }
        }
    }

    private static final class HashAndSize {
        final String hash;
        final long size;

        HashAndSize(String hash, long size) {
            this.hash = hash;
            this.size = size;
        }
    }

    private static final class ChapterExport {
        final Chapter chapter;
        final String relativePath;
        final byte[] textBytes;
        final String textHash;

        ChapterExport(Chapter chapter, String relativePath, byte[] textBytes, String textHash) {
            this.chapter = chapter;
            this.relativePath = relativePath;
            this.textBytes = textBytes;
            this.textHash = textHash;
        }
    }

    private static final class AudioExport {
        final AudioAsset asset;
        final String relativePath;
        final String contentHash;
        final long size;

        AudioExport(AudioAsset asset, String relativePath, String contentHash, long size) {
            this.asset = asset;
            this.relativePath = relativePath;
            this.contentHash = contentHash;
            this.size = size;
        }
    }

    private static final class ManifestSpec {
        final Book book;
        final List<ChapterSpec> chapterSpecs;
        final List<AudioSpec> audioSpecs;
        final String snapshotHash;

        ManifestSpec(Book book, List<ChapterSpec> chapterSpecs, List<AudioSpec> audioSpecs,
                     String snapshotHash) {
            this.book = book;
            this.chapterSpecs = chapterSpecs;
            this.audioSpecs = audioSpecs;
            this.snapshotHash = snapshotHash;
        }
    }

    private static final class ChapterSpec {
        final String id;
        final long revision;
        final String title;
        final int position;
        final long createdAt;
        final long updatedAt;
        final String relativePath;
        final String textHash;
        final long sizeBytes;
        String text;

        ChapterSpec(String id, long revision, String title, int position, long createdAt,
                    long updatedAt, String relativePath, String textHash, long sizeBytes) {
            this.id = id;
            this.revision = revision;
            this.title = title;
            this.position = position;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.relativePath = relativePath;
            this.textHash = textHash;
            this.sizeBytes = sizeBytes;
        }
    }

    private static final class AudioSpec {
        final String id;
        final String chapterId;
        final String displayName;
        final String mimeType;
        final long durationMs;
        final long sizeBytes;
        final int sampleRate;
        final int channelCount;
        final int audioFormat;
        final long createdAt;
        final String relativePath;
        final String contentHash;
        File stagedFile;

        AudioSpec(String id, String chapterId, String displayName, String mimeType, long durationMs,
                  long sizeBytes, int sampleRate, int channelCount, int audioFormat, long createdAt,
                  String relativePath, String contentHash) {
            this.id = id;
            this.chapterId = chapterId;
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.durationMs = durationMs;
            this.sizeBytes = sizeBytes;
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.audioFormat = audioFormat;
            this.createdAt = createdAt;
            this.relativePath = relativePath;
            this.contentHash = contentHash;
        }
    }

    private static final class ParsedPackage {
        final Book book;
        final List<Chapter> chapters;
        final List<AudioSpec> audioSpecs;

        ParsedPackage(Book book, List<Chapter> chapters, List<AudioSpec> audioSpecs) {
            this.book = book;
            this.chapters = chapters;
            this.audioSpecs = audioSpecs;
        }
    }
}
