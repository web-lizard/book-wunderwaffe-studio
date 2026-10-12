package com.templeoflizard.bookwunderwaffe.pocket.data;

import java.util.Objects;

/** Immutable chapter snapshot. Positions are zero based inside a book. */
public final class Chapter {
    private final String id;
    private final String bookId;
    private final String title;
    private final String text;
    private final int position;
    private final long createdAt;
    private final long updatedAt;
    private final long revision;

    public Chapter(String id, String bookId, String title, String text, int position,
                   long createdAt, long updatedAt, long revision) {
        this.id = require(id, "id");
        this.bookId = require(bookId, "bookId");
        this.title = normalizeTitle(title);
        this.text = text == null ? "" : text;
        this.position = Math.max(0, position);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.revision = Math.max(0L, revision);
    }

    public String getId() { return id; }
    public String getBookId() { return bookId; }
    public String getTitle() { return title; }
    public String getText() { return text; }
    public int getPosition() { return position; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public long getRevision() { return revision; }

    public Chapter withTitle(String value) {
        return new Chapter(id, bookId, value, text, position, createdAt, updatedAt, revision);
    }

    public Chapter withText(String value) {
        return new Chapter(id, bookId, title, value, position, createdAt, updatedAt, revision);
    }

    public Chapter withContent(String newTitle, String newText) {
        return new Chapter(id, bookId, newTitle, newText, position, createdAt, updatedAt, revision);
    }

    public Chapter withPosition(int value) {
        return new Chapter(id, bookId, title, text, value, createdAt, updatedAt, revision);
    }

    private static String require(String value, String name) {
        String result = Objects.requireNonNull(value, name).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(name + " is empty");
        return result;
    }

    private static String normalizeTitle(String value) {
        String result = value == null ? "" : value.trim();
        return result.isEmpty() ? "Новая глава" : result;
    }
}
