# UI architecture

The editor keeps browser integration in `App.vue` and moves reusable behaviour into small modules with direct Node tests.

## Module boundaries

- `editorModel.js` owns project defaults, legacy-data normalization, timecodes, chapter playback rates and word timing. It must stay independent from Vue, the DOM and the network.
- `apiClient.js` owns JSON requests, deadlines, cancellation and normalized API errors.
- `projectPersistence.js` owns browser-cache and server snapshot publication, including creation of the first video edition.
- `asyncControl.js` owns save coalescing, non-overlapping polling and global pointer-gesture cleanup.
- `timelineModel.js` owns sorted interval indexes, binary timeline lookup, viewport queries and timeline projection. It is a pure domain module and must not import Vue or browser APIs.
- `playbackClock.js` owns the admission policy for high-frequency playback updates. Media correctness stays in `App.vue`; the gate only limits UI invalidation frequency.
- `scenePlan.js` owns interpolation and lookup in the compiled visual plan.
- `App.vue` orchestrates Vue state, media elements and user actions. New pure algorithms and infrastructure should not be added there.

## Reliability rules

1. Never use an asynchronous `setInterval`; use the serial poller.
2. Never clear the dirty state for a snapshot older than the current revision.
3. Responses tied to a project must verify that the project is still active before mutating UI state.
4. Every global event listener, timer, object URL and audio context needs an explicit unmount cleanup path.
5. Project-schema changes require normalization tests for current, legacy and malformed input.
6. Timeline lanes must render viewport-indexed collections instead of complete project collections.
7. High-frequency media callbacks must not invalidate the root component faster than the configured UI frame gate.
8. Large replacement-only payloads such as waveform samples and compiled scene plans use shallow reactivity.

## Performance verification

Run the production preview and benchmark it with `npm run benchmark:ui -- <url> <label> <runs>`.
The benchmark creates an isolated synthetic project and never writes to the user's database. Current reference measurements and methodology live in `PERFORMANCE.md`.
