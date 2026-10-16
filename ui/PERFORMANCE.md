# UI performance baseline

Measured on 2026-10-16 with headless Chromium at 1440×960. Every number is the median of five fresh browser pages against production Vite builds. The fixture contains 120 chapters, 480 scenes, 240 music clips and 1,200 visual inserts. API and media requests are deterministic in-memory fixtures.

The historical baseline is commit `bdf7b97`, immediately before the 4.0 performance work. The optimized result includes the 4.0 work and the timeline architecture pass after `1f5b6a9`.

| Operation | Before 4.0 | Optimized | Change |
| --- | ---: | ---: | ---: |
| Editor ready | 1,413.5 ms | 798.7 ms | 43.5% faster |
| Zoom timeline to 12× | 246.7 ms | 234.3 ms | 5.0% faster |
| Timeline scroll, median | 66.2 ms | 49.9 ms | 24.6% faster |
| Timeline scroll, p95 | 67.1 ms | 66.3 ms | 1.2% faster |
| 120 cursor seeks | 4,493.4 ms | 249.3 ms | 94.5% faster |
| DOM nodes after zoom | 11,007 | 1,974 | 82.1% fewer |
| Rendered visual clips after zoom | 1,200 | 193 | 83.9% fewer |

The latest architecture pass alone reduced 120 cursor seeks from 1,356.3 ms to 249.3 ms (81.6%), reduced the zoomed DOM from 5,727 to 1,974 nodes (65.5%), and improved median scrolling from 65.7 ms to 49.9 ms (24.0%). Its one measured regression is the one-time 12× zoom transition, from 181.9 ms to 234.3 ms, because that transition now removes thousands of off-screen nodes. Steady-state interaction is substantially cheaper afterward.

Reproduce locally:

```powershell
npm run build
npm run preview
npm run benchmark:ui -- http://127.0.0.1:4178 local 5
```

Treat the benchmark as a comparative workload, not a promise for every machine. Keep the viewport, fixture, build mode and run count identical when calculating percentages.
