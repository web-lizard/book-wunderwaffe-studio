package com.templeoflizard.bookwunderwaffe.pocket.tts;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class StreamingWavWriterTest {
    @Test
    public void writesCanonicalLittleEndianWav() throws Exception {
        Path path = Files.createTempFile("streaming-wav", ".wav");
        try {
            try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
                StreamingWavWriter writer = StreamingWavWriter.open(file.getChannel(),
                        PcmAudioFormat.integerPcm(22_050, 1, 16), 0);
                writer.appendPcm(new byte[] {1, 2, 3, 4, 5, 6});
                assertEquals(6, writer.checkpoint());
                writer.close();
            }

            byte[] bytes = Files.readAllBytes(path);
            assertEquals("RIFF", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
            assertEquals("WAVE", new String(bytes, 8, 4, StandardCharsets.US_ASCII));
            assertEquals("data", new String(bytes, 36, 4, StandardCharsets.US_ASCII));
            ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(42, header.getInt(4));
            assertEquals(6, header.getInt(40));
            assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6},
                    java.util.Arrays.copyOfRange(bytes, 44, 50));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void resumeTruncatesUncommittedTail() throws Exception {
        Path path = Files.createTempFile("resumable-wav", ".wav");
        PcmAudioFormat format = PcmAudioFormat.integerPcm(16_000, 1, 16);
        try {
            try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
                StreamingWavWriter writer = StreamingWavWriter.open(file.getChannel(), format, 0);
                writer.appendPcm(new byte[] {1, 2, 3, 4});
                writer.checkpoint();
                writer.appendPcm(new byte[] {99, 99, 99, 99});
                writer.close();
            }

            try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
                StreamingWavWriter resumed = StreamingWavWriter.open(file.getChannel(), format, 4);
                assertEquals(48, file.length());
                resumed.appendPcm(new byte[] {5, 6, 7, 8});
                assertEquals(8, resumed.checkpoint());
                resumed.close();
            }

            byte[] bytes = Files.readAllBytes(path);
            assertEquals(52, bytes.length);
            assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8},
                    java.util.Arrays.copyOfRange(bytes, 44, 52));
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
