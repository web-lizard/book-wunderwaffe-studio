package com.templeoflizard.bookwunderwaffe.pocket;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import com.templeoflizard.bookwunderwaffe.pocket.data.AudioAsset;
import com.templeoflizard.bookwunderwaffe.pocket.data.Book;
import com.templeoflizard.bookwunderwaffe.pocket.data.BookRepository;
import com.templeoflizard.bookwunderwaffe.pocket.data.Chapter;
import com.templeoflizard.bookwunderwaffe.pocket.data.NarrationJob;
import com.templeoflizard.bookwunderwaffe.pocket.data.NarrationLogEntry;
import com.templeoflizard.bookwunderwaffe.pocket.share.ShareFileProvider;
import com.templeoflizard.bookwunderwaffe.pocket.tts.NarrationService;
import com.templeoflizard.bookwunderwaffe.pocket.transfer.BookPackageCodec;
import com.templeoflizard.bookwunderwaffe.pocket.ui.WaveformView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-activity, platform-only UI for Book Wunderwaffe Pocket.
 *
 * <p>The activity owns editing and playback. Long narration is persisted by the repository and
 * delegated to {@link NarrationService}, so it survives this screen and reports its progress when
 * the user returns.</p>
 */
public final class MainActivity extends Activity {
    private static final int PAGE_BOOKS = 0;
    private static final int PAGE_TEXT = 1;
    private static final int PAGE_AUDIO = 2;

    private static final int REQUEST_NOTIFICATIONS = 410;
    private static final int REQUEST_IMPORT_BOOK = 411;
    private static final int REQUEST_EXPORT_BOOK = 412;
    private static final int REQUEST_SAVE_AUDIO = 413;

    private static final String PREFS = "pocket_ui";
    private static final String PREF_BOOK_ID = "active_book_id";
    private static final String PREF_CHAPTER_ID = "active_chapter_id";
    private static final String PREF_VOICE = "voice_name";
    private static final String PREF_RATE = "speech_rate";
    private static final String PREF_PITCH = "pitch";
    private static final String PREF_PAGE = "page";
    private static final long AUTOSAVE_DELAY_MS = 500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final List<Book> books = new ArrayList<>();
    private final List<Chapter> chapters = new ArrayList<>();
    private final List<AudioAsset> audioAssets = new ArrayList<>();
    private final List<VoiceItem> voices = new ArrayList<>();

    private BookRepository repository;
    private BookPackageCodec packageCodec;
    private SharedPreferences preferences;

    private ViewFlipper contentFlipper;
    private Button navBooks;
    private Button navText;
    private Button navAudio;

    private TextView booksSummary;
    private Spinner bookSpinner;
    private Spinner chapterSpinner;
    private TextView chapterCountLabel;
    private Button bookRenameButton;
    private Button chapterNewButton;
    private Button chapterRenameButton;
    private Button exportBookButton;

    private TextView editorContextLabel;
    private TextView editorSaveLabel;
    private EditText chapterEditor;
    private TextView textStatsLabel;
    private Spinner voiceSpinner;
    private SeekBar speechRateSeek;
    private SeekBar pitchSeek;
    private TextView speechRateValue;
    private TextView pitchValue;
    private Button narrateChapterButton;
    private Button narrateBookButton;

    private View narrationProgressPanel;
    private TextView narrationStageLabel;
    private TextView narrationElapsedLabel;
    private TextView narrationPercentLabel;
    private ProgressBar narrationProgress;
    private Button cancelNarrationButton;
    private Button consoleToggleButton;
    private ScrollView consoleScroll;
    private TextView consoleText;

    private TextView audioContextLabel;
    private ListView audioList;
    private TextView audioEmptyLabel;
    private TextView playerTitle;
    private TextView playerSubtitle;
    private WaveformView waveform;
    private SeekBar playerSeek;
    private TextView playerCurrentTime;
    private TextView playerTotalTime;
    private Button playerBackButton;
    private Button playerPlayButton;
    private Button playerForwardButton;
    private Spinner playerSpeedSpinner;
    private Button saveAudioButton;
    private Button shareAudioButton;

    private ItemAdapter<Book> bookAdapter;
    private ItemAdapter<Chapter> chapterAdapter;
    private ItemAdapter<VoiceItem> voiceAdapter;
    private AudioListAdapter audioAdapter;

    private Book activeBook;
    private Chapter activeChapter;
    private AudioAsset currentAudio;
    private NarrationJob currentJob;
    private String currentJobId;
    private String terminalJobRefreshedId;

    private TextToSpeech voiceCatalog;
    private MediaPlayer mediaPlayer;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean mediaPrepared;
    private boolean updatingPlayerUi;
    private boolean userSeeking;
    private boolean bindingData;
    private boolean autosavePending;
    private boolean activityResumed;
    private int currentPage;

    private String pendingBookExportId;
    private AudioAsset pendingAudioSave;

    private final Runnable autosaveRunnable = this::saveEditorNow;
    private final Runnable narrationPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!activityResumed) return;
            pollNarration();
            mainHandler.postDelayed(this, 750L);
        }
    };
    private final Runnable playerTickRunnable = new Runnable() {
        @Override
        public void run() {
            updatePlayerPosition();
            mainHandler.postDelayed(this, 250L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemInsets();

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        repository = BookRepository.getInstance(getApplicationContext());
        packageCodec = new BookPackageCodec(getApplicationContext(), repository);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        bindViews();
        configureAdapters();
        configureNavigation();
        configureLibraryActions();
        configureEditor();
        configureNarrationControls();
        configurePlayer();

        float storedRate = preferences.getFloat(PREF_RATE, 1f);
        float storedPitch = preferences.getFloat(PREF_PITCH, 1f);
        speechRateSeek.setProgress(valueToSlider(storedRate));
        pitchSeek.setProgress(valueToSlider(storedPitch));
        updateSpeechSettingLabels();

        currentPage = savedInstanceState == null
                ? preferences.getInt(PREF_PAGE, PAGE_BOOKS)
                : savedInstanceState.getInt(PREF_PAGE, PAGE_BOOKS);
        showPage(Math.max(PAGE_BOOKS, Math.min(PAGE_AUDIO, currentPage)));

        refreshBooks(preferences.getString(PREF_BOOK_ID, null));
        initVoiceCatalog();
        restoreNarration();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        mainHandler.removeCallbacks(narrationPollRunnable);
        mainHandler.post(narrationPollRunnable);
        mainHandler.removeCallbacks(playerTickRunnable);
        mainHandler.post(playerTickRunnable);
    }

    @Override
    protected void onPause() {
        flushAutosave();
        activityResumed = false;
        mainHandler.removeCallbacks(narrationPollRunnable);
        mainHandler.removeCallbacks(playerTickRunnable);
        preferences.edit().putInt(PREF_PAGE, currentPage).apply();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(PREF_PAGE, currentPage);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (voiceCatalog != null) {
            voiceCatalog.shutdown();
            voiceCatalog = null;
        }
        ioExecutor.shutdown();
        super.onDestroy();
    }

    private void applySystemInsets() {
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
    }

    private void bindViews() {
        contentFlipper = findViewById(R.id.content_flipper);
        navBooks = findViewById(R.id.nav_books);
        navText = findViewById(R.id.nav_text);
        navAudio = findViewById(R.id.nav_audio);

        booksSummary = findViewById(R.id.books_summary);
        bookSpinner = findViewById(R.id.book_spinner);
        chapterSpinner = findViewById(R.id.chapter_spinner);
        chapterCountLabel = findViewById(R.id.chapter_count_label);
        bookRenameButton = findViewById(R.id.book_rename_button);
        chapterNewButton = findViewById(R.id.chapter_new_button);
        chapterRenameButton = findViewById(R.id.chapter_rename_button);
        exportBookButton = findViewById(R.id.export_book_button);

        editorContextLabel = findViewById(R.id.editor_context_label);
        editorSaveLabel = findViewById(R.id.editor_save_label);
        chapterEditor = findViewById(R.id.chapter_editor);
        textStatsLabel = findViewById(R.id.text_stats_label);
        voiceSpinner = findViewById(R.id.voice_spinner);
        speechRateSeek = findViewById(R.id.speech_rate_seek);
        pitchSeek = findViewById(R.id.pitch_seek);
        speechRateValue = findViewById(R.id.speech_rate_value);
        pitchValue = findViewById(R.id.pitch_value);
        narrateChapterButton = findViewById(R.id.narrate_chapter_button);
        narrateBookButton = findViewById(R.id.narrate_book_button);

        narrationProgressPanel = findViewById(R.id.narration_progress_panel);
        narrationStageLabel = findViewById(R.id.narration_stage_label);
        narrationElapsedLabel = findViewById(R.id.narration_elapsed_label);
        narrationPercentLabel = findViewById(R.id.narration_percent_label);
        narrationProgress = findViewById(R.id.narration_progress);
        cancelNarrationButton = findViewById(R.id.cancel_narration_button);
        consoleToggleButton = findViewById(R.id.console_toggle_button);
        consoleScroll = findViewById(R.id.console_scroll);
        consoleText = findViewById(R.id.console_text);

        audioContextLabel = findViewById(R.id.audio_context_label);
        audioList = findViewById(R.id.audio_list);
        audioEmptyLabel = findViewById(R.id.audio_empty_label);
        playerTitle = findViewById(R.id.player_title);
        playerSubtitle = findViewById(R.id.player_subtitle);
        waveform = findViewById(R.id.waveform);
        playerSeek = findViewById(R.id.player_seek);
        playerCurrentTime = findViewById(R.id.player_current_time);
        playerTotalTime = findViewById(R.id.player_total_time);
        playerBackButton = findViewById(R.id.player_back_button);
        playerPlayButton = findViewById(R.id.player_play_button);
        playerForwardButton = findViewById(R.id.player_forward_button);
        playerSpeedSpinner = findViewById(R.id.player_speed_spinner);
        saveAudioButton = findViewById(R.id.save_audio_button);
        shareAudioButton = findViewById(R.id.share_audio_button);
    }

    private void configureAdapters() {
        bookAdapter = new ItemAdapter<>(books, Book::getTitle);
        chapterAdapter = new ItemAdapter<>(chapters,
                chapter -> (chapter.getPosition() + 1) + ".  " + chapter.getTitle());
        voiceAdapter = new ItemAdapter<>(voices, VoiceItem::label);
        audioAdapter = new AudioListAdapter();

        bookSpinner.setAdapter(bookAdapter);
        chapterSpinner.setAdapter(chapterAdapter);
        voiceSpinner.setAdapter(voiceAdapter);
        audioList.setAdapter(audioAdapter);
    }

    private void configureNavigation() {
        navBooks.setOnClickListener(view -> showPage(PAGE_BOOKS));
        navText.setOnClickListener(view -> showPage(PAGE_TEXT));
        navAudio.setOnClickListener(view -> {
            refreshAudio(currentAudio == null ? null : currentAudio.getId());
            showPage(PAGE_AUDIO);
        });
    }

    private void showPage(int page) {
        currentPage = page;
        contentFlipper.setDisplayedChild(page);
        selectNav(navBooks, page == PAGE_BOOKS);
        selectNav(navText, page == PAGE_TEXT);
        selectNav(navAudio, page == PAGE_AUDIO);
        preferences.edit().putInt(PREF_PAGE, page).apply();
        if (page == PAGE_TEXT) chapterEditor.requestFocus();
    }

    private void selectNav(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(getColor(selected ? R.color.text : R.color.text_soft));
    }

    private void configureLibraryActions() {
        bookSpinner.setOnItemSelectedListener(new SimpleSelectionListener(position -> {
            if (bindingData || position < 0 || position >= books.size()) return;
            switchBook(books.get(position));
        }));
        chapterSpinner.setOnItemSelectedListener(new SimpleSelectionListener(position -> {
            if (bindingData || position < 0 || position >= chapters.size()) return;
            switchChapter(chapters.get(position));
        }));

        findViewById(R.id.book_new_button).setOnClickListener(view -> promptForName(
                "Новая книга", "Название книги", "", name -> {
                    Book book = repository.createBook(name);
                    refreshBooks(book.getId());
                    toast("Книга создана");
                }));

        bookRenameButton.setOnClickListener(view -> {
            if (activeBook == null) return;
            promptForName("Переименовать книгу", "Название", activeBook.getTitle(), name -> {
                Book saved = repository.saveBook(activeBook.withTitle(name));
                refreshBooks(saved.getId());
            });
        });

        chapterNewButton.setOnClickListener(view -> {
            if (activeBook == null) return;
            promptForName("Новая глава", "Название главы", "", name -> {
                flushAutosave();
                Chapter chapter = repository.createChapter(activeBook.getId(), name);
                refreshChapters(chapter.getId());
                showPage(PAGE_TEXT);
            });
        });

        chapterRenameButton.setOnClickListener(view -> {
            if (activeChapter == null) return;
            promptForName("Переименовать главу", "Название", activeChapter.getTitle(), name -> {
                flushAutosave();
                Chapter saved = repository.saveChapter(activeChapter.withTitle(name));
                refreshChapters(saved.getId());
            });
        });

        findViewById(R.id.import_book_button).setOnClickListener(view -> launchBookImport());
        exportBookButton.setOnClickListener(view -> launchBookExport());
    }

    private void configureEditor() {
        chapterEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateTextStats(s == null ? "" : s.toString());
                if (!bindingData && activeChapter != null) {
                    autosavePending = true;
                    editorSaveLabel.setText("Изменения…");
                    editorSaveLabel.setTextColor(getColor(R.color.amber));
                    mainHandler.removeCallbacks(autosaveRunnable);
                    mainHandler.postDelayed(autosaveRunnable, AUTOSAVE_DELAY_MS);
                }
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        findViewById(R.id.paste_button).setOnClickListener(view -> pasteText());
        findViewById(R.id.copy_all_button).setOnClickListener(view -> copyAllText());
        findViewById(R.id.clear_text_button).setOnClickListener(view -> confirmClearText());
    }

    private void configureNarrationControls() {
        SeekBar.OnSeekBarChangeListener settingListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateSpeechSettingLabels();
                if (fromUser) persistSpeechSettings();
            }
        };
        speechRateSeek.setOnSeekBarChangeListener(settingListener);
        pitchSeek.setOnSeekBarChangeListener(settingListener);

        voiceSpinner.setOnItemSelectedListener(new SimpleSelectionListener(position -> {
            if (bindingData || position < 0 || position >= voices.size()) return;
            preferences.edit().putString(PREF_VOICE, voices.get(position).name).apply();
        }));

        narrateChapterButton.setOnClickListener(view -> startNarration(false));
        narrateBookButton.setOnClickListener(view -> startNarration(true));
        cancelNarrationButton.setOnClickListener(view -> cancelNarration());
        consoleToggleButton.setOnClickListener(view -> toggleConsole());
    }

    private void configurePlayer() {
        setPlayerEnabled(false);
        waveform.setEnabled(false);
        waveform.setOnSeekListener((fraction, finished) -> {
            if (!mediaPrepared || mediaPlayer == null) return;
            int duration = mediaPlayer.getDuration();
            int target = Math.round(duration * fraction);
            playerCurrentTime.setText(formatDuration(target));
            if (finished) mediaPlayer.seekTo(target);
        });

        audioList.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < audioAssets.size()) {
                selectAudio(audioAssets.get(position));
            }
        });

        playerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (mediaPrepared && mediaPlayer != null) {
                    mediaPlayer.seekTo(Math.round(mediaPlayer.getDuration()
                            * (seekBar.getProgress() / 1000f)));
                }
                userSeeking = false;
            }
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPrepared && mediaPlayer != null) {
                    int target = Math.round(mediaPlayer.getDuration() * (progress / 1000f));
                    playerCurrentTime.setText(formatDuration(target));
                    waveform.setProgress(progress / 1000f);
                }
            }
        });

        playerPlayButton.setOnClickListener(view -> togglePlayback());
        playerBackButton.setOnClickListener(view -> skipPlayer(-15_000));
        playerForwardButton.setOnClickListener(view -> skipPlayer(15_000));

        List<String> speeds = Arrays.asList("0.75×", "1.00×", "1.25×", "1.50×", "2.00×");
        ItemAdapter<String> speedAdapter = new ItemAdapter<>(speeds, value -> value);
        playerSpeedSpinner.setAdapter(speedAdapter);
        playerSpeedSpinner.setSelection(1);
        playerSpeedSpinner.setOnItemSelectedListener(new SimpleSelectionListener(position -> {
            float[] values = {0.75f, 1f, 1.25f, 1.5f, 2f};
            if (position >= 0 && position < values.length) setPlayerSpeed(values[position]);
        }));

        saveAudioButton.setOnClickListener(view -> launchAudioSave());
        shareAudioButton.setOnClickListener(view -> shareCurrentAudio());
    }

    private void refreshBooks(String preferredBookId) {
        flushAutosave();
        String wanted = preferredBookId != null ? preferredBookId
                : activeBook == null ? null : activeBook.getId();
        try {
            List<Book> loaded = repository.listBooks();
            books.clear();
            books.addAll(loaded);
        } catch (RuntimeException exception) {
            showError("Не удалось открыть библиотеку", exception);
        }

        bindingData = true;
        bookAdapter.notifyDataSetChanged();
        int selection = indexOfBook(wanted);
        if (selection < 0 && !books.isEmpty()) selection = 0;
        if (selection >= 0) bookSpinner.setSelection(selection, false);
        bindingData = false;

        booksSummary.setText(books.isEmpty()
                ? "Создайте первую книгу — всё хранится на устройстве."
                : plural(books.size(), "%d книга", "%d книги", "%d книг")
                        + " · локально и без аккаунта");

        if (selection >= 0) {
            switchBook(books.get(selection));
        } else {
            activeBook = null;
            activeChapter = null;
            chapters.clear();
            chapterAdapter.notifyDataSetChanged();
            bindChapterToEditor();
            updateAvailability();
            refreshAudio(null);
        }
    }

    private void switchBook(Book book) {
        if (book == null || (activeBook != null && activeBook.getId().equals(book.getId()))) {
            if (activeBook != null) refreshChapters(preferences.getString(PREF_CHAPTER_ID, null));
            return;
        }
        flushAutosave();
        activeBook = book;
        preferences.edit().putString(PREF_BOOK_ID, book.getId()).apply();
        refreshChapters(preferences.getString(PREF_CHAPTER_ID, null));
        refreshAudio(null);
    }

    private void refreshChapters(String preferredChapterId) {
        if (activeBook == null) {
            chapters.clear();
            chapterAdapter.notifyDataSetChanged();
            activeChapter = null;
            bindChapterToEditor();
            updateAvailability();
            return;
        }

        String wanted = preferredChapterId != null ? preferredChapterId
                : activeChapter == null ? null : activeChapter.getId();
        try {
            chapters.clear();
            chapters.addAll(repository.listChapters(activeBook.getId()));
        } catch (RuntimeException exception) {
            showError("Не удалось загрузить главы", exception);
        }

        bindingData = true;
        chapterAdapter.notifyDataSetChanged();
        int selection = indexOfChapter(wanted);
        if (selection < 0 && !chapters.isEmpty()) selection = 0;
        if (selection >= 0) chapterSpinner.setSelection(selection, false);
        bindingData = false;

        chapterCountLabel.setText(chapters.isEmpty()
                ? "Нет глав"
                : plural(chapters.size(), "%d глава", "%d главы", "%d глав"));
        if (selection >= 0) {
            switchChapter(chapters.get(selection));
        } else {
            activeChapter = null;
            bindChapterToEditor();
        }
        updateAvailability();
    }

    private void switchChapter(Chapter chapter) {
        if (chapter == null) return;
        if (activeChapter != null && activeChapter.getId().equals(chapter.getId())) {
            activeChapter = chapter;
            bindChapterToEditor();
            return;
        }
        flushAutosave();
        activeChapter = chapter;
        preferences.edit().putString(PREF_CHAPTER_ID, chapter.getId()).apply();
        bindChapterToEditor();
        updateAvailability();
    }

    private void bindChapterToEditor() {
        bindingData = true;
        if (activeChapter == null) {
            editorContextLabel.setText(activeBook == null
                    ? "Выберите или создайте книгу"
                    : activeBook.getTitle() + " · добавьте главу");
            chapterEditor.setText("");
            editorSaveLabel.setText("Нет активной главы");
            editorSaveLabel.setTextColor(getColor(R.color.muted));
        } else {
            editorContextLabel.setText(activeBook.getTitle() + "  /  " + activeChapter.getTitle());
            chapterEditor.setText(activeChapter.getText());
            chapterEditor.setSelection(chapterEditor.length());
            editorSaveLabel.setText("Сохранено на устройстве");
            editorSaveLabel.setTextColor(getColor(R.color.aqua));
        }
        bindingData = false;
        updateTextStats(chapterEditor.getText().toString());
    }

    private void updateAvailability() {
        boolean hasBook = activeBook != null;
        boolean hasChapter = activeChapter != null;
        bookRenameButton.setEnabled(hasBook);
        chapterNewButton.setEnabled(hasBook);
        chapterRenameButton.setEnabled(hasChapter);
        exportBookButton.setEnabled(hasBook);
        chapterEditor.setEnabled(hasChapter);
        narrateChapterButton.setEnabled(hasChapter && !isNarrationBusy());
        narrateBookButton.setEnabled(hasBook && !chapters.isEmpty() && !isNarrationBusy());
        audioContextLabel.setText(hasBook ? activeBook.getTitle() : "Выберите книгу");
    }

    private void saveEditorNow() {
        mainHandler.removeCallbacks(autosaveRunnable);
        autosavePending = false;
        if (bindingData || activeChapter == null) return;
        try {
            activeChapter = repository.autosaveChapter(activeChapter.getId(),
                    activeChapter.getTitle(), chapterEditor.getText().toString());
            replaceChapter(activeChapter);
            editorSaveLabel.setText("Сохранено на устройстве");
            editorSaveLabel.setTextColor(getColor(R.color.aqua));
        } catch (RuntimeException exception) {
            editorSaveLabel.setText("Ошибка сохранения · повторим при выходе");
            editorSaveLabel.setTextColor(getColor(R.color.danger));
        }
    }

    private void flushAutosave() {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        if (autosavePending) saveEditorNow();
    }

    private void replaceChapter(Chapter chapter) {
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).getId().equals(chapter.getId())) {
                chapters.set(i, chapter);
                chapterAdapter.notifyDataSetChanged();
                return;
            }
        }
    }

    private void pasteText() {
        if (activeChapter == null) {
            toast("Сначала создайте главу");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast("В буфере обмена нет текста");
            return;
        }
        CharSequence value = clip.getItemAt(0).coerceToText(this);
        if (TextUtils.isEmpty(value)) {
            toast("В буфере обмена нет текста");
            return;
        }
        int start = Math.max(0, chapterEditor.getSelectionStart());
        int end = Math.max(0, chapterEditor.getSelectionEnd());
        int low = Math.min(start, end);
        int high = Math.max(start, end);
        chapterEditor.getText().replace(low, high, value);
        chapterEditor.setSelection(low + value.length());
        toast("Текст вставлен");
    }

    private void copyAllText() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        String label = activeChapter == null ? "Book Wunderwaffe" : activeChapter.getTitle();
        clipboard.setPrimaryClip(ClipData.newPlainText(label, chapterEditor.getText()));
        toast("Весь текст скопирован");
    }

    private void confirmClearText() {
        if (activeChapter == null || chapterEditor.length() == 0) return;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Очистить главу?")
                .setMessage("Текст будет удалён из этой главы. Отменить действие после сохранения нельзя.")
                .setNegativeButton("Оставить", null)
                .setPositiveButton("Очистить", (ignored, which) -> {
                    chapterEditor.setText("");
                    saveEditorNow();
                })
                .create();
        tintDialog(dialog, true);
    }

    private void updateTextStats(String text) {
        String trimmed = text.trim();
        int words = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
        textStatsLabel.setText(String.format(Locale.getDefault(), "%d символов · %d слов",
                text.length(), words));
    }

    private void promptForName(String title, String hint, String current, NameAction action) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(current);
        input.setSelectAllOnFocus(true);
        input.setTextColor(getColor(R.color.text));
        input.setHintTextColor(getColor(R.color.muted));
        input.setBackgroundResource(R.drawable.input_background);
        int padding = dp(13);
        input.setPadding(padding, padding, padding, padding);

        LinearLayout container = new LinearLayout(this);
        container.setPadding(dp(20), dp(4), dp(20), 0);
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(container)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Готово", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.accent));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.text_soft));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                String value = input.getText().toString().trim();
                if (value.isEmpty()) {
                    input.setError("Введите название");
                    return;
                }
                try {
                    action.run(value);
                    dialog.dismiss();
                } catch (RuntimeException exception) {
                    input.setError("Не удалось сохранить");
                }
            });
            input.requestFocus();
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dialog.show();
    }

    private void tintDialog(AlertDialog dialog, boolean dangerous) {
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(getColor(dangerous ? R.color.danger : R.color.accent));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.text_soft));
        });
        dialog.show();
    }

    private void initVoiceCatalog() {
        voices.clear();
        voices.add(new VoiceItem(null, "Загрузка системных голосов…", Locale.getDefault(), false));
        voiceAdapter.notifyDataSetChanged();
        voiceSpinner.setEnabled(false);
        try {
            voiceCatalog = new TextToSpeech(this, status -> mainHandler.post(() -> {
                if (status == TextToSpeech.SUCCESS) {
                    loadInstalledVoices();
                } else {
                    voices.clear();
                    voices.add(new VoiceItem(null, "Системный синтезатор недоступен",
                            Locale.getDefault(), false));
                    voiceAdapter.notifyDataSetChanged();
                    narrateChapterButton.setEnabled(false);
                    narrateBookButton.setEnabled(false);
                }
            }));
        } catch (RuntimeException exception) {
            voices.clear();
            voices.add(new VoiceItem(null, "Системный синтезатор недоступен",
                    Locale.getDefault(), false));
            voiceAdapter.notifyDataSetChanged();
        }
    }

    private void loadInstalledVoices() {
        String preferredName = preferences.getString(PREF_VOICE, null);
        List<VoiceItem> loaded = new ArrayList<>();
        if (voiceCatalog != null && voiceCatalog.getVoices() != null) {
            for (Voice voice : voiceCatalog.getVoices()) {
                if (voice == null || TextUtils.isEmpty(voice.getName())) continue;
                loaded.add(new VoiceItem(voice.getName(), friendlyVoiceName(voice),
                        voice.getLocale(), voice.isNetworkConnectionRequired()));
            }
        }
        Collections.sort(loaded, Comparator
                .comparing((VoiceItem voice) -> !"ru".equalsIgnoreCase(voice.locale.getLanguage()))
                .thenComparing(voice -> voice.network)
                .thenComparing(voice -> voice.displayName.toLowerCase(Locale.ROOT)));

        bindingData = true;
        voices.clear();
        voices.addAll(loaded);
        if (voices.isEmpty()) {
            voices.add(new VoiceItem(null, "Нет установленных голосов", Locale.getDefault(), false));
        }
        voiceAdapter.notifyDataSetChanged();
        int selected = 0;
        if (preferredName != null) {
            for (int i = 0; i < voices.size(); i++) {
                if (preferredName.equals(voices.get(i).name)) {
                    selected = i;
                    break;
                }
            }
        } else {
            for (int i = 0; i < voices.size(); i++) {
                if (!voices.get(i).network
                        && "ru".equalsIgnoreCase(voices.get(i).locale.getLanguage())) {
                    selected = i;
                    break;
                }
            }
        }
        voiceSpinner.setSelection(selected, false);
        voiceSpinner.setEnabled(voices.get(selected).name != null);
        bindingData = false;
        if (voices.get(selected).name != null) {
            preferences.edit().putString(PREF_VOICE, voices.get(selected).name).apply();
        }
        updateAvailability();
    }

    private String friendlyVoiceName(Voice voice) {
        String raw = voice.getName();
        String simplified = raw
                .replace("-language", "")
                .replace("-local", "")
                .replace("-network", "")
                .replace('_', ' ');
        Locale locale = voice.getLocale() == null ? Locale.getDefault() : voice.getLocale();
        String localeLabel = locale.getDisplayName(Locale.getDefault());
        return simplified + " · " + localeLabel;
    }

    private void updateSpeechSettingLabels() {
        speechRateValue.setText(String.format(Locale.getDefault(), "%.2f×",
                sliderToValue(speechRateSeek.getProgress())));
        pitchValue.setText(String.format(Locale.getDefault(), "%.2f×",
                sliderToValue(pitchSeek.getProgress())));
    }

    private void persistSpeechSettings() {
        preferences.edit()
                .putFloat(PREF_RATE, sliderToValue(speechRateSeek.getProgress()))
                .putFloat(PREF_PITCH, sliderToValue(pitchSeek.getProgress()))
                .apply();
    }

    private float sliderToValue(int progress) {
        return 0.5f + Math.max(0, Math.min(100, progress)) / 100f;
    }

    private int valueToSlider(float value) {
        return Math.max(0, Math.min(100, Math.round((value - 0.5f) * 100f)));
    }

    private void startNarration(boolean wholeBook) {
        if (isNarrationBusy()) {
            showPage(PAGE_TEXT);
            toast("Озвучивание уже идёт в фоне");
            return;
        }
        flushAutosave();
        if (activeBook == null || (!wholeBook && activeChapter == null)) {
            toast(wholeBook ? "Сначала создайте книгу" : "Сначала создайте главу");
            return;
        }
        if (wholeBook) {
            boolean hasText = false;
            for (Chapter chapter : chapters) {
                if (!chapter.getText().trim().isEmpty()) {
                    hasText = true;
                    break;
                }
            }
            if (!hasText) {
                toast("В книге пока нет текста");
                return;
            }
        } else if (chapterEditor.getText().toString().trim().isEmpty()) {
            toast("Глава пустая — добавьте текст");
            return;
        }

        maybeRequestNotificationPermission();
        VoiceItem voice = selectedVoice();
        String languageTag = voice == null || voice.locale == null
                ? Locale.getDefault().toLanguageTag() : voice.locale.toLanguageTag();
        String voiceName = voice == null ? null : voice.name;
        float rate = sliderToValue(speechRateSeek.getProgress());
        float pitch = sliderToValue(pitchSeek.getProgress());
        persistSpeechSettings();

        try {
            currentJob = wholeBook
                    ? repository.createWholeBookNarrationJob(activeBook.getId(), languageTag,
                            voiceName, rate, pitch)
                    : repository.createChapterNarrationJob(activeChapter.getId(), languageTag,
                            voiceName, rate, pitch);
            currentJobId = currentJob.getId();
            terminalJobRefreshedId = null;
            updateNarrationUi(currentJob);
            Intent serviceIntent = new Intent(this, NarrationService.class)
                    .setAction(NarrationService.ACTION_START)
                    .putExtra(NarrationService.EXTRA_JOB_ID, currentJobId);
            startForegroundService(serviceIntent);
            showPage(PAGE_TEXT);
            toast("Озвучивание запущено в фоне");
        } catch (RuntimeException exception) {
            showError("Не удалось запустить озвучивание", exception);
        }
    }

    private VoiceItem selectedVoice() {
        int position = voiceSpinner.getSelectedItemPosition();
        return position >= 0 && position < voices.size() ? voices.get(position) : null;
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    private void restoreNarration() {
        try {
            NarrationJob restored = repository.getActiveNarrationJob();
            if (restored != null) {
                currentJob = restored;
                currentJobId = restored.getId();
                updateNarrationUi(restored);
            }
        } catch (RuntimeException exception) {
            // Library editing remains available even if a stale job cannot be restored.
        }
    }

    private void pollNarration() {
        try {
            NarrationJob job = null;
            if (currentJobId != null) job = repository.getNarrationJob(currentJobId);
            if (job == null) job = repository.getActiveNarrationJob();
            if (job != null) {
                currentJob = job;
                currentJobId = job.getId();
                updateNarrationUi(job);
            }
        } catch (RuntimeException exception) {
            if (narrationProgressPanel.getVisibility() == View.VISIBLE) {
                narrationStageLabel.setText("Ждём данные фоновой службы…");
            }
        }
    }

    private void updateNarrationUi(NarrationJob job) {
        narrationProgressPanel.setVisibility(View.VISIBLE);
        int percent = Math.max(0, Math.min(100, job.getProgressPercent()));
        narrationProgress.setProgress(percent);
        narrationPercentLabel.setText(percent + "%");
        String stage = stageLabel(job.getStage(), job.getStatus());
        if (!TextUtils.isEmpty(job.getError())) stage += " · " + job.getError();
        narrationStageLabel.setText(stage);

        long started = job.getStartedAt() > 0 ? job.getStartedAt() : job.getCreatedAt();
        long ended = job.isTerminal() && job.getCompletedAt() > 0
                ? job.getCompletedAt() : System.currentTimeMillis();
        long elapsed = Math.max(0L, ended - started);
        String chunks = job.getTotalChunks() > 0
                ? String.format(Locale.getDefault(), " · фрагмент %d/%d",
                        Math.min(job.getResumeChunkIndex() + 1, job.getTotalChunks()),
                        job.getTotalChunks())
                : "";
        narrationElapsedLabel.setText("Прошло " + formatDuration(elapsed) + chunks);
        cancelNarrationButton.setEnabled(!job.isTerminal());
        cancelNarrationButton.setText(job.isTerminal() ? terminalStatusLabel(job) : "Отменить");
        updateAvailability();
        updateTechnicalLog(job);

        if (job.isTerminal() && !job.getId().equals(terminalJobRefreshedId)) {
            terminalJobRefreshedId = job.getId();
            if (NarrationJob.STATUS_COMPLETED.equals(job.getStatus())) refreshAudio(null);
        }
    }

    private String stageLabel(String stage, String status) {
        if (NarrationJob.STATUS_CANCEL_REQUESTED.equals(status)) return "Останавливаем…";
        if (NarrationJob.STATUS_FAILED.equals(status)) return "Озвучивание завершилось с ошибкой";
        if (NarrationJob.STATUS_CANCELLED.equals(status)) return "Озвучивание отменено";
        if (NarrationJob.STATUS_COMPLETED.equals(status)) return "Аудио готово";
        if (NarrationJob.STAGE_PREPARING.equals(stage)) return "Подготавливаем текст";
        if (NarrationJob.STAGE_SYNTHESIZING.equals(stage)) return "Синтезируем цельное аудио";
        if (NarrationJob.STAGE_FINALIZING.equals(stage)) return "Собираем WAV-файл";
        return "Задание в очереди";
    }

    private String terminalStatusLabel(NarrationJob job) {
        if (NarrationJob.STATUS_COMPLETED.equals(job.getStatus())) return "Готово";
        if (NarrationJob.STATUS_FAILED.equals(job.getStatus())) return "Ошибка";
        return "Отменено";
    }

    private void updateTechnicalLog(NarrationJob job) {
        List<NarrationLogEntry> entries;
        try {
            entries = repository.getNarrationLogs(job.getId(), 200);
        } catch (RuntimeException exception) {
            return;
        }
        StringBuilder text = new StringBuilder();
        DateFormat timeFormat = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        for (NarrationLogEntry entry : entries) {
            text.append(timeFormat.format(new Date(entry.getCreatedAt())))
                    .append("  [").append(entry.getLevel()).append("]  ")
                    .append(entry.getMessage()).append('\n');
        }
        if (text.length() == 0) text.append("Задание создано. Ожидаем сообщения службы…");
        consoleText.setText(text.toString());
        if (consoleScroll.getVisibility() == View.VISIBLE) {
            consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void cancelNarration() {
        if (currentJobId == null || currentJob == null || currentJob.isTerminal()) return;
        Intent intent = new Intent(this, NarrationService.class)
                .setAction(NarrationService.ACTION_STOP)
                .putExtra(NarrationService.EXTRA_JOB_ID, currentJobId);
        startService(intent);
        narrationStageLabel.setText("Останавливаем…");
        cancelNarrationButton.setEnabled(false);
    }

    private void toggleConsole() {
        boolean show = consoleScroll.getVisibility() != View.VISIBLE;
        consoleScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        consoleToggleButton.setText(show ? "Скрыть техлог" : "Показать техлог");
        if (show) consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
    }

    private boolean isNarrationBusy() {
        return currentJob != null && !currentJob.isTerminal();
    }

    private void refreshAudio(String preferredAssetId) {
        String wanted = preferredAssetId != null ? preferredAssetId
                : currentAudio == null ? null : currentAudio.getId();
        audioAssets.clear();
        if (activeBook != null) {
            try {
                audioAssets.addAll(repository.listAudio(activeBook.getId()));
            } catch (RuntimeException exception) {
                showError("Не удалось загрузить аудиофайлы", exception);
            }
        }
        audioAdapter.notifyDataSetChanged();
        boolean empty = audioAssets.isEmpty();
        audioList.setVisibility(empty ? View.GONE : View.VISIBLE);
        audioEmptyLabel.setVisibility(empty ? View.VISIBLE : View.GONE);
        audioContextLabel.setText(activeBook == null ? "Выберите книгу"
                : activeBook.getTitle() + " · "
                        + plural(audioAssets.size(), "%d файл", "%d файла", "%d файлов"));

        int selection = indexOfAudio(wanted);
        if (selection >= 0) {
            audioList.setItemChecked(selection, true);
            if (currentAudio == null || !wanted.equals(currentAudio.getId())) {
                selectAudio(audioAssets.get(selection));
            }
        } else if (empty) {
            currentAudio = null;
            releasePlayer();
            resetPlayerUi();
        }
    }

    private void selectAudio(AudioAsset asset) {
        currentAudio = asset;
        playerTitle.setText(asset.getDisplayName());
        String details = "WAV";
        if (asset.getDurationMs() > 0) details += " · " + formatDuration(asset.getDurationMs());
        if (asset.getSizeBytes() > 0) details += " · " + formatBytes(asset.getSizeBytes());
        playerSubtitle.setText(details + " · локально");
        waveform.setWaveSeed(asset.getContentHash() == null ? asset.getId() : asset.getContentHash());
        waveform.setProgress(0f);
        audioAdapter.notifyDataSetChanged();
        preparePlayer(asset);
    }

    private void preparePlayer(AudioAsset asset) {
        releasePlayer();
        resetPlayerPosition();
        mediaPrepared = false;
        playerPlayButton.setText("Загрузка…");
        setPlayerEnabled(false);
        saveAudioButton.setEnabled(true);
        shareAudioButton.setEnabled(true);
        try {
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            if (!TextUtils.isEmpty(asset.getLocalPath())) {
                player.setDataSource(asset.getLocalPath());
            } else if (!TextUtils.isEmpty(asset.getDocumentUri())) {
                player.setDataSource(this, Uri.parse(asset.getDocumentUri()));
            } else {
                throw new IllegalStateException("У аудиофайла нет доступного источника");
            }
            player.setOnPreparedListener(prepared -> {
                if (prepared != mediaPlayer) return;
                mediaPrepared = true;
                setPlayerEnabled(true);
                playerPlayButton.setText("▶  Играть");
                playerTotalTime.setText(formatDuration(prepared.getDuration()));
                setPlayerSpeed(selectedPlayerSpeed());
            });
            player.setOnCompletionListener(completed -> {
                completed.seekTo(0);
                playerPlayButton.setText("▶  Играть");
                updatePlayerPosition();
                abandonAudioFocus();
            });
            player.setOnErrorListener((failed, what, extra) -> {
                mediaPrepared = false;
                playerPlayButton.setText("Не удалось открыть");
                setPlayerEnabled(false);
                saveAudioButton.setEnabled(currentAudio != null);
                shareAudioButton.setEnabled(currentAudio != null);
                return true;
            });
            mediaPlayer = player;
            player.prepareAsync();
        } catch (Exception exception) {
            releasePlayer();
            playerPlayButton.setText("Не удалось открыть");
            toast("Аудиофайл недоступен");
        }
    }

    private void togglePlayback() {
        if (!mediaPrepared || mediaPlayer == null) return;
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                playerPlayButton.setText("▶  Продолжить");
            } else if (requestAudioFocus()) {
                mediaPlayer.start();
                playerPlayButton.setText("Ⅱ  Пауза");
            }
        } catch (IllegalStateException exception) {
            playerPlayButton.setText("Не удалось открыть");
        }
    }

    private void skipPlayer(int deltaMs) {
        if (!mediaPrepared || mediaPlayer == null) return;
        int position = mediaPlayer.getCurrentPosition();
        int target = Math.max(0, Math.min(mediaPlayer.getDuration(), position + deltaMs));
        mediaPlayer.seekTo(target);
        updatePlayerPosition();
    }

    private void updatePlayerPosition() {
        if (!mediaPrepared || mediaPlayer == null || userSeeking) return;
        try {
            int duration = Math.max(1, mediaPlayer.getDuration());
            int position = Math.max(0, mediaPlayer.getCurrentPosition());
            int progress = Math.max(0, Math.min(1000, Math.round(position * 1000f / duration)));
            updatingPlayerUi = true;
            playerSeek.setProgress(progress);
            waveform.setProgress(progress / 1000f);
            playerCurrentTime.setText(formatDuration(position));
            playerTotalTime.setText(formatDuration(duration));
            updatingPlayerUi = false;
        } catch (IllegalStateException ignored) {
            mediaPrepared = false;
        }
    }

    private void setPlayerSpeed(float speed) {
        if (!mediaPrepared || mediaPlayer == null) return;
        try {
            PlaybackParams params = mediaPlayer.getPlaybackParams();
            params.setSpeed(speed);
            mediaPlayer.setPlaybackParams(params);
            if (!mediaPlayer.isPlaying()) mediaPlayer.pause();
        } catch (RuntimeException exception) {
            toast("Этот плеер не поддерживает выбранную скорость");
        }
    }

    private float selectedPlayerSpeed() {
        float[] values = {0.75f, 1f, 1.25f, 1.5f, 2f};
        int position = playerSpeedSpinner.getSelectedItemPosition();
        return position >= 0 && position < values.length ? values[position] : 1f;
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;
        if (audioFocusRequest == null) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(change -> mainHandler.post(() -> {
                        if ((change == AudioManager.AUDIOFOCUS_LOSS
                                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                                && mediaPrepared && mediaPlayer != null && mediaPlayer.isPlaying()) {
                            mediaPlayer.pause();
                            playerPlayButton.setText("▶  Продолжить");
                        }
                    }))
                    .build();
        }
        return audioManager.requestAudioFocus(audioFocusRequest)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager != null && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
    }

    private void releasePlayer() {
        abandonAudioFocus();
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        mediaPrepared = false;
        if (player != null) {
            try {
                player.reset();
            } catch (RuntimeException ignored) {
            }
            player.release();
        }
    }

    private void setPlayerEnabled(boolean enabled) {
        playerSeek.setEnabled(enabled);
        waveform.setEnabled(enabled);
        playerBackButton.setEnabled(enabled);
        playerPlayButton.setEnabled(enabled);
        playerForwardButton.setEnabled(enabled);
        playerSpeedSpinner.setEnabled(enabled);
        saveAudioButton.setEnabled(enabled && currentAudio != null);
        shareAudioButton.setEnabled(enabled && currentAudio != null);
    }

    private void resetPlayerPosition() {
        updatingPlayerUi = true;
        playerSeek.setProgress(0);
        waveform.setProgress(0f);
        playerCurrentTime.setText("00:00");
        playerTotalTime.setText("00:00");
        updatingPlayerUi = false;
    }

    private void resetPlayerUi() {
        resetPlayerPosition();
        playerTitle.setText("Выберите аудиофайл");
        playerSubtitle.setText("WAV · локально");
        playerPlayButton.setText("▶  Играть");
        setPlayerEnabled(false);
    }

    private void launchBookImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{BookPackageCodec.MIME_TYPE, "application/zip",
                                "application/octet-stream"});
        startActivityForResult(intent, REQUEST_IMPORT_BOOK);
    }

    private void launchBookExport() {
        if (activeBook == null) return;
        flushAutosave();
        pendingBookExportId = activeBook.getId();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(BookPackageCodec.MIME_TYPE)
                .putExtra(Intent.EXTRA_TITLE, safeFileStem(activeBook.getTitle()) + ".bwlbook");
        startActivityForResult(intent, REQUEST_EXPORT_BOOK);
    }

    private void launchAudioSave() {
        if (currentAudio == null) return;
        pendingAudioSave = currentAudio;
        String name = currentAudio.getDisplayName();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".wav")) name += ".wav";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/wav")
                .putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, REQUEST_SAVE_AUDIO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri destination = data.getData();
        if (requestCode == REQUEST_IMPORT_BOOK) {
            importBook(destination);
        } else if (requestCode == REQUEST_EXPORT_BOOK) {
            exportBook(destination, pendingBookExportId);
            pendingBookExportId = null;
        } else if (requestCode == REQUEST_SAVE_AUDIO) {
            saveAudio(destination, pendingAudioSave);
            pendingAudioSave = null;
        }
    }

    private void importBook(Uri source) {
        toast("Импортируем книгу…");
        ioExecutor.execute(() -> {
            try {
                Book imported = packageCodec.importBook(source);
                runOnUiThread(() -> {
                    refreshBooks(imported.getId());
                    toast("Книга импортирована");
                });
            } catch (Exception exception) {
                runOnUiThread(() -> showError("Не удалось импортировать .bwlbook", exception));
            }
        });
    }

    private void exportBook(Uri destination, String bookId) {
        if (bookId == null) return;
        toast("Собираем .bwlbook…");
        ioExecutor.execute(() -> {
            try {
                packageCodec.exportBook(bookId, destination);
                runOnUiThread(() -> toast("Книга сохранена"));
            } catch (Exception exception) {
                runOnUiThread(() -> showError("Не удалось экспортировать книгу", exception));
            }
        });
    }

    private void saveAudio(Uri destination, AudioAsset asset) {
        if (asset == null) return;
        toast("Сохраняем WAV…");
        ioExecutor.execute(() -> {
            try (InputStream input = openAudioInput(asset);
                 OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (input == null || output == null) throw new IllegalStateException("Файл недоступен");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) output.write(buffer, 0, read);
                }
                output.flush();
                runOnUiThread(() -> toast("WAV сохранён на телефон"));
            } catch (Exception exception) {
                runOnUiThread(() -> showError("Не удалось сохранить WAV", exception));
            }
        });
    }

    private InputStream openAudioInput(AudioAsset asset) throws Exception {
        if (!TextUtils.isEmpty(asset.getLocalPath())) {
            return new FileInputStream(new File(asset.getLocalPath()));
        }
        if (!TextUtils.isEmpty(asset.getDocumentUri())) {
            return getContentResolver().openInputStream(Uri.parse(asset.getDocumentUri()));
        }
        throw new IllegalStateException("Источник аудио не найден");
    }

    private void shareCurrentAudio() {
        if (currentAudio == null) return;
        try {
            Uri uri;
            if (!TextUtils.isEmpty(currentAudio.getLocalPath())) {
                uri = ShareFileProvider.uriForFile(this, new File(currentAudio.getLocalPath()));
            } else if (!TextUtils.isEmpty(currentAudio.getDocumentUri())) {
                uri = Uri.parse(currentAudio.getDocumentUri());
            } else {
                throw new IllegalStateException("Файл недоступен");
            }
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType(TextUtils.isEmpty(currentAudio.getMimeType())
                            ? "audio/wav" : currentAudio.getMimeType())
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri(currentAudio.getDisplayName(), uri));
            startActivity(Intent.createChooser(share, "Поделиться аудио"));
        } catch (RuntimeException exception) {
            showError("Не удалось поделиться файлом", exception);
        }
    }

    @Override
    public void onBackPressed() {
        if (currentPage != PAGE_BOOKS) {
            showPage(PAGE_BOOKS);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            toast("Озвучивание продолжится в фоне; уведомление скрыто настройками Android");
        }
    }

    private int indexOfBook(String id) {
        if (id == null) return -1;
        for (int i = 0; i < books.size(); i++) {
            if (id.equals(books.get(i).getId())) return i;
        }
        return -1;
    }

    private int indexOfChapter(String id) {
        if (id == null) return -1;
        for (int i = 0; i < chapters.size(); i++) {
            if (id.equals(chapters.get(i).getId())) return i;
        }
        return -1;
    }

    private int indexOfAudio(String id) {
        if (id == null) return -1;
        for (int i = 0; i < audioAssets.size(); i++) {
            if (id.equals(audioAssets.get(i).getId())) return i;
        }
        return -1;
    }

    private String safeFileStem(String value) {
        String result = value == null ? "book" : value.trim();
        result = result.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").trim();
        if (result.isEmpty()) result = "book";
        if (result.length() > 80) result = result.substring(0, 80).trim();
        return result;
    }

    private String plural(int count, String one, String few, String many) {
        int mod100 = count % 100;
        int mod10 = count % 10;
        String pattern = mod100 >= 11 && mod100 <= 14 ? many
                : mod10 == 1 ? one
                : mod10 >= 2 && mod10 <= 4 ? few : many;
        return String.format(Locale.getDefault(), pattern, count);
    }

    private String formatDuration(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds) / 1000L;
        long seconds = totalSeconds % 60L;
        long minutes = (totalSeconds / 60L) % 60L;
        long hours = totalSeconds / 3600L;
        return hours > 0
                ? String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " Б";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f КБ", bytes / 1024f);
        }
        return String.format(Locale.getDefault(), "%.1f МБ", bytes / (1024f * 1024f));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void showError(String message, Throwable error) {
        android.util.Log.e("BookWunderwaffe", message, error);
        String detail = error == null || TextUtils.isEmpty(error.getMessage())
                ? "" : ": " + error.getMessage();
        Toast.makeText(this, message + detail, Toast.LENGTH_LONG).show();
    }

    private interface Labeler<T> {
        String label(T value);
    }

    private interface NameAction {
        void run(String value);
    }

    private interface IntAction {
        void run(int value);
    }

    private static final class VoiceItem {
        private final String name;
        private final String displayName;
        private final Locale locale;
        private final boolean network;

        private VoiceItem(String name, String displayName, Locale locale, boolean network) {
            this.name = name;
            this.displayName = displayName;
            this.locale = locale == null ? Locale.getDefault() : locale;
            this.network = network;
        }

        private String label() {
            return (network ? "☁  " : "●  ") + displayName
                    + (network ? " · сетевой" : " · на устройстве");
        }
    }

    private final class ItemAdapter<T> extends BaseAdapter {
        private final List<T> values;
        private final Labeler<T> labeler;

        private ItemAdapter(List<T> values, Labeler<T> labeler) {
            this.values = values;
            this.labeler = labeler;
        }

        @Override public int getCount() { return values.size(); }
        @Override public T getItem(int position) { return values.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return itemText(position, convertView, false);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return itemText(position, convertView, true);
        }

        private TextView itemText(int position, View convertView, boolean dropdown) {
            TextView text = convertView instanceof TextView
                    ? (TextView) convertView : new TextView(MainActivity.this);
            text.setText(labeler.label(values.get(position)));
            text.setTextColor(getColor(dropdown ? R.color.text : R.color.text_soft));
            text.setTextSize(dropdown ? 14f : 13f);
            text.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            text.setSingleLine(false);
            text.setMaxLines(dropdown ? 3 : 2);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setPadding(dp(12), dp(dropdown ? 12 : 6), dp(12), dp(dropdown ? 12 : 6));
            text.setMinHeight(dp(dropdown ? 48 : 42));
            text.setBackgroundColor(getColor(dropdown ? R.color.panel_raised : android.R.color.transparent));
            return text;
        }
    }

    private final class AudioListAdapter extends BaseAdapter {
        @Override public int getCount() { return audioAssets.size(); }
        @Override public AudioAsset getItem(int position) { return audioAssets.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AudioRow row;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof AudioRow) {
                row = (AudioRow) convertView.getTag();
            } else {
                LinearLayout container = new LinearLayout(MainActivity.this);
                container.setOrientation(LinearLayout.VERTICAL);
                container.setGravity(Gravity.CENTER_VERTICAL);
                container.setPadding(dp(12), dp(9), dp(12), dp(9));

                TextView title = new TextView(MainActivity.this);
                title.setTextColor(getColor(R.color.text));
                title.setTextSize(13f);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                container.addView(title, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                TextView subtitle = new TextView(MainActivity.this);
                subtitle.setTextColor(getColor(R.color.muted));
                subtitle.setTextSize(10f);
                subtitle.setSingleLine(true);
                subtitle.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                subtitleParams.topMargin = dp(2);
                container.addView(subtitle, subtitleParams);
                row = new AudioRow(container, title, subtitle);
                container.setTag(row);
                convertView = container;
            }

            AudioAsset asset = getItem(position);
            row.title.setText(asset.getDisplayName());
            String meta = formatDuration(asset.getDurationMs()) + " · "
                    + formatBytes(asset.getSizeBytes()) + " · "
                    + DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(asset.getCreatedAt()));
            row.subtitle.setText(meta);
            boolean selected = currentAudio != null && currentAudio.getId().equals(asset.getId());
            row.root.setBackgroundColor(selected ? Color.rgb(39, 28, 47) : Color.TRANSPARENT);
            row.title.setTextColor(getColor(selected ? R.color.accent : R.color.text));
            return convertView;
        }
    }

    private static final class AudioRow {
        private final LinearLayout root;
        private final TextView title;
        private final TextView subtitle;

        private AudioRow(LinearLayout root, TextView title, TextView subtitle) {
            this.root = root;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    private static final class SimpleSelectionListener implements AdapterView.OnItemSelectedListener {
        private final IntAction action;

        private SimpleSelectionListener(IntAction action) {
            this.action = action;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            action.run(position);
        }

        @Override public void onNothingSelected(AdapterView<?> parent) { }
    }
}
