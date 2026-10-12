package com.templeoflizard.bookwunderwaffe.pocket.data;

import java.io.File;
import java.util.Objects;

/** A playable audio file associated with a book and, optionally, one chapter. */
public final class AudioAsset {
    private final String id;
    private final String bookId;
    private final String chapterId;
    private final String narrationJobId;
    private final String displayName;
    private final String mimeType;
    private final String localPath;
    private final String documentUri;
    private final long durationMs;
    private final long sizeBytes;
    private final int sampleRate;
    private final int channelCount;
    private final int audioFormat;
    private final String contentHash;
    private final long createdAt;

    public AudioAsset(String id, String bookId, String chapterId, String narrationJobId,
                      String displayName, String mimeType, String localPath, String documentUri,
                      long durationMs, long sizeBytes, int sampleRate, int channelCount,
                      int audioFormat, String contentHash, long createdAt) {
        this.id = require(id, "id");
        this.bookId = require(bookId, "bookId");
        this.chapterId = emptyToNull(chapterId);
        this.narrationJobId = emptyToNull(narrationJobId);
        this.displayName = displayName == null ? "audio.wav" : displayName;
        this.mimeType = mimeType == null ? "audio/wav" : mimeType;
        this.localPath = emptyToNull(localPath);
        this.documentUri = emptyToNull(documentUri);
        this.durationMs = Math.max(0L, durationMs);
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.sampleRate = Math.max(0, sampleRate);
        this.channelCount = Math.max(0, channelCount);
        this.audioFormat = audioFormat;
        this.contentHash = emptyToNull(contentHash);
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getBookId() { return bookId; }
    public String getChapterId() { return chapterId; }
    public String getNarrationJobId() { return narrationJobId; }
    public String getDisplayName() { return displayName; }
    public String getMimeType() { return mimeType; }
    public String getLocalPath() { return localPath; }
    /** Alias useful to player code. May be null for a SAF-only asset. */
    public String getPath() { return localPath; }
    public File getFile() { return localPath == null ? null : new File(localPath); }
    public String getSuggestedFileName() { return displayName; }
    public String getDocumentUri() { return documentUri; }
    public long getDurationMs() { return durationMs; }
    public long getSizeBytes() { return sizeBytes; }
    public int getSampleRate() { return sampleRate; }
    public int getChannelCount() { return channelCount; }
    public int getAudioFormat() { return audioFormat; }
    public String getContentHash() { return contentHash; }
    public long getCreatedAt() { return createdAt; }

    private static String require(String value, String name) {
        String result = Objects.requireNonNull(value, name).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(name + " is empty");
        return result;
    }

    private static String emptyToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value;
    }
}
