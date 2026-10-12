package com.templeoflizard.bookwunderwaffe.pocket.data;

/** Small value object suitable for progress UI rendering. */
public final class NarrationProgress {
    private final String stage;
    private final String status;
    private final int percent;
    private final int resumeChunkIndex;
    private final int totalChunks;
    private final long committedPcmBytes;
    private final long updatedAt;

    public NarrationProgress(String stage, String status, int percent, int resumeChunkIndex,
                             int totalChunks, long committedPcmBytes, long updatedAt) {
        this.stage = stage == null ? NarrationJob.STAGE_QUEUED : stage;
        this.status = status == null ? NarrationJob.STATUS_QUEUED : status;
        this.percent = Math.max(0, Math.min(100, percent));
        this.resumeChunkIndex = Math.max(0, resumeChunkIndex);
        this.totalChunks = Math.max(0, totalChunks);
        this.committedPcmBytes = Math.max(0L, committedPcmBytes);
        this.updatedAt = updatedAt;
    }

    public String getStage() { return stage; }
    public String getStatus() { return status; }
    public int getPercent() { return percent; }
    public int getResumeChunkIndex() { return resumeChunkIndex; }
    public int getTotalChunks() { return totalChunks; }
    public long getCommittedPcmBytes() { return committedPcmBytes; }
    public long getUpdatedAt() { return updatedAt; }
}
