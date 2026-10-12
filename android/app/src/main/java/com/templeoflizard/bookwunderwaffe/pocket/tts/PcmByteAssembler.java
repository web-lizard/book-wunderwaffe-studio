package com.templeoflizard.bookwunderwaffe.pocket.tts;

import java.io.IOException;
import java.util.Objects;

/**
 * Joins arbitrarily-sized TTS callback buffers into complete interleaved PCM frames.
 * This class is Android-free and intentionally small enough for plain JVM tests.
 */
public final class PcmByteAssembler {
    public interface Sink {
        void write(byte[] data, int offset, int length) throws IOException;
    }

    private final int frameSize;
    private final Sink sink;
    private final byte[] pendingFrame;
    private int pendingCount;

    public PcmByteAssembler(int frameSize, Sink sink) {
        if (frameSize <= 0) {
            throw new IllegalArgumentException("frameSize must be positive");
        }
        this.frameSize = frameSize;
        this.sink = Objects.requireNonNull(sink, "sink");
        this.pendingFrame = new byte[frameSize];
    }

    public void append(byte[] data) throws IOException {
        Objects.requireNonNull(data, "data");
        append(data, 0, data.length);
    }

    public void append(byte[] data, int offset, int length) throws IOException {
        Objects.requireNonNull(data, "data");
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException("Invalid offset/length");
        }
        if (length == 0) {
            return;
        }

        int cursor = offset;
        int remaining = length;
        if (pendingCount > 0) {
            int copy = Math.min(frameSize - pendingCount, remaining);
            System.arraycopy(data, cursor, pendingFrame, pendingCount, copy);
            pendingCount += copy;
            cursor += copy;
            remaining -= copy;
            if (pendingCount == frameSize) {
                sink.write(pendingFrame, 0, frameSize);
                pendingCount = 0;
            }
        }

        int completeBytes = remaining - (remaining % frameSize);
        if (completeBytes > 0) {
            sink.write(data, cursor, completeBytes);
            cursor += completeBytes;
            remaining -= completeBytes;
        }
        if (remaining > 0) {
            System.arraycopy(data, cursor, pendingFrame, 0, remaining);
            pendingCount = remaining;
        }
    }

    public int getPendingByteCount() {
        return pendingCount;
    }

    /** A checkpoint is safe only between complete PCM frames. */
    public void requireFrameBoundary() throws IOException {
        if (pendingCount != 0) {
            throw new IOException("TTS returned an incomplete PCM frame (" + pendingCount
                    + " of " + frameSize + " bytes)");
        }
    }

    public void discardPending() {
        pendingCount = 0;
    }
}
