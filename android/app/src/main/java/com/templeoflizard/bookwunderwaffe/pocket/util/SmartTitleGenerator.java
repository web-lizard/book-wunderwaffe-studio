package com.templeoflizard.bookwunderwaffe.pocket.util;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Small deterministic/offline heuristic for readable cross-platform WAV file names. */
public final class SmartTitleGenerator {
    public static final int MAX_FILENAME_LENGTH = 80;
    private static final String EXTENSION = ".wav";
    private static final Pattern UNSAFE = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern GENERIC_CHAPTER = Pattern.compile(
            "(?iu)^(глава|chapter|часть|part)\\s*[№#.: -]*\\s*\\d+[а-яa-z]?$|^новая глава$");
    private static final Set<String> WINDOWS_RESERVED = new HashSet<>(Arrays.asList(
            "CON", "PRN", "AUX", "NUL", "CLOCK$", "COM1", "COM2", "COM3", "COM4", "COM5",
            "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5",
            "LPT6", "LPT7", "LPT8", "LPT9"));

    private SmartTitleGenerator() {}

    public static String generateAudioFileName(String bookTitle, String chapterTitle, String text) {
        return generateAudioFileName(bookTitle, chapterTitle, text, 0L);
    }

    /**
     * Uses titles when meaningful and otherwise promotes the first readable phrase from the text.
     * A timestamp makes separately generated versions recognizable without any network AI.
     */
    public static String generateAudioFileName(
            String bookTitle, String chapterTitle, String text, long timestampMillis) {
        String safeBook = sanitizeStem(bookTitle);
        String safeChapter = sanitizeStem(chapterTitle);
        String phrase = extractPhrase(text, 7);
        List<String> parts = new ArrayList<>();
        if (!safeBook.isEmpty() && !"Без названия".equalsIgnoreCase(safeBook)) parts.add(safeBook);
        if (!safeChapter.isEmpty() && !GENERIC_CHAPTER.matcher(safeChapter).matches()) {
            parts.add(safeChapter);
        } else if (!phrase.isEmpty()) {
            parts.add(phrase);
        } else if (!safeChapter.isEmpty()) {
            parts.add(safeChapter);
        }
        if (parts.isEmpty()) parts.add("Озвучка-" + Hashing.sha256(text).substring(0, 8));
        if (timestampMillis > 0L) {
            parts.add(new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
                    .format(new Date(timestampMillis)));
        }
        String stem = sanitizeStem(join(parts, " — "));
        if (stem.isEmpty()) stem = "Озвучка-" + Hashing.sha256(text).substring(0, 8);
        stem = avoidReserved(stem);
        return truncateCodePoints(stem, MAX_FILENAME_LENGTH - EXTENSION.length()) + EXTENSION;
    }

    /** Adds a collision suffix while retaining the 80-character invariant. */
    public static String withSuffix(String fileName, String suffix) {
        String value = fileName == null ? "" : fileName;
        String stem = value.toLowerCase(Locale.ROOT).endsWith(EXTENSION)
                ? value.substring(0, value.length() - EXTENSION.length()) : value;
        String safeSuffix = sanitizeStem(suffix);
        String tail = safeSuffix.isEmpty() ? "" : "-" + safeSuffix;
        int maxStem = MAX_FILENAME_LENGTH - EXTENSION.length() - tail.codePointCount(0, tail.length());
        return truncateCodePoints(avoidReserved(sanitizeStem(stem)), Math.max(1, maxStem))
                + tail + EXTENSION;
    }

    public static String sanitizeStem(String value) {
        if (value == null) return "";
        String result = UNSAFE.matcher(value).replaceAll(" ");
        result = WHITESPACE.matcher(result).replaceAll(" ").trim();
        result = result.replaceAll("[. ]+$", "");
        result = result.replaceAll("^[. ]+", "");
        result = result.replaceAll("(?:\\s*[—–-]\\s*){2,}", " — ");
        return avoidReserved(result);
    }

    static String extractPhrase(String text, int maxWords) {
        if (text == null || text.trim().isEmpty()) return "";
        String cleaned = text.replaceAll("(?m)^\\s{0,3}[#>*+-]+\\s*", "")
                .replaceAll("[`_~]+", " ");
        String[] sentences = cleaned.split("(?<=[.!?…])\\s+|[\\r\\n]+");
        for (String sentence : sentences) {
            String candidate = sanitizeStem(sentence.replaceAll("^[\\p{Punct}\\s]+", ""));
            if (candidate.length() < 3) continue;
            String[] words = candidate.split(" ");
            StringBuilder result = new StringBuilder();
            int accepted = 0;
            for (String word : words) {
                if (word.isEmpty()) continue;
                if (accepted++ >= maxWords) break;
                if (result.length() > 0) result.append(' ');
                result.append(word);
            }
            return sanitizeStem(result.toString().replaceAll("[.!?…]+$", ""));
        }
        return "";
    }

    private static String avoidReserved(String value) {
        if (WINDOWS_RESERVED.contains(value.toUpperCase(Locale.ROOT))) return "_" + value;
        return value;
    }

    private static String truncateCodePoints(String value, int maximum) {
        if (maximum <= 0) return "";
        int count = value.codePointCount(0, value.length());
        if (count <= maximum) return value;
        int end = value.offsetByCodePoints(0, maximum);
        return value.substring(0, end).replaceAll("[. —–-]+$", "");
    }

    private static String join(List<String> values, String delimiter) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(delimiter);
            result.append(value);
        }
        return result.toString();
    }
}
