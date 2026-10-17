<template>
  <div class="studio" :class="[`theme-${project.theme}`, `workspace-${activeWorkspace}`, { 'is-loading': loading, 'comic-edition': isComicEdition, 'comic-theater': comicTheater, 'comic-noir': isComicEdition && project.comic?.noirFilter !== false }]"
       :style="{ '--timeline-height': `${timelineHeight}px` }">
    <header class="topbar">
      <div class="brand" aria-label="Book Wunderwaffe Studio">
        <span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>
        <span class="brand-copy">
          <strong>BOOK WUNDERWAFFE STUDIO</strong>
          <small>BOOK · VOICE · VIDEO · v{{ backend.version || '4.0.0' }}</small>
        </span>
      </div>

      <div class="ecosystem-nav">
        <div class="workspace-tabs" role="tablist" aria-label="Рабочий режим">
          <button type="button" role="tab" :aria-selected="activeWorkspace === 'book'"
                  :class="{ active: activeWorkspace === 'book' }" @click="setWorkspace('book')">Книга</button>
          <button type="button" role="tab" :aria-selected="activeWorkspace === 'video'"
                  :class="{ active: activeWorkspace === 'video' }" @click="setWorkspace('video')"
                  @dragover.prevent @drop.prevent="dropBookAudioOnVideoTab">
            {{ videoPreparing ? 'Собираю видео…' : 'Видео' }}
          </button>
        </div>
        <label class="global-project-picker">
          <span>Проект</span>
          <select :value="activeProjectUuid || ''" :aria-busy="switchingProject" @change="switchProject($event.target.value)">
            <option value="" disabled>{{ projectCatalog.length ? 'Выберите проект' : 'Проекты не найдены' }}</option>
            <option v-for="item in projectCatalog" :key="item.uuid" :value="item.uuid">
              {{ item.title }} · {{ projectKindLabel(item.project_kind) }}
            </option>
          </select>
        </label>
        <button class="ecosystem-new" type="button" @click="createEcosystemProject">＋ Новый проект</button>
        <button class="ecosystem-manage" type="button" title="Управление проектами" @click="openProjectManager">•••</button>
      </div>

      <nav v-if="activeWorkspace === 'video'" class="project-actions" aria-label="Действия с видеопроектом">
        <button class="action-button" type="button" @click="newProject" title="Новый проект">
          <span aria-hidden="true">＋</span><b>Новый</b>
        </button>
        <button class="action-button" type="button" @click="projectInput?.click()" title="Открыть проект">
          <span aria-hidden="true">⌞</span><b>Открыть</b>
        </button>
        <button class="action-button" type="button" :disabled="saving" @click="saveProject()" title="Сохранить проект (Ctrl+S)">
          <span aria-hidden="true">◇</span><b>{{ saving ? 'Сохраняю' : 'Сохранить' }}</b>
        </button>
        <button class="action-button" type="button" @click="assetInput?.click()" title="Добавить аудио, видео или изображение">
          <span aria-hidden="true">↑</span><b>Импорт</b>
        </button>
        <button class="export-button" type="button" :disabled="!canOpenExport" @click="openExport">
          <span aria-hidden="true">↗</span><b>Экспорт</b>
        </button>
      </nav>
      <nav v-else class="project-actions book-actions" aria-label="Действия с книгой">
        <button class="book-icon-action" type="button" title="Новая книга"
                aria-label="Новая книга" @click="createBookProject">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M4 5.5c2.8-.7 5.4-.2 8 1.5v12c-2.6-1.7-5.2-2.2-8-1.5zM20 5.5c-2.8-.7-5.4-.2-8 1.5M17 9v6M14 12h6" />
          </svg>
        </button>
        <button class="book-icon-action" :class="{ saving: bookSaveState === 'saving' }"
                type="button" :disabled="bookSaveState === 'saving'"
                :title="bookSaveState === 'saving' ? 'Сохраняю…' : 'Сохранить'"
                :aria-label="bookSaveState === 'saving' ? 'Сохраняю' : 'Сохранить'"
                @click="saveCurrentChapter()">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 3.5h11l3 3V20.5H5zM8 3.5v6h8v-6M8 20.5v-7h8v7" />
          </svg>
        </button>
        <button class="book-icon-action" type="button" :disabled="!activeProjectUuid"
                title="Резервная копия" aria-label="Резервная копия"
                @click="backupActiveProject">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M4 7.5h16v13H4zM3 3.5h18v4H3zM9 11.5h6M12 11.5v5M9.5 14l2.5 2.5 2.5-2.5" />
          </svg>
        </button>
        <button class="book-export-button" type="button" :disabled="!activeProjectRecord?.book"
                @click="openBookExport">
          <span aria-hidden="true">⇩</span><b>Экспорт книги</b>
        </button>
        <button class="export-button" type="button" :disabled="!activeProjectRecord?.book" @click="openBookInVideo">
          <span aria-hidden="true">→</span><b>В видеокнигу</b>
        </button>
      </nav>

      <div class="topbar-status">
        <span class="status-light" :class="backend.online ? 'online' : 'offline'"></span>
        <span class="status-copy">
          <b>{{ backend.online ? 'Движок в сети' : 'Автономный режим' }}</b>
          <small>{{ activeWorkspace === 'book' ? bookSaveLabel : (dirty ? 'Есть изменения' : 'Проект сохранён') }}</small>
        </span>
      </div>

      <input ref="assetInput" class="visually-hidden" type="file" multiple
             accept="audio/*,video/*,image/png,image/jpeg,image/webp,image/gif" @change="onAssetInput" />
      <input ref="musicInput" class="visually-hidden" type="file"
             accept="audio/mpeg,audio/wav,audio/mp4,audio/aac,audio/flac,audio/ogg,audio/opus"
             @change="onMusicInput" />
      <input ref="projectInput" class="visually-hidden" type="file" accept="application/json,.json" @change="onProjectInput" />
      <input ref="bookChapterImageInput" class="visually-hidden" type="file"
             accept="image/png,image/jpeg,image/webp,image/bmp,image/gif"
             @change="onBookChapterImageInput" />
    </header>

    <main v-if="activeWorkspace === 'video'" class="workspace">
      <aside class="materials-panel panel-shell">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">Библиотека</span>
            <h2>Материалы</h2>
          </div>
          <span class="count-badge">{{ project.materials.length }}</span>
        </div>

        <button class="drop-zone" type="button" @click="assetInput?.click()"
                @dragover.prevent @drop.prevent="onDrop">
          <span class="drop-icon" aria-hidden="true">＋</span>
          <span><b>Добавить файлы</b><small>или перетащить сюда</small></span>
        </button>

        <div v-if="project.materials.length" class="material-list">
          <button v-for="asset in project.materials" :key="asset.id" type="button"
                  v-memo="[asset.name, asset.type, asset.role, asset.status, asset.progress, selection.type === 'asset' && selection.id === asset.id]"
                  class="material-row"
                  :draggable="asset.type === 'audio'"
                  :class="{ active: selection.type === 'asset' && selection.id === asset.id }"
                  @dragstart="startMaterialDrag(asset, $event)"
                  @click="select('asset', asset.id)">
            <span class="material-kind" :class="asset.type">{{ materialCode(asset.type) }}</span>
            <span class="material-copy">
              <b>{{ asset.name }}</b>
              <small>{{ materialRole(asset) || materialLabel(asset.type) }}</small>
              <span v-if="asset.status === 'uploading'" class="upload-track">
                <i :style="{ width: `${Math.round((asset.progress || 0) * 100)}%` }"></i>
              </span>
            </span>
            <span class="material-state" :class="asset.status" :title="assetStatus(asset)"></span>
          </button>
        </div>

        <div v-else class="empty-library">
          <span class="empty-wave" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i></span>
          <p>Начните с аудиокниги.<br />Обложку и фон можно добавить позже.</p>
        </div>

        <div class="library-summary">
          <span><i class="mini-dot audio"></i>{{ audioAsset ? 'Аудио готово' : 'Нет аудио' }}</span>
          <span><i class="mini-dot cover"></i>{{ coverAsset ? 'Обложка готова' : 'Нет обложки' }}</span>
        </div>
      </aside>

      <section class="stage-column">
        <div class="stage-toolbar">
          <div class="stage-context">
            <span class="eyebrow">Предпросмотр · 16:9</span>
            <strong>{{ project.title || 'Новая аудиокнига' }}</strong>
          </div>
          <div class="chapter-context" v-if="currentChapter">
            <p><b>{{ currentChapter.title }}</b></p>
          </div>
          <div class="stage-quick-actions">
            <button v-if="isComicEdition" type="button" class="comic-open-button" @click="toggleComicTheater">
              {{ comicTheater ? 'Выйти из просмотра' : 'Смотреть Comic Edition' }}
            </button>
            <label v-if="isComicEdition" class="caption-quick-toggle" :class="{ active: project.comic.noirFilter }">
              <input v-model="project.comic.noirFilter" type="checkbox" />
              <span aria-hidden="true">≋</span>
              <b>Нуар · помехи</b>
            </label>
            <span class="video-sync-count" :class="{ ready: videoReadyChapterCount > 0 }">
              Озвучено {{ videoReadyChapterCount }}/{{ videoBookChapterCount }}
            </span>
            <label class="caption-quick-toggle" :class="{ active: project.captions?.enabled }">
              <input v-model="project.captions.enabled" type="checkbox" />
              <span aria-hidden="true">Аа</span>
              <b>Текст на экране</b>
            </label>
          </div>
        </div>

        <div ref="sceneEl" class="scene-frame"
             :data-mode="visualScene.mode"
             :class="{ 'visual-active': visualScene.mode !== 'normal', 'glitch-enabled': project.glitch, 'background-unblurred': !project.backgroundBlur }"
             @pointerdown.self="select('project')">
          <div class="scene-backdrop" :style="backgroundStyle"></div>
          <video v-if="videoSource" ref="videoEl" class="scene-video" :src="videoSource" playsinline
                 crossorigin="anonymous" preload="metadata" :muted="masterKind === 'audio'"
                 :loop="masterKind === 'audio'"
                 @loadedmetadata="onMediaMetadata('video')" @timeupdate="onMediaTime('video')"
                 @play="onMediaPlay('video')" @pause="onMediaPause('video')"
                 @ended="onMediaEnded('video')" @error="onMediaError('video')"></video>
          <div class="scene-grade"></div>
          <div class="scene-grid" aria-hidden="true"></div>
          <div v-if="project.glitch" class="glitch-scan" aria-hidden="true"></div>

          <button v-if="!isComicEdition && coverEnabled" type="button" class="composition-layer cover-layer"
                  :class="{ selected: selection.type === 'layer' && selection.id === 'cover' }"
                  :style="layerStyle('cover')" @pointerdown.stop="onLayerPointerDown('cover', $event)">
            <img v-if="coverSource" :src="coverSource" alt="Обложка книги" />
            <span v-else class="cover-placeholder"><i>BW</i><b>ОБЛОЖКА</b><small>добавьте изображение</small></span>
            <span class="layer-resize-handle resize-e" @pointerdown.stop.prevent="onLayerResizePointerDown('cover', 'e', $event)"></span>
            <span class="layer-resize-handle resize-s" @pointerdown.stop.prevent="onLayerResizePointerDown('cover', 's', $event)"></span>
            <span class="layer-resize-handle resize-se" @pointerdown.stop.prevent="onLayerResizePointerDown('cover', 'se', $event)"></span>
          </button>

          <button v-if="!isComicEdition && layerVisible('title')" ref="titleLayerEl" type="button" class="composition-layer title-layer"
                  :class="{ selected: selection.type === 'layer' && selection.id === 'title' }"
                  :style="layerStyle('title')" @pointerdown.stop="onLayerPointerDown('title', $event)">
            <span ref="titleStackEl" class="chapter-stack" lang="ru">
              <span v-if="previousChapter" class="chapter-neighbor previous" aria-hidden="true">
                <b>{{ previousChapter.title }}</b>
              </span>
              <strong>{{ currentChapter?.title || 'Добавьте первую главу' }}</strong>
              <span v-if="nextChapter" class="chapter-neighbor next" aria-hidden="true">
                <b>{{ nextChapter.title }}</b>
              </span>
            </span>
            <span class="layer-resize-handle resize-e" @pointerdown.stop.prevent="onLayerResizePointerDown('title', 'e', $event)"></span>
            <span class="layer-resize-handle resize-s" @pointerdown.stop.prevent="onLayerResizePointerDown('title', 's', $event)"></span>
            <span class="layer-resize-handle resize-se" @pointerdown.stop.prevent="onLayerResizePointerDown('title', 'se', $event)"></span>
          </button>

          <button v-if="!isComicEdition && project.captions?.enabled && layerVisible('caption') && currentReadingCaption"
                  type="button" class="composition-layer reading-caption"
                  :class="{ selected: selection.type === 'layer' && selection.id === 'caption' }"
                  :style="layerStyle('caption')" aria-live="off"
                  title="Перетащите текст в нужное место"
                  @pointerdown.stop="onLayerPointerDown('caption', $event)">
            {{ currentReadingCaption }}
            <span class="layer-resize-handle resize-e" @pointerdown.stop.prevent="onLayerResizePointerDown('caption', 'e', $event)"></span>
            <span class="layer-resize-handle resize-s" @pointerdown.stop.prevent="onLayerResizePointerDown('caption', 's', $event)"></span>
            <span class="layer-resize-handle resize-se" @pointerdown.stop.prevent="onLayerResizePointerDown('caption', 'se', $event)"></span>
          </button>

          <div v-if="videoPreparing" class="video-prepare-overlay">
            <i></i><b>Собираю озвученные главы</b><span>Создаю мастер-аудио и таймлайн…</span>
          </div>
          <div v-else-if="!audioAsset && activeProjectRecord?.book" class="video-empty-audio">
            <b>В книге пока нет готовой озвучки</b>
            <span>Озвучьте хотя бы одну главу — она автоматически появится здесь.</span>
            <button type="button" @click="setWorkspace('book')">Перейти к озвучке</button>
          </div>

          <div v-if="visualScene.layers?.length" ref="visualSceneLayer" class="visual-scene-layer">
            <component v-for="layer in visualScene.layers"
                       :is="VISUAL_CONTENT_COMPONENTS[layer.visual.mode]"
                       :key="`${layer.visual.chapterId}-${layer.visual.id}`"
                       :data-visual-key="`${layer.visual.chapterId}:${layer.visual.id}`"
                       :clip="layer.visual" :src="assetPreviewUrl(assetById(layer.visual.assetId))"
                       :opacity="layer.opacity"
                       :comic="isComicEdition"
                       :scene-width="sceneEl?.clientWidth || 960" />
          </div>

          <transition name="comic-telegram">
            <a v-if="!isComicEdition || comicTelegramVisible" class="telegram-qr"
               :class="{ 'comic-telegram-banner': isComicEdition }"
               :href="TELEGRAM_URL" target="_blank" rel="noopener noreferrer"
               title="Telegram · Temple of Lizard" draggable="false" @pointerdown.stop @click.stop>
              <span class="telegram-qr-code">
                <QrcodeVue :value="TELEGRAM_URL" :size="256" level="H" render-as="svg"
                            foreground="#17131d" background="#f4efe7" />
              </span>
              <span class="telegram-qr-copy"><b>TELEGRAM</b><small>@temple_of_lizard · новые главы</small></span>
            </a>
          </transition>

          <button v-if="!isComicEdition && layerVisible('visualizer')" type="button" class="composition-layer visualizer-layer"
                  :class="{ selected: selection.type === 'layer' && selection.id === 'visualizer' }"
                  :style="layerStyle('visualizer')" @pointerdown.stop="onLayerPointerDown('visualizer', $event)">
            <canvas ref="visualizerCanvas" aria-label="Визуализация аудио"></canvas>
            <span class="layer-resize-handle resize-e" @pointerdown.stop.prevent="onLayerResizePointerDown('visualizer', 'e', $event)"></span>
            <span class="layer-resize-handle resize-s" @pointerdown.stop.prevent="onLayerResizePointerDown('visualizer', 's', $event)"></span>
            <span class="layer-resize-handle resize-se" @pointerdown.stop.prevent="onLayerResizePointerDown('visualizer', 'se', $event)"></span>
          </button>

          <div class="scene-corner scene-corner-bottom">
            <span ref="sceneCurrentTimeEl">{{ formatTime(currentTime) }}</span>
            <div><i ref="sceneProgressFill"></i></div>
            <span>{{ formatTime(duration) }}</span>
          </div>

          <div v-if="isComicEdition" class="comic-viewer-chrome" @pointerdown.stop>
            <transition name="comic-title">
              <header v-if="showComicChapterTitle" :key="currentChapter?.id" class="comic-chapter-title">
                <small>ТОРГОВЕЦ СОЛНЦЕМ</small>
                <strong>{{ currentChapter?.title }}</strong>
              </header>
            </transition>
            <div v-if="currentReadingCaption" class="comic-caption" aria-live="off">{{ currentReadingCaption }}</div>
            <button v-if="comicTheater" type="button" class="comic-theater-close" title="Закрыть просмотр" @click="toggleComicTheater">×</button>
          </div>
        </div>

        <div class="transport" :class="{ disabled: !masterKind }">
          <button type="button" class="transport-button seek-button" :disabled="!masterKind" @click="seekBy(-15)" title="Назад на 15 секунд">−15</button>
          <button type="button" class="transport-button play-button" :disabled="!masterKind" @click="togglePlay" :title="playing ? 'Пауза' : 'Воспроизвести'">
            <span v-if="playing" class="pause-icon" aria-hidden="true"><i></i><i></i></span>
            <span v-else class="play-icon" aria-hidden="true"></span>
          </button>
          <button type="button" class="transport-button stop-button" :disabled="!masterKind" @click="stopPlayback" title="Остановить"><span aria-hidden="true"></span></button>
          <button type="button" class="transport-button seek-button" :disabled="!masterKind" @click="seekBy(15)" title="Вперёд на 15 секунд">+15</button>
          <span ref="transportCurrentTimeEl" class="transport-time current">{{ formatTime(currentTime, true) }}</span>
          <input ref="transportScrubber" class="transport-scrubber" type="range" min="0" :max="Math.max(duration, 0.01)" step="0.01"
                 :disabled="!masterKind" aria-label="Позиция воспроизведения" @input="seekTo(Number($event.target.value))" />
          <span class="transport-time">{{ formatTime(duration, true) }}</span>
          <label class="volume-control" title="Громкость">
            <span aria-hidden="true">◖</span>
            <input type="range" min="0" max="1" step="0.01" v-model.number="volume" @input="applyVolume" />
          </label>
        </div>
      </section>

      <aside class="properties-panel panel-shell">
        <div class="panel-heading properties-heading">
          <div>
            <span class="eyebrow">Инспектор</span>
            <h2>{{ inspectorTitle }}</h2>
          </div>
          <button v-if="selection.type !== 'project'" type="button" class="back-button" @click="select('project')" title="К проекту">↩</button>
        </div>

        <div class="inspector-scroll">
          <template v-if="selection.type === 'project'">
            <label class="field-label">Название книги
              <input v-model.trim="project.title" type="text" placeholder="Название" />
            </label>
            <label class="field-label">Автор
              <input v-model.trim="project.author" type="text" placeholder="Имя автора" />
            </label>
            <label class="field-label">Характер оформления
              <select v-model="project.theme">
                <option value="amber">Тёплый графит</option>
                <option value="violet">Ночной фиолетовый</option>
                <option value="mono">Монохром</option>
              </select>
            </label>
            <label class="field-label">Режим просмотра
              <select v-model="project.presentationMode">
                <option value="classic">Классический</option>
                <option value="comic">Comic Edition</option>
              </select>
            </label>

            <div class="inspector-section">
              <span class="section-title">Сцена</span>
              <label class="switch-row">
                <span><b>Glitch-фон</b><small>Тонкие цифровые смещения</small></span>
                <input v-model="project.glitch" type="checkbox" /><i></i>
              </label>
              <label class="switch-row">
                <span><b>Визуализатор</b><small>Реагирует на воспроизведение</small></span>
                <input v-model="project.layers.visualizer.visible" type="checkbox" /><i></i>
              </label>
              <label class="switch-row">
                <span><b>Показывать обложку</b><small>Можно оставить только единый задник</small></span>
                <input :checked="project.coverMode !== 'background-only'" type="checkbox"
                       @change="setCoverEnabled($event.target.checked)" /><i></i>
              </label>
              <label class="switch-row">
                <span><b>Размытие задника</b><small>Отключите для исходного резкого изображения</small></span>
                <input v-model="project.backgroundBlur" type="checkbox" /><i></i>
              </label>
              <label class="switch-row">
                <span><b>Текст озвучки</b><small>Показывать читаемый фрагмент на экране и в MP4</small></span>
                <input v-model="project.captions.enabled" type="checkbox" /><i></i>
              </label>
            </div>

            <button type="button" class="music-summary-card" :class="{ ready: musicAsset }" @click="select('music')">
              <span aria-hidden="true">♫</span>
              <span>
                <b>{{ musicAsset ? 'Музыка проекта' : 'Добавить музыку' }}</b>
                <small>{{ musicAsset ? `${musicAsset.name} · громкость ${Math.round(project.music.volume * 100)}%` : 'Громкость и эквалайзер' }}</small>
              </span>
              <i>Настроить →</i>
            </button>

            <div class="project-readiness">
              <span class="section-title">Готовность</span>
              <div><i :class="{ done: !!audioAsset }"></i><span>Аудио</span><b>{{ audioAsset ? 'готово' : 'нужно' }}</b></div>
              <div><i :class="{ done: coverRequirementMet }"></i><span>{{ project.coverMode === 'background-only' ? 'Задник' : 'Обложка' }}</span><b>{{ coverRequirementMet ? 'готово' : 'нужно' }}</b></div>
              <div><i :class="{ done: project.chapters.length > 0 }"></i><span>Главы</span><b>{{ project.chapters.length || 'нужно' }}</b></div>
            </div>
          </template>

          <template v-else-if="selection.type === 'music'">
            <div class="music-mixer music-mixer-dedicated">
              <div class="mixer-heading">
                <span class="section-title">{{ selectedMusicClip ? 'Музыкальный клип' : 'Музыкальная дорожка' }}</span>
                <button type="button" @click="musicInput?.click()">＋ Добавить файл</button>
              </div>
              <label class="field-label">Файл музыки
                <select :value="selectedMusicClip?.assetId || project.musicAssetId"
                        @change="changeSelectedMusicAsset($event.target.value || null)">
                  <option :value="null">Без музыки</option>
                  <option v-for="asset in musicCandidates" :key="asset.id" :value="asset.id">{{ asset.name }}</option>
                </select>
              </label>
              <div v-if="selectedMusicClip" class="music-clip-inspector">
                <label class="field-label">Начало
                  <input :value="formatTimecode(selectedMusicClip.start)" type="text"
                         @change="updateMusicClipTime('start', $event.target.value)" />
                </label>
                <label class="field-label">Конец
                  <input :value="formatTimecode(selectedMusicClip.end)" type="text"
                         @change="updateMusicClipTime('end', $event.target.value)" />
                </label>
                <label class="field-label">Громкость клипа
                  <input v-model.number="selectedMusicClip.volume" type="range" min="0" max="2" step="0.01" />
                </label>
                <label class="switch-row music-clip-enabled">
                  <span><b>Клип включён</b><small>Можно временно выключить без удаления</small></span>
                  <input v-model="selectedMusicClip.enabled" type="checkbox" @change="applyMusicMix" /><i></i>
                </label>
                <div class="music-clip-actions">
                  <button type="button" @click="splitSelectedMusicClip">✂ Разрезать по курсору</button>
                  <button type="button" class="danger" @click="removeMusicClip(selectedMusicClip.id)">Удалить клип</button>
                </div>
              </div>
              <template v-if="musicAsset">
                <div class="music-volume-card">
                  <div>
                    <span>Громкость музыки</span>
                    <output>{{ Math.round(project.music.volume * 100) }}%</output>
                  </div>
                  <input v-model.number="project.music.volume" type="range" min="0" max="1" step="0.01"
                         aria-label="Громкость музыки" @input="onMusicControlInput(false)" />
                  <div class="music-volume-presets">
                    <button type="button" @click="setMusicVolume(0.08)">Тихо · 8%</button>
                    <button type="button" @click="setMusicVolume(0.16)">Фон · 16%</button>
                    <button type="button" @click="setMusicVolume(0.3)">Громче · 30%</button>
                  </div>
                  <button type="button" class="music-preview-button" @click="toggleMusicPreview">
                    {{ musicPreviewPlaying ? '■ Остановить пробу' : '▶ Проверить музыку' }}
                  </button>
                </div>
                <label class="switch-row">
                  <span><b>Музыка включена</b><small>Смешивать с озвучкой в MP4</small></span>
                  <input v-model="project.music.enabled" type="checkbox" @change="applyMusicMix" /><i></i>
                </label>
                <label class="switch-row">
                  <span><b>Повторять по кругу</b><small>На всю длину книги</small></span>
                  <input v-model="project.music.loop" type="checkbox" /><i></i>
                </label>
                <div class="simple-eq">
                  <span class="section-title">Эквалайзер</span>
                  <div class="music-eq-status" :class="{ active: musicEqState.active, error: musicEqState.error }">
                    <i></i><span>{{ musicEqState.message }}</span>
                  </div>
                  <label v-for="band in MUSIC_EQ_BANDS" :key="band.key">
                    <span><b>{{ band.label }}</b><output>{{ formatDb(project.music[band.key]) }}</output></span>
                    <input v-model.number="project.music[band.key]" type="range" min="-12" max="12" step="1"
                           @input="onMusicControlInput(true)" />
                  </label>
                  <small class="music-export-note">В экспортируемом MP4 эквалайзер применяется движком FFmpeg независимо от предпросмотра.</small>
                </div>
                <button type="button" class="jump-button" @click="resetMusicMix">Сбросить громкость и EQ</button>
              </template>
              <div v-else class="music-empty-state">
                <span aria-hidden="true">♫</span>
                <b>Музыка ещё не добавлена</b>
                <p>Выберите MP3, WAV, M4A, FLAC, OGG или OPUS. Файл появится на отдельной зелёной дорожке.</p>
                <button type="button" @click="musicInput?.click()">Выбрать музыку</button>
              </div>
            </div>
          </template>

          <template v-else-if="selectedAsset">
            <div class="asset-preview" :class="selectedAsset.type">
              <img v-if="selectedAsset.type === 'image'" :src="assetUrl(selectedAsset)" alt="" />
              <span v-else>{{ materialCode(selectedAsset.type) }}</span>
            </div>
            <div class="asset-meta">
              <strong>{{ selectedAsset.name }}</strong>
              <span>{{ materialLabel(selectedAsset.type) }}<i></i>{{ formatBytes(selectedAsset.size) }}</span>
            </div>
            <div class="role-actions" v-if="selectedAsset.type === 'image'">
              <button type="button" :class="{ active: project.coverAssetId === selectedAsset.id }" @click="assignAsset('cover', selectedAsset.id)">Как обложку</button>
              <button type="button" :class="{ active: project.backgroundAssetId === selectedAsset.id }" @click="assignAsset('background', selectedAsset.id)">Как фон</button>
            </div>
            <div class="role-actions" v-else-if="selectedAsset.type === 'audio'">
              <button type="button" :class="{ active: project.audioAssetId === selectedAsset.id }" @click="assignAsset('audio', selectedAsset.id)">Основная озвучка</button>
              <button type="button" :class="{ active: project.musicAssetId === selectedAsset.id }" @click="assignAsset('music', selectedAsset.id)">Музыка</button>
            </div>
            <button v-if="project.musicAssetId === selectedAsset.id" type="button" class="open-music-mixer" @click="select('music')">
              ♫ Настроить громкость и эквалайзер
            </button>
            <div class="role-actions" v-else-if="selectedAsset.type === 'video'">
              <button type="button" class="wide" :class="{ active: project.videoAssetId === selectedAsset.id }" @click="assignAsset('video', selectedAsset.id)">Видео сцены</button>
            </div>
            <p v-if="selectedAsset.status === 'uploading'" class="sync-note">Синхронизация с движком: {{ Math.round((selectedAsset.progress || 0) * 100) }}%</p>
            <p v-else-if="selectedAsset.status === 'local'" class="sync-note warning">Файл доступен для предпросмотра, но экспорт потребует запущенный backend.</p>
            <button type="button" class="danger-button" @click="removeAsset(selectedAsset.id)">Убрать из проекта</button>
          </template>

          <template v-else-if="selectedChapter">
            <label class="field-label">Название главы
              <input v-model.trim="selectedChapter.title" type="text" />
            </label>
            <label class="field-label">Начало
              <input :value="formatTimecode(selectedChapter.start_seconds)" type="text" placeholder="00:00:00"
                     @change="updateChapterStart(selectedChapter, $event.target.value)" />
            </label>
            <div class="readonly-row"><span>Конец</span><b>{{ formatTimecode(chapterEnd(selectedChapter.id)) }}</b></div>
            <div class="readonly-row"><span>Длительность</span><b>{{ formatTime(chapterEnd(selectedChapter.id) - selectedChapter.start_seconds, true) }}</b></div>
            <label class="field-label">Целевая длительность
              <input :value="formatTimecode(chapterTimelineDuration(selectedChapter))" type="text" placeholder="00:03:00"
                     @change="setChapterDuration(selectedChapter, $event.target.value)" />
            </label>
            <div class="readonly-row"><span>Скорость озвучки</span><b>×{{ chapterPlaybackRate(selectedChapter).toFixed(2) }}</b></div>
            <p class="sync-note">Потяните жёлтый правый край главы: влево — быстрее, вправо — медленнее.</p>
            <button v-if="Math.abs(chapterPlaybackRate(selectedChapter) - 1) > 0.001" type="button"
                    class="jump-button" @click="resetChapterPlaybackRate(selectedChapter)">Вернуть скорость ×1</button>
            <label class="field-label">Изображение главы
              <select v-model="selectedChapter.imageAssetId">
                <option :value="null">Общий фон проекта</option>
                <option v-for="image in imageAssets" :key="image.id" :value="image.id">{{ image.name }}</option>
              </select>
            </label>
            <button type="button" class="jump-button" @click="seekTo(selectedChapter.start_seconds)">Перейти к главе</button>
            <div class="visual-insert-inspector">
              <b>Визуальные вставки</b>
              <button type="button" class="jump-button" @click="openVisualPicker(selectedChapter)">＋ Иллюстрация</button>
              <button type="button" class="jump-button" :disabled="visualPlacementBusy" @click="autoPlaceVisuals(selectedChapter)">Авторасставить визуалы</button>
              <button v-for="insert in selectedChapter.visualInserts || []" :key="insert.id" type="button"
                      class="visual-insert-row" @click="select('visual', insert.id)">
                <img :src="assetThumbnailUrl(assetById(insert.assetId))" loading="lazy" decoding="async" alt="" />
                <span>{{ assetById(insert.assetId)?.name || 'Изображение не найдено' }}<small>{{ formatTime(insert.startTime, true) }} · {{ formatTime(insert.duration, true) }}</small></span>
              </button>
            </div>
            <button type="button" class="danger-button" @click="removeChapter(selectedChapter.id)">Удалить главу</button>
          </template>

          <template v-else-if="selectedVisual">
            <div class="readonly-row"><span>Режим</span><b>Иллюстрация</b></div>
            <label class="field-label">Изображение
              <select v-model="selectedVisual.assetId" aria-label="Изображение вставки">
                <option v-for="image in imageAssets" :key="image.id" :value="image.id">{{ image.name }}</option>
              </select>
            </label>
            <label class="field-label">Глава
              <select :value="selectedVisualChapter.id" @change="moveVisualToChapter($event.target.value)">
                <option v-for="chapter in timelineChapters" :key="chapter.id" :value="chapter.id">{{ chapter.title }}</option>
              </select>
            </label>
            <label class="field-label">Появление от начала главы
              <TimecodeField :value="visualTimecode(selectedVisual.startTime)" aria-label="Появление иллюстрации"
                             @commit="updateVisualTime('startTime', $event)" />
            </label>
            <label class="field-label">Длительность
              <TimecodeField :value="visualTimecode(selectedVisual.duration)" aria-label="Длительность иллюстрации"
                             @commit="updateVisualTime('duration', $event)" />
            </label>
            <label class="field-label">Переход
              <select v-model="selectedVisual.transition"><option value="crossfade">Плавное появление</option><option value="none">Без перехода</option></select>
            </label>
            <label v-if="selectedVisual.transition !== 'none'" class="field-label">Длительность перехода, с
              <input v-model.number="selectedVisual.transitionDuration" type="number" min="0.5" max="1" step="0.1" />
            </label>
            <p class="sync-note">Вставка ограничена своей главой. После неё возвращается обычная сцена.</p>
            <p v-if="selectedVisualIssue" class="sync-note">{{ selectedVisualIssue }}</p>
            <button type="button" class="jump-button" @click="seekTo(selectedVisualChapter.start_seconds + selectedVisual.startTime)">Перейти к иллюстрации</button>
            <button type="button" class="jump-button" @click="openVisualPicker(selectedVisualChapter)">＋ Иллюстрация</button>
            <button type="button" class="danger-button" @click="removeVisual(selectedVisual.id)">Удалить иллюстрацию</button>
          </template>

          <template v-else-if="selectedScene">
            <label class="field-label">Название сцены
              <input v-model.trim="selectedScene.name" type="text" />
            </label>
            <div class="field-grid">
              <label class="field-label">Начало
                <input :value="formatTimecode(selectedScene.start)" type="text" @change="updateSceneTime('start', $event.target.value)" />
              </label>
              <label class="field-label">Конец
                <input :value="formatTimecode(selectedScene.end)" type="text" @change="updateSceneTime('end', $event.target.value)" />
              </label>
            </div>
            <label class="field-label">Фон сцены
              <select v-model="selectedScene.backgroundAssetId">
                <option :value="null">Фон проекта</option>
                <option v-for="image in imageAssets" :key="image.id" :value="image.id">{{ image.name }}</option>
              </select>
            </label>
            <button type="button" class="jump-button" @click="seekTo(selectedScene.start)">Перейти к сцене</button>
            <button type="button" class="jump-button" :disabled="!visualTargetChapter" @click="openVisualPicker()">＋ Иллюстрация</button>
            <button type="button" class="danger-button" :disabled="project.scenes.length <= 1" @click="removeScene(selectedScene.id)">Удалить сцену</button>
          </template>

          <template v-else-if="selectedLayer">
            <label class="switch-row solo-switch">
              <span><b>Слой видим</b><small>{{ layerLabel(selection.id) }}</small></span>
              <input v-model="selectedLayer.visible" type="checkbox" /><i></i>
            </label>
            <div class="field-grid geometry-grid">
              <label class="field-label">X, %<input v-model.number="selectedLayer.x" type="number" min="0" max="100" step="0.5" /></label>
              <label class="field-label">Y, %<input v-model.number="selectedLayer.y" type="number" min="0" max="100" step="0.5" /></label>
              <label class="field-label">Ширина, %<input v-model.number="selectedLayer.w" type="number" min="5" max="100" step="0.5" /></label>
              <label class="field-label">Высота, %<input v-model.number="selectedLayer.h" type="number" min="5" max="100" step="0.5" /></label>
            </div>
            <p class="layer-drag-hint">Перетаскивайте слой за середину, ширину — за правый край, высоту — за нижний. Угол меняет оба размера.</p>
            <template v-if="selection.id === 'title'">
              <label class="field-label">Размер текста
                <input v-model.number="selectedLayer.fontSize" type="range" min="24" max="72" step="1" />
              </label>
              <label class="field-label color-field">Цвет заголовка
                <input v-model="selectedLayer.color" type="color" />
                <span>{{ selectedLayer.color }}</span>
              </label>
            </template>
            <template v-if="selection.id === 'visualizer'">
              <span class="section-title">Цветовой пресет</span>
              <div class="visualizer-preset-grid">
                <button v-for="preset in VISUALIZER_COLOR_PRESETS" :key="preset.id" type="button"
                        :class="{ active: selectedLayer.colorPreset === preset.id }"
                        :title="preset.label" @click="setVisualizerColorPreset(preset.id)">
                  <i :style="{ background: visualizerPresetGradient(preset) }"></i>
                  <span>{{ preset.label }}</span>
                </button>
              </div>
            </template>
            <template v-if="selection.id === 'caption'">
              <label class="field-label">Размер текста
                <input v-model.number="selectedLayer.fontSize" type="range" min="16" max="48" step="1" />
              </label>
              <label class="field-label">Начертание
                <select v-model.number="selectedLayer.fontWeight">
                  <option :value="300">Тонкое</option>
                  <option :value="400">Обычное</option>
                  <option :value="500">Среднее</option>
                </select>
              </label>
              <label class="field-label">Плотность подложки
                <input v-model.number="selectedLayer.backgroundOpacity" type="range" min="0" max="0.9" step="0.05" />
              </label>
            </template>
            <button type="button" class="jump-button" @click="resetLayer(selection.id)">Вернуть положение</button>
          </template>
        </div>
      </aside>
    </main>

    <main v-else class="book-workspace" :class="`inspector-${bookInspectorState}`">
      <aside v-if="activeProjectRecord?.book" class="book-chapters panel-shell">
        <div class="panel-heading">
          <div><span class="eyebrow">Структура</span><h2>Главы</h2></div>
          <span class="count-badge">{{ filteredBookChapters.length }}</span>
        </div>
        <label class="book-search">
          <span>⌕</span>
          <input v-model.trim="bookSearch" type="search" placeholder="Поиск по книге" />
        </label>
        <div class="chapter-book-list">
          <button v-for="chapter in filteredBookChapters" :key="chapter.id" type="button"
                  :style="chapterAccent(chapter)"
                  draggable="true"
                  :class="{
                    active: currentBookChapter?.id === chapter.id,
                    dragging: draggedBookChapterId === chapter.id,
                    'drop-before': bookChapterDropId === chapter.id && bookChapterDropSide === 'before',
                    'drop-after': bookChapterDropId === chapter.id && bookChapterDropSide === 'after',
                  }"
                  @dragstart="startBookChapterDrag(chapter, $event)"
                  @dragover.prevent="onBookChapterDragOver(chapter, $event)"
                  @drop.prevent="dropBookChapter(chapter)"
                  @dragend="finishBookChapterDrag"
                  @click="selectBookChapter(chapter.id)">
            <span class="chapter-order-handle" title="Перетащить главу"><i>⠿</i>{{ chapterDisplayNumber(chapter) }}</span>
            <b>{{ chapter.title }}</b>
            <small>{{ chapterStats.get(chapter.id) }}</small>
            <i class="chapter-audio-state"
               :class="chapterAudioState(chapter)"
               :title="chapterAudioStateLabel(chapter)"></i>
          </button>
        </div>
        <div class="chapter-list-actions">
          <button class="chapter-add" type="button" @click="createBookChapter">＋ Глава</button>
          <button v-if="!hasBookChapterZero" class="chapter-zero-add" type="button" title="Добавить вступление перед первой главой"
                  @click="createBookZeroChapter">＋ Глава 0</button>
          <button type="button" :disabled="!currentBookChapter" @click="moveBookChapter(-1)">↑</button>
          <button type="button" :disabled="!currentBookChapter" @click="moveBookChapter(1)">↓</button>
          <button type="button" :disabled="!currentBookChapter" title="Дублировать главу" @click="duplicateBookChapter">⧉</button>
          <button class="danger" type="button" :disabled="!currentBookChapter" @click="archiveBookChapter">Удалить</button>
        </div>
      </aside>

      <section v-if="activeProjectRecord?.book" class="book-editor-column">
        <header class="book-editor-header">
          <div>
            <span class="eyebrow">{{ activeProjectRecord.book.author || activeProjectRecord.author || 'Без автора' }}</span>
            <h1>{{ activeProjectRecord.book.title }}</h1>
          </div>
          <div class="book-editor-head-actions">
            <span class="book-save-indicator" :class="bookSaveState"><i></i>{{ bookSaveLabel }}</span>
            <button v-if="bookInspectorState === 'hidden'" type="button" @click="setBookInspectorState('open')">Настройки</button>
          </div>
        </header>
        <template v-if="currentBookChapter">
          <input v-model="bookChapterTitle" class="book-chapter-title" type="text"
                 aria-label="Название главы" @input="markBookDirty" />
          <details class="chapter-tech-panel" :open="bookChapterTechEnabled">
            <summary>
              <span><b>Служебный канон главы</b><small>Не попадёт в озвучку, читательский экспорт и объём главы</small></span>
              <i>{{ bookChapterTechEnabled ? 'TECH включён' : 'Не используется' }}</i>
            </summary>
            <button v-if="!bookChapterTechEnabled" class="book-secondary" type="button" @click="enableChapterTech">
              Добавить TECH-блок
            </button>
            <template v-else>
              <textarea v-model="bookChapterTechnicalContent" class="chapter-tech-editor" rows="12"
                        spellcheck="true" aria-label="Служебный канон главы"
                        @input="markBookDirty"></textarea>
              <div class="chapter-tech-actions">
                <small>Локальный канон имеет приоритет для анализа и иллюстраций.</small>
                <button class="danger" type="button" @click="removeChapterTech">Удалить TECH-блок</button>
              </div>
            </template>
          </details>
          <div class="book-text-editor-wrap">
            <div ref="bookHighlightLayer" class="book-text-highlights" aria-hidden="true" v-html="bookHighlightedHtml"></div>
            <textarea ref="bookTextEditor" v-model="bookChapterContent" class="book-text-editor"
                      spellcheck="true" aria-label="Текст главы" placeholder="Начните писать…"
                      @select="captureBookSelection" @click="openMetaNoteAtCaret" @scroll="syncBookHighlightScroll" @input="onBookTextInput"></textarea>
            <button v-if="bookSelection" class="meta-note-selection-action" type="button"
                    @mousedown.prevent @click="createMetaNote">Мета-пометка</button>
            <div v-if="activeMetaNote" class="meta-note-popover">
              <textarea v-model="activeMetaNoteDraft" rows="3" aria-label="Комментарий мета-пометки"></textarea>
              <button type="button" @click="saveActiveMetaNote">Сохранить</button>
              <button class="danger" type="button" @click="deleteMetaNote(activeMetaNote)">Удалить</button>
              <button type="button" @click="activeMetaNoteId = null">×</button>
            </div>
          </div>
          <div v-if="bookPlayerAsset" class="book-audio-player"
               :draggable="!bookPlayerAsset.temporary"
               @dragstart="startBookAudioDrag(bookPlayerAsset, $event)">
            <div class="book-player-copy">
              <small>{{ bookPlayerAsset.temporary ? 'Проба голоса' : 'Озвучка главы' }}</small>
              <b>{{ bookPlayerTitle }}</b>
              <span>{{ formatTime(bookPlayerDuration, true) }} · {{ voiceShortLabel(bookPlayerAsset.voice) }}</span>
            </div>
            <audio ref="bookAudioEl" :src="bookPlayerUrl" controls preload="metadata"
                   @loadedmetadata="onBookAudioMetadata" @timeupdate="onBookAudioTime" @seeked="onBookAudioTime"
                   @play="onBookAudioPlay" @pause="onBookAudioPause" @ended="onBookAudioEnded"></audio>
            <button type="button" :disabled="!previousChapterAudio" title="Предыдущая версия" @click="selectAdjacentChapterAudio(-1)">‹</button>
            <button type="button" :disabled="!nextChapterAudio" title="Следующая версия" @click="selectAdjacentChapterAudio(1)">›</button>
            <button v-if="!bookPlayerAsset.temporary" class="book-player-video" type="button"
                    title="Передать эту озвучку в видео"
                    @click="sendBookAudioToVideo(bookPlayerAsset)">→ В видео</button>
            <button v-if="!bookPlayerAsset.temporary" class="book-player-download" type="button"
                    :disabled="chapterAudioExportBusy"
                    title="Собрать голос и музыку без видео"
                    @click="downloadCurrentChapterAudio">
              {{ chapterAudioExportBusy ? 'Собираем аудио…' : '⇩ Скачать аудио' }}
            </button>
            <div v-if="!bookPlayerAsset.temporary && bookPlayerWords.length" class="book-word-navigator"
                 draggable="false" @dragstart.stop.prevent>
              <header>
                <b>Текст озвучки</b>
                <small :class="{ exact: bookPlayerHasExactTimings }">
                  {{ bookPlayerHasExactTimings ? `точная синхронизация · ${bookPlayerTimingCoveragePercent}%` : 'нет точных меток — переозвучьте главу' }}
                </small>
              </header>
              <div ref="bookWordNavigatorCopy" class="book-word-navigator-copy">
                <p v-for="paragraph in bookPlayerParagraphs" :key="paragraph[0]?.paragraph"
                   v-memo="[paragraph, bookPlayerHasExactTimings]">
                  <button v-for="word in paragraph" :key="word.index" type="button"
                          :data-word-index="word.index" :disabled="!bookPlayerHasExactTimings"
                          :title="bookPlayerHasExactTimings ? `${formatTime(word.start, true)} · перейти к слову` : 'Для точной навигации переозвучьте главу'"
                          @click="seekBookPlayer(word.start)">{{ word.text }}</button>
                </p>
              </div>
            </div>
          </div>
          <footer class="book-editor-footer">
            <span>{{ bookWordCount.toLocaleString('ru-RU') }} слов</span>
            <span>{{ bookChapterContent.length.toLocaleString('ru-RU') }} символов</span>
            <span v-if="currentChapterAudio" :class="{ stale: currentChapterAudio.is_stale }">
              {{ currentChapterAudio.is_stale ? 'Озвучка устарела' : 'Озвучка актуальна' }}
            </span>
          </footer>
        </template>
        <div v-else class="book-empty-chapter">
          <span>✦</span><h2>У книги пока нет глав</h2>
          <p>Создайте первую главу и сразу начинайте писать.</p>
          <button type="button" @click="createBookChapter">Создать главу</button>
        </div>
      </section>

      <aside v-if="activeProjectRecord?.book && bookInspectorState !== 'hidden'"
             class="book-inspector panel-shell" :class="{ collapsed: bookInspectorState === 'collapsed' }">
        <button v-if="bookInspectorState === 'collapsed'" class="book-inspector-rail" type="button"
                @click="setBookInspectorState('open')"><span>Настройки</span></button>
        <template v-else>
          <div class="panel-heading">
            <div><span class="eyebrow">Книга</span><h2>Инспектор</h2></div>
            <div class="inspector-toggle-actions">
              <button type="button" title="Свернуть" @click="setBookInspectorState('collapsed')">›</button>
              <button type="button" title="Скрыть" @click="setBookInspectorState('hidden')">×</button>
            </div>
          </div>
          <div class="book-inspector-scroll">
            <details>
              <summary>Книга</summary>
              <label class="field-label">Название
                <input v-model="bookMetadata.title" type="text" @change="saveBookMetadata" />
              </label>
              <label class="field-label">Автор
                <input v-model="bookMetadata.author" type="text" @change="saveBookMetadata" />
              </label>
              <label class="field-label">Описание
                <textarea v-model="bookMetadata.description" rows="4" @change="saveBookMetadata"></textarea>
              </label>
            </details>

            <details open>
              <summary>Изображение главы</summary>
              <div class="book-chapter-image-panel">
                <div class="book-chapter-image-preview"
                     :style="currentBookChapterImageUrl ? { backgroundImage: `url('${currentBookChapterImageUrl}')` } : {}">
                  <span v-if="!currentBookChapterImageUrl">У главы пока нет отдельной картинки</span>
                </div>
                <p>В видеокниге изображение этой главы заменит общий фон на время её озвучки.</p>
                <div>
                  <button class="book-primary" type="button" :disabled="!currentBookChapter"
                          @click="bookChapterImageInput?.click()">
                    {{ currentBookChapterImage ? 'Заменить картинку' : 'Выбрать картинку' }}
                  </button>
                  <button v-if="currentBookChapterImage" class="book-secondary" type="button"
                          @click="removeBookChapterImage">Убрать</button>
                </div>
              </div>
            </details>

            <details open>
              <summary>Мета-пометки · {{ currentMetaNotes.length }}</summary>
              <div class="book-meta-list">
                <p v-if="!currentMetaNotes.length">Выделите текст в редакторе и нажмите «Мета-пометка».</p>
                <article v-for="item in currentMetaNotes" :key="item.id" :class="{ detached: item.detached }">
                  <button type="button" @click="focusMetaNote(item)"><i :style="{ background: metaColor(item.colorIndex) }"></i><span><b>{{ item.note }}</b><small>{{ item.detached ? 'Привязка потеряна' : `«${item.selectedText}»` }}</small></span></button>
                  <button type="button" title="Изменить" @click="editMetaNote(item)">✎</button>
                  <button class="danger" type="button" title="Удалить" @click="deleteMetaNote(item)">×</button>
                </article>
              </div>
            </details>

            <details open>
              <summary>Версии текста · {{ currentTextVersions.length }}</summary>
              <p class="current-text-version">● Текущий рабочий текст</p>
              <button class="book-primary text-version-save" type="button" :disabled="!currentBookChapter" @click="saveTextVersion">Сохранить версию</button>
              <div class="book-version-list">
                <article v-for="item in currentTextVersions" :key="item.id" :class="{ active: viewedTextVersion?.id === item.id }">
                  <button type="button" @click="viewTextVersion(item)"><b>{{ item.title || `Версия ${item.id}` }}</b><small>{{ formatProjectDate(item.created_at) }}</small></button>
                  <button type="button" title="Восстановить" @click="restoreTextVersion(item)">↶</button>
                  <button type="button" title="Переименовать" @click="renameTextVersion(item)">✎</button>
                  <button class="danger" type="button" title="Удалить" @click="deleteTextVersion(item)">×</button>
                </article>
                <div v-if="viewedTextVersion" class="text-version-preview"><b>{{ viewedTextVersion.title || 'Сохранённая версия' }}</b><pre>{{ viewedTextVersion.content }}</pre></div>
              </div>
            </details>

            <details open>
              <summary>Озвучка</summary>
              <div class="book-tts-panel">
                <p class="tts-runtime-state" :class="{ unavailable: !ttsRuntime.available }">
                  <i></i>{{ ttsRuntime.message || 'Проверяю компонент озвучки…' }}
                </p>
                <div class="voice-filters">
                  <input v-model.trim="voiceSearch" type="search" placeholder="Поиск голоса" />
                  <select v-model="voiceLanguage">
                    <option value="">Все языки</option>
                    <option v-for="language in voiceLanguages" :key="language" :value="language">{{ language.toUpperCase() }}</option>
                  </select>
                </div>
                <label class="field-label">Голос
                  <select v-model="bookTts.voice" @change="saveBookTtsSettings">
                    <option v-for="voice in filteredTtsVoices" :key="voice.id" :value="voice.id">
                      {{ voiceLabel(voice) }}
                    </option>
                  </select>
                </label>
                <div class="field-grid">
                  <label class="field-label">Скорость
                    <select v-model="bookTts.rate" @change="saveBookTtsSettings">
                      <option v-for="preset in RATE_PRESETS" :key="preset.value" :value="preset.value">{{ preset.label }} · {{ preset.value }}</option>
                    </select>
                  </label>
                  <label class="field-label">Высота тона
                    <select v-model="bookTts.pitch" @change="saveBookTtsSettings">
                      <option v-for="preset in PITCH_PRESETS" :key="preset.value" :value="preset.value">{{ preset.label }} · {{ preset.value }}</option>
                    </select>
                  </label>
                </div>
                <label class="field-label">Громкость синтеза
                  <select v-model="bookTts.volume" @change="saveBookTtsSettings">
                    <option v-for="preset in VOLUME_PRESETS" :key="preset.value" :value="preset.value">{{ preset.label }} · {{ preset.value }}</option>
                  </select>
                </label>
                <div class="book-tts-actions">
                  <button class="book-secondary" type="button" :disabled="previewLoading || !ttsRuntime.available" @click="previewBookVoice">
                    {{ previewLoading ? 'Создаю пробу…' : 'Проба голоса' }}
                  </button>
                  <button class="book-primary" type="button" :disabled="!currentBookChapter || activeTtsJobRunning || !ttsRuntime.available" @click="narrateCurrentChapter">
                    {{ activeTtsJobRunning ? 'Озвучивание идёт…' : 'Озвучить главу' }}
                  </button>
                  <button class="book-secondary" type="button" :disabled="activeTtsJobRunning || !ttsRuntime.available" @click="narrateWholeBook">
                    Озвучить всю книгу
                  </button>
                </div>
                <div v-if="ttsProgressState.active" class="tts-progress-card">
                  <div class="tts-progress-heading">
                    <span><i></i><b>Озвучивание</b></span>
                    <strong>{{ ttsProgressState.percent }}%</strong>
                  </div>
                  <div class="tts-progress-track">
                    <i :style="{ width: `${ttsProgressState.percent}%` }"></i>
                  </div>
                  <p>{{ ttsProgressState.detail }}</p>
                  <p v-if="ttsProgressState.stage" class="tts-progress-stage">{{ ttsProgressState.stage }}</p>
                  <small>Прошло {{ formatTime(ttsProgressState.elapsed, true) }}</small>
                  <div class="tts-progress-actions">
                    <button class="tts-stop-button" type="button" :disabled="ttsStopping" @click="cancelActiveTtsJobs">
                      {{ ttsStopping ? 'Останавливаю…' : 'Остановить' }}
                    </button>
                    <button type="button" @click="ttsLogOpen = !ttsLogOpen">
                      {{ ttsLogOpen ? 'Скрыть диагностику' : 'Показать диагностику' }}
                    </button>
                  </div>
                </div>
                <p v-else-if="latestTtsJob" class="book-job-state" :class="latestTtsJob.status">
                  {{ ttsJobLabel(latestTtsJob) }}
                  <button v-if="latestTtsJob.status === 'failed'" type="button" @click="narrateCurrentChapter">Повторить</button>
                  <button type="button" @click="ttsLogOpen = !ttsLogOpen">Диагностика</button>
                  <button v-if="latestTtsJob.status === 'failed'" type="button" @click="openTtsLog">Открыть полный лог</button>
                </p>
                <div v-if="visibleTtsJobs.length > 1" class="tts-batch-list">
                  <button v-for="job in visibleTtsJobs.slice(0, 20)" :key="job.uuid" type="button"
                          :class="[job.status, { selected: ttsDebugJob?.uuid === job.uuid }]"
                          @click="selectTtsDebugJob(job)">
                    <b>{{ chapterTitleForJob(job) }}</b><i>{{ ttsJobShortLabel(job.status) }}</i>
                  </button>
                </div>
                <div v-if="ttsLogOpen && ttsDebugJob" class="tts-debug-console">
                  <div class="tts-debug-heading">
                    <span><b>{{ chapterTitleForJob(ttsDebugJob) }}</b><small>{{ ttsDebugJob.stage || ttsJobShortLabel(ttsDebugJob.status) }}</small></span>
                    <button type="button" @click="openTtsLog">Полный лог ↗</button>
                  </div>
                  <div class="tts-debug-meta">
                    <span>Задача {{ ttsDebugJob.uuid.slice(0, 8) }}</span>
                    <span>{{ ttsDebugJob.progress_done || 0 }}/{{ ttsDebugJob.progress_total || 1 }} фрагм.</span>
                    <span v-if="ttsDebugJob.last_activity_at">событие {{ formatTtsDiagnosticTime(ttsDebugJob.last_activity_at) }}</span>
                  </div>
                  <pre v-if="ttsDebugEvents.length">{{ ttsDebugEvents.map(formatTtsDiagnosticEvent).join('\n') }}</pre>
                  <p v-else>Диагностических событий пока нет.</p>
                  <p v-if="ttsDebugJob.error" class="tts-debug-error">{{ ttsDebugJob.error }}</p>
                </div>
              </div>
            </details>

            <details open>
              <summary>Аудио · {{ currentChapterAudios.length }}</summary>
              <div class="book-audio-list">
                <p v-if="!currentChapterAudios.length" class="book-audio-empty">
                  У текущей главы пока нет озвучки. Выберите голос и создайте первую версию.
                </p>
                <article v-for="asset in currentChapterAudios" :key="asset.id" draggable="true"
                         @dragstart="startBookAudioDrag(asset, $event)"
                         :class="{ active: asset.is_active, stale: asset.is_stale, selected: selectedBookAudioId === asset.id }">
                  <button class="audio-version-main" type="button" @click="selectBookAudio(asset, true)">
                    <span><b>{{ asset.title || `Версия ${asset.version_number}` }}</b><i v-if="asset.is_active">активная</i></span>
                    <small>{{ formatProjectDate(asset.created_at) }} · {{ voiceShortLabel(asset.voice) }}</small>
                    <small>{{ asset.rate }} · {{ pitchPresetLabel(asset.pitch) }} · {{ formatTime(asset.duration || 0, true) }}</small>
                    <em v-if="asset.is_stale">Озвучка устарела</em>
                  </button>
                  <div class="audio-version-actions">
                    <button type="button" title="Воспроизвести" @click="selectBookAudio(asset, true)">▶</button>
                    <button type="button" :disabled="asset.is_active" title="Сделать активной" @click="activateBookAudio(asset)">✓</button>
                    <button type="button" title="Переименовать" @click="renameBookAudio(asset)">✎</button>
                    <button type="button" title="Открыть папку" @click="openBookAudioFolder(asset)">⌞</button>
                    <button type="button" title="Передать в видео" @click="sendBookAudioToVideo(asset)">→</button>
                    <button class="danger" type="button" title="Удалить версию" @click="deleteBookAudio(asset)">×</button>
                  </div>
                </article>
              </div>
            </details>

            <details>
              <summary>Экспорт</summary>
              <div class="book-inspector-export">
                <button class="book-primary" type="button" @click="openBookExport">⇩ Скачать книгу</button>
                <button class="book-secondary" type="button" @click="openBookInVideo">Передать в видеоредактор</button>
              </div>
            </details>
            <details>
              <summary>Техническое</summary>
              <button class="book-secondary" type="button" @click="openTtsLog">Открыть журнал TTS</button>
              <p class="technical-note">Интерпретатор: {{ backend.python || 'не определён' }}</p>
            </details>
          </div>
        </template>
      </aside>

      <section v-else class="book-missing">
        <span class="loading-mark"><i></i><i></i><i></i></span>
        <h1>{{ activeProjectRecord ? 'У этого видеопроекта нет текстовой книги' : 'Выберите проект' }}</h1>
        <p v-if="activeProjectRecord">Текстовую часть можно добавить, не затрагивая существующий таймлайн и медиа.</p>
        <button v-if="activeProjectRecord" type="button" @click="createBookPart">Создать текстовую часть</button>
        <button v-else type="button" @click="createBookProject">Новая книга</button>
      </section>
    </main>

    <audio ref="audioEl" :src="audioSource" crossorigin="anonymous" preload="metadata"
           @loadedmetadata="onMediaMetadata('audio')" @timeupdate="onMediaTime('audio')"
           @play="onMediaPlay('audio')" @pause="onMediaPause('audio')"
           @ended="onMediaEnded('audio')" @error="onMediaError('audio')"></audio>
    <audio ref="musicEl" :src="musicSource" crossorigin="anonymous" preload="metadata"
           :loop="project.music.loop" @loadedmetadata="onMusicMetadata"
           @play="musicPreviewPlaying = true" @pause="musicPreviewPlaying = false"
           @error="onMusicError"></audio>

    <section v-if="activeWorkspace === 'video'" class="timeline-panel">
      <div class="timeline-resizer" role="separator" aria-label="Изменить высоту таймлайна"
           aria-orientation="horizontal" :aria-valuenow="timelineHeight"
           @pointerdown="startTimelineResize" @dblclick="resetTimelineHeight"></div>
      <div class="timeline-toolbar">
        <div class="timeline-title">
          <span class="eyebrow">Монтаж</span>
          <strong>Таймлайн</strong>
          <span>{{ formatTime(duration, true) }}</span>
        </div>
        <div class="timeline-actions">
          <button type="button" class="text-navigator-shortcut" @click="openTextNavigator">
            <span>¶</span> Текст книги
          </button>
          <button type="button" class="music-mixer-shortcut"
                  :class="{ active: selection.type === 'music', empty: !displayMusicClips.length }"
                  @click="select('music')">
            <span>♫</span>{{ displayMusicClips.length ? `Музыка · ${displayMusicClips.length} клип.` : 'Добавить музыку' }}
          </button>
          <button type="button" class="magnet-toggle" :class="{ active: timelineSnap }"
                  :aria-pressed="timelineSnap"
                  :title="`Магнит: ${timelineSnap ? 'включён' : 'выключен'} (N)`"
                  @click="toggleTimelineSnap">
            <span class="magnet-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24"><path d="M5 3.5v8.2a7 7 0 0 0 14 0V3.5M5 8.5h5v3.2a2 2 0 0 0 4 0V8.5h5M5 3.5h5v5H5zM14 3.5h5v5h-5z" /></svg>
            </span>
            <b>Магнит</b><small>{{ timelineSnap ? 'Вкл' : 'Выкл' }}</small>
          </button>
          <button type="button" :disabled="!selectedMusicClip" title="Разрезать выбранный клип по курсору (S)"
                  @click="splitSelectedMusicClip"><span>✂</span> Разрезать</button>
          <button type="button" @click="addChapterAtCursor"><span>＋</span> Глава</button>
          <button type="button" @click="addSceneAtCursor"><span>＋</span> Сцена</button>
          <button type="button" :disabled="!visualTargetChapter" @click="openVisualPicker()"><span>＋</span> Визуал</button>
          <button type="button" :disabled="!project.chapters.length || visualPlacementBusy" @click="autoPlaceVisuals()">Авторасставить визуалы</button>
          <label class="zoom-control" title="Масштаб таймлайна"><span>−</span><input v-model.number="timelineZoom" type="range" min="1" max="12" step="0.25" /><span>＋</span></label>
        </div>
      </div>

      <nav class="chapter-navigation" aria-label="Навигация по главам">
        <button type="button" :class="{ active: chapterTimelineMode }" :aria-pressed="chapterTimelineMode"
                @click="toggleChapterTimelineMode">По главам</button>
        <button type="button" :disabled="!previousChapter" aria-label="Предыдущая глава" @click="navigateChapter(previousChapter)">‹</button>
        <select :value="currentChapter?.id || ''" aria-label="Глава на таймлайне"
                :style="chapterAccent(currentChapter)" @change="navigateChapter(timelineChapters.find(chapter => chapter.id === $event.target.value))">
          <option v-for="chapter in timelineChapters" :key="chapter.id" :value="chapter.id">{{ chapter.index + 1 }}. {{ chapter.title }}</option>
        </select>
        <button type="button" :disabled="!nextChapter" aria-label="Следующая глава" @click="navigateChapter(nextChapter)">›</button>
        <span>{{ currentChapter ? `${formatTime(currentChapter.start_seconds, true)}–${formatTime(currentChapter.end_seconds, true)}` : 'Нет глав' }}</span>
        <button v-if="activeProjectRecord?.book" type="button" @click="openBookExport">⇩ MP3 книги</button>
      </nav>

      <div ref="timelineScroll" class="timeline-scroll"
           @scroll.passive="updateTimelineViewport"
           @wheel="onTimelineWheel" @pointerdown="startTimelinePan">
        <div class="timeline-labels">
          <div class="ruler-spacer"></div>
          <div><span class="track-icon chapter"></span>Главы</div>
          <div><span class="track-icon scene"></span>Сцены</div>
          <div><span class="track-icon insert"></span>Визуальные вставки</div>
          <div><span class="track-icon visual"></span>Видео / фон</div>
          <div><span class="track-icon audio"></span>Аудио</div>
          <button type="button" class="timeline-track-button" @click="select('music')">
            <span class="track-icon music"></span>
            <span>Музыка<small>{{ displayMusicClips.length ? `${displayMusicClips.length} клип.` : 'перетащите сюда' }}</small></span>
          </button>
        </div>
        <div ref="timelineContent" class="timeline-content"
             :class="{ scrubbing: timelineScrubbing, 'snap-enabled': timelineSnap }"
             :style="timelineContentStyle">
          <div class="timeline-ruler" @pointerdown="startTimelineScrub">
            <span v-for="mark in rulerMarks" :key="mark.p" :style="{ left: `${mark.p}%` }"><i></i>{{ mark.label }}</span>
          </div>

          <div class="timeline-lane chapter-lane" @pointerdown.self="startTimelineScrub">
            <button v-for="chapter in visibleTimelineChapters" :key="chapter.id" type="button" class="chapter-clip"
                    v-memo="[chapter.title, chapter.start_seconds, chapter.end_seconds, chapterPlaybackRate(chapter), currentChapter?.id === chapter.id, selection.type === 'chapter' && selection.id === chapter.id]"
                    :class="{ active: currentChapter?.id === chapter.id, selected: selection.type === 'chapter' && selection.id === chapter.id }"
                    :style="{ ...clipStyle(chapter.start_seconds, chapter.end_seconds, 0.35), ...chapterAccent(chapter) }"
                    @click.stop="selectChapter(chapter)">
              <i v-if="chapter.index > 0" class="chapter-boundary-handle" title="Переместить границу главы"
                 @pointerdown.stop="startChapterBoundaryPointer($event, chapter)"></i>
              <b>{{ chapter.title }}</b>
              <small v-if="Math.abs(chapterPlaybackRate(chapter) - 1) > 0.001">×{{ chapterPlaybackRate(chapter).toFixed(2) }}</small>
              <i class="chapter-stretch-handle" title="Растянуть главу: вправо — медленнее, влево — быстрее"
                 @pointerdown.stop="startChapterStretchPointer($event, chapter)"></i>
            </button>
          </div>

          <div class="timeline-lane scene-lane" @pointerdown.self="startTimelineScrub">
            <button v-for="scene in visibleTimelineScenes" :key="scene.id" type="button" class="scene-clip"
                    v-memo="[scene.name, scene.start, scene.end, currentScene?.id === scene.id, selection.type === 'scene' && selection.id === scene.id]"
                    :class="{ active: currentScene?.id === scene.id, selected: selection.type === 'scene' && selection.id === scene.id }"
                    :style="clipStyle(scene.start, scene.end, 0.8)"
                    @pointerdown.stop="startSceneClipPointer($event, scene, 'move')"
                    @click.stop="selectScene(scene)">
              <span class="scene-trim-handle start" @pointerdown.stop="startSceneClipPointer($event, scene, 'trim-start')"></span>
              <i></i><b>{{ scene.name }}</b>
              <span class="scene-trim-handle end" @pointerdown.stop="startSceneClipPointer($event, scene, 'trim-end')"></span>
            </button>
          </div>

          <div class="timeline-lane visual-inserts-lane" @pointerdown.self="startTimelineScrub">
            <button v-for="clip in visibleTimelineVisualClips" :key="clip.id" type="button" class="visual-insert-clip"
                    v-memo="[clip.assetId, clip.start, clip.end, selection.type === 'visual' && selection.id === clip.id, visualScene.visual?.id === clip.id]"
                    :class="{ selected: selection.type === 'visual' && selection.id === clip.id, active: visualScene.visual?.id === clip.id }"
                    :style="clipStyle(clip.start, clip.end, 0.15)"
                    :title="`${assetById(clip.assetId)?.name || 'Иллюстрация'} · ${clip.chapterTitle}`"
                    @pointerdown.stop="startVisualClipPointer($event, clip, 'move')"
                    @click.stop="select('visual', clip.id)">
              <span class="scene-trim-handle start" title="Изменить начало" @pointerdown.stop="startVisualClipPointer($event, clip, 'trim-start')"></span>
              <img v-if="showTimelineClipThumbnail(clip)" :src="assetThumbnailUrl(assetById(clip.assetId))"
                   loading="lazy" decoding="async" fetchpriority="low" alt="" />
              <b>{{ assetById(clip.assetId)?.name || 'Иллюстрация' }}</b>
              <span class="scene-trim-handle end" title="Изменить длительность" @pointerdown.stop="startVisualClipPointer($event, clip, 'trim-end')"></span>
            </button>
          </div>

          <div class="timeline-lane visual-lane" @pointerdown.self="startTimelineScrub">
            <button v-if="videoAsset || backgroundAsset" type="button" class="visual-clip"
                    :style="clipStyle(0, duration, 100)" @click.stop="select('asset', (videoAsset || backgroundAsset).id)">
              <span class="clip-thumb" :style="backgroundThumbnailStyle"></span>
              <b>{{ videoAsset?.name || backgroundAsset?.name }}</b><small>{{ videoAsset ? 'Видео сцены' : 'Фон проекта' }}</small>
            </button>
          </div>

          <div class="timeline-lane audio-lane" @pointerdown.self="startTimelineScrub">
            <button v-if="audioAsset" type="button" class="audio-clip master-audio-clip"
                    :style="clipStyle(0, duration, 100)" @click.stop="select('asset', audioAsset.id)">
              <span ref="timelineWaveform" class="timeline-waveform" aria-hidden="true">
                <i v-for="(bar, index) in timelineBars" :key="index" v-memo="[bar]" :style="{ height: `${bar * 100}%` }"></i>
              </span>
              <b>{{ audioAsset.name }}</b>
            </button>
            <button v-for="chapter in visibleVideoAudioChapters" :key="`audio-${chapter.id}`"
                    v-memo="[chapter.title, chapter.start_seconds, chapter.end_seconds, currentChapter?.id === chapter.id]"
                    type="button" class="chapter-audio-clip"
                    :class="{ active: currentChapter?.id === chapter.id }"
                    :style="clipStyle(chapter.start_seconds, chapter.end_seconds, 0.35)"
                    :title="`${chapter.title} · аудио прикреплено к границам главы`"
                    @click.stop="selectVideoChapterAudio(chapter)">
              <i></i><b>🔗 {{ chapter.title }}</b>
            </button>
          </div>

          <div class="timeline-lane music-lane" @pointerdown.self="startTimelineScrub"
               @dragover.prevent="onMusicLaneDragOver" @drop.prevent="dropMusicOnTimeline">
            <button v-for="clip in visibleMusicClips" :key="clip.id" type="button" class="music-clip"
                    v-memo="[clip.assetId, clip.start, clip.end, clip.enabled, project.music.enabled, selectedMusicClip?.id === clip.id]"
                    :class="{ muted: !project.music.enabled || clip.enabled === false, selected: selectedMusicClip?.id === clip.id }"
                    :style="clipStyle(clip.start, clip.end, 0.35)"
                    :title="`${assetById(clip.assetId)?.name || 'Музыка'} · перетаскивание: сдвиг, края: подрезка, Alt+клик: разрез`"
                    @pointerdown.stop="startMusicClipPointer($event, clip, 'move')"
                    @click.stop="selectMusicClip(clip)">
              <i class="music-trim-handle start" aria-hidden="true"
                 @pointerdown.stop="startMusicClipPointer($event, clip, 'trim-start')"></i>
              <span aria-hidden="true">♫</span>
              <b>{{ assetById(clip.assetId)?.name || 'Музыка' }}</b>
              <small>{{ clipChapterLabel(clip) }}</small>
              <i class="music-trim-handle end" aria-hidden="true"
                 @pointerdown.stop="startMusicClipPointer($event, clip, 'trim-end')"></i>
            </button>
            <p v-if="!displayMusicClips.length" class="music-lane-empty">Перетащите аудиофайл из материалов на нужную главу</p>
          </div>

          <i v-for="chapter in visibleTimelineChapters" :key="`grid-${chapter.id}`"
             v-memo="[chapter.title, chapter.start_seconds, duration]"
             class="timeline-chapter-grid-line" :style="{ left: `${chapter.start_seconds / Math.max(duration, 1) * 100}%` }"
             :title="chapter.title"></i>
          <div v-if="snapGuideTime !== null" class="timeline-snap-guide"
               :class="{ reverse: snapGuideTime / Math.max(duration, 1) > 0.82 }"
               :style="{ left: `${snapGuideTime / Math.max(duration, 1) * 100}%` }">
            <span>{{ snapGuideLabel }}</span>
          </div>
          <div v-if="selectedExcerpt" class="timeline-excerpt-range"
               :style="clipStyle(selectedExcerpt.start, selectedExcerpt.end, 0.2)"
               :title="`Выбранный текст · ${formatTime(selectedExcerpt.start)}–${formatTime(selectedExcerpt.end)}`"></div>
          <div ref="timelinePlayhead" class="timeline-playhead" :class="{ dragging: timelineScrubbing }"
               role="slider" tabindex="0"
               aria-label="Курсор таймлайна" aria-valuemin="0" :aria-valuemax="duration"
               @pointerdown.stop="startTimelineScrub" @keydown="onTimelinePlayheadKeydown">
            <i></i><span></span><em ref="timelinePlayheadLabel">{{ formatTime(currentTime, true) }}</em>
          </div>
        </div>
      </div>
    </section>

    <div v-if="notice.text" class="notice" :class="notice.kind" role="status">
      <i></i><span>{{ notice.text }}</span><button type="button" @click="notice.text = ''">×</button>
    </div>

    <div v-if="showExport" class="modal-backdrop" @mousedown.self="closeExport">
      <section class="export-modal" role="dialog" aria-modal="true" aria-labelledby="export-title">
        <header>
          <div><span class="eyebrow">Финальный файл</span><h2 id="export-title">Экспорт видеокниги</h2></div>
          <button type="button" @click="closeExport" aria-label="Закрыть">×</button>
        </header>

        <template v-if="!renderJob">
          <div class="export-summary">
            <div><span>Формат</span><b>MP4 · 1920×1080 · H.264</b></div>
            <label class="export-preset-row">
              <span>Качество YouTube</span>
              <span class="export-preset-control">
                <select v-model="project.renderPreset" aria-label="Профиль битрейта для YouTube">
                  <option v-for="preset in renderPresetOptions" :key="preset.id" :value="preset.id">
                    {{ preset.label }} · {{ preset.videoLabel }}
                  </option>
                </select>
                <small>Видео {{ activeRenderPreset.videoLabel }} · AAC {{ activeRenderPreset.audioLabel }}</small>
              </span>
            </label>
            <div><span>Длительность</span><b>{{ formatTime(duration, true) }}</b></div>
            <div><span>Оценка полного файла</span><b>≈ {{ estimatedFullRenderSize }}</b></div>
            <div><span>Главы</span><b>{{ project.chapters.length }}</b></div>
            <div><span>Музыка</span><b>{{ musicAsset && project.music.enabled ? `${musicAsset.name} · ${Math.round(project.music.volume * 100)}%` : 'нет' }}</b></div>
          </div>
          <label class="switch-row export-test-switch">
            <span><b>Тестовый фрагмент</b><small>Первые 60 секунд — быстрее проверить оформление</small></span>
            <input v-model="exportTest" type="checkbox" /><i></i>
          </label>
          <p v-if="duration > YOUTUBE_MAX_DURATION_SECONDS" class="export-warning youtube-limit-warning">
            YouTube не примет один файл длиннее 12 часов. Полный локальный MP4 будет создан, но перед загрузкой его потребуется разделить.
          </p>
          <p v-if="exportIssue" class="export-issue">{{ exportIssue }}</p>
          <p v-for="warning in exportReadiness?.warnings || []" :key="warning" class="export-warning">{{ warning }}</p>
          <button type="button" class="modal-primary" :disabled="!!exportIssue || startingExport" @click="startExport">
            {{ startingExport ? 'Подготавливаю…' : exportTest ? 'Собрать тест 60 секунд' : 'Начать полный экспорт' }}
          </button>
        </template>

        <template v-else>
          <div class="render-progress-orb" :class="renderJob.status">
            <span>{{ Math.round((renderJob.progress || 0) * 100) }}<small>%</small></span>
          </div>
          <h3>{{ renderStateTitle }}</h3>
          <p class="render-state-copy">{{ renderStateCopy }}</p>
          <div class="render-progress"><i :style="{ width: `${Math.round((renderJob.progress || 0) * 100)}%` }"></i></div>
          <a v-if="renderJob.status === 'done' && renderJob.download_url" class="modal-primary download-link" :href="renderJob.download_url">Скачать MP4</a>
          <button v-if="renderJob.status === 'done'" type="button" class="modal-secondary" @click="resetExport">Новый экспорт</button>
          <button v-if="renderJob.status === 'failed'" type="button" class="modal-primary" @click="renderJob = null">Вернуться к настройкам</button>
          <pre v-if="renderJob.status === 'failed' && renderJob.log?.length" class="render-error">{{ renderJob.log.slice(-4).join('\n') }}</pre>
        </template>
      </section>
    </div>

    <div v-if="showBookExport" class="modal-backdrop" @click.self="closeBookExport">
      <section class="book-export-modal" role="dialog" aria-modal="true" aria-labelledby="book-export-title">
        <header>
          <div>
            <span class="eyebrow">Текст и материалы</span>
            <h2 id="book-export-title">Экспорт книги</h2>
            <p>{{ activeProjectRecord?.book?.title }} · {{ activeProjectRecord?.chapters?.length || 0 }} глав</p>
          </div>
          <button type="button" :disabled="audioExportBusy" @click="closeBookExport" aria-label="Закрыть">×</button>
        </header>

        <div class="book-export-tabs" role="tablist" aria-label="Формат экспорта книги">
          <button type="button" role="tab" :aria-selected="bookExportTab === 'audio'" @click="bookExportTab = 'audio'">Аудиокнига MP3</button>
          <button type="button" role="tab" :aria-selected="bookExportTab === 'text'" @click="bookExportTab = 'text'">Текст и материалы</button>
        </div>
        <div v-if="bookExportTab === 'audio'" class="book-audio-export">
          <p>Активная озвучка выбранных глав с музыкой, громкостью и EQ из видеоредактора.</p>
          <div class="audio-export-selection-actions">
            <button type="button" :disabled="audioExportBusy" @click="selectAudioExportChapters(true)">Все озвученные</button>
            <button type="button" :disabled="audioExportBusy" @click="selectAudioExportChapters(false)">Снять выбор</button>
            <span>{{ audioExportChapterIds.length }} выбрано</span>
          </div>
          <div class="audio-export-chapters">
            <label v-for="chapter in activeProjectRecord?.chapters || []" :key="chapter.id" :style="chapterAccent(chapter)">
              <input v-model="audioExportChapterIds" type="checkbox" :value="chapter.id"
                     :disabled="audioExportBusy || chapterAudioState(chapter) === 'none'" />
              <span>{{ chapterDisplayNumber(chapter) }}. {{ chapter.title }}</span>
              <small>{{ chapterAudioStateLabel(chapter) }}</small>
            </label>
          </div>
          <div class="audio-export-options">
            <label>Файлы<select v-model="audioExportMode" :disabled="audioExportBusy"><option value="complete">Один MP3</option><option value="chapters">Отдельные MP3 в ZIP</option></select></label>
            <label>Качество<select v-model.number="audioExportBitrate" :disabled="audioExportBusy"><option v-for="rate in [128, 192, 256, 320]" :key="rate" :value="rate">{{ rate }} кбит/с</option></select></label>
          </div>
          <label class="audio-export-music"><input v-model="audioExportMusic" type="checkbox" :disabled="audioExportBusy" /> Добавить музыку с таймлайна</label>
          <button class="modal-primary" type="button" :disabled="audioExportBusy || !audioExportChapterIds.length" @click="startBookAudioExport">
            {{ audioExportBusy ? 'Собираю аудиокнигу…' : 'Экспортировать MP3' }}
          </button>
          <div v-if="audioExportJob" class="audio-export-status" role="status">
            <progress :value="audioExportJob.progress || 0" max="1"></progress>
            <span>{{ audioExportJob.stage }}</span>
            <a v-if="audioExportJob.status === 'done'" class="modal-primary download-link" :href="audioExportJob.download_url">Скачать {{ audioExportJob.download_url ? 'аудиокнигу' : 'MP3' }}</a>
          </div>
        </div>
        <template v-else>
        <div class="book-export-choices">
          <button type="button" :disabled="bookExportBusy" @click="downloadBookExport('complete')">
            <span class="book-export-choice-icon">▤</span>
            <span><b>Вся книга</b><small>Один аккуратно собранный TXT-файл</small></span>
            <i>TXT</i>
          </button>
          <button type="button" :disabled="bookExportBusy || !currentBookChapter" @click="downloadBookExport('chapter')">
            <span class="book-export-choice-icon">¶</span>
            <span><b>Текущая глава</b><small>{{ currentBookChapter?.title || 'Глава не выбрана' }}</small></span>
            <i>TXT</i>
          </button>
          <button class="featured" type="button" :disabled="bookExportBusy" @click="downloadBookExport('chapters')">
            <span class="book-export-choice-icon">▦</span>
            <span><b>Книга по главам</b><small>Каждая глава — отдельная папка и TXT</small></span>
            <i>ZIP</i>
          </button>
        </div>

        <label class="switch-row book-export-media-switch">
          <span>
            <b>Добавить материалы глав</b>
            <small>Активная озвучка и изображение попадут в поглавный ZIP</small>
          </span>
          <input v-model="bookExportIncludeMedia" type="checkbox" /><i></i>
        </label>
        </template>
        <footer>
          <span>Файлы сохраняются также в папке <b>exports</b> текущего проекта.</span>
          <i v-if="bookExportBusy">Собираю экспорт…</i>
        </footer>
      </section>
    </div>

    <div v-if="showTextNavigator" class="modal-backdrop text-navigator-backdrop"
         @mousedown.self="showTextNavigator = false">
      <section class="text-navigator-modal" role="dialog" aria-modal="true"
               aria-labelledby="text-navigator-title">
        <header>
          <div>
            <span class="eyebrow">Навигация по озвучке</span>
            <h2 id="text-navigator-title">Текст на таймлайне</h2>
            <p>Клик по слову перемещает курсор. Shift + клик выделяет отрывок.</p>
          </div>
          <button type="button" aria-label="Закрыть" @click="showTextNavigator = false">×</button>
        </header>

        <div class="text-navigator-layout">
          <aside>
            <label class="text-navigator-search">
              <span>⌕</span>
              <input v-model.trim="textNavigatorSearch" type="search" placeholder="Глава или фраза" />
            </label>
            <div class="text-navigator-chapters">
              <button v-for="chapter in filteredNavigatorChapters" :key="chapter.id" type="button"
                      :style="chapterAccent(chapter)"
                      :class="{ active: navigatorChapter?.id === chapter.id }"
                      @click="chooseNavigatorChapter(chapter)">
                <span>{{ chapter.index + 1 }}</span>
                <b>{{ chapter.title }}</b>
                <small>{{ formatTime(chapter.start_seconds) }}–{{ formatTime(chapter.end_seconds) }}</small>
              </button>
              <p v-if="!filteredNavigatorChapters.length">Ничего не найдено.</p>
            </div>
          </aside>

          <main>
            <div v-if="navigatorChapter" class="text-navigator-toolbar">
              <div>
                <span>Глава {{ navigatorChapter.index + 1 }}</span>
                <strong>{{ navigatorChapter.title }}</strong>
                <small>{{ formatTime(navigatorChapter.start_seconds, true) }}–{{ formatTime(navigatorChapter.end_seconds, true) }}</small>
              </div>
              <div class="text-selection-actions">
                <button type="button" :aria-pressed="textNavigatorFollow" @click="resumeTextFollow">
                  {{ textNavigatorFollow ? '✓ Следить за текстом' : 'Следить за текстом' }}
                </button>
                <button type="button" :disabled="!masterKind" @click="togglePlay">{{ playing ? 'Ⅱ Пауза' : '▶ Слушать' }}</button>
                <span v-if="selectedExcerpt">
                  {{ selectedExcerpt.wordCount }} сл. · {{ formatTime(selectedExcerpt.start, true) }}–{{ formatTime(selectedExcerpt.end, true) }}
                </span>
                <button type="button" :disabled="!selectedExcerpt || !masterKind"
                        @click="playSelectedExcerpt">
                  {{ excerptPlaybackEnd !== null ? '■ Остановить' : '▶ Слушать отрывок' }}
                </button>
                <button type="button" :disabled="!selectedExcerpt" @click="clearTextSelection">Сбросить</button>
              </div>
            </div>

            <div v-if="navigatorChapter" ref="textNavigatorScroll" class="text-navigator-copy"
                 @wheel.passive="textNavigatorFollow = false" @touchmove.passive="textNavigatorFollow = false"
                 @pointerdown.self="textNavigatorFollow = false"
                 @keydown="onReaderScrollKey">
              <p v-for="(paragraph, paragraphIndex) in navigatorParagraphs" :key="paragraphIndex"
                 v-memo="[paragraph, currentNavigatorWord?.paragraph === paragraph[0]?.paragraph ? currentNavigatorWord?.index : -1, textSelection.chapterId, textSelection.anchor, textSelection.focus]">
                <button v-for="word in paragraph" :key="word.index" type="button"
                        :data-word-index="word.index"
                        :class="{
                          selected: isNavigatorWordSelected(word),
                          current: currentNavigatorWord?.index === word.index,
                        }"
                        :title="`${formatTime(word.start, true)} · перейти на таймлайн`"
                        @click="selectNavigatorWord(word, $event)">{{ word.text }}</button>
              </p>
              <p v-if="!navigatorWords.length" class="text-navigator-empty">В этой главе пока нет текста.</p>
            </div>

            <div v-else class="text-navigator-empty-state">
              <span>¶</span><b>В видеопроекте нет текста глав</b>
              <small>Откройте книгу в видеоредакторе после создания или импорта глав.</small>
            </div>

            <footer v-if="navigatorChapter">
              <span><i></i> Текущее слово</span>
              <span><i></i> Выбранный отрывок</span>
              <b>{{ navigatorHasExactTimings
                ? 'Точные метки слов из озвучки.'
                : 'Привязка слов расчётная; переозвучьте главу для точных меток.' }}</b>
            </footer>
          </main>
        </div>
      </section>
    </div>

    <div v-if="visualPickerChapterId" class="modal-backdrop" @mousedown.self="visualPickerChapterId = null">
      <section class="visual-picker-modal" role="dialog" aria-modal="true" aria-labelledby="visual-picker-title">
        <header><div><h2 id="visual-picker-title">Иллюстрация главы</h2><p>{{ visualPickerChapter?.title }}</p></div>
          <button type="button" aria-label="Закрыть выбор иллюстрации" @click="visualPickerChapterId = null">×</button></header>
        <p>Выберите изображения из материалов. Начало и длительность будут предложены автоматически.</p>
        <div class="visual-picker-grid">
          <label v-for="asset in imageAssets" :key="asset.id" :class="{ selected: visualPickerAssets.includes(asset.id) }">
            <input v-model="visualPickerAssets" type="checkbox" :value="asset.id" :aria-label="asset.name" />
            <img :src="assetThumbnailUrl(asset)" loading="lazy" decoding="async" alt="" /><span>{{ asset.name }}</span>
          </label>
        </div>
        <p v-if="!imageAssets.length">Добавьте картинку — она появится и в общей библиотеке материалов.</p>
        <footer><label class="visual-file-import">Импортировать изображения<input type="file" accept="image/*" multiple @change="importVisualFiles" /></label>
          <button type="button" class="primary-button" :disabled="!visualPickerAssets.length || visualPlacementBusy" @click="addPickedVisuals">Добавить иллюстрации</button></footer>
      </section>
    </div>

    <div v-if="showProjectManager" class="modal-backdrop" @mousedown.self="showProjectManager = false">
      <section class="project-manager-modal" role="dialog" aria-modal="true" aria-labelledby="project-manager-title">
        <header>
          <div><span class="eyebrow">Локальная библиотека</span><h2 id="project-manager-title">Проекты Book Wunderwaffe</h2></div>
          <button type="button" @click="showProjectManager = false" aria-label="Закрыть">×</button>
        </header>
        <label class="project-manager-search">
          <span>⌕</span><input v-model.trim="projectManagerSearch" type="search" placeholder="Название или автор" />
        </label>
        <div class="project-manager-list">
          <article v-for="item in managedProjects" :key="item.uuid" :class="{ archived: !!item.archived_at }">
            <button class="project-manager-open" type="button" @click="openManagedProject(item.uuid)">
              <span>{{ projectKindLabel(item.project_kind) }}</span>
              <b>{{ item.title }}</b>
              <small>{{ item.author || 'Без автора' }} · {{ item.chapter_count }} глав · {{ formatProjectDate(item.updated_at) }}</small>
            </button>
            <div>
              <button type="button" title="Переименовать" @click="renameManagedProject(item)">✎</button>
              <button type="button" title="Дублировать" @click="duplicateManagedProject(item)">⧉</button>
              <button type="button" title="Резервная копия" @click="backupManagedProject(item)">◫</button>
              <button type="button" :title="item.archived_at ? 'Вернуть из архива' : 'Архивировать'"
                      @click="toggleManagedArchive(item)">{{ item.archived_at ? '↥' : '⌄' }}</button>
            </div>
          </article>
        </div>
        <footer>
          <span>Физическое удаление отключено: проекты безопасно архивируются.</span>
          <button type="button" @click="createEcosystemProject">＋ Новый проект</button>
        </footer>
      </section>
    </div>

    <div v-if="loading" class="loading-screen">
      <span class="loading-mark"><i></i><i></i><i></i></span>
      <strong>BOOK WUNDERWAFFE STUDIO</strong>
      <small>{{ loadingMessage }}</small>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { createApiClient } from './apiClient.js'
import { createLatestSaveCoordinator, createPointerGestureController, createSerialPoller } from './asyncControl.js'
import {
  DEFAULT_LAYERS, MAX_CHAPTER_PLAYBACK_RATE, MIN_CHAPTER_PLAYBACK_RATE,
  MUSIC_EQ_BANDS, PITCH_PRESETS, RATE_PRESETS, RENDER_PRESETS,
  VISUALIZER_COLOR_PRESETS, VOLUME_PRESETS, YOUTUBE_MAX_DURATION_SECONDS,
  chapterPlaybackRate, chapterSourceDuration, chapterSourceEnd, chapterSourceStart,
  chapterTimelineDuration, chapterTimelineWordTimings, chapterTimelineWords,
  clone, formatTime, formatTimecode, freshProject, materialType, normalizeChapter,
  normalizeProject, parseJsonObject, parseTimecode, readingCaptionChunks,
  timedTextWords, uid, wordAtTime, wordTimingCoverage, wordsByParagraph,
} from './editorModel.js'
import { chapterColor, followWord } from './editorNavigation.js'
import { createFrameGate } from './playbackClock.js'
import { createProjectSnapshotWriter } from './projectPersistence.js'
import { sceneAt } from './scenePlan.js'
import {
  buildTimelineChapters, buildTimelineVisualClips, createIntervalIndex,
  findActiveInterval, findStartedIndex, timelineViewportRange, visibleIntervals,
} from './timelineModel.js'
import ChapterVisual from './ChapterVisual.vue'
import TimecodeField from './TimecodeField.vue'
import QrcodeVue from 'qrcode.vue'

const VISUAL_CONTENT_COMPONENTS = { chapter_visual: ChapterVisual }

const API = import.meta.env.VITE_API_URL || '/api'
const apiRequest = createApiClient(API)
const TELEGRAM_URL = 'https://t.me/temple_of_lizard'
const renderPresetOptions = Object.values(RENDER_PRESETS)

const project = reactive(freshProject())
const visualPlan = shallowRef({ clips: [], issues: [] })
const visualPickerChapterId = ref(null)
const visualPickerAssets = ref([])
const visualPlacementBusy = ref(false)
let visualPlanTimer = null
let visualPlanRevision = 0
const backend = reactive({ online: false, checking: true, version: '', python: '' })
const selection = reactive({ type: 'project', id: null })
const notice = reactive({ text: '', kind: 'info' })
const activeWorkspace = ref('book')
const projectCatalog = ref([])
const managerProjectCatalog = ref([])
const activeProjectUuid = ref('')
const activeProjectRecord = ref(null)
const switchingProject = ref(false)
let queuedProjectSwitch = null
let projectSwitchPromise = null
const activeVideoEditionId = ref(null)
const showProjectManager = ref(false)
const projectManagerSearch = ref('')
const bookSearch = ref('')
const currentBookChapterId = ref(null)
const bookChapterTitle = ref('')
const bookChapterContent = ref('')
const bookChapterTechnicalContent = ref('')
const bookChapterTechEnabled = ref(false)
const bookSelection = ref(null)
const viewedTextVersion = ref(null)
const activeMetaNoteId = ref(null)
const activeMetaNoteDraft = ref('')
const META_COLORS = ['#7f6bd9', '#4f9b91', '#b88455', '#a76683', '#6688ad']
const bookSaveState = ref('saved')
const bookVideoTextSyncState = ref('idle')
const draggedBookChapterId = ref(null)
const bookChapterDropId = ref(null)
const bookChapterDropSide = ref('before')
const bookInspectorState = ref(localStorage.getItem('bookender.bookInspector') || 'open')
const bookMetadata = reactive({ title: '', author: '', description: '' })
const bookTts = reactive({
  voice: 'ru-RU-SvetlanaNeural',
  rate: '+0%',
  pitch: '+0Hz',
  volume: '+0%',
  provider: 'edge-tts',
})
const ttsJobs = ref([])
const narrationJobIds = ref([])
const ttsNow = ref(Date.now())
const ttsStopping = ref(false)
const ttsLogOpen = ref(false)
const ttsDebugJobId = ref('')
const ttsSessionStartedAt = Date.now()
const ttsRuntime = reactive({ available: false, message: '', provider: 'edge-tts', version: '' })
const ttsVoices = ref([])
const voiceSearch = ref('')
const voiceLanguage = ref('ru')
const previewLoading = ref(false)
const previewAudio = ref(null)
const selectedBookAudioId = ref(null)
const bookPlayerDuration = ref(0)
const bookPlayerPlaying = ref(false)
const bookPlayerVolume = ref(0.86)
const chapterAudioExportBusy = ref(false)
let bookAutosaveTimer = null
let metaNotesSaveTimer = null
let bookEditRevision = 0
let bookSavePromise = null
let ttsPoller = null
let ttsPollFailures = 0
let bookPlayerLoadToken = 0
let bookPlayerShouldAutoplay = false

const loading = ref(true)
const loadingMessage = ref('Подключаю медиадвижок…')
const dirty = ref(false)
const saving = ref(false)
const videoPreparing = ref(false)
const hydrating = ref(true)
let videoEditRevision = 0
const assetInput = ref(null)
const musicInput = ref(null)
const projectInput = ref(null)
const bookChapterImageInput = ref(null)
const bookTextEditor = ref(null)
const bookHighlightLayer = ref(null)
const bookAudioEl = ref(null)
const bookWordNavigatorCopy = ref(null)
const audioEl = ref(null)
const musicEl = ref(null)
const videoEl = ref(null)
const sceneEl = ref(null)
const visualSceneLayer = ref(null)
const sceneCurrentTimeEl = ref(null)
const sceneProgressFill = ref(null)
const transportCurrentTimeEl = ref(null)
const transportScrubber = ref(null)
const visualizerCanvas = ref(null)
const timelineScroll = ref(null)
const timelineContent = ref(null)
const timelineWaveform = ref(null)
const timelinePlayhead = ref(null)
const timelinePlayheadLabel = ref(null)
const timelineViewport = reactive({ left: 0, width: 0, contentWidth: 0 })
const textNavigatorScroll = ref(null)
const titleLayerEl = ref(null)
const titleStackEl = ref(null)

const currentTime = ref(0)
const audioDuration = ref(0)
const videoDuration = ref(0)
const playing = ref(false)
const comicTheater = ref(false)
const comicTelegramVisible = ref(true)
const volume = ref(0.86)
const musicPreviewPlaying = ref(false)
const musicEqState = reactive({
  active: false,
  error: false,
  message: 'Запустите пробу музыки — EQ включится в реальном времени',
})
const waveformSamples = shallowRef([])
const timelineZoom = ref(1)
const chapterTimelineMode = ref(false)
const timelineScrubbing = ref(false)
const timelineSnap = ref(localStorage.getItem('bookender.timelineSnap') !== 'false')
const snapGuideTime = ref(null)
const snapGuideLabel = ref('')
const storedTimelineHeight = Number(localStorage.getItem('bookender.timelineHeight'))
const timelineHeight = ref(Number.isFinite(storedTimelineHeight) && storedTimelineHeight >= 160 ? storedTimelineHeight : 306)

const showExport = ref(false)
const showBookExport = ref(false)
const showTextNavigator = ref(false)
const textNavigatorSearch = ref('')
const textNavigatorChapterId = ref('')
const textNavigatorFollow = ref(true)
const textSelection = reactive({ chapterId: '', anchor: -1, focus: -1 })
const excerptPlaybackEnd = ref(null)
const bookExportBusy = ref(false)
const bookExportIncludeMedia = ref(true)
const bookExportTab = ref('audio')
const audioExportChapterIds = ref([])
const audioExportMode = ref('complete')
const audioExportBitrate = ref(192)
const audioExportMusic = ref(true)
const audioExportJob = ref(null)
const audioExportBusy = ref(false)
let audioExportPoll = null
const exportTest = ref(true)
const exportReadiness = ref(null)
const startingExport = ref(false)
const renderJob = ref(null)
let renderPoller = null
let noticeTimer = null
let comicTelegramTimer = null
let playbackFrame = null
let bookPlaybackFrame = null
let renderedBookWordButton = null
const playbackUiGate = createFrameGate({ framesPerSecond: 10 })
let renderedWaveformElement = null
let renderedWaveformBars = -1
let visualFrame = null
let titleFitFrame = null
let titleResizeObserver = null
let timelineResizeObserver = null
let titleMeasureCanvas = null
let visualAudioContext = null
let visualAnalyser = null
let visualStreamSource = null
let visualizerStream = null
let visualizerMaster = null
let visualizerFrequencyData = null
let visualizerConnectPending = null
let visualizerLastConnectAttempt = 0
let visualizerGeneration = 0
let musicAudioContext = null
let musicMediaSource = null
let musicLowFilter = null
let musicMidFilter = null
let musicHighFilter = null
let musicGain = null
let musicStartPending = null
let playbackGeneration = 0
const retainedMusicAssetId = ref(null)
let timelinePointerMove = null
let timelinePointerUp = null
let timelineResizeMove = null
let timelineResizeUp = null
let timelinePanMove = null
let timelinePanUp = null
let resumeAfterTimelineScrub = false
const visualizerLevels = new Float32Array(72)
const visualizerPeaks = new Float32Array(72)
const objectUrls = new Set()
const layerGestures = createPointerGestureController(window)
const projectSnapshotWriter = createProjectSnapshotWriter({
  apiRequest,
  storage: localStorage,
  getOnline: () => backend.online,
  onEdition: (editionId, projectUuid) => {
    if (projectUuid === activeProjectUuid.value) activeVideoEditionId.value = editionId
  },
})
const projectSaver = createLatestSaveCoordinator({
  capture: () => ({
    revision: videoEditRevision,
    projectUuid: activeProjectUuid.value,
    editionId: activeVideoEditionId.value,
    payload: serializeProject(),
  }),
  persist: projectSnapshotWriter,
  isCurrent: snapshot => (
    snapshot.revision === videoEditRevision
    && snapshot.projectUuid === activeProjectUuid.value
  ),
})

const assetIndex = computed(() => new Map(project.materials.map(item => [String(item.id), item])))
const assetById = (id) => assetIndex.value.get(String(id)) || null
const audioAsset = computed(() => assetById(project.audioAssetId))
const musicAsset = computed(() => assetById(selectedMusicClip.value?.assetId || project.musicAssetId))
const videoAsset = computed(() => assetById(project.videoAssetId))
const coverAsset = computed(() => assetById(project.coverAssetId))
const backgroundAsset = computed(() => assetById(project.backgroundAssetId))
const coverEnabled = computed(() => project.coverMode !== 'background-only' && layerVisible('cover'))
const coverRequirementMet = computed(() => (
  project.coverMode === 'background-only' ? Boolean(backgroundAsset.value) : Boolean(coverAsset.value)
))
const imageAssets = computed(() => project.materials.filter((item) => item.type === 'image'))
const musicCandidates = computed(() => project.materials.filter((item) => (
  item.type === 'audio'
  && item.id !== project.audioAssetId
  && !['chapter-audio', 'book-master'].includes(item.role)
)))
const videoAudioChapters = computed(() => (
  project.chapters.filter((chapter) => chapter.audioAssetId && assetById(chapter.audioAssetId))
))
const videoReadyChapterCount = computed(() => videoAudioChapters.value.length)
const videoBookChapterCount = computed(() => activeProjectRecord.value?.chapters?.length || project.chapters.length)
const currentBookChapter = computed(() => (
  activeProjectRecord.value?.chapters?.find((item) => item.id === currentBookChapterId.value) || null
))
const filteredBookChapters = computed(() => {
  const chapters = activeProjectRecord.value?.chapters || []
  const query = bookSearch.value.toLocaleLowerCase('ru-RU')
  if (!query) return chapters
  return chapters.filter((item) => (
    item.title.toLocaleLowerCase('ru-RU').includes(query)
    || item.content.toLocaleLowerCase('ru-RU').includes(query)
  ))
})
const managedProjects = computed(() => {
  const query = projectManagerSearch.value.toLocaleLowerCase('ru-RU')
  if (!query) return managerProjectCatalog.value
  return managerProjectCatalog.value.filter((item) => (
    item.title.toLocaleLowerCase('ru-RU').includes(query)
    || String(item.author || '').toLocaleLowerCase('ru-RU').includes(query)
  ))
})
const bookWordCount = computed(() => (
  (bookChapterContent.value.trim().match(/[\p{L}\p{N}]+(?:[-’'][\p{L}\p{N}]+)*/gu) || []).length
))
const bookSaveLabel = computed(() => {
  const base = ({
    modified: 'Есть изменения',
    saving: 'Сохраняется…',
    saved: 'Сохранено',
    error: 'Ошибка сохранения',
  }[bookSaveState.value] || 'Сохранено')
  if (bookVideoTextSyncState.value === 'syncing') return `${base} · текст → видео`
  if (bookVideoTextSyncState.value === 'error') return `${base} · видео ожидает обновления`
  return base
})
const currentChapterAudios = computed(() => (
  (activeProjectRecord.value?.audio_assets || [])
    .filter((asset) => asset.chapter_id === currentBookChapterId.value)
    .sort((a, b) => Number(b.version_number || b.id) - Number(a.version_number || a.id))
))
const currentChapterAudio = computed(() => (
  currentChapterAudios.value.find((asset) => asset.is_active)
  || currentChapterAudios.value[0]
  || null
))
const normalizeMetaNote = (item) => ({ ...item, start: Number(item.start ?? item.start_offset), end: Number(item.end ?? item.end_offset), selectedText: item.selectedText ?? item.selected_text, colorIndex: Number(item.colorIndex ?? item.color_index), detached: Boolean(item.detached), createdAt: item.createdAt ?? item.created_at })
const currentMetaNotes = computed(() => (activeProjectRecord.value?.chapter_meta_notes || []).filter((item) => Number(item.chapter_id) === Number(currentBookChapterId.value)).map(normalizeMetaNote))
const activeMetaNote = computed(() => currentMetaNotes.value.find((item) => item.id === activeMetaNoteId.value) || null)
const currentTextVersions = computed(() => (activeProjectRecord.value?.chapter_text_versions || []).filter((item) => Number(item.chapter_id) === Number(currentBookChapterId.value)))
const bookHighlightedHtml = computed(() => {
  const text = bookChapterContent.value
  const notes = currentMetaNotes.value.filter((item) => !item.detached).sort((a, b) => a.start - b.start)
  let cursor = 0, html = ''
  for (const item of notes) {
    if (item.start < cursor || text.slice(item.start, item.end) !== item.selectedText) continue
    html += escapeHtml(text.slice(cursor, item.start)) + `<mark style="background:${metaColor(item.colorIndex)}55">${escapeHtml(text.slice(item.start, item.end))}</mark>`
    cursor = item.end
  }
  return html + escapeHtml(text.slice(cursor)) + '\n'
})
const currentBookChapterImages = computed(() => (
  (activeProjectRecord.value?.visual_assets || [])
    .filter((asset) => (
      asset.chapter_id === currentBookChapterId.value
      && asset.asset_type === 'chapter-image'
    ))
    .sort((a, b) => Number(b.id) - Number(a.id))
))
const currentBookChapterImage = computed(() => currentBookChapterImages.value[0] || null)
const currentBookChapterImageUrl = computed(() => (
  currentBookChapterImage.value?.file_path
    ? mediaUrl(currentBookChapterImage.value.file_path)
    : ''
))
const selectedBookAudio = computed(() => (
  currentChapterAudios.value.find((asset) => asset.id === selectedBookAudioId.value)
  || currentChapterAudio.value
  || null
))
const selectedBookAudioMetadata = computed(() => parseJsonObject(selectedBookAudio.value?.metadata_json))
const bookPlayerAsset = computed(() => previewAudio.value || selectedBookAudio.value)
const bookPlayerUrl = computed(() => {
  const asset = bookPlayerAsset.value
  if (!asset?.file_path || asset.file_path.startsWith('external:')) return ''
  return `${API}/project-media/${asset.file_path.split('/').map(encodeURIComponent).join('/')}`
})
const bookPlayerTitle = computed(() => {
  if (previewAudio.value) return `Проба · ${voiceShortLabel(previewAudio.value.voice)}`
  return selectedBookAudio.value?.title || `Версия ${selectedBookAudio.value?.version_number || ''}`
})
const selectedAudioIndex = computed(() => (
  currentChapterAudios.value.findIndex((asset) => asset.id === selectedBookAudio.value?.id)
))
const previousChapterAudio = computed(() => currentChapterAudios.value[selectedAudioIndex.value + 1] || null)
const nextChapterAudio = computed(() => currentChapterAudios.value[selectedAudioIndex.value - 1] || null)
const voiceLanguages = computed(() => (
  [...new Set(ttsVoices.value.map((voice) => voice.language).filter(Boolean))].sort((a, b) => (
    a === 'ru' ? -1 : b === 'ru' ? 1 : a.localeCompare(b)
  ))
))
const filteredTtsVoices = computed(() => {
  const query = voiceSearch.value.toLocaleLowerCase('ru-RU')
  return ttsVoices.value.filter((voice) => (
    (!voiceLanguage.value || voice.language === voiceLanguage.value)
    && (!query || `${voice.name} ${voice.friendly_name} ${voice.id} ${voice.locale}`.toLocaleLowerCase('ru-RU').includes(query))
  ))
})
const visibleTtsJobs = computed(() => ttsJobs.value.filter((job) => (
  ['queued', 'running'].includes(job.status)
  || new Date(job.created_at).getTime() >= ttsSessionStartedAt
)))
const latestTtsJob = computed(() => visibleTtsJobs.value[0] || null)
const ttsDebugJob = computed(() => (
  ttsJobs.value.find((job) => job.uuid === ttsDebugJobId.value)
  || ttsJobs.value.find((job) => job.status === 'running')
  || latestTtsJob.value
  || null
))
const ttsDebugEvents = computed(() => (
  Array.isArray(ttsDebugJob.value?.diagnostics)
    ? ttsDebugJob.value.diagnostics.slice(-80)
    : []
))
const activeTtsJobRunning = computed(() => ttsJobs.value.some((job) => ['queued', 'running'].includes(job.status)))
const narrationJobs = computed(() => {
  const selected = new Set(narrationJobIds.value)
  if (selected.size) return ttsJobs.value.filter((job) => selected.has(job.uuid))
  return ttsJobs.value.filter((job) => ['queued', 'running'].includes(job.status))
})
const bookPlayerWords = computed(() => timedTextWords(
  currentBookChapter.value?.content || '',
  0,
  Math.max(0.1, bookPlayerDuration.value || Number(selectedBookAudio.value?.duration) || 0.1),
  selectedBookAudio.value?.is_stale ? [] : selectedBookAudioMetadata.value.word_timings,
))
const bookPlayerParagraphs = computed(() => wordsByParagraph(bookPlayerWords.value))
const bookPlayerTimingCoverage = computed(() => wordTimingCoverage(bookPlayerWords.value))
const bookPlayerTimingCoveragePercent = computed(() => Math.round(bookPlayerTimingCoverage.value * 100))
const bookPlayerHasExactTimings = computed(() => (
  Array.isArray(selectedBookAudioMetadata.value.word_timings)
  && selectedBookAudioMetadata.value.word_timings.length > 0
  && !selectedBookAudio.value?.is_stale
  && bookPlayerTimingCoverage.value >= 0.98
))
const ttsProgressState = computed(() => {
  const jobs = narrationJobs.value
  const active = jobs.some((job) => ['queued', 'running'].includes(job.status))
  if (!active || !jobs.length) {
    return { active: false, percent: 0, detail: '', stage: '', elapsed: 0 }
  }
  const progress = jobs.reduce((total, job) => {
    if (['done', 'failed', 'cancelled', 'skipped'].includes(job.status)) return total + 1
    return total + Math.max(0, Math.min(1, Number(job.progress) || 0))
  }, 0) / jobs.length
  const running = jobs.find((job) => job.status === 'running')
    || jobs.find((job) => job.status === 'queued')
  const finished = jobs.filter((job) => (
    ['done', 'failed', 'cancelled', 'skipped'].includes(job.status)
  )).length
  const totalChunks = Math.max(1, Number(running?.progress_total) || 1)
  const completedChunks = Math.max(0, Number(running?.progress_done) || 0)
  const currentChunk = Math.min(totalChunks, completedChunks + 1)
  let detail = ''
  if (jobs.length > 1) {
    detail = `Готово глав: ${finished} из ${jobs.length}`
    if (running) detail += ` · сейчас «${chapterTitleForJob(running)}»`
  } else if (totalChunks > 1) {
    detail = `Фрагмент ${currentChunk} из ${totalChunks} · ${chapterTitleForJob(running)}`
  } else {
    detail = `Получаю аудио · ${chapterTitleForJob(running)}`
  }
  const timestamps = jobs
    .map((job) => new Date(job.started_at || job.created_at).getTime())
    .filter(Number.isFinite)
  const startedAt = timestamps.length ? Math.min(...timestamps) : ttsNow.value
  const activityAt = new Date(running?.last_activity_at || running?.started_at || running?.created_at).getTime()
  const quietSeconds = Number.isFinite(activityAt) ? Math.max(0, (ttsNow.value - activityAt) / 1000) : 0
  let stage = running?.stage || (running?.status === 'queued' ? 'В очереди' : 'Подготовка')
  if (running?.status === 'running' && quietSeconds > 45) {
    stage += ` · нет новых данных ${formatTime(quietSeconds, true)} — вероятна проблема сети или VPN`
  }
  return {
    active: true,
    percent: Math.max(1, Math.min(99, Math.round(progress * 100))),
    detail,
    stage,
    elapsed: Math.max(0, (ttsNow.value - startedAt) / 1000),
  }
})
const audioSource = computed(() => assetUrl(audioAsset.value))
const musicSource = computed(() => {
  return assetUrl(assetById(musicPlaybackClip.value?.assetId || retainedMusicAssetId.value || project.musicAssetId))
})
const videoSource = computed(() => assetUrl(videoAsset.value))
const coverSource = computed(() => assetUrl(coverAsset.value))

const masterKind = computed(() => audioSource.value ? 'audio' : videoSource.value ? 'video' : null)
const chapterMax = computed(() => project.chapters.reduce((max, item) => Math.max(max, Number(item.end_seconds) || Number(item.start_seconds) || 0), 0))
const sceneMax = computed(() => project.scenes.reduce((max, item) => Math.max(max, Number(item.end) || 0), 0))
const hasChapterTimeStretch = computed(() => project.chapters.some((chapter) => (
  Math.abs(chapterPlaybackRate(chapter) - 1) > 0.001
)))
const duration = computed(() => {
  if (hasChapterTimeStretch.value && chapterMax.value > 0) return chapterMax.value
  const media = masterKind.value === 'audio' ? audioDuration.value : videoDuration.value
  if (Number.isFinite(media) && media > 0) return media
  return Math.max(chapterMax.value, sceneMax.value, 0)
})
const activeRenderPreset = computed(() => RENDER_PRESETS[project.renderPreset] || RENDER_PRESETS.balanced)
const estimatedFullRenderSize = computed(() => {
  const seconds = Number(exportReadiness.value?.audioProbe?.duration) || duration.value
  const bitrate = activeRenderPreset.value.videoKbps + activeRenderPreset.value.audioKbps
  return formatBytes(Math.max(0, seconds) * bitrate * 1000 / 8)
})
const estimatedPeakRenderBytes = computed(() => {
  const seconds = Number(exportReadiness.value?.audioProbe?.duration) || duration.value
  const videoBytes = Math.max(0, seconds) * activeRenderPreset.value.videoKbps * 1000 / 8
  const audioBytes = Math.max(0, seconds) * activeRenderPreset.value.audioKbps * 1000 / 8
  return Math.ceil((videoBytes * 3 + audioBytes + 128 * 1024 ** 2) * 1.08)
})
const musicEqSummary = computed(() => {
  const values = MUSIC_EQ_BANDS.map((band) => Number(project.music?.[band.key]) || 0)
  return values.every((value) => value === 0) ? 'ровно' : values.map((value) => `${value > 0 ? '+' : ''}${value}`).join('/')
})
const displayMusicClips = computed(() => {
  if (project.musicClips.length) return project.musicClips
  if (!project.musicAssetId) return []
  return [{
    id: 'music-legacy',
    assetId: project.musicAssetId,
    start: 0,
    end: Math.max(duration.value, 0.1),
    sourceOffset: 0,
    volume: 1,
    enabled: true,
    legacy: true,
  }]
})
const musicClipIndex = computed(() => createIntervalIndex(displayMusicClips.value))
const selectedMusicClip = computed(() => (
  selection.type === 'music' && selection.id
    ? displayMusicClips.value.find((clip) => clip.id === selection.id) || null
    : null
))
const activeMusicClip = computed(() => findActiveInterval(
  musicClipIndex.value,
  currentTime.value,
  clip => clip.enabled !== false,
))
const musicPlaybackClip = computed(() => (
  playing.value
    ? activeMusicClip.value
    : activeMusicClip.value || selectedMusicClip.value || displayMusicClips.value[0] || null
))

const timelineChapters = computed(() => buildTimelineChapters(project.chapters, duration.value))
const timelineChapterIndex = computed(() => createIntervalIndex(timelineChapters.value, {
  start: 'start_seconds',
  end: 'end_seconds',
}))
const sourceChapterIndex = computed(() => createIntervalIndex(timelineChapters.value, {
  start: chapterSourceStart,
  end: chapterSourceEnd,
}))
const timelineSceneIndex = computed(() => createIntervalIndex(project.scenes))

const filteredNavigatorChapters = computed(() => {
  const query = textNavigatorSearch.value.toLocaleLowerCase('ru-RU')
  if (!query) return timelineChapters.value
  return timelineChapters.value.filter((chapter) => (
    chapter.title.toLocaleLowerCase('ru-RU').includes(query)
    || String(chapter.text || '').toLocaleLowerCase('ru-RU').includes(query)
  ))
})
const hasBookChapterZero = computed(() => (
  (activeProjectRecord.value?.chapters || []).some((chapter) => Boolean(chapter.is_zero))
))
const navigatorChapter = computed(() => (
  timelineChapters.value.find((chapter) => chapter.id === textNavigatorChapterId.value)
  || currentChapter.value
  || timelineChapters.value[0]
  || null
))
const navigatorWords = computed(() => chapterTimelineWords(navigatorChapter.value))
const navigatorHasExactTimings = computed(() => navigatorWords.value.some((word) => word.timing === 'exact'))
const navigatorParagraphs = computed(() => wordsByParagraph(navigatorWords.value))
const selectedExcerpt = computed(() => {
  const chapter = timelineChapters.value.find((item) => item.id === textSelection.chapterId)
  if (!chapter || textSelection.anchor < 0 || textSelection.focus < 0) return null
  const words = chapterTimelineWords(chapter)
  const firstIndex = Math.min(textSelection.anchor, textSelection.focus)
  const lastIndex = Math.max(textSelection.anchor, textSelection.focus)
  const selected = words.slice(firstIndex, lastIndex + 1)
  if (!selected.length) return null
  return {
    chapterId: chapter.id,
    start: selected[0].start,
    end: selected[selected.length - 1].end,
    text: selected.map((word) => word.text).join(' '),
    wordCount: selected.length,
    firstIndex,
    lastIndex,
  }
})
const currentNavigatorWord = computed(() => {
  const words = navigatorWords.value
  const chapter = navigatorChapter.value
  if (!words.length || !chapter) return null
  if (currentTime.value < chapter.start_seconds || currentTime.value > chapter.end_seconds) return null
  let low = 0
  let high = words.length - 1
  let result = words[0]
  while (low <= high) {
    const middle = Math.floor((low + high) / 2)
    if (words[middle].start <= currentTime.value) {
      result = words[middle]
      low = middle + 1
    } else {
      high = middle - 1
    }
  }
  return result
})

const currentChapterIndex = computed(() => {
  const time = Number.isFinite(Number(currentTime.value)) ? Math.max(0, Number(currentTime.value)) : 0
  return findStartedIndex(timelineChapterIndex.value, time)
})
const currentChapter = computed(() => currentChapterIndex.value >= 0 ? timelineChapters.value[currentChapterIndex.value] : null)
const isComicEdition = computed(() => project.presentationMode === 'comic')
const showComicChapterTitle = computed(() => {
  if (!isComicEdition.value || !currentChapter.value) return false
  return currentTime.value - Number(currentChapter.value.start_seconds || 0) < Number(project.comic?.chapterTitleSeconds || 8)
})
const previousChapter = computed(() => currentChapterIndex.value > 0 ? timelineChapters.value[currentChapterIndex.value - 1] : null)
const nextChapter = computed(() => currentChapterIndex.value >= 0 ? timelineChapters.value[currentChapterIndex.value + 1] || null : null)

const currentScene = computed(() => findActiveInterval(timelineSceneIndex.value, currentTime.value))

const currentChapterImageAsset = computed(() => assetById(currentChapter.value?.imageAssetId))
const sceneBackgroundAsset = computed(() => {
  if (project.coverMode === 'background-only') return backgroundAsset.value
  return (
    currentChapterImageAsset.value
    || assetById(currentScene.value?.backgroundAssetId)
    || backgroundAsset.value
  )
})
const backgroundSource = computed(() => assetUrl(sceneBackgroundAsset.value))
const backgroundStyle = computed(() => backgroundSource.value
  ? { backgroundImage: `url("${backgroundSource.value.replaceAll('"', '%22')}")` }
  : {})
const backgroundThumbnailStyle = computed(() => backgroundSource.value
  ? { backgroundImage: `url("${backgroundSource.value.replaceAll('"', '%22')}")` }
  : {})
const currentReadingWords = computed(() => chapterTimelineWords(currentChapter.value))
const currentReadingCaption = computed(() => {
  if (!project.captions?.enabled || !currentChapter.value?.text) return ''
  const words = currentReadingWords.value
  if (!words.length) return ''
  let low = 0
  let high = words.length - 1
  let activeIndex = 0
  while (low <= high) {
    const middle = Math.floor((low + high) / 2)
    if (words[middle].start <= currentTime.value) {
      activeIndex = middle
      low = middle + 1
    } else {
      high = middle - 1
    }
  }
  const size = Math.max(6, Math.min(28, Number(project.captions.wordsPerCard) || 14))
  const cardStart = Math.floor(activeIndex / size) * size
  return words.slice(cardStart, cardStart + size).map((word) => word.text).join(' ')
})

const selectedAsset = computed(() => selection.type === 'asset' ? assetById(selection.id) : null)
const selectedChapter = computed(() => selection.type === 'chapter' ? project.chapters.find((item) => item.id === selection.id) || null : null)
const selectedScene = computed(() => selection.type === 'scene' ? project.scenes.find((item) => item.id === selection.id) || null : null)
const selectedLayer = computed(() => selection.type === 'layer' ? project.layers[selection.id] || null : null)
const selectedVisualChapter = computed(() => selection.type === 'visual'
  ? project.chapters.find(ch => ch.visualInserts?.some(clip => clip.id === selection.id)) || null : null)
const selectedVisual = computed(() => selectedVisualChapter.value?.visualInserts.find(clip => clip.id === selection.id) || null)
const visualTargetChapter = computed(() => selectedChapter.value || selectedVisualChapter.value || currentChapter.value)
const visualPickerChapter = computed(() => project.chapters.find(ch => ch.id === visualPickerChapterId.value))
const selectedVisualIssue = computed(() => {
  const reason = visualPlan.value.issues.find(issue => issue.id === selectedVisual.value?.id)?.reason
  return reason === 'missing-image' ? 'Изображение не найдено в материалах.'
    : reason ? 'Вставка не видна: проверьте границы главы и пересечения с другими вставками.' : ''
})
const visualScene = computed(() => sceneAt(visualPlan.value, currentTime.value))
const timelineVisualClips = computed(() => buildTimelineVisualClips(timelineChapters.value))
const timelineVisualIndex = computed(() => createIntervalIndex(timelineVisualClips.value))
const videoAudioChapterIndex = computed(() => createIntervalIndex(videoAudioChapters.value, {
  start: 'start_seconds',
  end: 'end_seconds',
}))
const visibleTimelineRange = computed(() => timelineViewportRange(timelineViewport, duration.value))
const visibleTimelineChapters = computed(() => visibleIntervals(
  timelineChapterIndex.value, visibleTimelineRange.value.start, visibleTimelineRange.value.end,
))
const visibleTimelineScenes = computed(() => visibleIntervals(
  timelineSceneIndex.value, visibleTimelineRange.value.start, visibleTimelineRange.value.end,
))
const visibleTimelineVisualClips = computed(() => {
  const range = visibleTimelineRange.value
  return visibleIntervals(timelineVisualIndex.value, range.start, range.end)
})
const visibleVideoAudioChapters = computed(() => visibleIntervals(
  videoAudioChapterIndex.value, visibleTimelineRange.value.start, visibleTimelineRange.value.end,
))
const visibleMusicClips = computed(() => visibleIntervals(
  musicClipIndex.value, visibleTimelineRange.value.start, visibleTimelineRange.value.end,
))
const visualPlanInput = computed(() => ({
  duration_seconds: duration.value,
  chapters: timelineChapters.value.map(chapter => ({ id: chapter.id, title: chapter.title,
    start_seconds: chapter.start_seconds, end_seconds: chapter.end_seconds,
    imageAssetId: chapter.imageAssetId, visualInserts: chapter.visualInserts || [],
  })),
  materials: project.materials.map(asset => ({ id: asset.id, type: asset.type })),
}))
const visualPlanSignature = computed(() => {
  const chapters = visualPlanInput.value.chapters.map(chapter => {
    const inserts = chapter.visualInserts.map(clip => [
      clip.id, clip.assetId, clip.enabled !== false, clip.startTime, clip.duration,
      clip.transition, clip.transitionDuration,
    ].join(':')).join(',')
    return [chapter.id, chapter.start_seconds, chapter.end_seconds, chapter.imageAssetId, inserts].join(';')
  }).join('|')
  const materials = visualPlanInput.value.materials.map(asset => `${asset.id}:${asset.type}`).join(',')
  return `${visualPlanInput.value.duration_seconds}|${chapters}|${materials}`
})
const inspectorTitle = computed(() => ({
  project: 'Проект', music: 'Музыка', asset: 'Материал', chapter: 'Глава', scene: 'Сцена', visual: 'Иллюстрация', layer: layerLabel(selection.id),
})[selection.type] || 'Свойства')

const timelineBars = computed(() => {
  const source = waveformSamples.value.length ? waveformSamples.value : Array.from({ length: 720 }, (_, i) => 0.2 + Math.abs(Math.sin(i * 0.37) * Math.cos(i * 0.11)) * 0.65)
  const count = 180
  const step = Math.max(1, Math.floor(source.length / count))
  return Array.from({ length: count }, (_, index) => {
    const slice = source.slice(index * step, (index + 1) * step)
    return Math.max(0.08, Math.min(1, slice.reduce((sum, value) => sum + Math.abs(Number(value) || 0), 0) / Math.max(1, slice.length)))
  })
})

const rulerMarks = computed(() => Array.from({ length: 11 }, (_, index) => ({
  p: index * 10,
  label: formatTime(duration.value * index / 10),
})))
const timelineGridStep = computed(() => {
  const target = duration.value / Math.max(10, 10 * timelineZoom.value)
  const steps = [0.1, 0.2, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1200]
  return steps.find((step) => step >= target) || steps[steps.length - 1]
})
const timelineContentStyle = computed(() => ({
  width: `${timelineZoom.value * 100}%`,
  '--timeline-grid-size': `${Math.max(0.04, timelineGridStep.value / Math.max(duration.value, 1) * 100)}%`,
  '--timeline-grid-major-size': `${Math.max(0.2, timelineGridStep.value * 5 / Math.max(duration.value, 1) * 100)}%`,
}))

const hasUploadingAssets = computed(() => project.materials.some((item) => item.status === 'uploading'))
const canOpenExport = computed(() => backend.online && !!audioAsset.value && coverRequirementMet.value && project.chapters.length > 0 && !hasUploadingAssets.value)
const exportIssue = computed(() => {
  if (!backend.online) return 'Медиадвижок недоступен. Запустите проект через run.bat.'
  if (!audioAsset.value) return 'Добавьте основную аудиокнигу.'
  if (!coverRequirementMet.value) {
    return project.coverMode === 'background-only'
      ? 'Назначьте изображение единым задником.'
      : 'Назначьте изображение обложкой.'
  }
  if (!project.chapters.length) return 'Добавьте хотя бы одну главу.'
  if (hasUploadingAssets.value) return 'Дождитесь завершения синхронизации файлов.'
  if (exportReadiness.value && exportReadiness.value.ready === false) {
    const labels = {
      audio: 'Backend не видит выбранное аудио.',
      cover: 'Backend не видит выбранную обложку.',
      background: 'Backend не видит выбранный задник.',
      chapters: 'Главы не сохранены.',
      'audio-decodable': 'Выбранный аудиофайл повреждён или не читается FFmpeg. Назначьте другую копию из материалов.',
      'chapters-duration-mismatch': 'Главы не покрывают всю аудиокнигу. Продлите последнюю главу или обновите разметку из RPP.',
      'chapters-outside-audio': 'Разметка глав выходит за пределы аудиофайла.',
      'chapters-start-after-audio': 'Первая глава должна начинаться с 00:00:00.',
      'chapters-have-gaps': 'Между главами есть непокрытый интервал.',
      ffmpeg: 'FFmpeg не найден.',
      ffprobe: 'FFprobe не найден.',
    }
    return exportReadiness.value.missing?.map((item) => labels[item] || item).join(' · ') || 'Проект пока не готов к экспорту.'
  }
  const freeBytes = Number(exportReadiness.value?.renderEstimate?.full?.freeBytes)
  if (!exportTest.value && freeBytes > 0 && estimatedPeakRenderBytes.value > freeBytes) {
    return `Недостаточно места для надёжной сборки: нужно около ${formatBytes(estimatedPeakRenderBytes.value)}, доступно ${formatBytes(freeBytes)}. Выберите более компактный профиль или освободите диск.`
  }
  return ''
})

const renderStateTitle = computed(() => {
  if (renderJob.value?.status === 'done') return 'Видеокнига готова'
  if (renderJob.value?.status === 'failed') return 'Экспорт остановлен'
  return 'Собираю видеокнигу'
})
const renderStateCopy = computed(() => {
  if (renderJob.value?.status === 'done') return 'Файл собран и готов к скачиванию.'
  if (renderJob.value?.status === 'failed') return 'Движок вернул ошибку. Последние строки лога показаны ниже.'
  return exportTest.value ? 'Рендерится тестовый фрагмент длиной 60 секунд.' : 'Полный экспорт может занять продолжительное время.'
})

function materialCode(type) {
  return ({ audio: 'AUD', video: 'VID', image: 'IMG' })[type] || 'FILE'
}

function materialLabel(type) {
  return ({ audio: 'Аудио', video: 'Видео', image: 'Изображение' })[type] || 'Файл'
}

function materialRole(asset) {
  const roles = []
  if (project.audioAssetId === asset.id) roles.push('Основное аудио')
  if (project.musicAssetId === asset.id) roles.push('Музыка')
  if (project.videoAssetId === asset.id) roles.push('Видео сцены')
  if (project.coverAssetId === asset.id) roles.push('Обложка')
  if (project.backgroundAssetId === asset.id) roles.push('Фон')
  if (asset.role === 'chapter-audio') roles.push('Озвучка главы')
  if (asset.role === 'chapter-image') roles.push('Изображение главы')
  if (asset.role === 'book-master') roles.push('Мастер-аудио')
  if (asset.role === 'default-cover') roles.push('Обложка по умолчанию')
  return roles.join(' · ')
}

function assetStatus(asset) {
  if (asset.status === 'uploading') return `Синхронизация ${Math.round((asset.progress || 0) * 100)}%`
  if (asset.status === 'error') return 'Ошибка синхронизации'
  if (asset.status === 'local') return 'Только в этой сессии'
  return 'Готово'
}

function formatBytes(bytes) {
  const size = Number(bytes) || 0
  if (!size) return 'размер неизвестен'
  if (size >= 1024 ** 3) return `${(size / 1024 ** 3).toFixed(1)} ГБ`
  if (size >= 1024 ** 2) return `${(size / 1024 ** 2).toFixed(1)} МБ`
  return `${Math.max(1, Math.round(size / 1024))} КБ`
}

function formatDb(value) {
  const amount = Number(value) || 0
  return `${amount > 0 ? '+' : ''}${amount} дБ`
}

function mediaUrl(path) {
  if (!path) return ''
  const normalized = String(path).replaceAll('\\', '/').replace(/^\/+/, '')
  if (normalized === 'assets/bookender-studio-icon.png') {
    return '/bookender-studio-icon.png'
  }
  if (normalized.startsWith('projects/')) {
    return `${API}/project-media/${normalized.split('/').map(encodeURIComponent).join('/')}`
  }
  return `${API}/media/${normalized.split('/').map(encodeURIComponent).join('/')}`
}

function assetUrl(asset) {
  if (!asset) return ''
  return asset.src || mediaUrl(asset.serverPath)
}

function assetThumbnailUrl(asset, width = 320, height = 180) {
  if (!asset) return ''
  if (asset.src && !asset.serverPath) return asset.src
  const normalized = String(asset.serverPath || '').replaceAll('\\', '/').replace(/^\/+/, '')
  if (!normalized.startsWith('projects/')) return assetUrl(asset)
  const encoded = normalized.split('/').map(encodeURIComponent).join('/')
  const revision = encodeURIComponent(String(asset.size || 0))
  return `${API}/project-thumbnail/${encoded}?width=${width}&height=${height}&v=${revision}`
}

function assetPreviewUrl(asset) {
  return assetThumbnailUrl(asset, 1600, 900)
}

function replaceProject(value) {
  visualPlanRevision += 1
  visualPlan.value = { clips: [], issues: [] }
  visualPickerChapterId.value = null
  hydrating.value = true
  releaseObjectUrls()
  const normalized = normalizeProject(value, { mediaUrl })
  for (const key of Object.keys(project)) delete project[key]
  Object.assign(project, normalized)
  selection.type = 'project'
  selection.id = null
  currentTime.value = 0
  nextTick(() => {
    scheduleVisualPlan()
    audioEl.value?.load()
    musicEl.value?.load()
    videoEl.value?.load()
    hydrating.value = false
  })
}

function serializeProject() {
  return {
    ...clone(project),
    duration_seconds: duration.value,
    updatedAt: new Date().toISOString(),
    materials: project.materials.map(({ file, src, progress, ...asset }) => ({ ...asset, status: asset.serverPath ? 'ready' : 'missing' })),
    chapters: timelineChapters.value.map(({ index, ...chapter }) => chapter),
  }
}

function select(type, id = null) {
  selection.type = type
  selection.id = id
}

function openVisualPicker(chapter = visualTargetChapter.value) {
  if (!chapter) return
  visualPickerChapterId.value = chapter.id
  visualPickerAssets.value = chapter.imageAssetId ? [chapter.imageAssetId] : []
}

function importVisualFiles(event) {
  const previous = new Set(project.materials.map(asset => asset.id))
  importFiles(event.target.files, { asVisual: true })
  visualPickerAssets.value.push(...project.materials.filter(asset => !previous.has(asset.id)).map(asset => asset.id))
  event.target.value = ''
}

async function autoPlaceVisuals(chapter = null, assetIds = null) {
  if (visualPlacementBusy.value) return false
  visualPlacementBusy.value = true
  const projectIdentity = project.chapters
  try {
    const beforeCount = chapter?.visualInserts?.length || 0
    const response = await apiRequest('/visual-inserts/auto-place', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({
      project: visualPlanInput.value, chapterId: chapter?.id ?? null,
      ...(assetIds ? { assetIds, append: true } : {}),
    }) })
    if (projectIdentity !== project.chapters) return false
    let count = 0
    for (const result of response.chapters || []) {
      const target = project.chapters.find(item => item.id === result.id)
      if (!target) continue
      target.visualInserts = result.visualInserts.map(clip => ({ ...clip, id: clip.id || uid('visual') }))
      count += target.visualInserts.length
    }
    if (assetIds) {
      const inserted = project.chapters.find(item => item.id === chapter.id)?.visualInserts?.[beforeCount]
      if (!inserted) { setNotice('В конце главы нет места для новой вставки. Сократите предыдущую или авторасставьте визуалы заново.', 'info', 6500); return false }
      select('visual', inserted.id)
    }
    setNotice(count ? `Визуалы размещены: ${count}` : 'Назначьте изображение главы или добавьте иллюстрацию.', 'info')
    return true
  } catch (error) {
    setNotice(`Не удалось разместить визуалы: ${error.message}`, 'error')
    return false
  } finally { visualPlacementBusy.value = false }
}

async function addPickedVisuals() {
  const target = visualPickerChapter.value
  if (target && await autoPlaceVisuals(target, [...visualPickerAssets.value])) visualPickerChapterId.value = null
}

function removeVisual(id) {
  const chapter = project.chapters.find(item => item.visualInserts?.some(clip => clip.id === id))
  if (!chapter) return
  chapter.visualInserts = chapter.visualInserts.filter(clip => clip.id !== id)
  select('chapter', chapter.id)
}

function moveVisualToChapter(id) {
  const target = project.chapters.find(ch => String(ch.id) === String(id))
  const clip = selectedVisual.value, previous = selectedVisualChapter.value
  if (!target || !clip || previous === target) return
  previous.visualInserts = previous.visualInserts.filter(item => item !== clip)
  target.visualInserts ||= []
  clip.startTime = Math.min(clip.startTime, Math.max(0, chapterEnd(target.id) - target.start_seconds - clip.duration))
  target.visualInserts.push(clip)
}

function updateVisualTime(key, value) {
  if (!selectedVisual.value) return
  const parsed = parseTimecode(value)
  if (!Number.isFinite(parsed)) return
  const length = chapterEnd(selectedVisualChapter.value.id) - selectedVisualChapter.value.start_seconds
  if (key === 'startTime') selectedVisual.value.startTime = Math.max(0, Math.min(parsed, length - 0.05))
  else selectedVisual.value.duration = Math.max(0.05, Math.min(parsed, length - selectedVisual.value.startTime))
}

function visualTimecode(value) {
  const milliseconds = Math.round(Math.max(0, Number(value) || 0) * 1000)
  return `${formatTime(Math.floor(milliseconds / 1000), true)}.${String(milliseconds % 1000).padStart(3, '0')}`
}

function ensureExplicitMusicClips() {
  if (project.musicClips.length || !project.musicAssetId) return project.musicClips
  project.musicClips.push({
    id: uid('music-clip'),
    assetId: project.musicAssetId,
    start: 0,
    end: Math.max(duration.value, 0.1),
    sourceOffset: 0,
    volume: 1,
    enabled: true,
    chapterId: null,
    chapterBound: false,
  })
  return project.musicClips
}

function chapterAtTime(time) {
  return findActiveInterval(timelineChapterIndex.value, time)
}

function bindMusicClipToMatchingChapter(clip) {
  if (!timelineSnap.value) {
    clip.chapterId = null
    clip.chapterBound = false
    return
  }
  const chapter = timelineChapters.value.find((item) => (
    Math.abs(Number(item.start_seconds) - Number(clip.start)) < 0.025
    && Math.abs(Number(item.end_seconds) - Number(clip.end)) < 0.025
  ))
  clip.chapterId = chapter?.id || null
  clip.chapterBound = !!chapter
}

function syncChapterBoundMusicClips() {
  for (const clip of project.musicClips) {
    if (!clip.chapterBound || !clip.chapterId) continue
    const chapter = timelineChapters.value.find((item) => item.id === clip.chapterId)
    if (!chapter) {
      clip.chapterId = null
      clip.chapterBound = false
      continue
    }
    clip.start = Number(chapter.start_seconds)
    clip.end = Number(chapter.end_seconds)
  }
}

function toggleTimelineSnap() {
  timelineSnap.value = !timelineSnap.value
  snapGuideTime.value = null
  localStorage.setItem('bookender.timelineSnap', String(timelineSnap.value))
  setNotice(
    timelineSnap.value
      ? 'Магнит включён: главы, сцены, аудио и музыка прилипают к сетке'
      : 'Магнит выключен: элементы двигаются свободно',
    'info',
    2600,
  )
}

function addMusicClip(assetId, time = currentTime.value) {
  const position = Math.max(0, Math.min(Number(time) || 0, duration.value || Number(time) || 0))
  const chapter = timelineSnap.value ? chapterAtTime(position) : null
  const start = chapter ? Number(chapter.start_seconds) : position
  const end = chapter
    ? Number(chapter.end_seconds)
    : duration.value > start
      ? Math.min(duration.value, start + 30)
      : start + 30
  const clip = {
    id: uid('music-clip'),
    assetId,
    start,
    end: Math.max(start + 0.05, end),
    sourceOffset: 0,
    volume: 1,
    enabled: true,
    chapterId: chapter?.id || null,
    chapterBound: !!chapter,
  }
  project.musicClips.push(clip)
  project.musicAssetId ||= assetId
  project.music.enabled = true
  dirty.value = true
  return clip
}

function selectMusicClip(clip) {
  if (clip.legacy) {
    const [materialized] = ensureExplicitMusicClips()
    select('music', materialized?.id || null)
    return
  }
  select('music', clip.id)
}

function changeSelectedMusicAsset(assetId) {
  if (selectedMusicClip.value) {
    const clipId = selectedMusicClip.value.id
    if (selectedMusicClip.value.legacy) ensureExplicitMusicClips()
    const clip = project.musicClips.find((item) => item.id === clipId)
      || project.musicClips[0]
    if (!assetId) {
      if (clip) removeMusicClip(clip.id)
      return
    }
    if (clip) clip.assetId = assetId
  } else {
    project.musicAssetId = assetId
    if (assetId && !project.musicClips.length) addMusicClip(assetId, currentTime.value)
  }
  if (assetId) {
    const asset = assetById(assetId)
    if (asset) asset.role = 'music'
    project.musicAssetId ||= assetId
    project.music.enabled = true
  }
  onMusicSelectionChanged()
}

function updateMusicClipTime(key, value) {
  const clip = selectedMusicClip.value
  if (!clip) return
  if (clip.legacy) {
    ensureExplicitMusicClips()
    select('music', project.musicClips[0]?.id || null)
    return updateMusicClipTime(key, value)
  }
  clip.chapterId = null
  clip.chapterBound = false
  const parsed = Math.max(0, parseTimecode(value))
  if (key === 'start') {
    const previousStart = clip.start
    clip.start = Math.min(parsed, clip.end - 0.05)
    clip.sourceOffset = Math.max(0, clip.sourceOffset + clip.start - previousStart)
  } else {
    clip.end = Math.max(clip.start + 0.05, Math.min(parsed, duration.value || parsed))
  }
}

function removeMusicClip(id) {
  if (id === 'music-legacy') {
    ensureExplicitMusicClips()
    id = project.musicClips[0]?.id
  }
  const index = project.musicClips.findIndex((clip) => clip.id === id)
  if (index < 0) return
  project.musicClips.splice(index, 1)
  select('music', project.musicClips[index]?.id || project.musicClips[index - 1]?.id || null)
  if (!project.musicClips.length) {
    project.musicAssetId = null
    musicEl.value?.pause()
  }
}

function splitMusicClip(clip, time) {
  if (!clip) return false
  if (clip.legacy) {
    ensureExplicitMusicClips()
    clip = project.musicClips[0]
  }
  const cut = Math.max(clip.start, Math.min(Number(time) || 0, clip.end))
  if (cut <= clip.start + 0.05 || cut >= clip.end - 0.05) {
    setNotice('Курсор должен находиться внутри музыкального клипа', 'warning')
    return false
  }
  const right = {
    ...clone(clip),
    id: uid('music-clip'),
    start: cut,
    sourceOffset: Math.max(0, Number(clip.sourceOffset) + cut - clip.start),
    chapterId: null,
    chapterBound: false,
  }
  clip.end = cut
  clip.chapterId = null
  clip.chapterBound = false
  project.musicClips.push(right)
  project.musicClips.sort((a, b) => a.start - b.start)
  select('music', right.id)
  setNotice(`Клип разрезан в ${formatTime(cut, true)}`, 'success')
  return true
}

function splitSelectedMusicClip() {
  splitMusicClip(selectedMusicClip.value, currentTime.value)
}

function splitMusicClipAtPointer(event, clip) {
  seekTo(timelineTimeFromPointer(event))
  splitMusicClip(clip, currentTime.value)
}

function clipChapterLabel(clip) {
  const chapter = chapterAtTime(Number(clip.start) + 0.001)
  const clipVolume = Number.isFinite(Number(clip.volume)) ? Number(clip.volume) : 1
  const level = Math.round(clipVolume * project.music.volume * 100)
  return `${clip.chapterBound ? '🔗 ' : ''}${chapter?.title || formatTime(clip.start)} · ${level}%`
}

function startMaterialDrag(asset, event) {
  if (asset.type !== 'audio' || !event.dataTransfer) {
    event.preventDefault()
    return
  }
  event.dataTransfer.effectAllowed = 'copy'
  event.dataTransfer.setData('application/x-suviren-audio-asset-id', asset.id)
  event.dataTransfer.setData('text/plain', asset.name)
}

function onMusicLaneDragOver(event) {
  const hasAudioAsset = event.dataTransfer?.types?.includes('application/x-suviren-audio-asset-id')
  const hasFiles = event.dataTransfer?.types?.includes('Files')
  if (event.dataTransfer) event.dataTransfer.dropEffect = hasAudioAsset || hasFiles ? 'copy' : 'none'
}

function dropMusicOnTimeline(event) {
  const rawTime = timelineTimeFromPointer(event)
  const time = timelineSnap.value ? snapTimelineTime(rawTime) : rawTime
  const assetId = event.dataTransfer?.getData('application/x-suviren-audio-asset-id')
  if (assetId && assetById(assetId)?.type === 'audio') {
    ensureExplicitMusicClips()
    const clip = addMusicClip(assetId, time)
    select('music', clip.id)
    seekTo(clip.start)
    setNotice(`Музыка добавлена на «${chapterAtTime(clip.start)?.title || formatTime(clip.start)}»`, 'success')
    showTimelineSnap(null)
    return
  }
  const files = [...(event.dataTransfer?.files || [])]
  if (files.length) {
    importFiles(files, { asMusic: true, timelineTime: time })
    showTimelineSnap(null)
  }
}

function setNotice(text, kind = 'info', timeout = 4200) {
  notice.text = text
  notice.kind = kind
  clearTimeout(noticeTimer)
  if (timeout) noticeTimer = setTimeout(() => { notice.text = '' }, timeout)
}

function projectKindLabel(kind) {
  return ({ book: 'книга', video: 'видео', hybrid: 'книга + видео' }[kind] || kind)
}

async function loadProjectCatalog(includeArchived = false) {
  const response = await apiRequest(`/projects?include_archived=${includeArchived ? 'true' : 'false'}`)
  projectCatalog.value = response.projects || []
  return response.active_project_uuid || projectCatalog.value[0]?.uuid || ''
}

async function loadManagerCatalog() {
  const response = await apiRequest('/projects?include_archived=true')
  managerProjectCatalog.value = response.projects || []
}

async function openProjectManager() {
  try {
    await loadManagerCatalog()
    showProjectManager.value = true
  } catch (error) {
    setNotice(`Не удалось открыть библиотеку: ${error.message}`, 'error')
  }
}

async function openManagedProject(projectUuid) {
  showProjectManager.value = false
  await switchProject(projectUuid)
}

async function renameManagedProject(item) {
  const title = window.prompt('Новое название проекта', item.title)
  if (!title || title === item.title) return
  try {
    await apiRequest(`/projects/${encodeURIComponent(item.uuid)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title }),
    })
    await Promise.all([loadProjectCatalog(), loadManagerCatalog()])
    if (item.uuid === activeProjectUuid.value) {
      const record = await apiRequest(`/projects/${encodeURIComponent(item.uuid)}`)
      installBookRecord(record, {
        preserveDraft: bookSaveState.value !== 'saved' || Boolean(bookSavePromise),
      })
    }
  } catch (error) {
    setNotice(`Не удалось переименовать проект: ${error.message}`, 'error')
  }
}

async function duplicateManagedProject(item) {
  try {
    const duplicate = await apiRequest(`/projects/${encodeURIComponent(item.uuid)}/duplicate`, { method: 'POST' })
    await Promise.all([loadProjectCatalog(), loadManagerCatalog()])
    showProjectManager.value = false
    await switchProject(duplicate.uuid)
  } catch (error) {
    setNotice(`Не удалось дублировать проект: ${error.message}`, 'error')
  }
}

async function backupManagedProject(item) {
  try {
    const result = await apiRequest(`/projects/${encodeURIComponent(item.uuid)}/backup`, { method: 'POST' })
    setNotice(`Резервная копия создана: ${result.path}`, 'success', 7000)
  } catch (error) {
    setNotice(`Не удалось создать резервную копию: ${error.message}`, 'error')
  }
}

async function toggleManagedArchive(item) {
  const archived = !item.archived_at
  if (archived && !window.confirm(`Архивировать проект «${item.title}»?`)) return
  try {
    await apiRequest(`/projects/${encodeURIComponent(item.uuid)}/archive`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ archived }),
    })
    await Promise.all([loadProjectCatalog(), loadManagerCatalog()])
  } catch (error) {
    setNotice(`Не удалось изменить архив: ${error.message}`, 'error')
  }
}

function formatProjectDate(value) {
  if (!value) return 'дата неизвестна'
  return new Intl.DateTimeFormat('ru-RU', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value))
}

function normalizedVoiceId(value) {
  const voice = String(value || '')
  const folded = voice.toLocaleLowerCase('ru-RU')
  if (folded.includes('дмит') || folded === 'dmitry') return 'ru-RU-DmitryNeural'
  if (folded.includes('свет') || folded === 'svetlana') return 'ru-RU-SvetlanaNeural'
  return voice || 'ru-RU-SvetlanaNeural'
}

function normalizePreset(value, presets, fallback) {
  return presets.some((preset) => preset.value === value) ? value : fallback
}

function setBookInspectorState(state) {
  bookInspectorState.value = ['open', 'collapsed', 'hidden'].includes(state) ? state : 'open'
  localStorage.setItem('bookender.bookInspector', bookInspectorState.value)
}

function voiceLabel(voice) {
  const name = voice.name || voice.id
  const gender = ({ Female: 'жен.', Male: 'муж.' }[voice.gender] || voice.gender || '')
  return `${name} · ${voice.locale}${gender ? ` · ${gender}` : ''}`
}

function voiceShortLabel(voiceId) {
  const voice = ttsVoices.value.find((item) => item.id === voiceId)
  return voice?.name || voiceId || 'голос не указан'
}

function pitchPresetLabel(value) {
  return PITCH_PRESETS.find((preset) => preset.value === value)?.label || value || 'Нормальный'
}

async function loadTtsRuntime({ refresh = false } = {}) {
  try {
    const status = await apiRequest('/tts/status')
    Object.assign(ttsRuntime, status)
    if (!status.available) return
    const result = await apiRequest(`/tts/voices${refresh ? '?refresh=true' : ''}`)
    ttsVoices.value = result.voices || []
    if (!ttsVoices.value.some((voice) => voice.id === bookTts.voice)) {
      const fallback = ttsVoices.value.find((voice) => voice.id === 'ru-RU-SvetlanaNeural')
        || ttsVoices.value[0]
      if (fallback) bookTts.voice = fallback.id
    }
  } catch (error) {
    ttsRuntime.available = false
    ttsRuntime.message = error.message || 'Модуль озвучки не запущен. Проверьте установку компонентов TTS.'
  }
}

function hydrateBookState(record) {
  const book = record?.book
  bookMetadata.title = book?.title || record?.title || ''
  bookMetadata.author = book?.author || record?.author || ''
  bookMetadata.description = book?.description || record?.description || ''
  Object.assign(bookTts, {
    voice: normalizedVoiceId(record?.tts_settings?.voice),
    rate: normalizePreset(record?.tts_settings?.rate, RATE_PRESETS, '+0%'),
    pitch: normalizePreset(record?.tts_settings?.pitch, PITCH_PRESETS, '+0Hz'),
    volume: normalizePreset(record?.tts_settings?.volume, VOLUME_PRESETS, '+0%'),
    provider: record?.tts_settings?.provider || 'edge-tts',
  })
  const selectedVoice = ttsVoices.value.find((voice) => voice.id === bookTts.voice)
  if (selectedVoice?.language) voiceLanguage.value = selectedVoice.language
  const chapters = record?.chapters || []
  const preferred = chapters.find((item) => item.id === currentBookChapterId.value) || chapters[0] || null
  currentBookChapterId.value = preferred?.id || null
  bookChapterTitle.value = preferred?.title || ''
  bookChapterContent.value = preferred?.reader_content ?? preferred?.content ?? ''
  bookChapterTechnicalContent.value = preferred?.technical_content || ''
  bookChapterTechEnabled.value = Boolean(preferred?.has_technical_block)
  bookSelection.value = null
  viewedTextVersion.value = null
  bookSaveState.value = 'saved'
  bookVideoTextSyncState.value = 'idle'
  bookEditRevision = 0
  previewAudio.value = null
  const chapterAssets = (record?.audio_assets || []).filter((asset) => asset.chapter_id === preferred?.id)
  selectedBookAudioId.value = (
    chapterAssets.find((asset) => asset.is_active)?.id
    || chapterAssets[0]?.id
    || null
  )
  stopBookPlayer()
}

function installBookRecord(record, { preserveDraft = false } = {}) {
  const draft = preserveDraft && currentBookChapterId.value
    ? {
        chapterId: currentBookChapterId.value,
        title: bookChapterTitle.value,
        content: bookChapterContent.value,
        technicalContent: bookChapterTechnicalContent.value,
        techEnabled: bookChapterTechEnabled.value,
        saveState: bookSaveState.value,
        videoSyncState: bookVideoTextSyncState.value,
        revision: bookEditRevision,
      }
    : null
  activeProjectRecord.value = record
  hydrateBookState(record)
  if (!draft || !record?.chapters?.some((chapter) => chapter.id === draft.chapterId)) return
  currentBookChapterId.value = draft.chapterId
  bookChapterTitle.value = draft.title
  bookChapterContent.value = draft.content
  bookChapterTechnicalContent.value = draft.technicalContent
  bookChapterTechEnabled.value = draft.techEnabled
  bookSaveState.value = draft.saveState
  bookVideoTextSyncState.value = draft.videoSyncState
  bookEditRevision = draft.revision
}

function addBookAudioToVideo(record) {
  const grouped = new Map()
  const chapterHashes = new Map(
    (record?.chapters || []).map((chapter) => [chapter.id, chapter.narration_hash || chapter.content_hash]),
  )
  for (const audio of record?.audio_assets || []) {
    const chapterHash = chapterHashes.get(audio.chapter_id)
    if (
      audio.is_stale
      || (audio.source_text_hash && chapterHash && audio.source_text_hash !== chapterHash)
    ) continue
    const key = audio.chapter_id ?? `book-${audio.id}`
    const current = grouped.get(key)
    const rank = [
      Number(Boolean(audio.is_active)),
      Number(audio.version_number || 0),
      Number(audio.id || 0),
    ]
    const currentRank = [
      Number(Boolean(current?.is_active)),
      Number(current?.version_number || 0),
      Number(current?.id || 0),
    ]
    if (!current || rank.some((value, index) => (
      value > currentRank[index] && rank.slice(0, index).every((part, partIndex) => part === currentRank[partIndex])
    ))) {
      grouped.set(key, audio)
    }
  }
  const livePaths = new Set(
    [...grouped.values()]
      .map((audio) => audio.file_path)
      .filter((path) => path && !path.startsWith('external:')),
  )
  const retainedMaterials = project.materials.filter((item) => (
    item.role !== 'chapter-audio' || livePaths.has(item.serverPath)
  ))
  project.materials.splice(0, project.materials.length, ...retainedMaterials)
  const materialByChapter = new Map()
  for (const audio of grouped.values()) {
    if (!audio.file_path || audio.file_path.startsWith('external:')) continue
    const serverPath = audio.file_path
    const chapter = record.chapters?.find((item) => item.id === audio.chapter_id)
    let material = project.materials.find((item) => item.serverPath === serverPath)
    if (!material) {
      material = {
        id: `chapter-audio-${audio.id}`,
        type: 'audio',
        name: `${chapter?.title || 'Озвучка'}.mp3`,
        size: Number(audio.file_size || 0),
        serverPath,
        src: mediaUrl(serverPath),
        status: 'ready',
        progress: 1,
      }
      project.materials.push(material)
    }
    material.chapterId = audio.chapter_id
    material.sourceTextHash = audio.source_text_hash
    material.role = 'chapter-audio'
    materialByChapter.set(audio.chapter_id, material)
  }
  return materialByChapter
}

function addBookVisualsToVideo(record) {
  const latestByChapter = new Map()
  for (const visual of record?.visual_assets || []) {
    if (visual.asset_type !== 'chapter-image' || !visual.chapter_id || !visual.file_path) continue
    const current = latestByChapter.get(visual.chapter_id)
    if (!current || Number(visual.id) > Number(current.id)) {
      latestByChapter.set(visual.chapter_id, visual)
    }
  }
  const livePaths = new Set([...latestByChapter.values()].map((visual) => visual.file_path))
  const retainedMaterials = project.materials.filter((item) => (
    item.role !== 'chapter-image' || livePaths.has(item.serverPath)
  ))
  project.materials.splice(0, project.materials.length, ...retainedMaterials)
  const materialByChapter = new Map()
  for (const [chapterId, visual] of latestByChapter) {
    let metadata = {}
    try { metadata = JSON.parse(visual.metadata_json || '{}') } catch { /* optional metadata */ }
    let material = project.materials.find((item) => item.serverPath === visual.file_path)
    if (!material) {
      material = {
        id: `chapter-image-${visual.id}`,
        type: 'image',
        name: visual.title || `Глава ${chapterId}`,
        size: Number(metadata.size || 0),
        serverPath: visual.file_path,
        src: mediaUrl(visual.file_path),
        status: 'ready',
        progress: 1,
        chapterId,
        role: 'chapter-image',
      }
      project.materials.push(material)
    }
    materialByChapter.set(chapterId, material)
  }
  return materialByChapter
}

function ensureDefaultVideoCover() {
  let material = project.materials.find((item) => item.role === 'default-cover')
  if (!material) {
    material = {
      id: 'book-wunderwaffe-default-cover',
      type: 'image',
      name: 'Book Wunderwaffe · обложка по умолчанию',
      size: 0,
      serverPath: 'assets/bookender-studio-icon.png',
      src: '/bookender-studio-icon.png',
      status: 'ready',
      progress: 1,
      role: 'default-cover',
    }
    project.materials.push(material)
  }
  project.coverAssetId = material.id
  return material
}

function syncBookChaptersToVideo(record, master = null) {
  if (!record?.book) return
  const audioMaterials = addBookAudioToVideo(record)
  const images = addBookVisualsToVideo(record)
  const selectedCover = assetById(project.coverAssetId)
  if (project.coverMode === 'background-only') {
    project.coverAssetId = null
    if (!backgroundAsset.value && images.size) {
      project.backgroundAssetId = images.values().next().value.id
    }
  } else {
    if (images.size && (!selectedCover || selectedCover.role === 'default-cover')) {
      project.coverAssetId = images.values().next().value.id
    } else if (!selectedCover) {
      ensureDefaultVideoCover()
    }
  }
  const timings = new Map((master?.chapters || []).map((item) => [item.chapter_id, item]))
  const existing = new Map(project.chapters.map((item) => [item.chapterId, item]))
  const sourceChapters = master?.chapters?.length
    ? (record?.chapters || []).filter((chapter) => timings.has(chapter.id))
    : (record?.chapters || [])
  let sourceCursor = 0
  let timelineCursor = 0
  project.chapters = sourceChapters.map((chapter, index) => {
    const timing = timings.get(chapter.id)
    const previous = existing.get(chapter.id) || {}
    const sourceStart = timing
      ? Number(timing.start_seconds)
      : Math.max(sourceCursor, chapterSourceStart(previous) || index)
    const sourceEnd = timing
      ? Number(timing.end_seconds)
      : Math.max(sourceStart + 1, chapterSourceEnd(previous) || sourceStart + 1)
    const playbackRate = previous.id ? chapterPlaybackRate(previous) : 1
    const start = timelineCursor
    const end = start + Math.max(0.1, sourceEnd - sourceStart) / playbackRate
    sourceCursor = sourceEnd
    timelineCursor = end
    return {
      ...previous,
      id: previous.id || `book-chapter-${chapter.id}`,
      chapterId: chapter.id,
      title: chapter.title,
      text: chapter.reader_content ?? chapter.content,
      isZero: Boolean(chapter.is_zero),
      sourceContentHash: chapter.narration_hash || chapter.content_hash,
      start_seconds: start,
      end_seconds: end,
      sourceStartSeconds: sourceStart,
      sourceEndSeconds: sourceEnd,
      playbackRate,
      wordTimings: Array.isArray(timing?.word_timings) ? timing.word_timings : [],
      imageAssetId: images.get(chapter.id)?.id || null,
      visualInserts: previous.visualInserts || [],
      audioAssetId: audioMaterials.get(chapter.id)?.id || null,
    }
  })
  if (master?.file_path) {
    const retainedMaterials = project.materials.filter((item) => (
      item.role !== 'book-master' || item.serverPath === master.file_path
    ))
    project.materials.splice(0, project.materials.length, ...retainedMaterials)
    let material = project.materials.find((item) => item.serverPath === master.file_path)
    if (!material) {
      material = {
        id: `book-master-${master.source_asset_ids.join('-')}`,
        type: 'audio',
        name: `${record?.book?.title || record?.title || 'Книга'} · мастер.mp3`,
        size: Number(master.file_size || 0),
        serverPath: master.file_path,
        src: mediaUrl(master.file_path),
        status: 'ready',
        progress: 1,
        role: 'book-master',
      }
      project.materials.push(material)
    }
    project.audioAssetId = material.id
    const total = timelineCursor || Number(master.duration) || sourceCursor
    project.scenes = project.scenes
      .filter((scene) => Number(scene.start) < total)
      .map((scene) => ({
        ...scene,
        start: Math.max(0, Number(scene.start) || 0),
        end: Math.min(total, Math.max((Number(scene.start) || 0) + 0.1, Number(scene.end) || total)),
      }))
    if (!project.scenes.length) {
      project.scenes = [{ id: uid('scene'), name: 'Основная сцена', start: 0, end: total, backgroundAssetId: null }]
    } else if (project.scenes.length === 1) {
      project.scenes[0].start = 0
      project.scenes[0].end = total
    }
  }
}

async function prepareBookVideoProject(record) {
  let master = null
  if ((record?.audio_assets || []).length) {
    master = await apiRequest(
      `/projects/${encodeURIComponent(activeProjectUuid.value)}/video-audio/master`,
      { method: 'POST' },
    )
  }
  syncBookChaptersToVideo(record, master)
  dirty.value = true
  return master
}

async function publishVideoCompatibility(projectUuid, editionId, payload) {
  if (!backend.online || !projectUuid || !editionId) return
  await apiRequest(
    `/editor-project?project_uuid=${encodeURIComponent(projectUuid)}&edition_id=${encodeURIComponent(editionId)}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    },
  )
}

async function flushActiveWorkspace() {
  clearTimeout(bookAutosaveTimer)
  if (bookSaveState.value === 'modified' || bookSaveState.value === 'error') {
    const saved = await saveCurrentChapter({ silent: true })
    if (!saved) return false
  }
  if (dirty.value) {
    const saved = await saveProject({ silent: true })
    if (!saved) return false
  }
  return true
}

async function performProjectSwitch(projectUuid, { initial = false } = {}) {
  if (!initial && projectUuid === activeProjectUuid.value) return true
  try {
    if (!initial && !(await flushActiveWorkspace())) {
      setNotice('Переключение отменено: текущие изменения не сохранены', 'error')
      return false
    }
    stopTtsPolling()
    pauseAll()
    const record = await apiRequest(`/projects/${encodeURIComponent(projectUuid)}/open`, { method: 'POST' })
    activeProjectUuid.value = projectUuid
    activeProjectRecord.value = record
    hydrateBookState(record)
    ttsJobs.value = []
    narrationJobIds.value = []
    if (record.book) {
      try {
        const jobs = await apiRequest(`/projects/${encodeURIComponent(projectUuid)}/tts-jobs`)
        ttsJobs.value = jobs.jobs || []
        narrationJobIds.value = ttsJobs.value
          .filter((job) => ['queued', 'running'].includes(job.status))
          .map((job) => job.uuid)
        ttsNow.value = Date.now()
        if (narrationJobIds.value.length) pollTtsBook()
      } catch { /* TTS history is optional */ }
    }
    const edition = record.video_editions?.[0] || null
    activeVideoEditionId.value = edition?.id || null
    if (edition) {
      replaceProject(edition.settings)
      syncBookChaptersToVideo(record)
    } else {
      const emptyVideo = freshProject()
      emptyVideo.title = record.title
      emptyVideo.author = record.author
      emptyVideo.chapters = (record.chapters || []).map((chapter) => ({
        id: `book-chapter-${chapter.id}`,
        chapterId: chapter.id,
        title: chapter.title,
        text: chapter.reader_content ?? chapter.content,
        isZero: Boolean(chapter.is_zero),
        sourceContentHash: chapter.narration_hash || chapter.content_hash,
        start_seconds: 0,
        end_seconds: 0,
      }))
      replaceProject(emptyVideo)
    }
    if (initial) activeWorkspace.value = record.book ? 'book' : 'video'
    dirty.value = false
    await loadProjectCatalog()
    setNotice(`Открыт проект «${record.title}»`, 'success', 2200)
    return true
  } catch (error) {
    setNotice(`Не удалось открыть проект: ${error.message}`, 'error', 7000)
    return false
  }
}

async function switchProject(projectUuid, options = {}) {
  if (!projectUuid) return false
  queuedProjectSwitch = { projectUuid, options }
  if (projectSwitchPromise) return projectSwitchPromise

  switchingProject.value = true
  projectSwitchPromise = (async () => {
    let switched = true
    while (queuedProjectSwitch) {
      const request = queuedProjectSwitch
      queuedProjectSwitch = null
      switched = await performProjectSwitch(request.projectUuid, request.options)
      if (!switched) queuedProjectSwitch = null
    }
    return switched
  })()
  try {
    return await projectSwitchPromise
  } finally {
    projectSwitchPromise = null
    switchingProject.value = false
  }
}

async function setWorkspace(workspace) {
  if (workspace === activeWorkspace.value) return
  if (workspace === 'video' && activeProjectRecord.value?.book) {
    const readyMaster = project.materials.find(item => item.id === project.audioAssetId && item.role === 'book-master')
    if (activeVideoEditionId.value && readyMaster) {
      if (!(await flushActiveWorkspace())) return
      activeWorkspace.value = 'video'
      await nextTick()
      audioEl.value?.load()
      await refreshWaveform()
      return
    }
    await openBookInVideo()
    return
  }
  if (!(await flushActiveWorkspace())) return
  activeWorkspace.value = workspace
}

async function createEcosystemProject() {
  if (activeWorkspace.value === 'video') {
    const title = window.prompt('Название нового видеопроекта', 'Новая видеокнига')
    if (!title) return
    await createProjectRequest({ title, project_kind: 'video' })
  } else {
    await createBookProject()
  }
}

async function createBookProject() {
  const title = window.prompt('Название новой книги', 'Новая книга')
  if (!title) return
  const author = window.prompt('Автор', '') ?? ''
  await createProjectRequest({
    title,
    author,
    project_kind: 'book',
    create_first_chapter: true,
    voice: bookTts.voice,
  })
}

async function createProjectRequest(payload) {
  if (!(await flushActiveWorkspace())) return
  try {
    const created = await apiRequest('/projects', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    await loadProjectCatalog()
    await switchProject(created.uuid, { initial: true })
  } catch (error) {
    setNotice(`Не удалось создать проект: ${error.message}`, 'error')
  }
}

async function createBookPart() {
  if (!activeProjectUuid.value) return
  try {
    const record = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/book`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: activeProjectRecord.value?.title, language: 'ru' }),
    })
    installBookRecord(record, {
      preserveDraft: bookVideoTextSyncState.value === 'error',
    })
    await createBookChapter()
  } catch (error) {
    setNotice(`Не удалось создать книгу: ${error.message}`, 'error')
  }
}

async function selectBookChapter(chapterId) {
  if (chapterId === currentBookChapterId.value) return
  clearTimeout(bookAutosaveTimer)
  if (!(await saveCurrentChapter({ silent: true }))) return
  const chapter = activeProjectRecord.value?.chapters?.find((item) => item.id === chapterId)
  if (!chapter) return
  currentBookChapterId.value = chapterId
  bookChapterTitle.value = chapter.title
  bookChapterContent.value = chapter.reader_content ?? chapter.content
  bookChapterTechnicalContent.value = chapter.technical_content || ''
  bookChapterTechEnabled.value = Boolean(chapter.has_technical_block)
  bookSaveState.value = 'saved'
  bookEditRevision = 0
  previewAudio.value = null
  const assets = (activeProjectRecord.value?.audio_assets || []).filter((asset) => asset.chapter_id === chapterId)
  selectedBookAudioId.value = assets.find((asset) => asset.is_active)?.id || assets[0]?.id || null
  stopBookPlayer()
}

function markBookDirty() {
  bookEditRevision += 1
  bookSaveState.value = 'modified'
  clearTimeout(bookAutosaveTimer)
  bookAutosaveTimer = setTimeout(() => { void saveCurrentChapter({ silent: true }) }, 900)
}

function composeBookChapterContent() {
  const prose = String(bookChapterContent.value || '').replace(/^\s*\n/, '')
  const technical = String(bookChapterTechnicalContent.value || '').trim()
  if (!bookChapterTechEnabled.value || !technical) return prose
  return `[[TECH_CHAPTER]]\n${technical}\n[[/TECH_CHAPTER]]${prose ? `\n\n${prose}` : ''}`
}

function enableChapterTech() {
  bookChapterTechEnabled.value = true
  if (!bookChapterTechnicalContent.value.trim()) {
    bookChapterTechnicalContent.value = `chapter: ${bookChapterTitle.value || 'Без названия'}\n\n[VISUAL]\n\n[CHARACTERS]\n\n[LOCATION]\n\n[CONTINUITY]\n\n[DRAMATURGY]\n\n[ILLUSTRATION_RULES]\n`
  }
  markBookDirty()
}

function removeChapterTech() {
  if (!window.confirm('Удалить служебный TECH-блок этой главы? Художественный текст останется.')) return
  bookChapterTechEnabled.value = false
  bookChapterTechnicalContent.value = ''
  markBookDirty()
}

async function syncSavedBookChapterToVideo(saved) {
  if (!activeVideoEditionId.value) {
    bookVideoTextSyncState.value = 'idle'
    return true
  }
  let videoChapter = project.chapters.find((chapter) => Number(chapter.chapterId) === Number(saved.id))
  if (!videoChapter) {
    syncBookChaptersToVideo(activeProjectRecord.value)
    videoChapter = project.chapters.find((chapter) => Number(chapter.chapterId) === Number(saved.id))
  }
  if (!videoChapter) {
    bookVideoTextSyncState.value = 'error'
    return false
  }
  const readerContent = saved.reader_content ?? saved.content
  const narrationHash = saved.narration_hash || saved.content_hash
  const contentChanged = (
    videoChapter.text !== readerContent
    || (videoChapter.sourceContentHash && videoChapter.sourceContentHash !== narrationHash)
  )
  const invalidAudioIds = new Set()
  for (const material of project.materials) {
    if (contentChanged && material.role === 'book-master') invalidAudioIds.add(material.id)
    if (
      material.role === 'chapter-audio'
      && Number(material.chapterId) === Number(saved.id)
      && material.sourceTextHash !== narrationHash
    ) invalidAudioIds.add(material.id)
  }
  if (invalidAudioIds.has(videoChapter.audioAssetId)) videoChapter.audioAssetId = null
  if (invalidAudioIds.has(project.audioAssetId)) project.audioAssetId = null
  if (invalidAudioIds.size) {
    const retained = project.materials.filter((material) => !invalidAudioIds.has(material.id))
    project.materials.splice(0, project.materials.length, ...retained)
  }
  videoChapter.title = saved.title
  videoChapter.text = readerContent
  videoChapter.isZero = Boolean(saved.is_zero)
  videoChapter.sourceContentHash = narrationHash
  dirty.value = true
  bookVideoTextSyncState.value = 'syncing'
  try {
    await publishVideoCompatibility(
      activeProjectUuid.value,
      activeVideoEditionId.value,
      serializeProject(),
    )
    dirty.value = false
    bookVideoTextSyncState.value = 'synced'
    return true
  } catch {
    bookVideoTextSyncState.value = 'error'
    return false
  }
}

function escapeHtml(value) {
  return String(value).replace(/[&<>]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' })[char])
}

function metaColor(index) { return META_COLORS[Math.abs(Number(index) || 0) % META_COLORS.length] }

function captureBookSelection(event) {
  const start = event.target.selectionStart, end = event.target.selectionEnd
  bookSelection.value = end > start ? { start, end, text: bookChapterContent.value.slice(start, end) } : null
}

function openMetaNoteAtCaret(event) {
  if (event.target.selectionStart !== event.target.selectionEnd) return
  const position = event.target.selectionStart
  const item = currentMetaNotes.value.find((value) => !value.detached && position >= value.start && position <= value.end)
  if (!item) { activeMetaNoteId.value = null; return }
  activeMetaNoteId.value = item.id; activeMetaNoteDraft.value = item.note
}

async function saveActiveMetaNote() {
  const raw = activeProjectRecord.value.chapter_meta_notes.find((value) => value.id === activeMetaNoteId.value)
  if (!raw || !activeMetaNoteDraft.value.trim()) return
  raw.note = activeMetaNoteDraft.value.trim(); await saveMetaNotes(); activeMetaNoteId.value = null
}

function syncBookHighlightScroll() {
  if (!bookTextEditor.value || !bookHighlightLayer.value) return
  bookHighlightLayer.value.scrollTop = bookTextEditor.value.scrollTop
  bookHighlightLayer.value.scrollLeft = bookTextEditor.value.scrollLeft
}

function reconcileMetaNotes() {
  const text = bookChapterContent.value
  for (const raw of activeProjectRecord.value?.chapter_meta_notes || []) {
    if (Number(raw.chapter_id) !== Number(currentBookChapterId.value)) continue
    const item = normalizeMetaNote(raw)
    if (text.slice(item.start, item.end) === item.selectedText) { raw.detached = 0; continue }
    const radius = Math.max(500, item.selectedText.length * 4)
    const from = Math.max(0, item.start - radius), found = text.indexOf(item.selectedText, from)
    if (found >= 0 && found <= item.end + radius) {
      raw.start = raw.start_offset = found; raw.end = raw.end_offset = found + item.selectedText.length; raw.detached = 0
    } else raw.detached = 1
  }
}

function onBookTextInput() {
  reconcileMetaNotes(); markBookDirty(); clearTimeout(metaNotesSaveTimer)
  metaNotesSaveTimer = setTimeout(() => { void saveMetaNotes() }, 900)
}

async function saveMetaNotes() {
  if (!currentBookChapterId.value) return
  const notes = currentMetaNotes.value
  await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters/${currentBookChapterId.value}/meta-notes`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ notes }),
  })
}

async function createMetaNote() {
  const selected = bookSelection.value
  if (!selected) return
  const note = window.prompt('Комментарий к выделенному тексту')
  if (!note?.trim()) return
  const all = activeProjectRecord.value.chapter_meta_notes ||= []
  all.push({ id: uid('note'), chapter_id: currentBookChapterId.value, start: selected.start, end: selected.end, selectedText: selected.text, note: note.trim(), colorIndex: currentMetaNotes.value.length % META_COLORS.length, detached: 0, createdAt: new Date().toISOString() })
  bookSelection.value = null
  await saveMetaNotes()
}

async function editMetaNote(item) {
  const note = window.prompt('Комментарий', item.note)
  if (note === null || !note.trim()) return
  const raw = activeProjectRecord.value.chapter_meta_notes.find((value) => value.id === item.id); if (raw) raw.note = note.trim()
  await saveMetaNotes()
}

async function deleteMetaNote(item) {
  if (!window.confirm('Удалить мета-пометку?')) return
  activeProjectRecord.value.chapter_meta_notes = activeProjectRecord.value.chapter_meta_notes.filter((value) => value.id !== item.id)
  activeMetaNoteId.value = null
  await saveMetaNotes()
}

async function focusMetaNote(item) {
  if (item.detached) return
  await nextTick(); bookTextEditor.value?.focus(); bookTextEditor.value?.setSelectionRange(item.start, item.end)
  const line = bookChapterContent.value.slice(0, item.start).split('\n').length - 1
  bookTextEditor.value.scrollTop = Math.max(0, line * 28 - bookTextEditor.value.clientHeight / 3); syncBookHighlightScroll()
}

async function refreshBookRecord() {
  const record = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}`)
  installBookRecord(record)
}

async function saveTextVersion() {
  if (!(await saveCurrentChapter({ silent: true }))) return
  clearTimeout(metaNotesSaveTimer); await saveMetaNotes()
  const title = window.prompt('Короткое название версии (необязательно)', '')
  if (title === null) return
  await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters/${currentBookChapterId.value}/text-versions`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title }) })
  await refreshBookRecord(); setNotice('Версия текста сохранена', 'success')
}

async function viewTextVersion(item) { viewedTextVersion.value = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters/${currentBookChapterId.value}/text-versions/${item.id}`) }
async function renameTextVersion(item) { const title = window.prompt('Название версии', item.title || ''); if (title === null) return; await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters/${currentBookChapterId.value}/text-versions/${item.id}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ title }) }); await refreshBookRecord() }
async function deleteTextVersion(item) { if (!window.confirm('Удалить эту версию?')) return; await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters/${currentBookChapterId.value}/text-versions/${item.id}`, { method: 'DELETE' }); viewedTextVersion.value = null; await refreshBookRecord() }
async function restoreTextVersion(item) { if (!window.confirm('Восстановить эту версию? Текущий текст будет автоматически сохранён отдельной версией.')) return; await saveCurrentChapter({ silent: true }); await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters/${currentBookChapterId.value}/text-versions/${item.id}/restore`, { method: 'POST' }); viewedTextVersion.value = null; await refreshBookRecord(); setNotice('Версия восстановлена; прежний текст сохранён', 'success') }

async function saveCurrentChapter({ silent = false } = {}) {
  const chapter = currentBookChapter.value
  if (!chapter) return true
  const storedContent = composeBookChapterContent()
  if (
    bookSaveState.value === 'saved'
    && chapter.title === bookChapterTitle.value
    && chapter.content === storedContent
  ) {
    if (bookVideoTextSyncState.value === 'error') {
      await syncSavedBookChapterToVideo(chapter)
    }
    return true
  }
  if (bookSavePromise) return bookSavePromise

  const chapterId = chapter.id
  bookSavePromise = (async () => {
    while (currentBookChapterId.value === chapterId) {
      const revision = bookEditRevision
      const title = bookChapterTitle.value
      const content = composeBookChapterContent()
      const removeTechnicalBlock = Boolean(chapter.has_technical_block && !bookChapterTechEnabled.value)
      bookSaveState.value = 'saving'
      try {
        const saved = await apiRequest(
          `/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters/${chapterId}`,
          {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title, content, remove_technical_block: removeTechnicalBlock }),
          },
        )
        const index = activeProjectRecord.value.chapters.findIndex((item) => item.id === saved.id)
        if (index >= 0) activeProjectRecord.value.chapters[index] = saved
        for (const asset of activeProjectRecord.value.audio_assets || []) {
          if (asset.chapter_id === saved.id) {
            asset.is_stale = Boolean(asset.source_text_hash && asset.source_text_hash !== (saved.narration_hash || saved.content_hash))
          }
        }
        const unchanged = (
          currentBookChapterId.value === chapterId
          && bookEditRevision === revision
          && bookChapterTitle.value === title
          && composeBookChapterContent() === content
        )
        if (!unchanged) {
          bookSaveState.value = 'modified'
          continue
        }
        await syncSavedBookChapterToVideo(saved)
        if (
          currentBookChapterId.value !== chapterId
          ||
          bookEditRevision !== revision
          || bookChapterTitle.value !== title
          || composeBookChapterContent() !== content
        ) {
          bookSaveState.value = 'modified'
          continue
        }
        bookSaveState.value = 'saved'
        if (!silent) setNotice('Глава и текст видеоверсии сохранены', 'success', 1800)
        return true
      } catch (error) {
        bookSaveState.value = 'error'
        if (!silent) setNotice(`Не удалось сохранить главу: ${error.message}`, 'error')
        return false
      }
    }
    return true
  })()
  try {
    return await bookSavePromise
  } finally {
    bookSavePromise = null
  }
}

function chapterDisplayNumber(chapter) {
  if (chapter?.is_zero) return 0
  const regular = (activeProjectRecord.value?.chapters || []).filter((item) => !item.is_zero)
  const index = regular.findIndex((item) => item.id === chapter?.id)
  return index >= 0 ? index + 1 : Number(chapter?.position || 0) + 1
}

async function syncBookStructureToVideo() {
  if (!activeVideoEditionId.value) return
  bookVideoTextSyncState.value = 'syncing'
  try {
    await rebuildBookVideo(activeProjectRecord.value)
    bookVideoTextSyncState.value = 'synced'
  } catch {
    syncBookChaptersToVideo(activeProjectRecord.value)
    bookVideoTextSyncState.value = 'error'
  }
}

async function createBookChapter({ isZero = false } = {}) {
  if (!activeProjectUuid.value) return
  if (!(await saveCurrentChapter({ silent: true }))) return
  try {
    const chapter = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: isZero ? 'Глава 0' : '', content: '', is_zero: isZero }),
    })
    activeProjectRecord.value = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}`)
    await selectBookChapter(chapter.id)
    await syncBookStructureToVideo()
  } catch (error) {
    setNotice(`Не удалось создать главу: ${error.message}`, 'error')
  }
}

async function createBookZeroChapter() {
  await createBookChapter({ isZero: true })
}

async function duplicateBookChapter() {
  const chapter = currentBookChapter.value
  if (!chapter || !(await saveCurrentChapter({ silent: true }))) return
  try {
    const duplicate = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title: `${bookChapterTitle.value || chapter.title} — копия`,
        content: bookChapterContent.value,
      }),
    })
    activeProjectRecord.value = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}`)
    await selectBookChapter(duplicate.id)
    await syncBookStructureToVideo()
  } catch (error) {
    setNotice(`Не удалось дублировать главу: ${error.message}`, 'error')
  }
}

async function moveBookChapter(direction) {
  const chapters = activeProjectRecord.value?.chapters || []
  const index = chapters.findIndex((item) => item.id === currentBookChapterId.value)
  const nextIndex = index + direction
  if (index < 0 || nextIndex < 0 || nextIndex >= chapters.length) return
  const reordered = [...chapters]
  const [item] = reordered.splice(index, 1)
  reordered.splice(nextIndex, 0, item)
  await persistBookChapterOrder(reordered)
}

async function persistBookChapterOrder(reordered) {
  if (!(await saveCurrentChapter({ silent: true }))) return false
  try {
    await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters/reorder`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ chapter_ids: reordered.map((chapter) => chapter.id) }),
    })
    reordered.forEach((chapter, position) => { chapter.position = position })
    activeProjectRecord.value.chapters = reordered
    await syncBookStructureToVideo()
    setNotice('Порядок глав и видеоверсия обновлены', 'success', 2200)
    return true
  } catch (error) {
    setNotice(`Не удалось изменить порядок: ${error.message}`, 'error')
    return false
  }
}

function startBookChapterDrag(chapter, event) {
  draggedBookChapterId.value = chapter.id
  bookChapterDropId.value = chapter.id
  bookChapterDropSide.value = 'before'
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('application/x-bookender-chapter-id', String(chapter.id))
  }
}

function onBookChapterDragOver(chapter, event) {
  if (!draggedBookChapterId.value || draggedBookChapterId.value === chapter.id) return
  const rect = event.currentTarget.getBoundingClientRect()
  bookChapterDropId.value = chapter.id
  bookChapterDropSide.value = event.clientY < rect.top + rect.height / 2 ? 'before' : 'after'
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
}

async function dropBookChapter(target) {
  const draggedId = draggedBookChapterId.value
  const chapters = [...(activeProjectRecord.value?.chapters || [])]
  const from = chapters.findIndex((chapter) => chapter.id === draggedId)
  let to = chapters.findIndex((chapter) => chapter.id === target.id)
  const side = bookChapterDropSide.value
  finishBookChapterDrag()
  if (from < 0 || to < 0 || from === to) return
  const [dragged] = chapters.splice(from, 1)
  to = chapters.findIndex((chapter) => chapter.id === target.id)
  if (side === 'after') to += 1
  chapters.splice(to, 0, dragged)
  await persistBookChapterOrder(chapters)
}

function finishBookChapterDrag() {
  draggedBookChapterId.value = null
  bookChapterDropId.value = null
  bookChapterDropSide.value = 'before'
}

async function archiveBookChapter() {
  const chapter = currentBookChapter.value
  if (!chapter || !window.confirm(`Удалить главу «${chapter.title}»? Она будет архивирована.`)) return
  try {
    await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters/${chapter.id}`, { method: 'DELETE' })
    activeProjectRecord.value = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}`)
    currentBookChapterId.value = null
    hydrateBookState(activeProjectRecord.value)
    await syncBookStructureToVideo()
  } catch (error) {
    setNotice(`Не удалось удалить главу: ${error.message}`, 'error')
  }
}

async function saveBookMetadata() {
  if (!(await saveCurrentChapter({ silent: true }))) return
  try {
    const record = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(bookMetadata),
    })
    installBookRecord(record, {
      preserveDraft: bookVideoTextSyncState.value === 'error',
    })
    await loadProjectCatalog()
  } catch (error) {
    setNotice(`Не удалось сохранить метаданные: ${error.message}`, 'error')
  }
}

async function saveBookTtsSettings() {
  try {
    const settings = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/tts-settings`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(bookTts),
    })
    activeProjectRecord.value.tts_settings = settings
    return true
  } catch (error) {
    setNotice(`Не удалось сохранить настройки TTS: ${error.message}`, 'error')
    return false
  }
}

async function narrateCurrentChapter() {
  if (!currentBookChapter.value || !(await saveCurrentChapter({ silent: true }))) return
  if (!(await saveBookTtsSettings())) return
  try {
    const job = await apiRequest(
      `/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters/${currentBookChapter.value.id}/tts`,
      { method: 'POST' },
    )
    ttsJobs.value.unshift(job)
    narrationJobIds.value = [job.uuid]
    ttsNow.value = Date.now()
    pollTtsJob(job.uuid)
  } catch (error) {
    setNotice(`Не удалось запустить озвучивание: ${error.message}`, 'error')
  }
}

async function narrateWholeBook() {
  if (!(await saveCurrentChapter({ silent: true }))) return
  if (!(await saveBookTtsSettings())) return
  if (!window.confirm('Поставить все непустые главы в последовательную очередь озвучивания?')) return
  try {
    const result = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/tts`, { method: 'POST' })
    ttsJobs.value = [...(result.jobs || []), ...ttsJobs.value]
    narrationJobIds.value = (result.jobs || []).map((job) => job.uuid)
    ttsNow.value = Date.now()
    if (result.jobs?.length) pollTtsBook()
    setNotice(`В очередь добавлено глав: ${result.queued}`, 'success')
  } catch (error) {
    setNotice(`Не удалось озвучить книгу: ${error.message}`, 'error')
  }
}

async function cancelActiveTtsJobs() {
  if (!activeProjectUuid.value || !activeTtsJobRunning.value || ttsStopping.value) return
  ttsStopping.value = true
  ttsLogOpen.value = true
  try {
    const result = await apiRequest(
      `/projects/${encodeURIComponent(activeProjectUuid.value)}/tts/cancel`,
      { method: 'POST' },
    )
    for (const changed of result.jobs || []) {
      const index = ttsJobs.value.findIndex((job) => job.uuid === changed.uuid)
      if (index >= 0) ttsJobs.value[index] = changed
    }
    const running = (result.jobs || []).find((job) => job.status === 'running')
    if (running) ttsDebugJobId.value = running.uuid
    setNotice(
      running
        ? 'Запрошена остановка. Текущий сетевой запрос завершится не позднее таймаута.'
        : `Остановлено задач: ${result.cancelled || 0}`,
      'warning',
      6500,
    )
  } catch (error) {
    setNotice(`Не удалось остановить озвучивание: ${error.message}`, 'error', 7000)
  } finally {
    ttsStopping.value = false
  }
}

function selectTtsDebugJob(job) {
  ttsDebugJobId.value = job?.uuid || ''
  ttsLogOpen.value = true
}

function formatTtsDiagnosticTime(value) {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '—'
  return date.toLocaleTimeString('ru-RU', { hour12: false })
}

function formatTtsDiagnosticEvent(event) {
  const level = String(event?.level || 'info').toUpperCase()
  const details = event?.details && Object.keys(event.details).length
    ? ` ${JSON.stringify(event.details)}`
    : ''
  return `[${formatTtsDiagnosticTime(event?.timestamp)}] ${level} ${event?.message || event?.event || 'событие'}${details}`
}

function stopTtsPolling() {
  ttsPoller?.stop()
  ttsPoller = null
}

function startTtsPolling(task, intervalMs) {
  stopTtsPolling()
  ttsPoller = createSerialPoller(task, { intervalMs })
  ttsPoller.start()
}

function pollTtsBook() {
  const projectUuid = activeProjectUuid.value
  ttsPollFailures = 0
  startTtsPolling(async () => {
    try {
      ttsNow.value = Date.now()
      const result = await apiRequest(`/projects/${encodeURIComponent(projectUuid)}/tts-jobs`)
      if (projectUuid !== activeProjectUuid.value) return false
      ttsPollFailures = 0
      ttsJobs.value = result.jobs || []
      if (!ttsJobs.value.some((job) => ['queued', 'running'].includes(job.status))) {
        const draftSaved = await saveCurrentChapter({ silent: true })
        const record = await apiRequest(`/projects/${encodeURIComponent(projectUuid)}`)
        if (projectUuid !== activeProjectUuid.value) return false
        installBookRecord(record, { preserveDraft: !draftSaved })
        if (activeVideoEditionId.value && draftSaved) {
          try { await rebuildBookVideo(record) } catch { /* video can be rebuilt on next open */ }
        }
        const failed = visibleTtsJobs.value.filter((job) => job.status === 'failed').length
        const cancelled = visibleTtsJobs.value.filter((job) => job.status === 'cancelled').length
        setNotice(
          failed
            ? `Очередь завершена, ошибок: ${failed}. Готовые файлы сохранены.`
            : cancelled
              ? `Озвучивание остановлено. Отменено задач: ${cancelled}; готовые файлы сохранены.`
              : 'Озвучивание книги завершено',
          failed || cancelled ? 'warning' : 'success',
          7000,
        )
        return false
      }
    } catch (error) {
      ttsPollFailures += 1
      if (ttsPollFailures === 3) {
        setNotice(`Не удаётся обновить прогресс: ${error.message}. Продолжаю переподключаться.`, 'warning', 6000)
      }
    }
    return true
  }, 1500)
}

function pollTtsJob(jobUuid) {
  const projectUuid = activeProjectUuid.value
  ttsPollFailures = 0
  startTtsPolling(async () => {
    try {
      ttsNow.value = Date.now()
      const job = await apiRequest(`/tts-jobs/${encodeURIComponent(jobUuid)}`)
      if (projectUuid !== activeProjectUuid.value) return false
      ttsPollFailures = 0
      const index = ttsJobs.value.findIndex((item) => item.uuid === jobUuid)
      if (index >= 0) ttsJobs.value[index] = job
      if (!['queued', 'running'].includes(job.status)) {
        const draftSaved = await saveCurrentChapter({ silent: true })
        const record = await apiRequest(`/projects/${encodeURIComponent(projectUuid)}`)
        if (projectUuid !== activeProjectUuid.value) return false
        installBookRecord(record, { preserveDraft: !draftSaved })
        if (job.status === 'done') {
          if (activeVideoEditionId.value && draftSaved) {
            try { await rebuildBookVideo(record) } catch { /* video can be rebuilt on next open */ }
          }
          const ready = record.audio_assets?.find((asset) => asset.file_path === job.output_path)
          if (ready) selectBookAudio(ready, true)
          setNotice('Озвучка главы готова', 'success')
        } else if (job.status === 'cancelled') {
          setNotice('Озвучивание главы остановлено', 'warning', 5000)
        } else {
          setNotice(job.user_error || 'Модуль озвучки не запущен. Проверьте установку компонентов TTS.', 'error', 8000)
        }
        return false
      }
    } catch (error) {
      ttsPollFailures += 1
      if (ttsPollFailures === 3) {
        setNotice(`Не удаётся обновить прогресс: ${error.message}. Продолжаю переподключаться.`, 'warning', 6000)
      }
    }
    return true
  }, 1200)
}

function ttsJobLabel(job) {
  return ({
    queued: 'Задача поставлена в очередь',
    running: 'Озвучивание выполняется в фоне',
    done: 'Озвучка готова',
    failed: job.user_error || 'Модуль озвучки не запущен. Проверьте установку компонентов TTS.',
    cancelled: 'Озвучивание отменено',
  }[job.status] || job.status)
}

function ttsJobShortLabel(status) {
  return ({
    queued: 'ожидает',
    running: 'генерируется',
    done: 'готово',
    failed: 'ошибка',
    cancelled: 'отменено',
    skipped: 'пропущено',
  }[status] || status)
}

function chapterTitleForJob(job) {
  return activeProjectRecord.value?.chapters?.find((chapter) => chapter.id === job.chapter_id)?.title || 'Глава'
}

async function previewBookVoice() {
  if (!activeProjectUuid.value || previewLoading.value) return
  if (!(await saveBookTtsSettings())) return
  const editor = bookTextEditor.value
  const selected = editor && editor.selectionStart !== editor.selectionEnd
    ? bookChapterContent.value.slice(editor.selectionStart, editor.selectionEnd)
    : ''
  previewLoading.value = true
  try {
    const preview = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/tts-preview`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...bookTts, text: selected }),
    })
    bookPlayerShouldAutoplay = true
    previewAudio.value = preview
    selectedBookAudioId.value = null
  } catch (error) {
    setNotice(error.message || 'Не удалось создать пробу голоса.', 'error', 7000)
  } finally {
    previewLoading.value = false
  }
}

function selectBookAudio(asset, autoplay = false) {
  const previousUrl = bookPlayerUrl.value
  bookPlayerShouldAutoplay = autoplay
  previewAudio.value = null
  selectedBookAudioId.value = asset.id
  bookPlayerDuration.value = Number(asset.duration || 0)
  clearBookWordHighlight()
  if (bookPlayerUrl.value === previousUrl) {
    bookPlayerShouldAutoplay = false
    void reloadBookPlayer(autoplay)
  }
}

function selectAdjacentChapterAudio(direction) {
  const asset = direction < 0 ? previousChapterAudio.value : nextChapterAudio.value
  if (asset) selectBookAudio(asset, true)
}

function toggleBookPlayer() {
  const player = bookAudioEl.value
  if (!player) return
  if (player.paused) player.play().catch(() => setNotice('Не удалось воспроизвести аудиофайл.', 'error'))
  else player.pause()
}

function stopBookPlayer() {
  bookPlayerLoadToken += 1
  stopBookPlaybackClock()
  const player = bookAudioEl.value
  if (player) {
    player.pause()
    player.currentTime = 0
  }
  bookPlayerPlaying.value = false
  updateBookWordHighlight(0)
}

async function reloadBookPlayer(autoplay = false) {
  const token = ++bookPlayerLoadToken
  await nextTick()
  const player = bookAudioEl.value
  if (!player || !bookPlayerUrl.value || token !== bookPlayerLoadToken) return
  player.pause()
  player.currentTime = 0
  stopBookPlaybackClock()
  bookPlayerPlaying.value = false
  bookPlayerDuration.value = Number(bookPlayerAsset.value?.duration || 0)
  updateBookWordHighlight(0)
  applyBookPlayerVolume()
  if (!autoplay) return
  const ready = await waitForBookPlayerReady(player)
  if (token !== bookPlayerLoadToken) return
  if (!ready) {
    setNotice('Аудиофайл создан, но плеер не смог его загрузить. Нажмите ▶ или создайте пробу ещё раз.', 'error', 7000)
    return
  }
  try {
    await player.play()
  } catch (error) {
    if (token !== bookPlayerLoadToken) return
    setNotice('Аудиофайл создан, но браузер не разрешил автозапуск. Нажмите ▶ в плеере.', 'error', 7000)
  }
}

function waitForBookPlayerReady(player) {
  if (player.readyState >= 3) return Promise.resolve(true)
  return new Promise((resolve) => {
    let timeoutId = null
    const finish = (ready) => {
      player.removeEventListener('canplay', onReady)
      player.removeEventListener('error', onError)
      if (timeoutId !== null) window.clearTimeout(timeoutId)
      resolve(ready)
    }
    const onReady = () => finish(true)
    const onError = () => finish(false)
    player.addEventListener('canplay', onReady, { once: true })
    player.addEventListener('error', onError, { once: true })
    timeoutId = window.setTimeout(() => finish(player.readyState >= 2), 8000)
  })
}

function seekBookPlayer(value) {
  const player = bookAudioEl.value
  if (!player) return
  const target = Math.max(0, Math.min(Number(value) || 0, bookPlayerDuration.value || Number(value) || 0))
  try { player.currentTime = target } catch { return }
  updateBookWordHighlight(target)
}

function applyBookPlayerVolume() {
  if (bookAudioEl.value) bookAudioEl.value.volume = bookPlayerVolume.value
}

function onBookAudioMetadata() {
  const player = bookAudioEl.value
  bookPlayerDuration.value = Number.isFinite(player?.duration) ? player.duration : Number(bookPlayerAsset.value?.duration || 0)
  applyBookPlayerVolume()
  updateBookWordHighlight(player?.currentTime || 0)
}

function onBookAudioTime() {
  updateBookWordHighlight(bookAudioEl.value?.currentTime || 0)
}

function clearBookWordHighlight() {
  if (!renderedBookWordButton) return
  renderedBookWordButton.classList.remove('current')
  renderedBookWordButton.removeAttribute('aria-current')
  renderedBookWordButton = null
}

function updateBookWordHighlight(timeValue) {
  const container = bookWordNavigatorCopy.value
  if (!container || !bookPlayerHasExactTimings.value) {
    clearBookWordHighlight()
    return
  }
  const word = wordAtTime(bookPlayerWords.value, timeValue, { activeOnly: true })
  const button = word ? container.querySelector(`[data-word-index="${word.index}"]`) : null
  if (button === renderedBookWordButton && (!button || button.isConnected)) return
  clearBookWordHighlight()
  if (!button) return
  button.classList.add('current')
  button.setAttribute('aria-current', 'true')
  renderedBookWordButton = button
}

function tickBookPlayback() {
  bookPlaybackFrame = null
  const player = bookAudioEl.value
  if (!player) return
  updateBookWordHighlight(player.currentTime || 0)
  if (!player.paused && !player.ended) bookPlaybackFrame = requestAnimationFrame(tickBookPlayback)
}

function startBookPlaybackClock() {
  if (bookPlaybackFrame !== null) cancelAnimationFrame(bookPlaybackFrame)
  bookPlaybackFrame = requestAnimationFrame(tickBookPlayback)
}

function stopBookPlaybackClock() {
  if (bookPlaybackFrame !== null) cancelAnimationFrame(bookPlaybackFrame)
  bookPlaybackFrame = null
}

function onBookAudioPlay() {
  bookPlayerPlaying.value = true
  startBookPlaybackClock()
}

function onBookAudioPause() {
  bookPlayerPlaying.value = false
  stopBookPlaybackClock()
  onBookAudioTime()
}

function onBookAudioEnded() {
  bookPlayerPlaying.value = false
  stopBookPlaybackClock()
  onBookAudioTime()
}

async function refreshActiveProjectRecord() {
  const record = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}`)
  const chapterId = currentBookChapterId.value
  activeProjectRecord.value = record
  currentBookChapterId.value = chapterId
  return record
}

async function activateBookAudio(asset) {
  try {
    await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/audio/${asset.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ is_active: true }),
    })
    await refreshActiveProjectRecord()
    selectedBookAudioId.value = asset.id
  } catch (error) {
    setNotice(`Не удалось выбрать версию: ${error.message}`, 'error')
  }
}

async function renameBookAudio(asset) {
  const title = window.prompt('Название версии озвучки', asset.title || `Версия ${asset.version_number}`)
  if (!title) return
  try {
    await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/audio/${asset.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title }),
    })
    await refreshActiveProjectRecord()
  } catch (error) {
    setNotice(`Не удалось переименовать аудио: ${error.message}`, 'error')
  }
}

async function openBookAudioFolder(asset) {
  try {
    await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/audio/${asset.id}/open-folder`, { method: 'POST' })
  } catch (error) {
    setNotice(`Не удалось открыть папку: ${error.message}`, 'error')
  }
}

async function deleteBookAudio(asset) {
  if (!window.confirm(`Удалить аудиоверсию «${asset.title || asset.file_path}»? Это действие удалит файл с диска.`)) return
  try {
    await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/audio/${asset.id}`, { method: 'DELETE' })
    if (selectedBookAudioId.value === asset.id) selectedBookAudioId.value = null
    await refreshActiveProjectRecord()
  } catch (error) {
    setNotice(`Не удалось удалить аудио: ${error.message}`, 'error')
  }
}

async function sendBookAudioToVideo(asset) {
  if (!asset.is_active) await activateBookAudio(asset)
  await openBookInVideo()
  if (activeWorkspace.value === 'video') {
    await saveProject({ silent: true })
    setNotice('Озвучка собрана в мастер-аудио и добавлена на видеотаймлайн', 'success', 6500)
  }
}

async function downloadCurrentChapterAudio() {
  const chapter = currentBookChapter.value
  if (!activeProjectUuid.value || !chapter || !currentChapterAudio.value || chapterAudioExportBusy.value) return
  if (!(await saveCurrentChapter({ silent: true }))) {
    setNotice('Сначала нужно сохранить текущую главу', 'error')
    return
  }
  chapterAudioExportBusy.value = true
  setNotice('Собираем аудио…', 'info', 0)
  try {
    const response = await fetch(
      `${API}/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters/${chapter.id}/audio-export`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ editor_project: serializeProject() }),
      },
    )
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}))
      throw new Error(
        typeof payload.detail === 'string'
          ? payload.detail
          : payload.detail?.message || 'Сервер не смог собрать аудио главы',
      )
    }
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = responseDownloadName(response, `${String(chapterDisplayNumber(chapter)).padStart(2, '0')} ${chapter.title}.mp3`)
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1500)
    setNotice('Аудио главы готово — скачивание началось', 'success', 5500)
  } catch (error) {
    setNotice(`Не удалось скачать аудио: ${error.message}`, 'error', 7500)
  } finally {
    chapterAudioExportBusy.value = false
  }
}

function startBookAudioDrag(asset, event) {
  if (!asset?.id || asset.temporary || !event?.dataTransfer) return
  event.dataTransfer.effectAllowed = 'copy'
  event.dataTransfer.setData('application/x-bookender-audio-id', String(asset.id))
  event.dataTransfer.setData('text/plain', asset.title || asset.file_path || 'Озвучка главы')
}

async function dropBookAudioOnVideoTab(event) {
  const assetId = Number(event.dataTransfer?.getData('application/x-bookender-audio-id'))
  const asset = (activeProjectRecord.value?.audio_assets || []).find((item) => item.id === assetId)
  if (!asset) return
  await sendBookAudioToVideo(asset)
}

async function openTtsLog() {
  try {
    await apiRequest('/tts/open-log', { method: 'POST' })
  } catch (error) {
    setNotice(error.message || 'Не удалось открыть технический лог.', 'error')
  }
}

function chapterAudioState(chapter) {
  const assets = (activeProjectRecord.value?.audio_assets || []).filter((asset) => asset.chapter_id === chapter.id)
  if (!assets.length) return 'none'
  return assets.some((asset) => !asset.is_stale) ? 'ready' : 'stale'
}

function chapterAudioStateLabel(chapter) {
  return ({ none: 'Нет озвучки', ready: 'Озвучка готова', stale: 'Озвучка устарела' }[chapterAudioState(chapter)])
}

function chapterTitleForAudio(asset) {
  return activeProjectRecord.value?.chapters?.find((item) => item.id === asset.chapter_id)?.title || 'Аудиокнига'
}

async function createVideoEditionForActiveBook({ prepare = true } = {}) {
  if (!activeProjectUuid.value) return
  try {
    const edition = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/video-editions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: activeProjectRecord.value?.title || null }),
    })
    activeVideoEditionId.value = edition.id
    replaceProject(edition.settings)
    if (prepare) {
      await prepareBookVideoProject(activeProjectRecord.value)
      await publishVideoCompatibility(activeProjectUuid.value, edition.id, serializeProject())
      dirty.value = false
    }
    const refreshed = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}`)
    activeProjectRecord.value = refreshed
    await loadProjectCatalog()
    return edition
  } catch (error) {
    setNotice(`Не удалось создать видеоверсию: ${error.message}`, 'error')
    return null
  }
}

async function rebuildBookVideo(record, { announce = false } = {}) {
  let master = null
  let warning = ''
  try {
    master = await prepareBookVideoProject(record)
    if (master?.missing_chapter_ids?.length) {
      warning = `Без озвучки пока глав: ${master.missing_chapter_ids.length}.`
    }
  } catch (error) {
    syncBookChaptersToVideo(record)
    warning = error.message
  }
  if (activeVideoEditionId.value) {
    await publishVideoCompatibility(
      activeProjectUuid.value,
      activeVideoEditionId.value,
      serializeProject(),
    )
    dirty.value = false
  }
  await nextTick()
  audioEl.value?.load()
  await refreshWaveform()
  if (announce) {
    if (master) {
      setNotice(
        warning
          ? `Озвученные главы разложены на таймлайне. ${warning}`
          : `Видео готово: на таймлайне глав ${master.chapters.length}.`,
        warning ? 'warning' : 'success',
        7000,
      )
    } else {
      setNotice(
        `Видео открыто без мастер-аудио: ${warning || 'нет готовых озвучек'}`,
        'warning',
        7000,
      )
    }
  }
  return master
}

async function openBookInVideo() {
  if (videoPreparing.value) return
  if (!(await saveCurrentChapter({ silent: true }))) return
  videoPreparing.value = true
  activeWorkspace.value = 'video'
  pauseAll()
  try {
    if (!activeVideoEditionId.value) {
      await createVideoEditionForActiveBook({ prepare: false })
      if (!activeVideoEditionId.value) return
    }
    await rebuildBookVideo(activeProjectRecord.value, { announce: true })
  } catch (error) {
    setNotice(`Не удалось подготовить видео: ${error.message}`, 'error', 8000)
  } finally {
    videoPreparing.value = false
  }
}

async function backupActiveProject() {
  if (!activeProjectUuid.value) return
  try {
    const result = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/backup`, { method: 'POST' })
    setNotice(`Резервная копия создана: ${result.path}`, 'success', 7000)
  } catch (error) {
    setNotice(`Не удалось создать резервную копию: ${error.message}`, 'error')
  }
}

async function loadInitialProject() {
  loading.value = true
  hydrating.value = true
  try {
    const health = await apiRequest('/health', { timeoutMs: 4000 })
    backend.online = !!health.ok
    backend.version = health.version || ''
    backend.python = health.python || ''
    Object.assign(ttsRuntime, health.tts || {})
  } catch {
    backend.online = false
  } finally {
    backend.checking = false
  }

  let restored = false
  if (backend.online) {
    void loadTtsRuntime()
    loadingMessage.value = 'Загружаю библиотеку проектов…'
    try {
      const activeUuid = await loadProjectCatalog()
      if (activeUuid) {
        await switchProject(activeUuid, { initial: true })
        restored = true
      }
    } catch (error) {
      // Compatibility fallback for an installation that has not run the
      // ecosystem migration yet.
      try {
        const saved = await apiRequest('/editor-project')
        if (saved.exists && saved.project) {
          replaceProject(saved.project)
          activeWorkspace.value = 'video'
          restored = true
        }
      } catch { /* recovery JSON is optional */ }
      setNotice(`Библиотека проектов недоступна: ${error.message}`, 'warning')
    }
    if (activeWorkspace.value === 'video') void refreshWaveform()
  } else {
    const local = localStorage.getItem('book-wunderwaffe-project')
    if (local) {
      try { replaceProject(JSON.parse(local)); restored = true } catch { /* ignore invalid local cache */ }
    }
  }

  ensureSceneBounds()
  dirty.value = false
  hydrating.value = false
  loading.value = false
  if (!backend.online) setNotice('Backend не найден: предпросмотр работает, экспорт временно недоступен.', 'warning', 6500)
  else if (restored) setNotice('Проект восстановлен', 'success', 2400)

  await nextTick()
  audioEl.value?.load()
  videoEl.value?.load()
}

function hydrateDiscoveredMaterials(data) {
  const discovered = Array.isArray(data.materials)
    ? data.materials
    : [...(data.audios || []), ...(data.images || []), ...(data.videos || [])]
  for (const item of discovered) {
    const serverPath = String(item.serverPath || item.path || '')
    if (!serverPath || project.materials.some((asset) => asset.serverPath === serverPath)) continue
    const type = item.kind || materialType(item.name || serverPath, '')
    if (!type) continue
    project.materials.push({
      id: uid(`server-${type}`),
      type,
      name: item.name || serverPath.replaceAll('\\', '/').split('/').pop(),
      size: item.size || 0,
      serverPath,
      src: mediaUrl(serverPath),
      status: 'ready',
      progress: 1,
    })
  }

  const entries = [
    ['audio', data.audio, 'audio', 'audioAssetId'],
    ['video', data.video, 'video', 'videoAssetId'],
    ['cover', data.cover, 'image', 'coverAssetId'],
    ['background', data.background, 'image', 'backgroundAssetId'],
  ]
  for (const [role, info, type, key] of entries) {
    if (!info?.exists || project[key]) continue
    const serverPath = String(info.path || '')
    let asset = project.materials.find((item) => item.serverPath === serverPath)
    if (!asset) {
      asset = {
        id: uid(`server-${role}`),
        type,
        name: serverPath.replaceAll('\\', '/').split('/').pop() || role,
        size: info.size || 0,
        serverPath,
        src: mediaUrl(serverPath),
        status: 'ready',
        progress: 1,
      }
      project.materials.push(asset)
    }
    project[key] = asset.id
  }
}

async function refreshWaveform() {
  if (!backend.online || !audioAsset.value) return
  try {
    const result = await apiRequest('/waveform?samples=10000')
    if (Array.isArray(result.samples)) waveformSamples.value = result.samples
  } catch { /* visualizer has a deterministic idle fallback */ }
}

function newProject() {
  if (dirty.value && !window.confirm('Создать новый проект? Несохранённые изменения будут потеряны.')) return
  pauseAll()
  replaceProject(freshProject())
  waveformSamples.value = []
  dirty.value = true
  setNotice('Создан новый проект', 'success')
}

async function saveProject({ silent = false } = {}) {
  saving.value = true
  try {
    const { result } = await projectSaver.save()
    dirty.value = false
    if (!silent) {
      if (backend.online && !result.browserCached) {
        setNotice('Проект сохранён на сервере, но локальный кэш браузера переполнен', 'warning', 7000)
      } else {
        setNotice(backend.online ? 'Проект сохранён' : 'Проект сохранён в браузере', 'success')
      }
    }
    return true
  } catch (error) {
    setNotice(`Не удалось сохранить: ${error.message}`, 'error', 7000)
    return false
  } finally {
    saving.value = projectSaver.running
  }
}

function onProjectInput(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    try {
      replaceProject(JSON.parse(String(reader.result)))
      dirty.value = true
      ensureSceneBounds()
      setNotice('Проект открыт. Локальные файлы без копии на backend нужно добавить повторно.', 'success', 6200)
    } catch (error) {
      setNotice(`Файл проекта повреждён: ${error.message}`, 'error')
    }
  }
  reader.readAsText(file, 'utf-8')
}

function onAssetInput(event) {
  importFiles(event.target.files)
  event.target.value = ''
}

function onMusicInput(event) {
  importFiles(event.target.files, { asMusic: true, timelineTime: currentTime.value })
  event.target.value = ''
}

async function onBookChapterImageInput(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  const chapterId = currentBookChapterId.value
  if (!file || !chapterId || !activeProjectUuid.value) return
  try {
    await apiRequest(
      `/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters/${chapterId}/image?filename=${encodeURIComponent(file.name)}`,
      {
        method: 'PUT',
        headers: { 'Content-Type': file.type || 'application/octet-stream' },
        body: file,
      },
    )
    const record = await refreshActiveProjectRecord()
    if (activeVideoEditionId.value) {
      syncBookChaptersToVideo(record)
      await publishVideoCompatibility(
        activeProjectUuid.value,
        activeVideoEditionId.value,
        serializeProject(),
      )
    }
    setNotice('Изображение главы сохранено и будет использовано в видеокниге', 'success', 6000)
  } catch (error) {
    setNotice(`Не удалось сохранить изображение главы: ${error.message}`, 'error', 7000)
  }
}

async function removeBookChapterImage() {
  const chapterId = currentBookChapterId.value
  if (!chapterId || !activeProjectUuid.value) return
  try {
    await apiRequest(
      `/projects/${encodeURIComponent(activeProjectUuid.value)}/chapters/${chapterId}/image`,
      { method: 'DELETE' },
    )
    const record = await refreshActiveProjectRecord()
    if (activeVideoEditionId.value) {
      syncBookChaptersToVideo(record)
      await publishVideoCompatibility(
        activeProjectUuid.value,
        activeVideoEditionId.value,
        serializeProject(),
      )
    }
    setNotice('Изображение главы убрано', 'success')
  } catch (error) {
    setNotice(`Не удалось убрать изображение: ${error.message}`, 'error')
  }
}

function onDrop(event) {
  importFiles(event.dataTransfer?.files)
}

function importFiles(fileList, { asMusic = false, asVisual = false, timelineTime = currentTime.value } = {}) {
  const files = [...(fileList || [])]
  if (!files.length) return
  if (asMusic) ensureExplicitMusicClips()
  let accepted = 0
  let lastMusicClip = null
  for (const file of files) {
    const type = materialType(file.name, file.type)
    if (asVisual && type !== 'image') continue
    if (asMusic && type !== 'audio') {
      setNotice(`Для музыкальной дорожки нужен аудиофайл: «${file.name}»`, 'error', 6000)
      continue
    }
    if (!type) {
      setNotice(`Формат «${file.name}» не поддерживается`, 'error', 6000)
      continue
    }
    const src = URL.createObjectURL(file)
    objectUrls.add(src)
    const asset = {
      id: uid('asset'), type, name: file.name, size: file.size, mime: file.type,
      file, src, serverPath: '', status: backend.online ? 'uploading' : 'local', progress: 0,
      role: asMusic ? 'music' : undefined,
    }
    project.materials.push(asset)
    if (asMusic) {
      project.music.enabled = true
      lastMusicClip = addMusicClip(asset.id, timelineTime)
    } else if (!asVisual) {
      assignImportedAsset(asset)
    }
    accepted += 1
    if (backend.online) uploadAsset(asset)
  }
  if (accepted) {
    if (lastMusicClip) {
      select('music', lastMusicClip.id)
      seekTo(lastMusicClip.start)
    }
    dirty.value = true
    setNotice(`Добавлено файлов: ${accepted}`, 'success')
    nextTick(() => {
      if (asVisual) return
      audioEl.value?.load()
      musicEl.value?.load()
      videoEl.value?.load()
      applyMusicMix()
    })
  }
}

function assignImportedAsset(asset) {
  if (asset.type === 'audio' && !project.audioAssetId) project.audioAssetId = asset.id
  if (asset.type === 'video' && !project.videoAssetId) project.videoAssetId = asset.id
  if (asset.type === 'image') {
    const lower = asset.name.toLowerCase()
    if ((!project.coverAssetId || /cover|облож/.test(lower)) && !/background|backdrop|\bbg\b|фон/.test(lower)) project.coverAssetId = asset.id
    else if (!project.backgroundAssetId) project.backgroundAssetId = asset.id
  }
}

function uploadAsset(asset) {
  const xhr = new XMLHttpRequest()
  const query = new URLSearchParams({ filename: asset.name, kind: asset.type })
  xhr.open('PUT', `${API}/media/import?${query}`)
  xhr.setRequestHeader('Content-Type', asset.mime || 'application/octet-stream')
  xhr.upload.onprogress = (event) => {
    if (event.lengthComputable) asset.progress = event.loaded / event.total
  }
  xhr.onerror = () => {
    asset.status = 'error'
    setNotice(`Не удалось синхронизировать «${asset.name}»`, 'error', 6500)
  }
  xhr.onload = async () => {
    let result = {}
    try { result = JSON.parse(xhr.responseText || '{}') } catch { /* handled below */ }
    if (xhr.status < 200 || xhr.status >= 300) {
      asset.status = 'error'
      setNotice(result.detail || `Ошибка загрузки «${asset.name}»`, 'error', 6500)
      return
    }
    asset.serverPath = result.serverPath || result.path || ''
    asset.status = 'ready'
    asset.progress = 1
    dirty.value = true
    if (asset.type === 'audio' && project.audioAssetId === asset.id) await refreshWaveform()
    if (asset.type === 'audio' && (project.musicAssetId === asset.id || project.musicClips.some((clip) => clip.assetId === asset.id))) {
      nextTick(() => {
        musicEl.value?.load()
        applyMusicMix()
      })
    }
  }
  xhr.send(asset.file)
}

function assignAsset(role, id) {
  if (role === 'music' && project.audioAssetId === id) project.audioAssetId = null
  if (role === 'audio' && project.musicAssetId === id) project.musicAssetId = null
  if (role === 'music') ensureExplicitMusicClips()
  if (role === 'audio') project.musicClips = project.musicClips.filter((clip) => clip.assetId !== id)
  project[`${role}AssetId`] = id
  if (role === 'music') {
    const asset = assetById(id)
    if (asset) asset.role = 'music'
    project.music.enabled = true
    const clip = addMusicClip(id, currentTime.value)
    select('music', clip.id)
  }
  dirty.value = true
  nextTick(() => {
    if (role === 'audio') { audioEl.value?.load(); refreshWaveform() }
    if (role === 'music') {
      musicEl.value?.load()
      applyMusicMix()
    }
    if (role === 'video') videoEl.value?.load()
  })
}

function removeAsset(id) {
  const asset = assetById(id)
  if (!asset) return
  if (project.audioAssetId === id || project.musicAssetId === id || project.videoAssetId === id) pauseAll()
  for (const key of ['audioAssetId', 'musicAssetId', 'videoAssetId', 'coverAssetId', 'backgroundAssetId']) {
    if (project[key] === id) project[key] = null
  }
  for (const scene of project.scenes) if (scene.backgroundAssetId === id) scene.backgroundAssetId = null
  for (const chapter of project.chapters) {
    if (chapter.imageAssetId === id) chapter.imageAssetId = null
    chapter.visualInserts = (chapter.visualInserts || []).filter(clip => clip.assetId !== id)
  }
  project.musicClips = project.musicClips.filter((clip) => clip.assetId !== id)
  if (asset.src?.startsWith('blob:')) {
    URL.revokeObjectURL(asset.src)
    objectUrls.delete(asset.src)
  }
  project.materials.splice(project.materials.findIndex((item) => item.id === id), 1)
  select('project')
  dirty.value = true
  setNotice('Материал убран из проекта. Исходный файл не удалён.', 'info')
}

function layerVisible(id) {
  return project.layers[id]?.visible !== false
}

function measuredTextWidth(context, text, letterSpacing) {
  const value = String(text || '')
  return context.measureText(value).width + Math.max(0, value.length - 1) * letterSpacing
}

function measuredLineCount(context, text, maxWidth, letterSpacing) {
  let lines = 0
  for (const paragraph of String(text || '').split(/\r?\n/)) {
    const words = paragraph.trim().split(/\s+/).filter(Boolean)
    if (!words.length) {
      lines += 1
      continue
    }
    let line = ''
    for (const word of words) {
      const candidate = line ? `${line} ${word}` : word
      if (measuredTextWidth(context, candidate, letterSpacing) <= maxWidth) {
        line = candidate
        continue
      }
      if (line) {
        lines += 1
        line = ''
      }
      if (measuredTextWidth(context, word, letterSpacing) <= maxWidth) {
        line = word
        continue
      }
      let fragment = ''
      for (const character of word) {
        if (fragment && measuredTextWidth(context, `${fragment}${character}`, letterSpacing) > maxWidth) {
          lines += 1
          fragment = character
        } else {
          fragment += character
        }
      }
      line = fragment
    }
    if (line) lines += 1
  }
  return Math.max(1, lines)
}

function fitChapterTitles() {
  const stack = titleStackEl.value
  if (!stack || stack.clientWidth < 2 || stack.clientHeight < 2) return

  const configured = Number(project.layers.title?.fontSize)
  const maximum = Math.max(16, Math.min(96, Number.isFinite(configured) ? configured : 48))
  const title = currentChapter.value?.title || 'Добавьте первую главу'
  const titleElement = stack.querySelector(':scope > strong')
  const titleStyle = titleElement ? getComputedStyle(titleElement) : null
  titleMeasureCanvas ||= document.createElement('canvas')
  const context = titleMeasureCanvas.getContext('2d')
  if (!context) return

  let low = 24
  let high = Math.round(maximum * 2)
  let best = low

  while (low <= high) {
    const middle = Math.floor((low + high) / 2)
    const candidate = middle / 2
    const weight = titleStyle?.fontWeight || '640'
    const family = titleStyle?.fontFamily || 'Inter, sans-serif'
    context.font = `${weight} ${candidate}px ${family}`
    const letterSpacing = candidate * -.035
    const titleLines = measuredLineCount(context, title, stack.clientWidth, letterSpacing)
    const neighborCount = Number(!!previousChapter.value) + Number(!!nextChapter.value)
    const neighborHeight = Math.max(8, candidate * .3) * 1.15
    const gap = Math.max(4, candidate * .18)
    const contentHeight = titleLines * candidate * 1.04
      + neighborCount * neighborHeight
      + Math.max(0, neighborCount) * gap
    const fits = contentHeight <= stack.clientHeight + 1
    if (fits) {
      best = middle
      low = middle + 1
    } else {
      high = middle - 1
    }
  }

  stack.style.setProperty('font-size', `${best / 2}px`, 'important')
}

function scheduleTitleFit() {
  if (titleFitFrame !== null) cancelAnimationFrame(titleFitFrame)
  titleFitFrame = requestAnimationFrame(() => {
    titleFitFrame = null
    fitChapterTitles()
  })
}

function layerStyle(id) {
  const layer = project.layers[id]
  if (!layer) return {}
  const style = { left: `${layer.x}%`, top: `${layer.y}%`, width: `${layer.w}%`, height: `${layer.h}%` }
  if (id === 'title') {
    style['--title-size'] = `${layer.fontSize || 48}px`
    style['--title-color'] = layer.color || '#f4f0e8'
  }
  if (id === 'caption') {
    style['--caption-size'] = `${layer.fontSize || 28}px`
    style['--caption-weight'] = String(layer.fontWeight || 400)
    style['--caption-background'] = `rgba(7, 5, 10, ${Number.isFinite(Number(layer.backgroundOpacity)) ? Number(layer.backgroundOpacity) : 0.68})`
  }
  return style
}

function layerLabel(id) {
  return ({ cover: 'Обложка', title: 'Заголовок', visualizer: 'Визуализатор', caption: 'Текст озвучки' })[id] || 'Слой'
}

function setCoverEnabled(enabled) {
  if (!enabled && !project.backgroundAssetId && project.coverAssetId) {
    project.backgroundAssetId = project.coverAssetId
  }
  if (!enabled) project.coverAssetId = null
  if (enabled && !project.coverAssetId && project.backgroundAssetId) {
    project.coverAssetId = project.backgroundAssetId
  }
  project.coverMode = enabled ? 'cover' : 'background-only'
  project.layers.cover.visible = Boolean(enabled)
  if (!enabled) selection.type = 'project'
  dirty.value = true
}

function visualizerPreset(id = project.layers.visualizer.colorPreset) {
  return (
    VISUALIZER_COLOR_PRESETS.find((preset) => preset.id === id)
    || VISUALIZER_COLOR_PRESETS[0]
  )
}

function visualizerPresetGradient(preset) {
  return `linear-gradient(90deg, ${preset.colors.join(', ')})`
}

function setVisualizerColorPreset(id) {
  project.layers.visualizer.colorPreset = visualizerPreset(id).id
  dirty.value = true
}

function onLayerPointerDown(id, event) {
  if (event.button !== 0) return
  select('layer', id)
  const layer = project.layers[id]
  const rect = sceneEl.value?.getBoundingClientRect()
  if (!layer || !rect) return
  const start = { clientX: event.clientX, clientY: event.clientY, x: layer.x, y: layer.y }
  const move = (moveEvent) => {
    const dx = (moveEvent.clientX - start.clientX) / rect.width * 100
    const dy = (moveEvent.clientY - start.clientY) / rect.height * 100
    layer.x = Math.max(0, Math.min(100 - layer.w, start.x + dx))
    layer.y = Math.max(0, Math.min(100 - layer.h, start.y + dy))
    dirty.value = true
  }
  layerGestures.start(move)
}

function onLayerResizePointerDown(id, axis, event) {
  if (event.button !== 0) return
  select('layer', id)
  const layer = project.layers[id]
  const rect = sceneEl.value?.getBoundingClientRect()
  if (!layer || !rect) return
  const start = {
    clientX: event.clientX,
    clientY: event.clientY,
    width: Number(layer.w) || 5,
    height: Number(layer.h) || 5,
  }
  const minimum = id === 'visualizer' ? { w: 12, h: 8 } : { w: 8, h: 8 }
  const move = (moveEvent) => {
    if (axis.includes('e')) {
      const dx = (moveEvent.clientX - start.clientX) / rect.width * 100
      layer.w = Math.max(minimum.w, Math.min(100 - layer.x, start.width + dx))
    }
    if (axis.includes('s')) {
      const dy = (moveEvent.clientY - start.clientY) / rect.height * 100
      layer.h = Math.max(minimum.h, Math.min(100 - layer.y, start.height + dy))
    }
    dirty.value = true
  }
  layerGestures.start(move)
}

function resetLayer(id) {
  if (!DEFAULT_LAYERS[id]) return
  Object.assign(project.layers[id], clone(DEFAULT_LAYERS[id]))
  dirty.value = true
}

function chapterEnd(id) {
  return timelineChapters.value.find((item) => item.id === id)?.end_seconds || 0
}

function updateChapterStart(chapter, value) {
  chapter.start_seconds = Math.max(0, Math.min(parseTimecode(value), Math.max(0, duration.value - 0.1)))
  project.chapters.sort((a, b) => a.start_seconds - b.start_seconds)
  syncChapterBoundMusicClips()
  dirty.value = true
}

function addChapterAtCursor() {
  const start = project.chapters.length ? Math.round(currentTime.value * 10) / 10 : 0
  const chapter = normalizeChapter({ title: `Глава ${project.chapters.length + 1}`, start_seconds: start }, project.chapters.length)
  project.chapters.push(chapter)
  project.chapters.sort((a, b) => a.start_seconds - b.start_seconds)
  select('chapter', chapter.id)
  dirty.value = true
  setNotice(`Глава добавлена в ${formatTimecode(start)}`, 'success')
}

function removeChapter(id) {
  const index = project.chapters.findIndex((item) => item.id === id)
  if (index < 0) return
  project.chapters.splice(index, 1)
  syncChapterBoundMusicClips()
  select('project')
  dirty.value = true
}

function selectChapter(chapter) {
  seekTo(chapter.start_seconds)
  select('chapter', chapter.id)
}

const chapterStats = computed(() => new Map((activeProjectRecord.value?.chapters || []).map(chapter => [
  chapter.id,
  `${Number(chapter.artistic_word_count ?? (chapter.reader_content?.match(/\S+/g) || []).length).toLocaleString('ru-RU')} слов · ${Number(chapter.artistic_paragraph_count ?? (chapter.reader_content?.split(/\n\s*\n/).filter(line => line.trim()) || []).length)} абз.`,
])))
const chapterColors = computed(() => new Map((activeProjectRecord.value?.chapters || []).map((chapter, index) => [chapter.id, chapterColor(index)])))
function chapterAccent(chapter) {
  return { '--chapter-color': chapterColors.value.get(chapter?.chapterId ?? chapter?.id) || chapterColor(chapter?.index ?? 0) }
}

async function focusChapterTimeline(chapter) {
  if (!chapter || !timelineScroll.value) return
  const span = Math.max(0.1, chapter.end_seconds - chapter.start_seconds)
  const available = Math.max(1, timelineScroll.value.clientWidth - 156)
  timelineZoom.value = Math.max(1, Math.min(2000, duration.value / span * available / timelineScroll.value.clientWidth))
  await nextTick()
  const scroller = timelineScroll.value
  const content = timelineContent.value
  if (scroller && content) {
    scroller.scrollLeft = chapter.start_seconds / Math.max(duration.value, 0.1) * content.offsetWidth
    updateTimelineViewport()
  }
}

function toggleChapterTimelineMode() {
  chapterTimelineMode.value = !chapterTimelineMode.value
  if (chapterTimelineMode.value) void focusChapterTimeline(currentChapter.value)
  else { timelineZoom.value = 1; if (timelineScroll.value) timelineScroll.value.scrollLeft = 0 }
}

function navigateChapter(chapter) {
  if (!chapter) return
  selectChapter(chapter)
  if (chapterTimelineMode.value) void focusChapterTimeline(chapter)
  else scrollTimelineToTime(chapter.start_seconds, 'auto')
}

function selectVideoChapterAudio(chapter) {
  seekTo(chapter.start_seconds)
  if (chapter.audioAssetId) select('asset', chapter.audioAssetId)
}

function openTextNavigator() {
  const chapter = currentChapter.value || timelineChapters.value[0]
  if (chapter) textNavigatorChapterId.value = chapter.id
  showTextNavigator.value = true
  textNavigatorFollow.value = true
  nextTick(scrollCurrentNavigatorWordIntoView)
}

function chooseNavigatorChapter(chapter) {
  textNavigatorChapterId.value = chapter.id
  clearTextSelection()
  seekTo(chapter.start_seconds)
  scrollTimelineToTime(chapter.start_seconds)
}

function selectNavigatorWord(word, event) {
  const chapter = navigatorChapter.value
  if (!chapter) return
  excerptPlaybackEnd.value = null
  if (event.shiftKey && textSelection.chapterId === chapter.id && textSelection.anchor >= 0) {
    textSelection.focus = word.index
  } else {
    textSelection.chapterId = chapter.id
    textSelection.anchor = word.index
    textSelection.focus = word.index
  }
  seekTo(word.start)
  scrollTimelineToTime(word.start)
}

function isNavigatorWordSelected(word) {
  if (!navigatorChapter.value || textSelection.chapterId !== navigatorChapter.value.id) return false
  const first = Math.min(textSelection.anchor, textSelection.focus)
  const last = Math.max(textSelection.anchor, textSelection.focus)
  return word.index >= first && word.index <= last
}

function clearTextSelection() {
  if (excerptPlaybackEnd.value !== null) pauseAll()
  textSelection.chapterId = ''
  textSelection.anchor = -1
  textSelection.focus = -1
  excerptPlaybackEnd.value = null
}

async function playSelectedExcerpt() {
  const excerpt = selectedExcerpt.value
  if (!excerpt) return
  if (excerptPlaybackEnd.value !== null) {
    pauseAll()
    return
  }
  if (!masterElement()) {
    setNotice('У этой видеоверсии пока нет собранной озвучки.', 'warning', 6000)
    return
  }
  pauseAll()
  seekTo(excerpt.start)
  excerptPlaybackEnd.value = Math.max(excerpt.start + 0.02, excerpt.end)
  await togglePlay()
}

function stopAtExcerptEnd(timeValue = currentTime.value) {
  const selectedEnd = excerptPlaybackEnd.value
  if (selectedEnd === null) return false
  const end = Number(selectedEnd)
  if (!Number.isFinite(end) || Number(timeValue) < end - 0.015) return false
  pauseAll()
  seekTo(end)
  return true
}

function scrollTimelineToTime(time, behavior = 'smooth') {
  const scroller = timelineScroll.value
  const content = timelineContent.value
  if (!scroller || !content || !duration.value) return
  const point = content.offsetLeft + Math.max(0, Math.min(1, Number(time) / duration.value)) * content.offsetWidth
  const target = Math.max(0, point - scroller.clientWidth * 0.52)
  scroller.scrollTo({ left: target, behavior })
}

function scrollCurrentNavigatorWordIntoView() {
  const container = textNavigatorScroll.value
  const index = currentNavigatorWord.value?.index
  if (!container || index === undefined || !textNavigatorFollow.value) return
  followWord(container, container.querySelector(`[data-word-index="${index}"]`))
}

function resumeTextFollow() {
  textNavigatorFollow.value = !textNavigatorFollow.value
  if (textNavigatorFollow.value) nextTick(scrollCurrentNavigatorWordIntoView)
}

function onReaderScrollKey(event) {
  if (['PageUp', 'PageDown', 'Home', 'End', 'ArrowUp', 'ArrowDown'].includes(event.key)) textNavigatorFollow.value = false
}

function ensureSceneBounds() {
  if (!project.scenes.length) project.scenes.push({ id: uid('scene'), name: 'Основная сцена', start: 0, end: Math.max(duration.value, 60), backgroundAssetId: null })
  if (project.scenes.length === 1 && project.scenes[0].start === 0 && project.scenes[0].end <= 60) {
    project.scenes[0].end = Math.max(duration.value, 60)
  }
}

function addSceneAtCursor() {
  const start = Math.round(currentTime.value * 10) / 10
  const nextChapter = timelineChapters.value.find((item) => item.start_seconds > start)
  const end = Math.min(duration.value || start + 60, Math.max(start + 1, nextChapter?.start_seconds || start + 60))
  const scene = { id: uid('scene'), name: `Сцена ${project.scenes.length + 1}`, start, end, backgroundAssetId: null }
  project.scenes.push(scene)
  project.scenes.sort((a, b) => a.start - b.start)
  select('scene', scene.id)
  dirty.value = true
  setNotice(`Сцена добавлена в ${formatTimecode(start)}`, 'success')
}

function selectScene(scene) {
  seekTo(scene.start)
  select('scene', scene.id)
}

function updateSceneTime(key, value) {
  if (!selectedScene.value) return
  const parsed = Math.max(0, parseTimecode(value))
  if (key === 'start') selectedScene.value.start = Math.min(parsed, selectedScene.value.end - 0.1)
  else selectedScene.value.end = Math.max(parsed, selectedScene.value.start + 0.1)
  project.scenes.sort((a, b) => a.start - b.start)
  dirty.value = true
}

function removeScene(id) {
  if (project.scenes.length <= 1) return
  const index = project.scenes.findIndex((item) => item.id === id)
  if (index >= 0) project.scenes.splice(index, 1)
  select('project')
  dirty.value = true
}

function masterElement() {
  return masterKind.value === 'audio' ? audioEl.value : masterKind.value === 'video' ? videoEl.value : null
}

function secondaryElement() {
  return masterKind.value === 'audio' && videoSource.value ? videoEl.value : null
}

function musicTimeAt(absoluteTime) {
  const music = musicEl.value
  const clip = activeMusicClip.value || selectedMusicClip.value || musicPlaybackClip.value
  const clipTime = clip
    ? Math.max(0, Number(clip.sourceOffset) || 0) + Math.max(0, absoluteTime - Number(clip.start))
    : Math.max(0, absoluteTime)
  const musicDuration = Number(music?.duration)
  if (!Number.isFinite(musicDuration) || musicDuration <= 0) return clipTime
  if (project.music.loop) return clipTime % musicDuration
  return Math.min(clipTime, Math.max(0, musicDuration - 0.02))
}

async function ensureMusicGraph() {
  const element = musicEl.value
  const AudioContextClass = globalThis.AudioContext || globalThis.webkitAudioContext
  if (!element || !AudioContextClass) {
    musicEqState.active = false
    musicEqState.error = true
    musicEqState.message = 'EQ предпросмотра недоступен; в MP4 он всё равно будет применён'
    return false
  }
  try {
    if (!musicAudioContext || musicAudioContext.state === 'closed') {
      musicAudioContext = new AudioContextClass()
    }
    if (!musicMediaSource) {
      musicMediaSource = musicAudioContext.createMediaElementSource(element)
      musicLowFilter = musicAudioContext.createBiquadFilter()
      musicLowFilter.type = 'lowshelf'
      musicLowFilter.frequency.value = 120
      musicMidFilter = musicAudioContext.createBiquadFilter()
      musicMidFilter.type = 'peaking'
      musicMidFilter.frequency.value = 1100
      musicMidFilter.Q.value = 0.85
      musicHighFilter = musicAudioContext.createBiquadFilter()
      musicHighFilter.type = 'highshelf'
      musicHighFilter.frequency.value = 7000
      musicGain = musicAudioContext.createGain()
      musicMediaSource
        .connect(musicLowFilter)
        .connect(musicMidFilter)
        .connect(musicHighFilter)
        .connect(musicGain)
        .connect(musicAudioContext.destination)
    }
    if (musicAudioContext.state === 'suspended') await musicAudioContext.resume()
    musicEqState.active = musicAudioContext.state === 'running'
    musicEqState.error = !musicEqState.active
    musicEqState.message = musicEqState.active
      ? 'EQ активен — изменения слышны сразу'
      : 'Аудиодвижок приостановлен; нажмите «Проверить музыку»'
    return true
  } catch (error) {
    musicEqState.active = false
    musicEqState.error = true
    musicEqState.message = `EQ предпросмотра не запустился: ${error?.message || 'ошибка аудиодвижка'}`
    return false
  }
}

function applyMusicMix() {
  const element = musicEl.value
  const clip = musicPlaybackClip.value
  const enabled = !!project.music.enabled && clip?.enabled !== false && !!musicSource.value
  const rawClipVolume = Number(clip?.volume)
  const clipVolume = Math.max(0, Math.min(2, Number.isFinite(rawClipVolume) ? rawClipVolume : 1))
  const level = enabled ? Math.max(0, Math.min(1, (Number(project.music.volume) || 0) * clipVolume)) : 0
  const now = musicAudioContext?.currentTime || 0

  function setAudioValue(parameter, value) {
    if (!parameter) return
    parameter.cancelScheduledValues(now)
    parameter.setValueAtTime(value, now)
  }

  setAudioValue(musicLowFilter?.gain, Math.max(-12, Math.min(12, Number(project.music.bass) || 0)))
  setAudioValue(musicMidFilter?.gain, Math.max(-12, Math.min(12, Number(project.music.mid) || 0)))
  setAudioValue(musicHighFilter?.gain, Math.max(-12, Math.min(12, Number(project.music.treble) || 0)))
  setAudioValue(musicGain?.gain, 1)
  if (element) {
    // Native media volume is reliable in Qt WebEngine even when Web Audio is
    // unavailable. Keep EQ in the graph, but never route volume through it.
    element.volume = level
    element.loop = !!project.music.loop
    if (!enabled) element.pause()
  }
}

async function onMusicControlInput(activateEq = false) {
  applyMusicMix()
  if (activateEq || musicPreviewPlaying.value || playing.value) {
    await ensureMusicGraph()
    applyMusicMix()
  }
}

async function startMusicPreview(absoluteTime) {
  const generation = playbackGeneration
  await nextTick()
  const element = musicEl.value
  const clip = activeMusicClip.value || musicPlaybackClip.value
  if (!element || !musicSource.value || !project.music.enabled || !clip) {
    element?.pause()
    return
  }
  const target = musicTimeAt(absoluteTime)
  if (Math.abs((element.currentTime || 0) - target) > 0.08) {
    try { element.currentTime = target } catch { /* metadata not ready */ }
  }
  await ensureMusicGraph()
  if (generation !== playbackGeneration || element !== musicEl.value) return
  applyMusicMix()
  await element.play()
}

function syncMusicPlayback() {
  const element = musicEl.value
  if (!element) return
  if (!playing.value || !activeMusicClip.value || !project.music.enabled) {
    if (playing.value) element.pause()
    return
  }
  applyMusicMix()
  const target = musicTimeAt(currentTime.value)
  if (element.readyState > 0 && !element.seeking && Math.abs(element.currentTime - target) > 0.35) {
    try { element.currentTime = target } catch { /* metadata will synchronize */ }
  }
  if (element.paused && !element.ended && !musicStartPending && element.readyState >= 2) {
    musicStartPending = startMusicPreview(currentTime.value)
      .catch(error => { if (error.name !== 'AbortError') musicEqState.message = `Музыка: ${error.message}` })
      .finally(() => { musicStartPending = null })
  }
}

async function toggleMusicPreview() {
  const element = musicEl.value
  if (!element || !musicSource.value) {
    setNotice('Сначала выберите музыкальный файл', 'warning')
    return
  }
  if (!element.paused) {
    element.pause()
    return
  }
  if (selectedMusicClip.value && activeMusicClip.value?.id !== selectedMusicClip.value.id) {
    seekTo(selectedMusicClip.value.start)
    await nextTick()
  }
  audioEl.value?.pause()
  videoEl.value?.pause()
  playing.value = false
  cancelAnimationFrame(playbackFrame)
  try {
    project.music.enabled = true
    await ensureMusicGraph()
    applyMusicMix()
    const target = musicTimeAt(currentTime.value)
    if (Math.abs((element.currentTime || 0) - target) > 0.08) element.currentTime = target
    await element.play()
  } catch (error) {
    musicEqState.active = false
    musicEqState.error = true
    musicEqState.message = `Не удалось запустить пробу: ${error.message}`
  }
}

function onMusicMetadata() {
  const element = musicEl.value
  if (element) {
    try { element.currentTime = musicTimeAt(currentTime.value) } catch { /* metadata not ready */ }
  }
  applyMusicMix()
  syncMusicPlayback()
}

function onMusicError() {
  musicEqState.active = false
  musicEqState.error = true
  musicEqState.message = 'Музыкальный файл не удалось открыть'
  if (musicSource.value) setNotice('Музыкальный файл недоступен или не поддерживается браузером.', 'error', 6500)
}

function onMusicSelectionChanged() {
  pauseAll()
  nextTick(() => {
    musicEl.value?.load()
    applyMusicMix()
  })
}

function resetMusicMix() {
  Object.assign(project.music, clone(DEFAULT_MUSIC_MIX))
  applyMusicMix()
}

function setMusicVolume(level) {
  project.music.volume = Math.max(0, Math.min(1, Number(level) || 0))
  void onMusicControlInput(false)
}

function releaseLiveVisualizer() {
  try { visualStreamSource?.disconnect() } catch { /* already disconnected */ }
  try { visualAnalyser?.disconnect() } catch { /* analyser has no outputs */ }
  for (const track of visualizerStream?.getTracks?.() || []) {
    try { track.stop() } catch { /* already stopped */ }
  }
  visualStreamSource = null
  visualAnalyser = null
  visualizerStream = null
  visualizerMaster = null
  visualizerFrequencyData = null
}

function disconnectLiveVisualizer({ clearPending = true } = {}) {
  visualizerGeneration += 1
  releaseLiveVisualizer()
  if (clearPending) visualizerConnectPending = null
}

async function connectLiveVisualizer() {
  const master = masterElement()
  if (!master || !playing.value) return false
  if (visualizerConnectPending) return visualizerConnectPending

  visualizerLastConnectAttempt = performance.now()
  const capture = master.captureStream || master.mozCaptureStream
  const AudioContextClass = globalThis.AudioContext || globalThis.webkitAudioContext
  if (typeof capture !== 'function' || !AudioContextClass) return false

  const generation = visualizerGeneration
  const request = (async () => {
    let stream = null
    let source = null
    let analyser = null
    try {
      const liveTracks = visualizerStream?.getAudioTracks?.()
        ?.some((track) => track.readyState === 'live')
      if (visualAnalyser && visualizerMaster === master && liveTracks) {
        if (visualAudioContext?.state === 'suspended') await visualAudioContext.resume()
        return generation === visualizerGeneration
          && masterElement() === master
          && playing.value
          && visualAudioContext?.state === 'running'
      }

      stream = capture.call(master)
      if (!stream?.getAudioTracks?.().length) return false
      if (!visualAudioContext || visualAudioContext.state === 'closed') {
        visualAudioContext = new AudioContextClass()
      }
      if (visualAudioContext.state === 'suspended') await visualAudioContext.resume()
      if (generation !== visualizerGeneration || masterElement() !== master || !playing.value) return false

      analyser = visualAudioContext.createAnalyser()
      analyser.fftSize = 2048
      analyser.smoothingTimeConstant = 0.62
      analyser.minDecibels = -84
      analyser.maxDecibels = -10
      source = visualAudioContext.createMediaStreamSource(stream)
      source.connect(analyser)

      releaseLiveVisualizer()
      visualStreamSource = source
      visualAnalyser = analyser
      visualizerStream = stream
      visualizerMaster = master
      visualizerFrequencyData = new Uint8Array(analyser.frequencyBinCount)
      stream = null
      source = null
      analyser = null
      return true
    } catch {
      return false
    } finally {
      try { source?.disconnect() } catch { /* connection was never published */ }
      try { analyser?.disconnect() } catch { /* analyser was never published */ }
      for (const track of stream?.getTracks?.() || []) {
        try { track.stop() } catch { /* temporary capture already stopped */ }
      }
    }
  })()
  visualizerConnectPending = request
  try {
    return await request
  } finally {
    if (visualizerConnectPending === request) visualizerConnectPending = null
  }
}

function secondaryTimeAt(absoluteTime) {
  const secondary = secondaryElement()
  const secondaryDuration = Number(secondary?.duration)
  if (Number.isFinite(secondaryDuration) && secondaryDuration > 0) return absoluteTime % secondaryDuration
  return absoluteTime
}

function chapterAtTimelineTime(timeValue) {
  const time = Math.max(0, Number(timeValue) || 0)
  return findActiveInterval(timelineChapterIndex.value, time) || timelineChapters.value.at(-1) || null
}

function chapterAtSourceTime(timeValue) {
  const time = Math.max(0, Number(timeValue) || 0)
  return findActiveInterval(sourceChapterIndex.value, time) || timelineChapters.value.at(-1) || null
}

function sourceTimeAtTimeline(timeValue) {
  const time = Math.max(0, Number(timeValue) || 0)
  const chapter = chapterAtTimelineTime(time)
  if (!chapter || !hasChapterTimeStretch.value) return time
  return Math.max(
    chapterSourceStart(chapter),
    Math.min(
      chapterSourceEnd(chapter),
      chapterSourceStart(chapter)
        + (time - Number(chapter.start_seconds)) * chapterPlaybackRate(chapter),
    ),
  )
}

function timelineTimeAtSource(timeValue) {
  const time = Math.max(0, Number(timeValue) || 0)
  const chapter = chapterAtSourceTime(time)
  if (!chapter || !hasChapterTimeStretch.value) return time
  return Math.max(
    Number(chapter.start_seconds),
    Math.min(
      Number(chapter.end_seconds),
      Number(chapter.start_seconds)
        + (time - chapterSourceStart(chapter)) / chapterPlaybackRate(chapter),
    ),
  )
}

function applyMasterPlaybackRate(timeValue = currentTime.value) {
  const master = masterElement()
  if (!master) return
  const rate = hasChapterTimeStretch.value
    ? chapterPlaybackRate(chapterAtTimelineTime(timeValue))
    : 1
  if (Math.abs(Number(master.playbackRate || 1) - rate) > 0.001) master.playbackRate = rate
  master.preservesPitch = true
}

async function togglePlay() {
  const master = masterElement()
  if (!master) return setNotice('Сначала добавьте аудио или видео', 'warning')
  if (!master.paused) {
    pauseAll()
    return
  }

  // Keep the audible master on the browser's native media path. Capturing the
  // element in a suspended Web Audio graph can leave Firefox playing silence.
  const level = Math.min(1, Math.max(0, Number(volume.value) || 0))
  master.volume = level
  applyMasterPlaybackRate(currentTime.value)
  if (master === audioEl.value) {
    master.defaultMuted = false
    master.muted = false
  }

  const secondary = secondaryElement()
  if (secondary) {
    secondary.muted = true
    const target = secondaryTimeAt(currentTime.value)
    if (Math.abs(secondary.currentTime - target) > 0.08) secondary.currentTime = target
  }
  try {
    await startMusicPreview(currentTime.value)
  } catch (error) {
    setNotice(`Музыка не запустилась, озвучка продолжит играть: ${error.message}`, 'warning', 6000)
  }
  const generation = ++playbackGeneration
  try {
    if (generation !== playbackGeneration) return
    await master.play()
    void connectLiveVisualizer()
    if (secondary) secondary.play().catch(() => {})
  } catch (error) {
    musicEl.value?.pause()
    setNotice(`Не удалось воспроизвести файл: ${error.message}`, 'error', 6500)
  }
}

function pauseAll() {
  playbackGeneration += 1
  audioEl.value?.pause()
  videoEl.value?.pause()
  musicEl.value?.pause()
  playing.value = false
  excerptPlaybackEnd.value = null
  cancelAnimationFrame(playbackFrame)
  playbackUiGate.reset()
}

function stopPlayback() {
  pauseAll()
  seekTo(0)
}

function seekBy(offset) {
  seekTo(currentTime.value + offset)
}

function seekTo(value) {
  const next = Math.max(0, Math.min(Number(value) || 0, duration.value || Number(value) || 0))
  currentTime.value = next
  updatePlaybackDom(next)
  for (const element of [audioEl.value, videoEl.value, musicEl.value]) {
    if (element && Number.isFinite(element.duration)) {
      const target = element === musicEl.value
        ? musicTimeAt(next)
        : element === secondaryElement()
          ? secondaryTimeAt(next)
          : element === masterElement()
            ? sourceTimeAtTimeline(next)
            : Math.min(next, element.duration)
      if (Math.abs(element.currentTime - target) > 0.02) {
        try { element.currentTime = Math.min(target, element.duration) } catch { /* metadata not ready */ }
      }
    }
  }
  applyMasterPlaybackRate(next)
}

function toggleComicTheater() {
  if (!isComicEdition.value) return
  comicTheater.value = !comicTheater.value
  showComicTelegramBanner()
}

function showComicTelegramBanner() {
  clearTimeout(comicTelegramTimer)
  if (!isComicEdition.value) {
    comicTelegramVisible.value = true
    return
  }
  comicTelegramVisible.value = true
  comicTelegramTimer = setTimeout(() => {
    comicTelegramVisible.value = false
  }, 6500)
}

function onMediaMetadata(kind) {
  const element = kind === 'audio' ? audioEl.value : videoEl.value
  const mediaDuration = Number(element?.duration)
  if (Number.isFinite(mediaDuration) && mediaDuration > 0) {
    if (kind === 'audio') audioDuration.value = mediaDuration
    else videoDuration.value = mediaDuration
    ensureSceneBounds()
  }
  applyVolume()
  nextTick(() => updatePlaybackDom(currentTime.value))
}

function playbackBoundaryChanged(timeValue, liveVisualScene) {
  const chapterPosition = findStartedIndex(timelineChapterIndex.value, timeValue)
  const chapter = timelineChapterIndex.value.items[chapterPosition] || null
  const scene = findActiveInterval(timelineSceneIndex.value, timeValue)
  const music = findActiveInterval(musicClipIndex.value, timeValue, clip => clip.enabled !== false)
  return (
    chapter?.id !== currentChapter.value?.id
    || scene?.id !== currentScene.value?.id
    || music?.id !== activeMusicClip.value?.id
    || liveVisualScene?.visual?.id !== visualScene.value?.visual?.id
  )
}

function updatePlaybackDom(timeValue, liveVisualScene = sceneAt(visualPlan.value, timeValue)) {
  const time = Math.max(0, Number(timeValue) || 0)
  const total = Math.max(0.01, Number(duration.value) || 0.01)
  const fraction = Math.max(0, Math.min(1, time / total))
  const preciseTime = formatTime(time, true)
  const compactTime = formatTime(time)

  if (sceneCurrentTimeEl.value && sceneCurrentTimeEl.value.textContent !== compactTime) {
    sceneCurrentTimeEl.value.textContent = compactTime
  }
  if (transportCurrentTimeEl.value && transportCurrentTimeEl.value.textContent !== preciseTime) {
    transportCurrentTimeEl.value.textContent = preciseTime
  }
  if (timelinePlayheadLabel.value && timelinePlayheadLabel.value.textContent !== preciseTime) {
    timelinePlayheadLabel.value.textContent = preciseTime
  }
  if (sceneProgressFill.value) sceneProgressFill.value.style.transform = `scaleX(${fraction})`

  const scrubber = transportScrubber.value
  if (scrubber && Math.abs(Number(scrubber.value) - time) > 0.005) scrubber.value = String(time)

  const playhead = timelinePlayhead.value
  if (playhead) {
    const width = Math.max(1, Number(timelineViewport.contentWidth) || timelineContent.value?.offsetWidth || 1)
    playhead.style.transform = `translate3d(${fraction * width - 8}px, 0, 0)`
    if (playhead.getAttribute('aria-valuetext') !== preciseTime) {
      playhead.setAttribute('aria-valuenow', String(time))
      playhead.setAttribute('aria-valuetext', preciseTime)
    }
  }

  const waveform = timelineWaveform.value
  if (waveform !== renderedWaveformElement) {
    renderedWaveformElement = waveform
    renderedWaveformBars = -1
  }
  if (waveform) {
    const bars = waveform.children
    const played = Math.max(0, Math.min(bars.length, Math.floor(fraction * bars.length) + 1))
    if (played !== renderedWaveformBars) {
      const start = Math.max(0, Math.min(played, renderedWaveformBars < 0 ? 0 : renderedWaveformBars))
      const end = Math.min(bars.length, Math.max(played, renderedWaveformBars < 0 ? bars.length : renderedWaveformBars))
      for (let index = start; index < end; index += 1) bars[index].classList.toggle('played', index < played)
      renderedWaveformBars = played
    }
  }

  if (visualSceneLayer.value) {
    const liveLayers = liveVisualScene?.layers || []
    const opacityByKey = new Map(liveLayers.map(layer => [
      `${layer.visual.chapterId}:${layer.visual.id}`,
      layer.opacity,
    ]))
    visualSceneLayer.value.style.opacity = liveLayers.length ? '1' : '0'
    for (const element of visualSceneLayer.value.children) {
      element.style.opacity = String(opacityByKey.get(element.dataset.visualKey) ?? 0)
    }
  }
  sceneEl.value?.classList.toggle('visual-opaque', Number(liveVisualScene?.opacity) >= 1)
}

function commitPlaybackFrame(timeValue, timestamp, { force = false } = {}) {
  const liveVisualScene = sceneAt(visualPlan.value, timeValue)
  updatePlaybackDom(timeValue, liveVisualScene)
  const boundaryChanged = playbackBoundaryChanged(timeValue, liveVisualScene)
  if (playbackUiGate.shouldCommit(timestamp, { force: force || boundaryChanged })) currentTime.value = timeValue
}

function onMediaTime(kind) {
  if (kind !== masterKind.value) return
  const master = masterElement()
  if (!master) return
  const timelineTime = timelineTimeAtSource(master.currentTime || 0)
  commitPlaybackFrame(timelineTime, performance.now())
  applyMasterPlaybackRate(timelineTime)
  if (stopAtExcerptEnd(timelineTime)) return
  const secondary = secondaryElement()
  const secondaryTarget = secondaryTimeAt(timelineTime)
  if (secondary && Math.abs(secondary.currentTime - secondaryTarget) > 0.22) secondary.currentTime = secondaryTarget
  syncMusicPlayback()
}

function onMediaPlay(kind) {
  if (kind !== masterKind.value) return
  playing.value = true
  void connectLiveVisualizer()
  cancelAnimationFrame(playbackFrame)
  playbackUiGate.reset()
  tickPlayback(performance.now())
}

function onMediaPause(kind) {
  if (kind !== masterKind.value) return
  playing.value = false
  cancelAnimationFrame(playbackFrame)
  visualAudioContext?.suspend().catch(() => {})
  secondaryElement()?.pause()
  musicEl.value?.pause()
}

function onMediaEnded(kind) {
  if (kind !== masterKind.value) return
  pauseAll()
  disconnectLiveVisualizer()
}

function onMediaError(kind) {
  const source = kind === 'audio' ? audioSource.value : videoSource.value
  if (source) setNotice(`${kind === 'audio' ? 'Аудио' : 'Видео'} не поддерживается браузером или файл недоступен.`, 'error', 7000)
}

function tickPlayback(frameTime) {
  if (!playing.value) return
  const master = masterElement()
  if (master) {
    const timelineTime = timelineTimeAtSource(master.currentTime || 0)
    commitPlaybackFrame(timelineTime, frameTime)
    applyMasterPlaybackRate(timelineTime)
    if (stopAtExcerptEnd(timelineTime)) return
  }
  playbackFrame = requestAnimationFrame(tickPlayback)
}

function applyVolume() {
  const master = masterElement()
  const level = Math.min(1, Math.max(0, Number(volume.value) || 0))
  if (master) {
    master.volume = level
    if (master === audioEl.value) {
      master.defaultMuted = false
      master.muted = false
    }
  }
  if (videoEl.value && masterKind.value === 'audio') videoEl.value.muted = true
}

function clipStyle(start, end, minWidth = 0.4) {
  const total = Math.max(duration.value, 1)
  const left = Math.max(0, Math.min(100, Number(start) / total * 100))
  const width = Math.max(minWidth, Math.min(100 - left, (Math.max(Number(end), Number(start)) - Number(start)) / total * 100))
  return { left: `${left}%`, width: `${width}%` }
}

function updateTimelineViewport() {
  const scroller = timelineScroll.value
  const content = timelineContent.value
  if (!scroller || !content) return
  timelineViewport.left = scroller.scrollLeft
  timelineViewport.width = scroller.clientWidth
  timelineViewport.contentWidth = content.offsetWidth
}

function showTimelineClipThumbnail(clip) {
  if (selection.type === 'visual' && selection.id === clip.id) return true
  if (visualScene.value.visual?.id === clip.id) return true
  const total = Math.max(0.1, duration.value)
  const contentWidth = Math.max(1, timelineViewport.contentWidth)
  return Math.max(0, clip.end - clip.start) / total * contentWidth >= 44
}

function timelineTimeFromPointer(event) {
  const rect = timelineContent.value?.getBoundingClientRect()
  if (!rect?.width) return currentTime.value
  const fraction = Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width))
  return fraction * duration.value
}

function timelineSnapCandidates(excludeTimes = []) {
  const excluded = (value) => excludeTimes.some((time) => Math.abs(Number(time) - value) < 0.001)
  const candidates = []
  const add = (time, label, priority) => {
    const value = Number(time)
    if (!Number.isFinite(value) || value < 0 || value > duration.value || excluded(value)) return
    candidates.push({ time: value, label, priority })
  }
  const step = timelineGridStep.value
  const gridCount = Math.min(1600, Math.ceil(duration.value / Math.max(step, 0.001)))
  for (let index = 0; index <= gridCount; index += 1) add(index * step, `Сетка · ${formatTime(index * step, true)}`, 1)
  add(0, 'Начало проекта', 6)
  add(duration.value, 'Конец проекта', 6)
  add(currentTime.value, `Курсор · ${formatTime(currentTime.value, true)}`, 3)
  for (const scene of project.scenes) {
    add(scene.start, `Сцена · ${scene.name}`, 2)
    add(scene.end, `Сцена · ${scene.name}`, 2)
  }
  for (const clip of displayMusicClips.value) {
    add(clip.start, `Музыка · ${assetById(clip.assetId)?.name || 'клип'}`, 2)
    add(clip.end, `Музыка · ${assetById(clip.assetId)?.name || 'клип'}`, 2)
  }
  for (const clip of timelineVisualClips.value) {
    if (selection.type === 'visual' && selection.id === clip.id) continue
    add(clip.start, 'Иллюстрация · начало', 2)
    add(clip.end, 'Иллюстрация · конец', 2)
  }
  for (const chapter of timelineChapters.value) {
    add(chapter.start_seconds, `Глава · ${chapter.title}`, 7)
    add(chapter.end_seconds, `Глава · ${chapter.title}`, 7)
  }
  return candidates
}

function findTimelineSnap(value, { excludeTimes = [] } = {}) {
  const bounded = Math.max(0, Math.min(Number(value) || 0, duration.value || Number(value) || 0))
  if (!timelineSnap.value) return { time: bounded, snapped: false, distance: Infinity, priority: 0, label: '' }
  const rect = timelineContent.value?.getBoundingClientRect()
  const tolerance = rect?.width ? Math.max(0.025, duration.value * 11 / rect.width) : 0.15
  let result = { time: bounded, snapped: false, distance: tolerance, priority: 0, label: '' }
  for (const candidate of timelineSnapCandidates(excludeTimes)) {
    const distance = Math.abs(candidate.time - bounded)
    if (
      distance < result.distance - 0.0001
      || (Math.abs(distance - result.distance) <= 0.0001 && candidate.priority > result.priority)
    ) {
      result = { ...candidate, snapped: true, distance }
    }
  }
  return result
}

function showTimelineSnap(result) {
  if (!result?.snapped) {
    snapGuideTime.value = null
    snapGuideLabel.value = ''
    return
  }
  snapGuideTime.value = result.time
  snapGuideLabel.value = result.label
}

function snapTimelineTime(value, options = {}) {
  const result = findTimelineSnap(value, options)
  showTimelineSnap(result)
  return result.time
}

function startVisualClipPointer(event, displayClip, mode) {
  if (event.button !== 0) return
  event.preventDefault()
  select('visual', displayClip.id)
  const clip = selectedVisual.value, chapter = selectedVisualChapter.value
  removeTimelinePointerListeners()
  const rect = timelineContent.value?.getBoundingClientRect()
  if (!clip || !chapter || !rect?.width || !duration.value) return
  const originX = event.clientX, origin = clip.startTime, length = clip.duration
  const chapterLength = chapterEnd(chapter.id) - chapter.start_seconds
  const originalEnd = Math.min(chapterLength, origin + length)
  const excludeTimes = [displayClip.start, displayClip.end]
  timelinePointerMove = moveEvent => {
    moveEvent.preventDefault()
    const delta = (moveEvent.clientX - originX) / rect.width * duration.value
    const point = mode === 'trim-end' ? originalEnd + delta : origin + delta
    const snapped = snapTimelineTime(chapter.start_seconds + point, { excludeTimes }) - chapter.start_seconds
    if (mode === 'move') {
      clip.startTime = Math.max(0, Math.min(snapped, Math.max(0, chapterLength - length)))
    } else if (mode === 'trim-start') {
      clip.startTime = Math.max(0, Math.min(snapped, originalEnd - 0.05))
      clip.duration = originalEnd - clip.startTime
    } else {
      clip.duration = Math.max(0.05, Math.min(snapped, chapterLength) - origin)
    }
  }
  timelinePointerUp = () => {
    removeTimelinePointerListeners()
    snapGuideTime.value = null
    snapGuideLabel.value = ''
  }
  window.addEventListener('pointermove', timelinePointerMove)
  window.addEventListener('pointerup', timelinePointerUp)
  window.addEventListener('pointercancel', timelinePointerUp)
}

function startSceneClipPointer(event, scene, mode = 'move') {
  if (event.button !== 0) return
  event.preventDefault()
  select('scene', scene.id)
  removeTimelinePointerListeners()
  const rect = timelineContent.value?.getBoundingClientRect()
  if (!rect?.width || !duration.value) return
  const originX = event.clientX
  const original = { start: Number(scene.start), end: Number(scene.end) }
  const length = original.end - original.start
  let moved = false
  timelinePointerMove = (moveEvent) => {
    moveEvent.preventDefault()
    const delta = (moveEvent.clientX - originX) / rect.width * duration.value
    if (Math.abs(moveEvent.clientX - originX) > 2) moved = true
    if (mode === 'move') {
      let nextStart = Math.max(0, Math.min(original.start + delta, Math.max(0, duration.value - length)))
      const snappedStart = findTimelineSnap(nextStart)
      const snappedEnd = findTimelineSnap(nextStart + length)
      const useEnd = snappedEnd.snapped && (
        !snappedStart.snapped
        || snappedEnd.distance < snappedStart.distance
        || (Math.abs(snappedEnd.distance - snappedStart.distance) < 0.0001 && snappedEnd.priority > snappedStart.priority)
      )
      const activeSnap = useEnd ? snappedEnd : snappedStart
      nextStart = useEnd ? snappedEnd.time - length : snappedStart.time
      showTimelineSnap(activeSnap)
      scene.start = Math.max(0, Math.min(nextStart, Math.max(0, duration.value - length)))
      scene.end = scene.start + length
    } else if (mode === 'trim-start') {
      scene.start = Math.min(
        original.end - 0.1,
        snapTimelineTime(Math.max(0, Math.min(original.start + delta, original.end - 0.1))),
      )
    } else {
      scene.end = Math.max(
        original.start + 0.1,
        snapTimelineTime(Math.min(duration.value, original.end + delta)),
      )
    }
  }
  timelinePointerUp = () => {
    removeTimelinePointerListeners()
    snapGuideTime.value = null
    snapGuideLabel.value = ''
    if (moved) {
      project.scenes.sort((a, b) => a.start - b.start)
      seekTo(mode === 'trim-end' ? scene.end : scene.start)
    }
  }
  window.addEventListener('pointermove', timelinePointerMove)
  window.addEventListener('pointerup', timelinePointerUp)
  window.addEventListener('pointercancel', timelinePointerUp)
}

function retimeChapterToDuration(chapter, durationValue) {
  const source = project.chapters.find((item) => item.id === chapter?.id)
  if (!source) return false
  const sorted = [...project.chapters].sort((left, right) => left.start_seconds - right.start_seconds)
  const index = sorted.findIndex((item) => item.id === source.id)
  if (index < 0) return false
  const sourceDuration = chapterSourceDuration(source)
  const minimum = Math.max(0.1, sourceDuration / MAX_CHAPTER_PLAYBACK_RATE)
  const maximum = sourceDuration / MIN_CHAPTER_PLAYBACK_RATE
  const nextDuration = Math.max(minimum, Math.min(maximum, Number(durationValue) || sourceDuration))
  const oldEnd = Number(source.end_seconds)
  const nextEnd = Number(source.start_seconds) + nextDuration
  const delta = nextEnd - oldEnd
  if (Math.abs(delta) < 0.0001) return false

  source.end_seconds = nextEnd
  source.playbackRate = sourceDuration / nextDuration
  for (const later of sorted.slice(index + 1)) {
    later.start_seconds = Math.max(0, Number(later.start_seconds) + delta)
    later.end_seconds = Math.max(later.start_seconds + 0.1, Number(later.end_seconds) + delta)
    later.playbackRate = chapterPlaybackRate(later)
  }
  for (const scene of project.scenes) {
    const sceneStart = Number(scene.start) || 0
    const sceneEnd = Number(scene.end) || sceneStart
    if (sceneStart >= oldEnd - 0.001) {
      scene.start = Math.max(0, sceneStart + delta)
      scene.end = Math.max(scene.start + 0.1, sceneEnd + delta)
    } else if (sceneEnd >= oldEnd - 0.001) {
      scene.end = Math.max(sceneStart + 0.1, sceneEnd + delta)
    }
  }
  syncChapterBoundMusicClips()
  dirty.value = true
  applyMasterPlaybackRate(currentTime.value)
  return true
}

function setChapterDuration(chapter, value) {
  const requested = parseTimecode(value)
  if (!Number.isFinite(requested) || requested <= 0) {
    setNotice('Введите длительность главы, например 00:03:00.', 'warning')
    return
  }
  const sourceDuration = chapterSourceDuration(chapter)
  const minimum = sourceDuration / MAX_CHAPTER_PLAYBACK_RATE
  const maximum = sourceDuration / MIN_CHAPTER_PLAYBACK_RATE
  retimeChapterToDuration(chapter, requested)
  const applied = chapterTimelineDuration(chapter)
  if (requested < minimum - 0.01 || requested > maximum + 0.01) {
    setNotice(`Допустимая скорость ×${MIN_CHAPTER_PLAYBACK_RATE}–×${MAX_CHAPTER_PLAYBACK_RATE}; применено ${formatTime(applied, true)}.`, 'warning', 6000)
  } else {
    setNotice(`Глава: ${formatTime(applied, true)} · скорость ×${chapterPlaybackRate(chapter).toFixed(2)}`, 'success')
  }
  seekTo(chapter.start_seconds)
}

function resetChapterPlaybackRate(chapter) {
  if (retimeChapterToDuration(chapter, chapterSourceDuration(chapter))) {
    seekTo(chapter.start_seconds)
    setNotice('Скорость главы возвращена к ×1.', 'success')
  }
}

function startChapterStretchPointer(event, chapter) {
  if (event.button !== 0) return
  event.preventDefault()
  const source = project.chapters.find((item) => item.id === chapter.id)
  const rect = timelineContent.value?.getBoundingClientRect()
  if (!source || !rect?.width || !duration.value) return
  pauseAll()
  select('chapter', source.id)
  removeTimelinePointerListeners()
  const originX = event.clientX
  const originalDuration = chapterTimelineDuration(source)
  const originalTimelineDuration = duration.value
  timelinePointerMove = (moveEvent) => {
    moveEvent.preventDefault()
    const delta = (moveEvent.clientX - originX) / rect.width * originalTimelineDuration
    retimeChapterToDuration(source, originalDuration + delta)
  }
  timelinePointerUp = () => {
    removeTimelinePointerListeners()
    snapGuideTime.value = null
    snapGuideLabel.value = ''
    seekTo(source.start_seconds)
    setNotice(`Глава: ${formatTime(chapterTimelineDuration(source), true)} · скорость ×${chapterPlaybackRate(source).toFixed(2)}`, 'success')
  }
  window.addEventListener('pointermove', timelinePointerMove)
  window.addEventListener('pointerup', timelinePointerUp)
  window.addEventListener('pointercancel', timelinePointerUp)
}

function startChapterBoundaryPointer(event, chapter) {
  if (event.button !== 0 || chapter.index <= 0) return
  event.preventDefault()
  const source = project.chapters.find((item) => item.id === chapter.id)
  const sorted = [...project.chapters].sort((a, b) => a.start_seconds - b.start_seconds)
  const index = sorted.findIndex((item) => item.id === chapter.id)
  if (!source || index <= 0) return
  const rect = timelineContent.value?.getBoundingClientRect()
  if (!rect?.width || !duration.value) return
  select('chapter', source.id)
  removeTimelinePointerListeners()
  const originX = event.clientX
  const originalStart = Number(source.start_seconds)
  const previous = sorted[index - 1]
  const minimum = Math.max(
    Number(previous.start_seconds) + chapterSourceDuration(previous) / MAX_CHAPTER_PLAYBACK_RATE,
    Number(source.end_seconds) - chapterSourceDuration(source) / MIN_CHAPTER_PLAYBACK_RATE,
  )
  const maximum = Math.min(
    Number(previous.start_seconds) + chapterSourceDuration(previous) / MIN_CHAPTER_PLAYBACK_RATE,
    Number(source.end_seconds) - chapterSourceDuration(source) / MAX_CHAPTER_PLAYBACK_RATE,
  )
  timelinePointerMove = (moveEvent) => {
    moveEvent.preventDefault()
    const delta = (moveEvent.clientX - originX) / rect.width * duration.value
    const bounded = Math.max(minimum, Math.min(maximum, originalStart + delta))
    const snapped = snapTimelineTime(bounded, { excludeTimes: [originalStart] })
    const finalValue = Math.max(minimum, Math.min(maximum, snapped))
    if (Math.abs(finalValue - snapped) > 0.001) showTimelineSnap(null)
    source.start_seconds = finalValue
    previous.end_seconds = finalValue
    source.playbackRate = chapterPlaybackRate(source)
    previous.playbackRate = chapterPlaybackRate(previous)
    syncChapterBoundMusicClips()
    dirty.value = true
  }
  timelinePointerUp = () => {
    removeTimelinePointerListeners()
    snapGuideTime.value = null
    snapGuideLabel.value = ''
    seekTo(source.start_seconds)
  }
  window.addEventListener('pointermove', timelinePointerMove)
  window.addEventListener('pointerup', timelinePointerUp)
  window.addEventListener('pointercancel', timelinePointerUp)
}

function startMusicClipPointer(event, sourceClip, mode = 'move') {
  if (event.button !== 0) return
  event.preventDefault()
  if (event.altKey && mode === 'move') {
    splitMusicClipAtPointer(event, sourceClip)
    return
  }
  let clip = sourceClip
  if (clip.legacy) {
    ensureExplicitMusicClips()
    clip = project.musicClips[0]
  }
  select('music', clip.id)
  removeTimelinePointerListeners()
  const rect = timelineContent.value?.getBoundingClientRect()
  if (!rect?.width || !duration.value) return
  const originX = event.clientX
  const original = {
    start: Number(clip.start),
    end: Number(clip.end),
    sourceOffset: Number(clip.sourceOffset) || 0,
  }
  const length = original.end - original.start
  let moved = false
  timelinePointerMove = (moveEvent) => {
    moveEvent.preventDefault()
    const delta = (moveEvent.clientX - originX) / rect.width * duration.value
    if (Math.abs(moveEvent.clientX - originX) > 2) {
      moved = true
      clip.chapterId = null
      clip.chapterBound = false
    }
    if (mode === 'move') {
      let nextStart = Math.max(0, Math.min(original.start + delta, Math.max(0, duration.value - length)))
      const snappedStart = findTimelineSnap(nextStart)
      const snappedEnd = findTimelineSnap(nextStart + length)
      const useEnd = snappedEnd.snapped && (
        !snappedStart.snapped
        || snappedEnd.distance < snappedStart.distance
        || (Math.abs(snappedEnd.distance - snappedStart.distance) < 0.0001 && snappedEnd.priority > snappedStart.priority)
      )
      const activeSnap = useEnd ? snappedEnd : snappedStart
      if (useEnd) {
        nextStart = snappedEnd.time - length
      } else {
        nextStart = snappedStart.time
      }
      showTimelineSnap(activeSnap)
      clip.start = Math.max(0, Math.min(nextStart, Math.max(0, duration.value - length)))
      clip.end = clip.start + length
    } else if (mode === 'trim-start') {
      const earliestStart = Math.max(0, original.start - original.sourceOffset)
      const nextStart = snapTimelineTime(
        Math.max(earliestStart, Math.min(original.start + delta, original.end - 0.05)),
      )
      clip.start = Math.min(nextStart, original.end - 0.05)
      clip.sourceOffset = Math.max(0, original.sourceOffset + clip.start - original.start)
    } else {
      clip.end = Math.max(
        original.start + 0.05,
        snapTimelineTime(
          Math.min(duration.value, original.end + delta),
        ),
      )
    }
  }
  timelinePointerUp = () => {
    removeTimelinePointerListeners()
    snapGuideTime.value = null
    snapGuideLabel.value = ''
    if (moved) {
      bindMusicClipToMatchingChapter(clip)
      seekTo(mode === 'trim-end' ? clip.end : clip.start)
      scrollTimelineToTime(currentTime.value, 'auto')
    }
  }
  window.addEventListener('pointermove', timelinePointerMove)
  window.addEventListener('pointerup', timelinePointerUp)
  window.addEventListener('pointercancel', timelinePointerUp)
}

function onTimelineWheel(event) {
  const scroller = timelineScroll.value
  if (!scroller) return
  if (event.ctrlKey || event.metaKey) {
    event.preventDefault()
    const rect = scroller.getBoundingClientRect()
    const pointerX = event.clientX - rect.left
    const oldWidth = Math.max(1, scroller.scrollWidth - 128)
    const timelineX = Math.max(0, scroller.scrollLeft + pointerX - 128)
    const anchor = timelineX / oldWidth
    timelineZoom.value = Math.max(1, Math.min(12, timelineZoom.value * Math.exp(-event.deltaY * 0.0025)))
    nextTick(() => {
      const newWidth = Math.max(1, scroller.scrollWidth - 128)
      scroller.scrollLeft = Math.max(0, anchor * newWidth - pointerX + 128)
    })
    return
  }
  if (event.shiftKey) {
    event.preventDefault()
    scroller.scrollLeft += event.deltaY + event.deltaX
  }
}

function removeTimelinePanListeners() {
  if (timelinePanMove) window.removeEventListener('pointermove', timelinePanMove)
  if (timelinePanUp) {
    window.removeEventListener('pointerup', timelinePanUp)
    window.removeEventListener('pointercancel', timelinePanUp)
  }
  timelinePanMove = null
  timelinePanUp = null
}

function startTimelinePan(event) {
  if (event.button !== 1 || !timelineScroll.value) return
  event.preventDefault()
  const scroller = timelineScroll.value
  const origin = { x: event.clientX, y: event.clientY, left: scroller.scrollLeft, top: scroller.scrollTop }
  scroller.classList.add('panning')
  timelinePanMove = (moveEvent) => {
    moveEvent.preventDefault()
    scroller.scrollLeft = origin.left - (moveEvent.clientX - origin.x)
    scroller.scrollTop = origin.top - (moveEvent.clientY - origin.y)
  }
  timelinePanUp = () => {
    scroller.classList.remove('panning')
    removeTimelinePanListeners()
  }
  window.addEventListener('pointermove', timelinePanMove)
  window.addEventListener('pointerup', timelinePanUp)
  window.addEventListener('pointercancel', timelinePanUp)
}

function removeTimelineResizeListeners() {
  if (timelineResizeMove) window.removeEventListener('pointermove', timelineResizeMove)
  if (timelineResizeUp) {
    window.removeEventListener('pointerup', timelineResizeUp)
    window.removeEventListener('pointercancel', timelineResizeUp)
  }
  timelineResizeMove = null
  timelineResizeUp = null
}

function startTimelineResize(event) {
  if (event.button !== 0) return
  event.preventDefault()
  document.body.classList.add('resizing-timeline')
  timelineResizeMove = (moveEvent) => {
    const maximum = Math.max(220, Math.round(window.innerHeight * 0.7))
    timelineHeight.value = Math.max(160, Math.min(maximum, window.innerHeight - moveEvent.clientY))
  }
  timelineResizeUp = () => {
    document.body.classList.remove('resizing-timeline')
    localStorage.setItem('bookender.timelineHeight', String(Math.round(timelineHeight.value)))
    removeTimelineResizeListeners()
  }
  window.addEventListener('pointermove', timelineResizeMove)
  window.addEventListener('pointerup', timelineResizeUp)
  window.addEventListener('pointercancel', timelineResizeUp)
}

function resetTimelineHeight() {
  timelineHeight.value = 306
  localStorage.setItem('bookender.timelineHeight', '306')
}

function removeTimelinePointerListeners() {
  if (timelinePointerMove) window.removeEventListener('pointermove', timelinePointerMove)
  if (timelinePointerUp) {
    window.removeEventListener('pointerup', timelinePointerUp)
    window.removeEventListener('pointercancel', timelinePointerUp)
  }
  timelinePointerMove = null
  timelinePointerUp = null
  snapGuideTime.value = null
  snapGuideLabel.value = ''
}

function startTimelineScrub(event) {
  if (event.button !== undefined && event.button !== 0) return
  event.preventDefault()
  removeTimelinePointerListeners()
  const master = masterElement()
  resumeAfterTimelineScrub = playing.value || !!(master && !master.paused)
  pauseAll()
  timelineScrubbing.value = true
  seekTo(timelineTimeFromPointer(event))
  timelinePointerMove = (moveEvent) => {
    moveEvent.preventDefault()
    seekTo(timelineTimeFromPointer(moveEvent))
  }
  timelinePointerUp = (upEvent) => {
    seekTo(timelineTimeFromPointer(upEvent))
    removeTimelinePointerListeners()
    timelineScrubbing.value = false
    scrollTimelineToTime(currentTime.value, 'auto')
    const shouldResume = resumeAfterTimelineScrub
    resumeAfterTimelineScrub = false
    if (shouldResume) void togglePlay()
  }
  window.addEventListener('pointermove', timelinePointerMove)
  window.addEventListener('pointerup', timelinePointerUp)
  window.addEventListener('pointercancel', timelinePointerUp)
}

function onTimelinePlayheadKeydown(event) {
  const step = event.shiftKey ? 10 : 1
  let target = null
  if (event.key === 'ArrowLeft') target = currentTime.value - step
  if (event.key === 'ArrowRight') target = currentTime.value + step
  if (event.key === 'Home') target = 0
  if (event.key === 'End') target = duration.value
  if (target === null) return
  event.preventDefault()
  excerptPlaybackEnd.value = null
  seekTo(target)
  scrollTimelineToTime(target)
}

function visualizerValues(now) {
  const count = visualizerLevels.length
  const live = playing.value
    && visualAnalyser
    && visualizerFrequencyData
    && visualizerMaster === masterElement()
    && visualizerStream?.getAudioTracks?.().some((track) => track.readyState === 'live')
    && visualAudioContext?.state === 'running'

  if (playing.value && !live && !visualizerConnectPending && now - visualizerLastConnectAttempt > 1200) {
    void connectLiveVisualizer()
  }

  let targets = null
  if (live) {
    visualAnalyser.getByteFrequencyData(visualizerFrequencyData)
    const binHz = visualAudioContext.sampleRate / visualAnalyser.fftSize
    const minFrequency = 70
    const maxFrequency = Math.min(12000, visualAudioContext.sampleRate / 2)
    const spread = maxFrequency / minFrequency
    targets = Array.from({ length: count }, (_, index) => {
      const low = minFrequency * spread ** (index / count)
      const high = minFrequency * spread ** ((index + 1) / count)
      const first = Math.max(1, Math.floor(low / binHz))
      const last = Math.max(first + 1, Math.min(visualizerFrequencyData.length, Math.ceil(high / binHz)))
      let peak = 0
      let sum = 0
      for (let bin = first; bin < last; bin += 1) {
        const value = visualizerFrequencyData[bin]
        peak = Math.max(peak, value)
        sum += value
      }
      const raw = (peak * 0.72 + sum / Math.max(1, last - first) * 0.28) / 255
      const normalized = Math.max(0, (raw - 0.035) / 0.965)
      return Math.min(1, normalized ** 0.58 * 1.18)
    })
  } else {
    const source = waveformSamples.value
    const cursor = source.length && duration.value
      ? Math.floor(currentTime.value / duration.value * Math.max(0, source.length - 1))
      : 0
    targets = Array.from({ length: count }, (_, index) => {
      const sample = source.length
        ? Math.abs(Number(source[Math.max(0, Math.min(source.length - 1, cursor + (index % 11) - 5))]) || 0)
        : 0.26
      const shimmer = 0.22 + Math.abs(
        Math.sin(now * 0.0052 + index * 0.71)
        * Math.cos(now * 0.0017 - index * 0.19),
      ) * 0.86
      const energy = Math.min(1, sample ** 0.52 * 1.3)
      return playing.value ? Math.min(1, energy * shimmer) : Math.max(0.035, energy * 0.3)
    })
  }

  for (let index = 0; index < count; index += 1) {
    const current = visualizerLevels[index]
    const target = targets[index]
    const response = target > current ? 0.62 : 0.13
    visualizerLevels[index] = current + (target - current) * response
    visualizerPeaks[index] = Math.max(visualizerLevels[index], visualizerPeaks[index] * (playing.value ? 0.965 : 0.92))
  }
  return visualizerLevels
}

function addRoundedBar(context, x, y, width, height, radius) {
  if (typeof context.roundRect === 'function') context.roundRect(x, y, width, height, radius)
  else context.rect(x, y, width, height)
}

function drawVisualizer() {
  const canvas = visualizerCanvas.value
  if (canvas) {
    const rect = canvas.getBoundingClientRect()
    const ratio = Math.min(2, globalThis.devicePixelRatio || 1)
    const width = Math.max(1, Math.floor(rect.width * ratio))
    const height = Math.max(1, Math.floor(rect.height * ratio))
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width
      canvas.height = height
    }
    const context = canvas.getContext('2d')
    const now = performance.now()
    const values = visualizerValues(now)
    context.clearRect(0, 0, width, height)

    context.strokeStyle = 'rgba(255,255,255,.055)'
    context.lineWidth = Math.max(1, ratio * 0.55)
    for (let row = 1; row < 5; row += 1) {
      context.beginPath()
      context.moveTo(0, height * row / 5)
      context.lineTo(width, height * row / 5)
      context.stroke()
    }

    const travel = Math.sin(now * 0.00035) * width * 0.28
    const gradient = context.createLinearGradient(-width * 0.35 + travel, 0, width * 1.35 + travel, 0)
    const preset = visualizerPreset()
    const gradientColors = [...preset.colors, preset.colors[0]]
    gradientColors.forEach((color, index) => {
      gradient.addColorStop(index / Math.max(1, gradientColors.length - 1), color)
    })

    const baseline = height * 0.78
    const slot = width / values.length
    const barWidth = Math.max(1.2 * ratio, slot * 0.58)
    const maximumBarHeight = height * 0.68

    context.save()
    context.fillStyle = gradient
    context.shadowColor = preset.colors[Math.min(1, preset.colors.length - 1)]
    context.shadowBlur = 11 * ratio
    context.beginPath()
    values.forEach((value, index) => {
      const barHeight = Math.max(1.5 * ratio, value ** 0.86 * maximumBarHeight)
      const x = index * slot + (slot - barWidth) / 2
      addRoundedBar(context, x, baseline - barHeight, barWidth, barHeight, barWidth / 2)
    })
    context.fill()
    context.restore()

    context.save()
    context.globalAlpha = 0.16
    context.fillStyle = gradient
    context.beginPath()
    values.forEach((value, index) => {
      const reflection = Math.max(1, value ** 0.86 * height * 0.12)
      const x = index * slot + (slot - barWidth) / 2
      addRoundedBar(context, x, baseline + 3 * ratio, barWidth, reflection, barWidth / 2)
    })
    context.fill()
    context.restore()

    context.save()
    context.strokeStyle = gradient
    context.globalAlpha = 0.82
    context.lineWidth = Math.max(1, ratio * 0.85)
    visualizerPeaks.forEach((peak, index) => {
      const x = index * slot + slot * 0.23
      const y = baseline - peak ** 0.86 * maximumBarHeight - 2.5 * ratio
      context.beginPath()
      context.moveTo(x, y)
      context.lineTo(x + barWidth, y)
      context.stroke()
    })
    context.restore()

    context.save()
    context.globalAlpha = 0.34
    context.strokeStyle = gradient
    context.lineWidth = Math.max(1, ratio * 0.6)
    context.beginPath()
    context.moveTo(0, baseline + ratio)
    context.lineTo(width, baseline + ratio)
    context.stroke()
    context.restore()
  }
  visualFrame = requestAnimationFrame(drawVisualizer)
}

async function openExport() {
  exportReadiness.value = null
  showExport.value = true
  if (backend.online) {
    try {
      const running = await apiRequest('/render/status')
      if (running.active && running.latest) {
        renderJob.value = running.latest
        startRenderPolling(running.latest.id)
        return
      }
      if (dirty.value) {
        setNotice('Сохраняю актуальную разметку перед проверкой экспорта…', 'info', 0)
        const saved = await saveProject({ silent: true })
        if (!saved) return
      }
      exportReadiness.value = await apiRequest('/export/readiness')
      if (exportReadiness.value.missing?.includes('chapters-duration-mismatch') && !exportReadiness.value.editorProject?.exists) {
        setNotice('Обновляю главы из исходного RPP…', 'info', 0)
        const refreshed = await apiRequest('/book-project/refresh-chapters', { method: 'POST' })
        if (refreshed.ok && refreshed.chapters?.length) {
          project.chapters = refreshed.chapters.map(normalizeChapter)
          ensureSceneBounds()
          dirty.value = true
          exportReadiness.value = await apiRequest('/export/readiness')
          setNotice(`Разметка обновлена: ${project.chapters.length} глав`, 'success')
        }
      }
    } catch { /* client checks remain visible */ }
  }
}

function openBookExport() {
  if (!activeProjectRecord.value?.book) return
  if (!audioExportBusy.value) selectAudioExportChapters(true)
  showBookExport.value = true
}

function closeBookExport() {
  if (audioExportBusy.value) return
  showBookExport.value = false
}

function selectAudioExportChapters(all) {
  audioExportChapterIds.value = all
    ? (activeProjectRecord.value?.chapters || []).filter(chapter => chapterAudioState(chapter) !== 'none').map(chapter => chapter.id)
    : []
}

async function pollBookAudioExport(jobId) {
  try {
    const job = await apiRequest(`/jobs/${encodeURIComponent(jobId)}`)
    audioExportJob.value = job
    audioExportBusy.value = job.status === 'running'
    if (audioExportBusy.value) audioExportPoll = setTimeout(() => pollBookAudioExport(jobId), 1000)
  } catch (error) {
    // A brief API outage must not lose the running render or start a second one.
    audioExportJob.value = { ...audioExportJob.value, stage: `Восстанавливаю связь: ${error.message}` }
    audioExportPoll = setTimeout(() => pollBookAudioExport(jobId), 3000)
  }
}

async function startBookAudioExport() {
  if (!activeProjectUuid.value || audioExportBusy.value || !audioExportChapterIds.value.length) return
  audioExportBusy.value = true
  audioExportJob.value = { status: 'running', progress: 0, stage: 'Подготовка аудио…' }
  try {
    if (!(await saveCurrentChapter({ silent: true }))) {
      audioExportBusy.value = false
      audioExportJob.value = null
      return
    }
    const job = await apiRequest(`/projects/${encodeURIComponent(activeProjectUuid.value)}/audio-export`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        chapter_ids: audioExportChapterIds.value, mode: audioExportMode.value,
        bitrate: audioExportBitrate.value, include_music: audioExportMusic.value,
        editor_project: serializeProject(),
      }),
    })
    audioExportJob.value = job
    clearTimeout(audioExportPoll)
    void pollBookAudioExport(job.id)
  } catch (error) {
    audioExportBusy.value = false
    audioExportJob.value = { status: 'failed', progress: 0, stage: error.message }
  }
}

function responseDownloadName(response, fallback) {
  const disposition = response.headers.get('content-disposition') || ''
  const encoded = disposition.match(/filename\*=utf-8''([^;]+)/i)?.[1]
  if (encoded) {
    try { return decodeURIComponent(encoded) } catch { /* use plain fallback */ }
  }
  return disposition.match(/filename="?([^";]+)"?/i)?.[1] || fallback
}

async function downloadBookExport(mode) {
  if (!activeProjectUuid.value || bookExportBusy.value) return
  if (!(await saveCurrentChapter({ silent: true }))) {
    setNotice('Сначала нужно сохранить текущую главу', 'error')
    return
  }
  bookExportBusy.value = true
  try {
    const params = new URLSearchParams({
      mode,
      include_media: String(bookExportIncludeMedia.value),
    })
    if (mode === 'chapter' && currentBookChapterId.value) {
      params.set('chapter_id', String(currentBookChapterId.value))
    }
    const response = await fetch(
      `${API}/projects/${encodeURIComponent(activeProjectUuid.value)}/book-export?${params}`,
    )
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}))
      throw new Error(
        typeof payload.detail === 'string'
          ? payload.detail
          : 'Сервер не смог собрать файл книги',
      )
    }
    const extension = mode === 'chapters' ? 'zip' : 'txt'
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = responseDownloadName(response, `Книга.${extension}`)
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1500)
    setNotice(
      mode === 'chapters'
        ? 'Поглавный архив книги готов'
        : 'Текст книги экспортирован',
      'success',
      5500,
    )
  } catch (error) {
    setNotice(`Не удалось экспортировать книгу: ${error.message}`, 'error', 7500)
  } finally {
    bookExportBusy.value = false
  }
}

async function resetExport() {
  renderJob.value = null
  await openExport()
}

function closeExport() {
  if (renderJob.value?.status === 'running') return
  showExport.value = false
}

async function startExport() {
  if (exportIssue.value) return
  startingExport.value = true
  try {
    const saved = await saveProject({ silent: true })
    if (!saved) return
    exportReadiness.value = await apiRequest('/export/readiness')
    if (!exportReadiness.value.ready || exportIssue.value) return
    const response = await apiRequest(exportTest.value ? '/render/test' : '/render/full', { method: 'POST' })
    renderJob.value = { id: response.job_id, status: 'running', progress: 0, log: [], download_url: response.download_url || '' }
    startRenderPolling(response.job_id)
  } catch (error) {
    setNotice(`Экспорт не запущен: ${error.message}`, 'error', 7500)
  } finally {
    startingExport.value = false
  }
}

function stopRenderPolling() {
  renderPoller?.stop()
  renderPoller = null
}

function startRenderPolling(jobId) {
  stopRenderPolling()
  renderPoller = createSerialPoller(() => pollRender(jobId), { intervalMs: 1200, retryMs: 2400 })
  renderPoller.start()
}

async function pollRender(jobId) {
  try {
    const job = await apiRequest(`/jobs/${encodeURIComponent(jobId)}`)
    renderJob.value = job
    if (job.status === 'done' || job.status === 'failed') {
      if (job.status === 'done') setNotice('Экспорт завершён', 'success', 7000)
      return false
    }
  } catch { /* keep polling transient backend errors */ }
  return true
}

function releaseObjectUrls() {
  for (const url of objectUrls) URL.revokeObjectURL(url)
  objectUrls.clear()
}

function handleKeydown(event) {
  const target = event.target
  const editing = target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement
  if (visualPickerChapterId.value) {
    if (event.key === 'Escape') visualPickerChapterId.value = null
    return
  }
  if (event.key === 'Escape' && comicTheater.value) {
    event.preventDefault()
    comicTheater.value = false
    return
  }
  if (activeWorkspace.value === 'video' && ['Delete', 'Backspace'].includes(event.key)
      && !editing && selectedVisual.value && !showExport.value && !showTextNavigator.value) {
    event.preventDefault()
    removeVisual(selectedVisual.value.id)
    return
  }
  if (event.key === 'Escape' && showTextNavigator.value) {
    event.preventDefault()
    showTextNavigator.value = false
    return
  }
  const isSKey = event.code === 'KeyS' || event.key.toLowerCase() === 's'
  const isNKey = event.code === 'KeyN' || event.key.toLowerCase() === 'n'
  if ((event.ctrlKey || event.metaKey) && isSKey) {
    event.preventDefault()
    if (activeWorkspace.value === 'book') saveCurrentChapter()
    else saveProject()
  } else if (activeWorkspace.value === 'video' && isNKey && !editing
             && !showExport.value && !showTextNavigator.value) {
    event.preventDefault()
    toggleTimelineSnap()
  } else if (activeWorkspace.value === 'video' && isSKey && !editing
             && !showExport.value && !showTextNavigator.value) {
    event.preventDefault()
    splitSelectedMusicClip()
  } else if (activeWorkspace.value === 'video' && (event.key === 'Delete' || event.key === 'Backspace')
             && !editing && selectedMusicClip.value && !showExport.value && !showTextNavigator.value) {
    event.preventDefault()
    removeMusicClip(selectedMusicClip.value.id)
  } else if (activeWorkspace.value === 'video' && event.code === 'Space' && !editing
             && !showExport.value && !showTextNavigator.value) {
    event.preventDefault()
    togglePlay()
  }
}

function scheduleVisualPlan() {
  const revision = ++visualPlanRevision
  clearTimeout(visualPlanTimer)
  if (!project.chapters.some(chapter => chapter.visualInserts?.length)) {
    visualPlan.value = { clips: [], issues: [] }
    return
  }
  visualPlanTimer = setTimeout(async () => {
    try {
      const plan = await apiRequest('/scene-plan', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(visualPlanInput.value) })
      if (revision === visualPlanRevision) visualPlan.value = plan
    } catch (error) {
      if (revision === visualPlanRevision) {
        visualPlan.value = { clips: [], issues: [] }
        setNotice(`Предпросмотр иллюстраций недоступен: ${error.message}`, 'error')
      }
    }
  }, 140)
}

watch(visualPlanSignature, scheduleVisualPlan, { immediate: true })

watch(visualPlan, () => nextTick(() => updatePlaybackDom(currentTime.value)), { flush: 'post' })

watch(() => visualScene.value.visual?.id, () => {
  nextTick(() => updatePlaybackDom(currentTime.value))
}, { flush: 'post' })

watch([timelineZoom, duration, activeWorkspace], () => nextTick(() => {
  updateTimelineViewport()
  updatePlaybackDom(currentTime.value)
}), { flush: 'post' })

watch(() => audioSource.value, () => {
  pauseAll()
  disconnectLiveVisualizer()
  visualizerLevels.fill(0)
  visualizerPeaks.fill(0)
  audioDuration.value = 0
  nextTick(() => audioEl.value?.load())
})

watch(() => videoSource.value, () => {
  pauseAll()
  disconnectLiveVisualizer()
  visualizerLevels.fill(0)
  visualizerPeaks.fill(0)
  videoDuration.value = 0
  nextTick(() => videoEl.value?.load())
})

watch(() => musicPlaybackClip.value?.assetId, assetId => {
  if (assetId) retainedMusicAssetId.value = assetId
})
watch([musicSource, () => activeMusicClip.value?.id, () => project.music.enabled], () => {
  nextTick(syncMusicPlayback)
}, { flush: 'post' })

watch(() => currentChapter.value?.id, (chapterId) => {
  if (isComicEdition.value && chapterId) showComicTelegramBanner()
  if (chapterTimelineMode.value) void focusChapterTimeline(currentChapter.value)
  if (!showTextNavigator.value || !chapterId || !playing.value) return
  textNavigatorChapterId.value = chapterId
  nextTick(scrollCurrentNavigatorWordIntoView)
})

watch(isComicEdition, enabled => {
  if (!enabled) comicTheater.value = false
  showComicTelegramBanner()
})

watch(() => currentNavigatorWord.value?.index, () => {
  if (showTextNavigator.value && playing.value) nextTick(scrollCurrentNavigatorWordIntoView)
})

watch(() => bookPlayerUrl.value, () => {
  const autoplay = bookPlayerShouldAutoplay
  bookPlayerShouldAutoplay = false
  void reloadBookPlayer(autoplay)
}, { flush: 'sync' })

watch([bookPlayerWords, bookPlayerHasExactTimings], () => {
  clearBookWordHighlight()
  nextTick(() => updateBookWordHighlight(bookAudioEl.value?.currentTime || 0))
}, { flush: 'post' })

watch([
  () => previousChapter.value?.id,
  () => previousChapter.value?.title,
  () => currentChapter.value?.id,
  () => currentChapter.value?.title,
  () => nextChapter.value?.id,
  () => nextChapter.value?.title,
  () => project.layers.title?.w,
  () => project.layers.title?.h,
  () => project.layers.title?.fontSize,
  () => project.layers.title?.visible,
], () => nextTick(scheduleTitleFit), { flush: 'post' })

watch(titleLayerEl, (element, previous) => {
  if (previous) titleResizeObserver?.unobserve(previous)
  if (element) {
    titleResizeObserver?.observe(element)
    nextTick(scheduleTitleFit)
  }
}, { flush: 'post' })

watch(project, () => {
  if (!hydrating.value) {
    videoEditRevision += 1
    dirty.value = true
  }
}, { deep: true })

onMounted(() => {
  window.addEventListener('keydown', handleKeydown)
  window.addEventListener('beforeunload', handleBeforeUnload)
  if (typeof ResizeObserver !== 'undefined') {
    titleResizeObserver = new ResizeObserver(scheduleTitleFit)
    if (titleLayerEl.value) titleResizeObserver.observe(titleLayerEl.value)
    timelineResizeObserver = new ResizeObserver(updateTimelineViewport)
    if (timelineScroll.value) timelineResizeObserver.observe(timelineScroll.value)
    if (timelineContent.value) timelineResizeObserver.observe(timelineContent.value)
  }
  document.fonts?.ready?.then(scheduleTitleFit).catch(() => {})
  scheduleTitleFit()
  drawVisualizer()
  loadInitialProject()
  nextTick(() => {
    updateTimelineViewport()
    updatePlaybackDom(currentTime.value)
  })
})

onBeforeUnmount(() => {
  visualPlanRevision += 1
  clearTimeout(visualPlanTimer)
  window.removeEventListener('keydown', handleKeydown)
  window.removeEventListener('beforeunload', handleBeforeUnload)
  removeTimelinePointerListeners()
  removeTimelinePanListeners()
  removeTimelineResizeListeners()
  layerGestures.stop()
  document.body.classList.remove('resizing-timeline')
  stopBookPlayer()
  pauseAll()
  cancelAnimationFrame(visualFrame)
  stopRenderPolling()
  clearTimeout(noticeTimer)
  clearTimeout(comicTelegramTimer)
  clearTimeout(bookAutosaveTimer)
  clearTimeout(audioExportPoll)
  stopTtsPolling()
  titleResizeObserver?.disconnect()
  timelineResizeObserver?.disconnect()
  if (titleFitFrame !== null) cancelAnimationFrame(titleFitFrame)
  disconnectLiveVisualizer()
  visualAudioContext?.close().catch(() => {})
  musicAudioContext?.close().catch(() => {})
  releaseObjectUrls()
})

function handleBeforeUnload(event) {
  if (!dirty.value && !['modified', 'saving', 'error'].includes(bookSaveState.value)) return
  event.preventDefault()
  event.returnValue = ''
}
</script>
