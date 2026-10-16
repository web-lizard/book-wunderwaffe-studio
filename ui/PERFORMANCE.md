# UI performance baseline

Measured on 2026-10-16 with headless Chromium at 1440×960. Every number is the median of five fresh browser pages against production Vite builds. The fixture contains 120 chapters, 480 scenes, 240 music clips and 1,200 visual inserts. API and media requests are deterministic in-memory fixtures.

The historical baseline is commit `bdf7b97`, immediately before the 4.0 performance work. The optimized result includes the 4.0 work and the timeline architecture pass after `1f5b6a9`.

| Operation | Before 4.0 | Optimized | Change |
| --- | ---: | ---: | ---: |
| Editor ready | 1,413.5 ms | 760.0 ms | 46.2% faster |
| Zoom timeline to 12× | 246.7 ms | 161.5 ms | 34.5% faster |
| Timeline scroll, median | 66.2 ms | 50.0 ms | 24.5% faster |
| Timeline scroll, p95 | 67.1 ms | 66.5 ms | 0.9% faster |
| 120 cursor seeks | 4,493.4 ms | 236.3 ms | 94.7% faster |
| DOM nodes after zoom | 11,007 | 1,974 | 82.1% fewer |
| Rendered visual clips after zoom | 1,200 | 193 | 83.9% fewer |

The latest architecture pass alone reduced 120 cursor seeks from 1,356.3 ms to 249.3 ms (81.6%), reduced the zoomed DOM from 5,727 to 1,974 nodes (65.5%), and improved median scrolling from 65.7 ms to 49.9 ms (24.0%). Its one measured regression was the one-time 12× zoom transition, from 181.9 ms to 234.3 ms, because that transition removes thousands of off-screen nodes. The playback pass brought that transition down to 161.5 ms.

The playback pass updates the cursor, clocks, scrubber, waveform and visual opacity directly on animation frames while committing root Vue state at 10 Hz and at semantic boundaries. On the same production-build workload it reduced playback DOM mutation records from 3,227 to 1,479 (54.2%). Long word navigators are split into paragraph-sized memoized blocks and off-screen paragraphs use native `content-visibility`.

Reproduce locally:

```powershell
npm run build
npm run preview
npm run benchmark:ui -- http://127.0.0.1:4178 local 5
```

Treat the benchmark as a comparative workload, not a promise for every machine. Keep the viewport, fixture, build mode and run count identical when calculating percentages.
