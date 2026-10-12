package com.templeoflizard.bookwunderwaffe.pocket.tts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import com.templeoflizard.bookwunderwaffe.pocket.data.BookRepository;
import com.templeoflizard.bookwunderwaffe.pocket.data.NarrationJob;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Foreground, resumable Android TextToSpeech renderer.
 *
 * <p>Each job contains an immutable text snapshot. Audio callbacks are appended to one private
 * partial WAV. After every utterance the WAV is fsynced first and the repository checkpoint is
 * updated second. A redelivered intent therefore safely truncates any uncommitted tail before
 * synthesizing the same deterministic chunk again.</p>
 */
public final class NarrationService extends Service {
    public static final String ACTION_START =
            "com.templeoflizard.bookwunderwaffe.pocket.action.START_NARRATION";
    public static final String ACTION_STOP =
            "com.templeoflizard.bookwunderwaffe.pocket.action.STOP_NARRATION";
    public static final String EXTRA_JOB_ID =
            "com.templeoflizard.bookwunderwaffe.pocket.extra.JOB_ID";

    public static final String NOTIFICATION_CHANNEL_ID = "narration_generation";
    public static final int NOTIFICATION_ID = 0x425750;

    private static final String TAG = "NarrationService";
    private static final String WAKE_LOCK_TAG = "BookWunderwaffePocket:Narration";

    private final Object stateLock = new Object();
    private final TextChunker chunker = new TextChunker();

    private Handler mainHandler;
    private NotificationManager notificationManager;
    private BookRepository repository;
    private PowerManager.WakeLock wakeLock;
    private TextToSpeech textToSpeech;

    private NarrationJob activeJob;
    private List<TextChunker.Chunk> chunks = Collections.emptyList();
    private int nextChunkIndex;
    private long committedPcmBytes;
    private long activeUtteranceBytes;
    private String activeUtteranceId;
    private int activeStartId;

    private RandomAccessFile partialFileHandle;
    private StreamingWavWriter wavWriter;
    private PcmAudioFormat pcmFormat;
    private File scratchFile;

    private boolean terminal;
    private boolean cancellationRequested;
    private boolean destroying;

    private final UtteranceProgressListener utteranceListener =
            new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    if (isCurrentUtterance(utteranceId)) {
                        appendLog("DEBUG", "TTS engine started " + chunkLabel());
                    }
                }

                @Override
                public void onBeginSynthesis(String utteranceId, int sampleRateInHz,
                        int audioFormat, int channelCount) {
                    if (!isCurrentUtterance(utteranceId)) {
                        return;
                    }
                    try {
                        PcmAudioFormat callbackFormat = toPcmFormat(
                                sampleRateInHz, audioFormat, channelCount);
                        synchronized (stateLock) {
                            if (!isWritableCallbackLocked(utteranceId)) {
                                return;
                            }
                            if (wavWriter == null) {
                                repository.updateNarrationAudioFormat(activeJob.getId(),
                                        sampleRateInHz, channelCount, audioFormat);
                            }
                            openWriterLocked(callbackFormat);
                            activeUtteranceBytes = 0;
                        }
                        appendLog("INFO", "PCM: " + callbackFormat);
                    } catch (Exception error) {
                        failActiveJob("Не удалось открыть единый WAV: " + safeMessage(error), error);
                    }
                }

                @Override
                public void onAudioAvailable(String utteranceId, byte[] audio) {
                    if (audio == null || audio.length == 0) {
                        return;
                    }
                    try {
                        synchronized (stateLock) {
                            if (!isWritableCallbackLocked(utteranceId)) {
                                return;
                            }
                            if (wavWriter == null) {
                                throw new IOException("TTS sent audio before format metadata");
                            }
                            wavWriter.appendPcm(audio);
                            activeUtteranceBytes += audio.length;
                        }
                    } catch (Exception error) {
                        failActiveJob("Ошибка записи PCM: " + safeMessage(error), error);
                    }
                }

                @Override
                public void onDone(String utteranceId) {
                    handleUtteranceDone(utteranceId);
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onError(String utteranceId) {
                    onError(utteranceId, TextToSpeech.ERROR);
                }

                @Override
                public void onError(String utteranceId, int errorCode) {
                    if (isCurrentUtterance(utteranceId)) {
                        failActiveJob("TTS завершился с ошибкой " + errorCode, null);
                    }
                }

                @Override
                public void onStop(String utteranceId, boolean interrupted) {
                    synchronized (stateLock) {
                        if (!isWritableCallbackLocked(utteranceId) || cancellationRequested) {
                            return;
                        }
                    }
                    failActiveJob("TTS был неожиданно остановлен", null);
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        repository = BookRepository.getInstance(getApplicationContext());
        createNotificationChannel();

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        String jobId = intent == null ? null : intent.getStringExtra(EXTRA_JOB_ID);

        if (ACTION_STOP.equals(action)) {
            requestCancellation(jobId, "Остановлено пользователем");
            return START_NOT_STICKY;
        }

        if (intent == null) {
            NarrationJob recoverable = repository.getActiveNarrationJob();
            if (recoverable == null) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            jobId = recoverable.getId();
        } else if (!ACTION_START.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (isBlank(jobId)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        synchronized (stateLock) {
            if (activeJob != null && !terminal) {
                if (activeJob.getId().equals(jobId)) {
                    updateNotification(progressFor(nextChunkIndex), currentStatusLine(), true);
                } else {
                    repository.failNarrationJob(jobId,
                            "Уже выполняется другая фоновая озвучка");
                }
                return START_REDELIVER_INTENT;
            }
        }

        startForeground(NOTIFICATION_ID,
                buildNotification("Подготовка озвучки", 0, true, jobId));
        beginJob(jobId, startId);
        return START_REDELIVER_INTENT;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        TextToSpeech engine;
        synchronized (stateLock) {
            destroying = true;
            engine = textToSpeech;
            textToSpeech = null;
            closeWorkingFileLocked();
        }
        if (engine != null) {
            engine.stop();
            engine.shutdown();
        }
        releaseWakeLock();
        super.onDestroy();
    }

    /** Android 15 data-sync foreground services have a system-enforced timeout. */
    @Override
    public void onTimeout(int startId, int foregroundServiceType) {
        failActiveJob("Android остановил длительную фоновую задачу по тайм-ауту", null);
    }

    private void beginJob(String jobId, int startId) {
        NarrationJob job;
        try {
            job = repository.getNarrationJob(jobId);
            if (job == null) {
                throw new IllegalArgumentException("Задание озвучки не найдено: " + jobId);
            }
            if (job.isTerminal()) {
                throw new IllegalStateException("Задание уже завершено: " + job.getStatus());
            }
            validateJob(job);
        } catch (Exception error) {
            repository.failNarrationJob(jobId, safeMessage(error));
            showTerminalNotification("Озвучка не запущена", safeMessage(error), jobId);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return;
        }

        List<TextChunker.Chunk> jobChunks = chunker.split(job.getTextSnapshot());
        if (jobChunks.isEmpty()) {
            repository.failNarrationJob(jobId, "В тексте нет символов для озвучивания");
            showTerminalNotification("Озвучка не запущена", "Текст пуст", jobId);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return;
        }
        if (job.getResumeChunkIndex() > jobChunks.size()) {
            repository.failNarrationJob(jobId, "Некорректный индекс checkpoint");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return;
        }
        if (job.getResumeChunkIndex() == 0 && job.getCommittedPcmBytes() != 0) {
            repository.failNarrationJob(jobId,
                    "Checkpoint PCM не согласован с индексом фрагмента");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return;
        }

        synchronized (stateLock) {
            activeJob = job;
            chunks = jobChunks;
            nextChunkIndex = job.getResumeChunkIndex();
            committedPcmBytes = job.getCommittedPcmBytes();
            activeStartId = startId;
            terminal = false;
            cancellationRequested = false;
            destroying = false;
        }

        acquireWakeLock();
        appendLog("INFO", "Запуск: " + chunks.size() + " фрагм., resume="
                + nextChunkIndex + ", committed=" + committedPcmBytes + " B");
        if (NarrationJob.STATUS_CANCEL_REQUESTED.equals(job.getStatus())) {
            requestCancellation(job.getId(), "Восстановлен ранее запрошенный cancel");
            return;
        }
        try {
            PcmAudioFormat plannedFormat = plannedFormatFor(job);
            preflightStorage(job, plannedFormat);
            appendLog("INFO", "Проверка места пройдена для " + plannedFormat);
            repository.updateNarrationProgress(job.getId(), NarrationJob.STAGE_PREPARING,
                    NarrationJob.STATUS_RUNNING, progressFor(nextChunkIndex), nextChunkIndex,
                    chunks.size(), committedPcmBytes);
        } catch (Exception error) {
            failActiveJob("Не удалось сохранить начальный прогресс: " + safeMessage(error), error);
            return;
        }
        updateNotification(progressFor(nextChunkIndex), "Подготовка TTS", true);

        if (nextChunkIndex == chunks.size()) {
            mainHandler.post(this::finalizeActiveJob);
        } else {
            initializeTextToSpeech();
        }
    }

    private void validateJob(NarrationJob job) {
        if (isBlank(job.getId())) {
            throw new IllegalArgumentException("Пустой id задания");
        }
        if (isBlank(job.getPartPath()) || isBlank(job.getOutputPath())) {
            throw new IllegalArgumentException("Не заданы part/output пути");
        }
        try {
            File part = new File(job.getPartPath()).getCanonicalFile();
            File output = new File(job.getOutputPath()).getCanonicalFile();
            if (part.equals(output)) {
                throw new IllegalArgumentException("Part и output должны быть разными файлами");
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Некорректный путь WAV", error);
        }
    }

    private void initializeTextToSpeech() {
        appendLog("INFO", "Инициализация Android TextToSpeech");
        TextToSpeech engine = new TextToSpeech(getApplicationContext(),
                status -> mainHandler.post(() -> handleTtsInitialized(status)));
        synchronized (stateLock) {
            if (terminal || destroying) {
                engine.shutdown();
                return;
            }
            textToSpeech = engine;
        }
    }

    private void handleTtsInitialized(int status) {
        TextToSpeech engine;
        NarrationJob job;
        synchronized (stateLock) {
            if (terminal || destroying || activeJob == null) {
                return;
            }
            engine = textToSpeech;
            job = activeJob;
        }
        if (engine == null || status != TextToSpeech.SUCCESS) {
            failActiveJob("Android TTS не инициализировался (код " + status + ")", null);
            return;
        }

        try {
            configureVoice(engine, job);
            engine.setOnUtteranceProgressListener(utteranceListener);
            appendLog("INFO", "TTS готов, engine=" + engine.getDefaultEngine());
            synthesizeNextChunk();
        } catch (Exception error) {
            failActiveJob("Не удалось настроить голос: " + safeMessage(error), error);
        }
    }

    private static void configureVoice(TextToSpeech engine, NarrationJob job) {
        Locale locale = Locale.forLanguageTag(job.getLanguageTag());
        if (locale.getLanguage().isEmpty()) {
            throw new IllegalArgumentException("Некорректный languageTag: "
                    + job.getLanguageTag());
        }
        int languageResult = engine.setLanguage(locale);
        if (languageResult == TextToSpeech.LANG_MISSING_DATA
                || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw new IllegalStateException("Язык TTS не установлен: " + job.getLanguageTag());
        }

        String desiredVoice = job.getVoiceName();
        if (!isBlank(desiredVoice)) {
            Set<Voice> voices = engine.getVoices();
            Voice match = null;
            if (voices != null) {
                for (Voice voice : voices) {
                    if (desiredVoice.equals(voice.getName())) {
                        match = voice;
                        break;
                    }
                }
            }
            if (match == null || engine.setVoice(match) != TextToSpeech.SUCCESS) {
                throw new IllegalStateException("Голос TTS недоступен: " + desiredVoice);
            }
        }

        float rate = job.getSpeechRate() > 0f ? job.getSpeechRate() : 1f;
        float pitch = job.getPitch() > 0f ? job.getPitch() : 1f;
        if (engine.setSpeechRate(rate) != TextToSpeech.SUCCESS) {
            throw new IllegalStateException("TTS не принял скорость " + rate);
        }
        if (engine.setPitch(pitch) != TextToSpeech.SUCCESS) {
            throw new IllegalStateException("TTS не принял высоту голоса " + pitch);
        }
    }

    private void synthesizeNextChunk() {
        NarrationJob job;
        TextToSpeech engine;
        TextChunker.Chunk chunk;
        int index;
        synchronized (stateLock) {
            if (terminal || destroying || cancellationRequested || activeJob == null) {
                return;
            }
            if (nextChunkIndex >= chunks.size()) {
                mainHandler.post(this::finalizeActiveJob);
                return;
            }
            job = activeJob;
            engine = textToSpeech;
            index = nextChunkIndex;
            chunk = chunks.get(index);
        }

        try {
            if (repository.isCancellationRequested(job.getId())) {
                requestCancellation(job.getId(), "Отмена подтверждена хранилищем");
                return;
            }
        } catch (Exception error) {
            failActiveJob("Не удалось проверить отмену: " + safeMessage(error), error);
            return;
        }

        if (engine == null) {
            failActiveJob("TTS engine потерян", null);
            return;
        }

        File nextScratch = new File(getCacheDir(),
                "tts-" + safeFileToken(job.getId()) + "-" + index + ".tmp");
        deleteQuietly(nextScratch);
        String utteranceId = job.getId() + ":" + index + ":"
                + SystemClock.elapsedRealtimeNanos();
        synchronized (stateLock) {
            if (terminal || nextChunkIndex != index) {
                return;
            }
            scratchFile = nextScratch;
            activeUtteranceId = utteranceId;
            activeUtteranceBytes = 0;
        }

        int percent = progressFor(index);
        String line = "Фрагмент " + (index + 1) + " из " + chunks.size();
        updateNotification(percent, line, true);
        try {
            repository.updateNarrationProgress(job.getId(), NarrationJob.STAGE_SYNTHESIZING,
                    NarrationJob.STATUS_RUNNING, percent, index, chunks.size(),
                    committedPcmBytes);
            appendLog("INFO", line + ", символов=" + chunk.length());
        } catch (Exception error) {
            failActiveJob("Не удалось сохранить статус фрагмента: " + safeMessage(error), error);
            return;
        }

        Bundle parameters = new Bundle();
        int result = engine.synthesizeToFile(chunk.getText(), parameters, nextScratch,
                utteranceId);
        if (result != TextToSpeech.SUCCESS) {
            failActiveJob("TTS отклонил фрагмент " + (index + 1), null);
        }
    }

    private void handleUtteranceDone(String utteranceId) {
        NarrationJob job;
        int completedIndex;
        int percent;
        long checkpointBytes;
        long utteranceBytes;
        try {
            synchronized (stateLock) {
                if (!isWritableCallbackLocked(utteranceId)) {
                    return;
                }
                if (wavWriter == null) {
                    throw new IOException(
                            "TTS engine не предоставил PCM через onAudioAvailable");
                }
                wavWriter.finishUtterance();
                checkpointBytes = wavWriter.checkpoint();
                completedIndex = nextChunkIndex + 1;
                percent = progressFor(completedIndex);
                utteranceBytes = activeUtteranceBytes;
                job = activeJob;
            }

            // This database update is intentionally after WAV FileChannel.force(true).
            repository.updateNarrationProgress(job.getId(), NarrationJob.STAGE_SYNTHESIZING,
                    NarrationJob.STATUS_RUNNING, percent, completedIndex, chunks.size(),
                    checkpointBytes);

            synchronized (stateLock) {
                if (!isWritableCallbackLocked(utteranceId)) {
                    return;
                }
                committedPcmBytes = checkpointBytes;
                nextChunkIndex = completedIndex;
                activeUtteranceId = null;
                deleteScratchLocked();
            }
            appendLog("INFO", "Checkpoint " + completedIndex + "/" + chunks.size()
                    + ": +" + utteranceBytes + " B, всего " + checkpointBytes + " B");
            updateNotification(percent, currentStatusLine(), true);
            mainHandler.post(this::synthesizeNextChunk);
        } catch (Exception error) {
            failActiveJob("Не удалось зафиксировать фрагмент: " + safeMessage(error), error);
        }
    }

    private void openWriterLocked(PcmAudioFormat callbackFormat) throws IOException {
        if (wavWriter != null) {
            if (!wavWriter.getFormat().equals(callbackFormat)) {
                throw new IOException("TTS изменил PCM формат между фрагментами: "
                        + wavWriter.getFormat() + " -> " + callbackFormat);
            }
            return;
        }

        File part = new File(activeJob.getPartPath());
        preflightStorage(activeJob, callbackFormat);
        File parent = part.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Не удалось создать каталог " + parent);
        }
        partialFileHandle = new RandomAccessFile(part, "rw");
        try {
            wavWriter = StreamingWavWriter.open(partialFileHandle.getChannel(), callbackFormat,
                    committedPcmBytes);
            pcmFormat = callbackFormat;
        } catch (Exception error) {
            try {
                partialFileHandle.close();
            } catch (IOException ignored) {
                // The original exception is more useful.
            }
            partialFileHandle = null;
            throw error;
        }
    }

    private void finalizeActiveJob() {
        NarrationJob job;
        int total;
        long bytes;
        PcmAudioFormat finalFormat;
        File part;
        File output;
        synchronized (stateLock) {
            if (terminal || destroying || activeJob == null) {
                return;
            }
            if (nextChunkIndex != chunks.size()) {
                failActiveJob("Финализация вызвана до завершения всех фрагментов", null);
                return;
            }
            job = activeJob;
            total = chunks.size();
            part = new File(job.getPartPath());
            output = new File(job.getOutputPath());
        }

        try {
            repository.updateNarrationProgress(job.getId(), NarrationJob.STAGE_FINALIZING,
                    NarrationJob.STATUS_RUNNING, 99, total, total, committedPcmBytes);
            updateNotification(99, "Финализация WAV", true);
            appendLog("INFO", "Финализация единого WAV");

            synchronized (stateLock) {
                if (wavWriter != null) {
                    bytes = wavWriter.checkpoint();
                    finalFormat = wavWriter.getFormat();
                    closeWorkingFileLocked();
                } else if (part.isFile()) {
                    partialFileHandle = new RandomAccessFile(part, "rw");
                    wavWriter = StreamingWavWriter.openExisting(
                            partialFileHandle.getChannel(), committedPcmBytes);
                    bytes = wavWriter.checkpoint();
                    finalFormat = wavWriter.getFormat();
                    closeWorkingFileLocked();
                } else if (output.isFile()) {
                    try (RandomAccessFile outputHandle = new RandomAccessFile(output, "r")) {
                        finalFormat = StreamingWavWriter.inspectFormat(outputHandle.getChannel());
                    }
                    bytes = committedPcmBytes;
                    if (output.length() < StreamingWavWriter.HEADER_SIZE + bytes) {
                        throw new IOException("Готовый WAV короче checkpoint");
                    }
                } else {
                    throw new IOException("Partial WAV отсутствует");
                }
                pcmFormat = finalFormat;
                committedPcmBytes = bytes;
            }

            if (part.isFile()) {
                moveCompletedWav(part, output);
            }
            long sizeBytes = output.length();
            long durationMs = finalFormat.durationMillisFor(bytes);
            repository.completeNarrationJob(job.getId(), output.getAbsolutePath(), durationMs,
                    sizeBytes);
            appendLog("INFO", "Готово: " + output.getAbsolutePath() + ", " + sizeBytes
                    + " B, " + durationMs + " ms");
            completeService(job, output, durationMs);
        } catch (Exception error) {
            failActiveJob("Ошибка финализации WAV: " + safeMessage(error), error);
        }
    }

    private static void moveCompletedWav(File part, File output) throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Не удалось создать каталог результата: " + parent);
        }
        try {
            Files.move(part.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(part.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Conservative capacity check. It is run before TTS initialization using persisted settings and
     * again on the first PCM callback using the engine's actual format.
     */
    private static void preflightStorage(NarrationJob job, PcmAudioFormat format)
            throws IOException {
        long estimatedSeconds = Math.max(1L,
                (job.getTextSnapshot().length() + 11L) / 12L);
        long estimatedPcmBytes = saturatedMultiply(estimatedSeconds, format.getByteRate());
        long variabilityMargin = estimatedPcmBytes / 4L;
        long projectedPcmBytes = saturatedAdd(estimatedPcmBytes, variabilityMargin);
        if (projectedPcmBytes > StreamingWavWriter.MAX_PCM_BYTES) {
            throw new IOException("Оценка аудио превышает лимит WAV 4 ГиБ: "
                    + projectedPcmBytes + " B с запасом");
        }

        long margin = Math.max(32L * 1024L * 1024L, variabilityMargin);
        File part = new File(job.getPartPath());
        long bytesAlreadyAllocated = part.isFile() ? part.length() : 0L;
        long sourceNeed = Math.max(0L, estimatedPcmBytes - bytesAlreadyAllocated);
        sourceNeed = saturatedAdd(sourceNeed, margin);
        requireAvailableBytes(part, sourceNeed, "partial WAV");

        File output = new File(job.getOutputPath());
        File partParent = part.getCanonicalFile().getParentFile();
        File outputParent = output.getCanonicalFile().getParentFile();
        if (partParent == null || !partParent.equals(outputParent)) {
            requireAvailableBytes(output, saturatedAdd(estimatedPcmBytes, margin),
                    "готовый WAV");
        }
    }

    private static void requireAvailableBytes(File target, long required, String label)
            throws IOException {
        File existing = nearestExistingAncestor(target);
        long available = new StatFs(existing.getAbsolutePath()).getAvailableBytes();
        if (available < required) {
            throw new IOException("Недостаточно места для " + label + ": нужно примерно "
                    + required + " B, доступно " + available + " B");
        }
    }

    private static File nearestExistingAncestor(File target) throws IOException {
        File current = target.getCanonicalFile();
        if (!current.isDirectory()) {
            current = current.getParentFile();
        }
        while (current != null && !current.exists()) {
            current = current.getParentFile();
        }
        if (current == null) {
            throw new IOException("Не найден существующий каталог для " + target);
        }
        return current;
    }

    private static PcmAudioFormat plannedFormatFor(NarrationJob job) {
        int sampleRate = job.getSampleRate() > 0 ? job.getSampleRate() : 48_000;
        int channels = job.getChannelCount() > 0 ? job.getChannelCount() : 2;
        int encoding = job.getAudioFormat();
        if (encoding == AudioFormat.ENCODING_PCM_8BIT
                || encoding == AudioFormat.ENCODING_PCM_16BIT
                || encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED
                || encoding == AudioFormat.ENCODING_PCM_32BIT
                || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            return toPcmFormat(sampleRate, encoding, channels);
        }
        return PcmAudioFormat.integerPcm(sampleRate, channels, 16);
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0 || right <= 0) {
            return 0;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long saturatedAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private void completeService(NarrationJob job, File output, long durationMs) {
        synchronized (stateLock) {
            if (terminal) {
                return;
            }
            terminal = true;
            deleteScratchLocked();
        }
        shutdownEngine();
        releaseWakeLock();
        updateNotification(100, "Аудио готово", false);
        showTerminalNotification("Аудио готово", job.getDisplayTitle(), job.getId());
        mainHandler.post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(activeStartId);
        });
    }

    private void requestCancellation(String requestedJobId, String reason) {
        NarrationJob job;
        synchronized (stateLock) {
            if (activeJob == null) {
                job = null;
            } else if (!isBlank(requestedJobId)
                    && !activeJob.getId().equals(requestedJobId)) {
                return;
            } else {
                job = activeJob;
                if (terminal) {
                    return;
                }
                cancellationRequested = true;
                terminal = true;
            }
        }

        if (job == null) {
            cancelInactiveJob(requestedJobId, reason);
            return;
        }

        shutdownEngine();
        synchronized (stateLock) {
            closeWorkingFileLocked();
            deleteScratchLocked();
        }
        File part = new File(job.getPartPath());
        if (part.exists() && !part.delete()) {
            appendLog("WARN", "Не удалось удалить partial WAV: " + part);
        }
        appendLog("INFO", reason + "; partial WAV удалён");
        try {
            repository.markNarrationCancelled(job.getId());
        } catch (Exception error) {
            Log.e(TAG, "Could not persist cancellation", error);
        }
        releaseWakeLock();
        updateNotification(progressFor(nextChunkIndex), "Озвучка отменена", false);
        mainHandler.post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(activeStartId);
        });
    }

    private void cancelInactiveJob(String jobId, String reason) {
        if (isBlank(jobId)) {
            stopSelf();
            return;
        }
        try {
            NarrationJob job = repository.getNarrationJob(jobId);
            if (job != null && !job.isTerminal()) {
                deleteQuietly(new File(job.getPartPath()));
                repository.appendNarrationLog(jobId, "INFO", reason);
                repository.markNarrationCancelled(jobId);
            }
        } catch (Exception error) {
            Log.e(TAG, "Could not cancel stored narration", error);
        }
        stopSelf();
    }

    private void failActiveJob(String message, Throwable error) {
        NarrationJob job;
        synchronized (stateLock) {
            if (terminal || destroying) {
                return;
            }
            terminal = true;
            job = activeJob;
            closeWorkingFileLocked();
            deleteScratchLocked();
        }
        shutdownEngine();
        if (error == null) {
            Log.e(TAG, message);
        } else {
            Log.e(TAG, message, error);
        }
        if (job != null) {
            try {
                repository.appendNarrationLog(job.getId(), "ERROR", message);
                repository.failNarrationJob(job.getId(), message);
            } catch (Exception repositoryError) {
                Log.e(TAG, "Could not persist narration failure", repositoryError);
            }
            showTerminalNotification("Ошибка озвучивания", message, job.getId());
        }
        releaseWakeLock();
        mainHandler.post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(activeStartId);
        });
    }

    private boolean isCurrentUtterance(String utteranceId) {
        synchronized (stateLock) {
            return isWritableCallbackLocked(utteranceId);
        }
    }

    private boolean isWritableCallbackLocked(String utteranceId) {
        return !terminal && !destroying && activeJob != null && utteranceId != null
                && utteranceId.equals(activeUtteranceId);
    }

    private String chunkLabel() {
        synchronized (stateLock) {
            return "фрагмент " + (nextChunkIndex + 1) + "/" + chunks.size();
        }
    }

    private int progressFor(int completedChunkCount) {
        synchronized (stateLock) {
            if (chunks.isEmpty()) {
                return 0;
            }
            long totalCharacters = 0;
            long completedCharacters = 0;
            for (int i = 0; i < chunks.size(); i++) {
                int length = chunks.get(i).length();
                totalCharacters += length;
                if (i < completedChunkCount) {
                    completedCharacters += length;
                }
            }
            if (completedChunkCount >= chunks.size()) {
                return 99;
            }
            return totalCharacters == 0 ? 0
                    : Math.min(98, (int) ((completedCharacters * 100L) / totalCharacters));
        }
    }

    private String currentStatusLine() {
        synchronized (stateLock) {
            if (chunks.isEmpty()) {
                return "Подготовка";
            }
            if (nextChunkIndex >= chunks.size()) {
                return "Финализация";
            }
            return "Фрагмент " + (nextChunkIndex + 1) + " из " + chunks.size();
        }
    }

    private void closeWorkingFileLocked() {
        if (wavWriter != null) {
            try {
                wavWriter.close();
            } catch (IOException error) {
                Log.w(TAG, "Could not close WAV writer", error);
            }
            wavWriter = null;
        }
        if (partialFileHandle != null) {
            try {
                partialFileHandle.close();
            } catch (IOException error) {
                Log.w(TAG, "Could not close partial WAV", error);
            }
            partialFileHandle = null;
        }
    }

    private void deleteScratchLocked() {
        if (scratchFile != null) {
            deleteQuietly(scratchFile);
            scratchFile = null;
        }
    }

    private void shutdownEngine() {
        TextToSpeech engine;
        synchronized (stateLock) {
            engine = textToSpeech;
            textToSpeech = null;
            activeUtteranceId = null;
        }
        if (engine != null) {
            engine.stop();
            engine.shutdown();
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void appendLog(String level, String message) {
        NarrationJob job;
        synchronized (stateLock) {
            job = activeJob;
        }
        if (job == null) {
            Log.d(TAG, level + ": " + message);
            return;
        }
        try {
            repository.appendNarrationLog(job.getId(), level, message);
        } catch (Exception error) {
            Log.w(TAG, "Could not persist narration log", error);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                "Озвучивание книг", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Прогресс фонового создания аудиокниги");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String statusLine, int percent, boolean ongoing,
            String jobId) {
        String title = "Book Wunderwaffe Pocket";
        synchronized (stateLock) {
            if (activeJob != null && !isBlank(activeJob.getDisplayTitle())) {
                title = activeJob.getDisplayTitle();
            }
        }
        Notification.Builder builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(ongoing ? android.R.drawable.stat_sys_download
                        : android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(statusLine)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setProgress(100, Math.max(0, Math.min(100, percent)), false);

        if (ongoing && !isBlank(jobId)) {
            Intent stopIntent = new Intent(this, NarrationService.class)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_JOB_ID, jobId);
            PendingIntent stopPendingIntent = PendingIntent.getService(this, NOTIFICATION_ID,
                    stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent).build());
        }
        return builder.build();
    }

    private void updateNotification(int percent, String status, boolean ongoing) {
        String jobId;
        synchronized (stateLock) {
            jobId = activeJob == null ? null : activeJob.getId();
        }
        notificationManager.notify(NOTIFICATION_ID,
                buildNotification(status, percent, ongoing, jobId));
    }

    private void showTerminalNotification(String title, String text, String jobId) {
        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        notificationManager.notify(NOTIFICATION_ID + 1, notification);
    }

    private static PcmAudioFormat toPcmFormat(int sampleRateHz, int androidEncoding,
            int channelCount) {
        switch (androidEncoding) {
            case AudioFormat.ENCODING_PCM_8BIT:
                return PcmAudioFormat.integerPcm(sampleRateHz, channelCount, 8);
            case AudioFormat.ENCODING_PCM_16BIT:
                return PcmAudioFormat.integerPcm(sampleRateHz, channelCount, 16);
            case AudioFormat.ENCODING_PCM_24BIT_PACKED:
                return PcmAudioFormat.integerPcm(sampleRateHz, channelCount, 24);
            case AudioFormat.ENCODING_PCM_32BIT:
                return PcmAudioFormat.integerPcm(sampleRateHz, channelCount, 32);
            case AudioFormat.ENCODING_PCM_FLOAT:
                return PcmAudioFormat.floatingPointPcm(sampleRateHz, channelCount, 32);
            default:
                throw new IllegalArgumentException("Неподдерживаемый PCM encoding: "
                        + androidEncoding);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "неизвестная ошибка";
        }
        String message = error.getMessage();
        return isBlank(message) ? error.getClass().getSimpleName() : message;
    }

    private static String safeFileToken(String value) {
        return value == null ? "job" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete " + file);
        }
    }
}
