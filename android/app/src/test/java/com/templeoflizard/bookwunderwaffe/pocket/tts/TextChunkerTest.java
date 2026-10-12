package com.templeoflizard.bookwunderwaffe.pocket.tts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class TextChunkerTest {
    @Test
    public void prefersParagraphAndSentenceBoundaries() {
        String text = "Первое короткое предложение. Второе предложение подлиннее.\n\n"
                + "Новый абзац начинается здесь и продолжается ещё немного.";
        List<TextChunker.Chunk> chunks = new TextChunker(48).split(text);

        assertTrue(chunks.size() >= 2);
        for (TextChunker.Chunk chunk : chunks) {
            assertFalse(chunk.getText().isEmpty());
            assertTrue(chunk.length() <= 48);
        }
        assertTrue(chunks.get(0).getText().endsWith("."));
    }

    @Test
    public void neverCutsUnicodeSurrogatePair() {
        String text = "а".repeat(31) + "🙂" + "б".repeat(40);
        List<TextChunker.Chunk> chunks = new TextChunker(32).split(text);

        for (TextChunker.Chunk chunk : chunks) {
            String value = chunk.getText();
            assertFalse(Character.isHighSurrogate(value.charAt(value.length() - 1)));
            assertFalse(Character.isLowSurrogate(value.charAt(0)));
        }
        assertEquals(1, chunks.stream().map(TextChunker.Chunk::getText)
                .mapToInt(value -> value.contains("🙂") ? 1 : 0).sum());
    }

    @Test
    public void producesSameBoundariesEveryTime() {
        String text = ("Глава. Фраза с несколькими словами!\n\n").repeat(20);
        TextChunker chunker = new TextChunker(64);
        assertEquals(chunker.splitToStrings(text), chunker.splitToStrings(text));
    }
}
