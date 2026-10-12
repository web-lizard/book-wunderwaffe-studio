package com.templeoflizard.bookwunderwaffe.pocket.data;

import java.util.Objects;

/** Immutable book metadata. Content lives in ordered {@link Chapter} rows. */
public final class Book {
    private final String id;
    private final String title;
    private final String author;
    private final long createdAt;
    private final long updatedAt;
    private final long revision;

    public Book(String id, String title, long createdAt, long updatedAt, long revision) {
        this(id, title, "", createdAt, updatedAt, revision);
    }

    public Book(String id, String title, String author, long createdAt, long updatedAt, long revision) {
        this.id = requireId(id);
        this.title = normalizeTitle(title);
        this.author = author == null ? "" : author.trim();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.revision = Math.max(0L, revision);
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public long getRevision() { return revision; }

    public Book withTitle(String value) {
        return new Book(id, value, author, createdAt, updatedAt, revision);
    }

    public Book withAuthor(String value) {
        return new Book(id, title, value, createdAt, updatedAt, revision);
    }

    private static String requireId(String value) {
        String result = Objects.requireNonNull(value, "id").trim();
        if (result.isEmpty()) throw new IllegalArgumentException("id is empty");
        return result;
    }

    static String normalizeTitle(String value) {
        String result = value == null ? "" : value.trim();
        return result.isEmpty() ? "Без названия" : result;
    }
}
