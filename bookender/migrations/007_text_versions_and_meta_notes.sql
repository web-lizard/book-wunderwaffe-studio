CREATE TABLE chapter_meta_notes (
    id TEXT PRIMARY KEY,
    chapter_id INTEGER NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    selected_text TEXT NOT NULL,
    note TEXT NOT NULL,
    color_index INTEGER NOT NULL DEFAULT 0,
    detached INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE chapter_text_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    chapter_id INTEGER NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    title TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL,
    notes_json TEXT NOT NULL DEFAULT '[]',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_chapter_meta_notes_chapter ON chapter_meta_notes(chapter_id, start_offset);
CREATE INDEX idx_chapter_text_versions_chapter ON chapter_text_versions(chapter_id, created_at DESC);
