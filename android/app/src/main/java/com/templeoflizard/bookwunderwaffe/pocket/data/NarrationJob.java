package com.templeoflizard.bookwunderwaffe.pocket.data;

/**
 * Immutable persisted narration request. Text and voice settings are snapshots: editing the
 * source chapter while a job is running cannot change the generated audio.
 */
public final class NarrationJob {
    public static final String SOURCE_BOOK = "BOOK";
    public static final String SOURCE_CHAPTER = "CHAPTER";

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_CANCEL_REQUESTED = "CANCEL_REQUESTED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String STAGE_QUEUED = "QUEUED";
    public static final String STAGE_PREPARING = "PREPARING";
    public static final String STAGE_SYNTHESIZING = "SYNTHESIZING";
    public static final String STAGE_FINALIZING = "FINALIZING";
    public static final String STAGE_DONE = "DONE";
    public static final String STAGE_ERROR = "ERROR";
    public static final String STAGE_CANCELLED = "CANCELLED";

    private final String id;
    private final String bookId;
    private final String chapterId;
    private final String sourceType;
    private final String displayTitle;
    private final String textSnapshot;
    private final String textHash;
    private final String settingsJson;
    private final String languageTag;
    private final String voiceName;
    private final float speechRate;
    private final float pitch;
    private final String stage;
    private final String status;
    private final int progressPercent;
    private final int resumeChunkIndex;
    private final int totalChunks;
    private final long committedPcmBytes;
    private final int sampleRate;
    private final int channelCount;
    private final int audioFormat;
    private final String outputPath;
    private final String partPath;
    private final String error;
    private final long createdAt;
    private final long updatedAt;
    private final long startedAt;
    private final long completedAt;

    public NarrationJob(
            String id, String bookId, String chapterId, String sourceType, String displayTitle,
            String textSnapshot, String textHash, String settingsJson, String languageTag,
            String voiceName, float speechRate, float pitch, String stage, String status,
            int progressPercent, int resumeChunkIndex, int totalChunks, long committedPcmBytes,
            int sampleRate, int channelCount, int audioFormat, String outputPath, String partPath,
            String error, long createdAt, long updatedAt, long startedAt, long completedAt) {
        this.id = id;
        this.bookId = bookId;
        this.chapterId = emptyToNull(chapterId);
        this.sourceType = sourceType;
        this.displayTitle = isBlank(displayTitle) ? "Озвучивание книги" : displayTitle;
        this.textSnapshot = textSnapshot == null ? "" : textSnapshot;
        this.textHash = textHash;
        this.settingsJson = settingsJson == null ? "{}" : settingsJson;
        this.languageTag = isBlank(languageTag) ? "ru-RU" : languageTag;
        this.voiceName = emptyToNull(voiceName);
        this.speechRate = speechRate;
        this.pitch = pitch;
        this.stage = stage == null ? STAGE_QUEUED : stage;
        this.status = status == null ? STATUS_QUEUED : status;
        this.progressPercent = Math.max(0, Math.min(100, progressPercent));
        this.resumeChunkIndex = Math.max(0, resumeChunkIndex);
        this.totalChunks = Math.max(0, totalChunks);
        this.committedPcmBytes = Math.max(0L, committedPcmBytes);
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        this.audioFormat = audioFormat;
        this.outputPath = outputPath;
        this.partPath = partPath;
        this.error = emptyToNull(error);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public String getId() { return id; }
    public String getBookId() { return bookId; }
    public String getChapterId() { return chapterId; }
    public String getSourceType() { return sourceType; }
    public String getDisplayTitle() { return displayTitle; }
    public String getTextSnapshot() { return textSnapshot; }
    /** Compatibility alias for worker implementations. */
    public String getSnapshotText() { return textSnapshot; }
    public String getTextHash() { return textHash; }
    public String getSettingsJson() { return settingsJson; }
    public String getLanguageTag() { return languageTag; }
    public String getVoiceName() { return voiceName; }
    public float getSpeechRate() { return speechRate; }
    public float getPitch() { return pitch; }
    public String getStage() { return stage; }
    public String getStatus() { return status; }
    public int getProgressPercent() { return progressPercent; }
    public int getPercent() { return progressPercent; }
    public int getResumeChunkIndex() { return resumeChunkIndex; }
    public int getTotalChunks() { return totalChunks; }
    public long getCommittedPcmBytes() { return committedPcmBytes; }
    public int getSampleRate() { return sampleRate; }
    public int getChannelCount() { return channelCount; }
    public int getAudioFormat() { return audioFormat; }
    public String getOutputPath() { return outputPath; }
    public String getPartPath() { return partPath; }
    public String getError() { return error; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public long getStartedAt() { return startedAt; }
    public long getCompletedAt() { return completedAt; }

    public NarrationProgress getProgress() {
        return new NarrationProgress(stage, status, progressPercent, resumeChunkIndex,
                totalChunks, committedPcmBytes, updatedAt);
    }

    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status)
                || STATUS_CANCELLED.equals(status);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String emptyToNull(String value) {
        return isBlank(value) ? null : value;
    }
}
