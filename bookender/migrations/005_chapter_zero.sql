ALTER TABLE chapters ADD COLUMN is_zero INTEGER NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX idx_chapters_one_zero_per_book
ON chapters(book_id)
WHERE is_zero = 1 AND archived_at IS NULL;
