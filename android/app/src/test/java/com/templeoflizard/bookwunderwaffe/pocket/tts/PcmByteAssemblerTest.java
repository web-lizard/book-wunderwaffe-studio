package com.templeoflizard.bookwunderwaffe.pocket.tts;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Test;

public final class PcmByteAssemblerTest {
    @Test
    public void joinsCallbacksIntoWholeFrames() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PcmByteAssembler assembler = new PcmByteAssembler(4, output::write);

        assembler.append(new byte[] {1, 2, 3});
        assertEquals(3, assembler.getPendingByteCount());
        assembler.append(new byte[] {4, 5, 6, 7, 8});
        assembler.requireFrameBoundary();

        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, output.toByteArray());
    }

    @Test
    public void rejectsCheckpointInsideFrame() throws Exception {
        PcmByteAssembler assembler = new PcmByteAssembler(2, (data, offset, length) -> { });
        assembler.append(new byte[] {1});
        try {
            assembler.requireFrameBoundary();
            fail("Expected IOException");
        } catch (IOException expected) {
            assertEquals(1, assembler.getPendingByteCount());
        }
    }
}
