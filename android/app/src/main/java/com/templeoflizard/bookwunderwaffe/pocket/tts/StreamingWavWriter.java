package com.templeoflizard.bookwunderwaffe.pocket.tts;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Streams PCM into one seekable WAV file and exposes fsync-backed chunk checkpoints.
 *
 * <p>The database checkpoint must be written only after {@link #checkpoint()} returns. On resume,
 * bytes beyond the database's committed count are truncated, making a process death in the middle
 * of an utterance safe and deterministic.</p>
 */
public final class StreamingWavWriter implements AutoCloseable {
    public static final int HEADER_SIZE = 44;
    public static final long MAX_PCM_BYTES = 0xffff_ffffL - 36L;

    private final SeekableByteChannel channel;
    private final PcmAudioFormat format;
    private final PcmByteAssembler assembler;
    private long pcmBytes;
    private boolean closed;

    public static StreamingWavWriter open(SeekableByteChannel channel, PcmAudioFormat format,
            long committedPcmBytes) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(format, "format");
        validateCommittedBytes(format, committedPcmBytes);

        if (committedPcmBytes == 0) {
            channel.truncate(0);
            writeHeader(channel, format, 0);
            force(channel);
        } else {
            if (channel.size() < HEADER_SIZE + committedPcmBytes) {
                throw new EOFException("Partial WAV is shorter than its committed checkpoint");
            }
            PcmAudioFormat existing = readFormat(channel);
            if (!format.equals(existing)) {
                throw new IOException("TTS audio format changed across resume: " + existing
                        + " -> " + format);
            }
            channel.truncate(HEADER_SIZE + committedPcmBytes);
            writeHeader(channel, format, committedPcmBytes);
            force(channel);
        }
        channel.position(HEADER_SIZE + committedPcmBytes);
        return new StreamingWavWriter(channel, format, committedPcmBytes);
    }

    /** Opens a fully-described existing partial WAV when all chunks were already checkpointed. */
    public static StreamingWavWriter openExisting(SeekableByteChannel channel,
            long committedPcmBytes) throws IOException {
        Objects.requireNonNull(channel, "channel");
        PcmAudioFormat format = readFormat(channel);
        return open(channel, format, committedPcmBytes);
    }

    public static PcmAudioFormat inspectFormat(SeekableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        return readFormat(channel);
    }

    private StreamingWavWriter(SeekableByteChannel channel, PcmAudioFormat format, long pcmBytes) {
        this.channel = channel;
        this.format = format;
        this.pcmBytes = pcmBytes;
        this.assembler = new PcmByteAssembler(format.getFrameSizeBytes(), this::writeDirect);
    }

    public synchronized void appendPcm(byte[] audio) throws IOException {
        ensureOpen();
        assembler.append(audio);
    }

    public synchronized void appendPcm(byte[] audio, int offset, int length) throws IOException {
        ensureOpen();
        assembler.append(audio, offset, length);
    }

    /** Verifies the utterance ended on a complete interleaved sample frame. */
    public synchronized void finishUtterance() throws IOException {
        ensureOpen();
        assembler.requireFrameBoundary();
    }

    /** Rewrites the WAV sizes and forces both audio and header to stable storage. */
    public synchronized long checkpoint() throws IOException {
        ensureOpen();
        assembler.requireFrameBoundary();
        long endPosition = HEADER_SIZE + pcmBytes;
        writeHeader(channel, format, pcmBytes);
        channel.position(endPosition);
        force(channel);
        return pcmBytes;
    }

    public synchronized long getPcmBytesWritten() {
        return pcmBytes;
    }

    public PcmAudioFormat getFormat() {
        return format;
    }

    private void writeDirect(byte[] data, int offset, int length) throws IOException {
        if (pcmBytes + length > MAX_PCM_BYTES) {
            throw new IOException("Classic WAV 4 GiB size limit exceeded");
        }
        channel.position(HEADER_SIZE + pcmBytes);
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
        writeFully(channel, buffer);
        pcmBytes += length;
    }

    /**
     * Closes storage without turning uncheckpointed callback bytes into a committed chunk. The next
     * process invocation will truncate them using the repository's last committed byte count.
     */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        assembler.discardPending();
        channel.close();
    }

    private void ensureOpen() throws IOException {
        if (closed || !channel.isOpen()) {
            throw new IOException("WAV writer is closed");
        }
    }

    private static void validateCommittedBytes(PcmAudioFormat format, long committedPcmBytes) {
        if (committedPcmBytes < 0 || committedPcmBytes > MAX_PCM_BYTES) {
            throw new IllegalArgumentException("Invalid committed PCM byte count");
        }
        if (committedPcmBytes % format.getFrameSizeBytes() != 0) {
            throw new IllegalArgumentException("Committed PCM bytes do not end on a frame boundary");
        }
    }

    private static void writeHeader(SeekableByteChannel channel, PcmAudioFormat format,
            long pcmBytes) throws IOException {
        validateCommittedBytes(format, pcmBytes);
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt((int) (36L + pcmBytes));
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) format.getWavFormatCode());
        header.putShort((short) format.getChannelCount());
        header.putInt(format.getSampleRateHz());
        header.putInt((int) format.getByteRate());
        header.putShort((short) format.getFrameSizeBytes());
        header.putShort((short) format.getBitsPerSample());
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt((int) pcmBytes);
        header.flip();
        channel.position(0);
        writeFully(channel, header);
    }

    private static PcmAudioFormat readFormat(SeekableByteChannel channel) throws IOException {
        if (channel.size() < HEADER_SIZE) {
            throw new EOFException("Partial WAV has no complete header");
        }
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(0);
        readFully(channel, header);
        header.flip();

        requireAscii(header, "RIFF");
        header.getInt();
        requireAscii(header, "WAVE");
        requireAscii(header, "fmt ");
        int formatSize = header.getInt();
        if (formatSize != 16) {
            throw new IOException("Only canonical 44-byte PCM WAV headers are supported");
        }
        int wavCode = Short.toUnsignedInt(header.getShort());
        int channels = Short.toUnsignedInt(header.getShort());
        int sampleRate = header.getInt();
        long byteRate = Integer.toUnsignedLong(header.getInt());
        int blockAlign = Short.toUnsignedInt(header.getShort());
        int bits = Short.toUnsignedInt(header.getShort());
        requireAscii(header, "data");
        header.getInt();

        PcmAudioFormat format = new PcmAudioFormat(sampleRate, channels, bits, wavCode);
        if (blockAlign != format.getFrameSizeBytes() || byteRate != format.getByteRate()) {
            throw new IOException("Corrupt WAV PCM alignment metadata");
        }
        return format;
    }

    private static void requireAscii(ByteBuffer buffer, String expected) throws IOException {
        byte[] bytes = new byte[expected.length()];
        buffer.get(bytes);
        String actual = new String(bytes, StandardCharsets.US_ASCII);
        if (!expected.equals(actual)) {
            throw new IOException("Invalid WAV marker: expected " + expected + ", got " + actual);
        }
    }

    private static void readFully(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                throw new EOFException("Unexpected end of WAV header");
            }
            if (read == 0) {
                Thread.yield();
            }
        }
    }

    private static void writeFully(SeekableByteChannel channel, ByteBuffer buffer)
            throws IOException {
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            if (written == 0) {
                Thread.yield();
            }
        }
    }

    private static void force(SeekableByteChannel channel) throws IOException {
        if (channel instanceof FileChannel) {
            ((FileChannel) channel).force(true);
        }
    }
}
