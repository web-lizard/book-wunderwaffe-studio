package com.templeoflizard.bookwunderwaffe.pocket.data;

/** One line from a job's persistent bounded technical log. */
public final class NarrationLogEntry {
    private final long sequence;
    private final String jobId;
    private final long createdAt;
    private final String level;
    private final String message;

    public NarrationLogEntry(long sequence, String jobId, long createdAt, String level, String message) {
        this.sequence = sequence;
        this.jobId = jobId;
        this.createdAt = createdAt;
        this.level = level == null ? "INFO" : level;
        this.message = message == null ? "" : message;
    }

    public long getSequence() { return sequence; }
    public String getJobId() { return jobId; }
    public long getCreatedAt() { return createdAt; }
    public String getLevel() { return level; }
    public String getMessage() { return message; }
}
