# Book Wunderwaffe portable book (`.bwlbook`) — schema 1

`.bwlbook` — ZIP-контейнер с UTF-8 именами и обязательным `manifest.json`. Это
транспортный формат, а не копия SQLite. Расширение зарезервировано для обмена между
Pocket, desktop Studio и будущими sync-провайдерами.

## Структура

```text
manifest.json
chapters/<chapter-uuid>.txt
audio/<audio-uuid>.wav       # необязательно
```

Минимальный manifest:

```json
{
  "format": "book-wunderwaffe-book",
  "schemaVersion": 1,
  "exportedAt": "2026-08-30T12:00:00Z",
  "book": {
    "uuid": "stable-uuid",
    "revision": 4,
    "title": "Название",
    "author": "Автор",
    "updatedAt": "2026-08-30T11:59:00Z"
  },
  "chapters": [
    {
      "uuid": "chapter-uuid",
      "revision": 2,
      "position": 0,
      "title": "Глава 1",
      "textPath": "chapters/chapter-uuid.txt",
      "sha256": "hex"
    }
  ],
  "audio": [
    {
      "uuid": "audio-uuid",
      "revision": 1,
      "path": "audio/audio-uuid.wav",
      "sha256": "hex",
      "sourceHash": "hex",
      "mimeType": "audio/wav"
    }
  ],
  "tombstones": []
}
```

Unknown fields must be preserved or ignored, never treated as an error within the
same major schema. A reader must reject a newer unsupported `schemaVersion` before
changing local data.

## Integrity and security

- every chapter and audio entry has SHA-256 checked before import;
- paths are relative, normalized and limited to the declared `chapters/` or `audio/`
  namespace; absolute paths and `..` are rejected;
- importer limits entry count, per-entry size and total uncompressed size to resist
  ZIP bombs;
- import is staged and committed only after the whole manifest validates;
- duplicate stable UUID is a merge decision based on revision, not an implicit SQL
  overwrite.

## Planned sync

Phase 1 is manual SAF export/import. Phase 2 can advertise a desktop endpoint with a
QR pairing secret and send the same bundle over authenticated local HTTPS. Phase 3
can exchange entity manifests first, transfer only changed hashes, and resolve
concurrent revisions. These phases do not require a mobile database migration solely
to change the transport.
