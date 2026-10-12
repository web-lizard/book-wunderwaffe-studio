package com.templeoflizard.bookwunderwaffe.pocket.tts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministically splits a text snapshot into TTS-sized utterances.
 *
 * <p>The algorithm deliberately has no Android dependencies, so the exact same chunk boundaries can
 * be reconstructed after the foreground service is killed and redelivered. Boundaries are preferred
 * in this order: paragraph, line, sentence, clause, whitespace, and finally a hard UTF-16 limit.</p>
 */
public final class TextChunker {
    public static final int DEFAULT_MAX_CHARS = 3_000;

    private final int maxChars;
    private final int preferredBoundaryWindow;

    public TextChunker() {
        this(DEFAULT_MAX_CHARS);
    }

    public TextChunker(int maxChars) {
        if (maxChars < 32) {
            throw new IllegalArgumentException("maxChars must be at least 32");
        }
        this.maxChars = maxChars;
        this.preferredBoundaryWindow = Math.max(16, maxChars / 2);
    }

    public int getMaxChars() {
        return maxChars;
    }

    public List<Chunk> split(String source) {
        if (source == null || source.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<Chunk> chunks = new ArrayList<>();
        int cursor = 0;
        while (cursor < source.length()) {
            cursor = skipWhitespace(source, cursor);
            if (cursor >= source.length()) {
                break;
            }

            int hardEnd = Math.min(source.length(), cursor + maxChars);
            hardEnd = avoidSplittingSurrogatePair(source, hardEnd);
            int end = hardEnd;
            if (hardEnd < source.length()) {
                end = chooseBoundary(source, cursor, hardEnd);
            }
            if (end <= cursor) {
                end = hardEnd;
            }

            int contentEnd = trimTrailingWhitespace(source, cursor, end);
            if (contentEnd > cursor) {
                chunks.add(new Chunk(chunks.size(), cursor, contentEnd,
                        source.substring(cursor, contentEnd)));
            }
            cursor = Math.max(end, cursor + 1);
        }
        return Collections.unmodifiableList(chunks);
    }

    public List<String> splitToStrings(String source) {
        List<Chunk> chunks = split(source);
        List<String> texts = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            texts.add(chunk.getText());
        }
        return Collections.unmodifiableList(texts);
    }

    private int chooseBoundary(String text, int start, int hardEnd) {
        int minimum = Math.max(start + 1, hardEnd - preferredBoundaryWindow);

        int paragraph = lastParagraphBoundary(text, minimum, hardEnd);
        if (paragraph > start) {
            return paragraph;
        }

        for (int i = hardEnd - 1; i >= minimum; i--) {
            if (text.charAt(i) == '\n') {
                return i + 1;
            }
        }

        for (int i = hardEnd - 1; i >= minimum; i--) {
            char c = text.charAt(i);
            if (isSentencePunctuation(c) && isBoundaryAfter(text, i + 1)) {
                return i + 1;
            }
        }

        for (int i = hardEnd - 1; i >= minimum; i--) {
            char c = text.charAt(i);
            if ((c == ';' || c == ':') && isBoundaryAfter(text, i + 1)) {
                return i + 1;
            }
        }

        for (int i = hardEnd - 1; i >= minimum; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }
        return hardEnd;
    }

    private static int lastParagraphBoundary(String text, int minimum, int hardEnd) {
        for (int i = hardEnd - 1; i > minimum; i--) {
            if (text.charAt(i) == '\n' && text.charAt(i - 1) == '\n') {
                return i + 1;
            }
            if (i >= 2 && text.charAt(i) == '\n' && text.charAt(i - 1) == '\r'
                    && text.charAt(i - 2) == '\n') {
                return i + 1;
            }
        }
        return -1;
    }

    private static boolean isSentencePunctuation(char c) {
        return c == '.' || c == '!' || c == '?' || c == '\u2026';
    }

    private static boolean isBoundaryAfter(String text, int index) {
        return index >= text.length() || Character.isWhitespace(text.charAt(index))
                || text.charAt(index) == '\u201d' || text.charAt(index) == '"'
                || text.charAt(index) == '\u00bb';
    }

    private static int skipWhitespace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int trimTrailingWhitespace(String text, int start, int end) {
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    private static int avoidSplittingSurrogatePair(String text, int end) {
        if (end > 0 && end < text.length()
                && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            return end - 1;
        }
        return end;
    }

    public static final class Chunk {
        private final int index;
        private final int sourceStart;
        private final int sourceEnd;
        private final String text;

        Chunk(int index, int sourceStart, int sourceEnd, String text) {
            this.index = index;
            this.sourceStart = sourceStart;
            this.sourceEnd = sourceEnd;
            this.text = text;
        }

        public int getIndex() {
            return index;
        }

        public int getSourceStart() {
            return sourceStart;
        }

        public int getSourceEnd() {
            return sourceEnd;
        }

        public String getText() {
            return text;
        }

        public int length() {
            return text.length();
        }
    }
}
