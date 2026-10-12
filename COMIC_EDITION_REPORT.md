# «Торговец Солнцем» — Comic Edition: отчёт первой партии

## Проекты и сохранность

- Предварительный git-checkpoint: `bd70806` (`checkpoint: preserve pre-comic edition state`).
- Бэкап исходного проекта: `user_data/backups/966f93ef-7be5-4efd-b10b-8ca58437d116-20261012-130858`.
- Исходник: `966f93ef-7be5-4efd-b10b-8ca58437d116` — 17 глав, 23 аудио, classic, без comic-кадров.
- Original Layout: `48e9851d-ca3b-43b0-bf67-e3064e79e198` — независимая полная копия, 17 глав, 23 аудио, classic.
- Comic Edition: `d1e0570e-03d8-4163-8312-7a6b41e2ddaa` — независимая полная копия, 17 глав, 23 аудио, режим `comic`.
- Хэши текста всех 17 глав совпадают во всех трёх проектах. Исходник и Original Layout не получили comic-материалов.

## Выполненный диапазон

- Глава 0: 6 кадров (`chapter-0-01.png` … `chapter-0-06.png`).
- Глава 1: 8 кадров (`chapter-1-01.png` … `chapter-1-08.png`).
- Глава 2: 9 кадров (`chapter-2-01.png` … `chapter-2-09.png`).
- Глава 3: 10 кадров (`chapter-3-01.png` … `chapter-3-10.png`).
- Итого: 33 самостоятельных 16:9 иллюстрации, 33 материала библиотеки и 33 клипа таймлайна; ошибок компиляции scene plan — 0.
- Работа остановлена после главы 3. Последующие главы не иллюстрировались.

Все кадры и character anchor лежат в
`user_data/projects/d1e0570e-03d8-4163-8312-7a6b41e2ddaa/images/comic/`.
Раскадровка с точными локальными таймингами и содержанием каждого beat находится
в `COMIC_EDITION_STORYBOARD.md`.

## Генерация

Использована встроенная генерация изображений Codex (`imagegen`), без внешнего
API. Каждый файл создан отдельным запросом. Общий prompt-set: single-frame 16:9
motion-comic illustration, adult satirical cinematic graphic-novel realism,
gritty port metropolis, amber practical light, soot-black shadows, tactile
painted texture, consistent Musa/Mammon character anchor, fully clothed adults,
no text/captions/speech balloons/panels/logos/watermarks; сюжетная часть каждого
запроса соответствует строке раскадровки. Один двусмысленный запрос главы 2 был
безопасно переформулирован без изменения сюжетного beat.

## Просмотрщик

- Full-bleed кадр без старой обложки, QR, визуализатора и редакторского chrome.
- Лёгкий Ken Burns, затемнение под читаемость, короткий заголовок главы.
- Литературные субтитры по 16 слов, синхронизированные с неизменённой озвучкой.
- Play/Pause, ±10 секунд, scrubber, текущая/общая длительность, Escape; управление скрывается во время воспроизведения.
- Кнопка `Смотреть Comic Edition` открывает отдельный theater-mode. Classic остаётся режимом по умолчанию для остальных проектов.

## Проверки

- `npm test`: 24/24 passed.
- `npm run build`: passed.
- `npx playwright test e2e/visual-inserts.spec.mjs`: 3/3 passed.
- Python-тесты проекта/дублирования/визуальных вставок: 13/13 passed, включая реальный FFmpeg render/seek.
- Реальный браузерный smoke-test Comic Edition: кадры на 0.8, 32.8, 195.8, 1055.432, 2327.048 и 3945.2 с; переходы между главами, последняя сцена, Play/Pause и Escape — passed.
- Скриншот smoke-test: `user_data/projects/d1e0570e-03d8-4163-8312-7a6b41e2ddaa/comic-edition-browser.png`.
- Полный `unittest discover`: 57 passed, 2 существующих audio-energy threshold теста не прошли на локальном FFmpeg 9.0.1 (1714/1800 и 1799/1800); comic-код и данные они не затрагивают.
