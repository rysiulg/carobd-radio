package pl.radiokia.fm;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.method.DigitsKeyListener;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class Radio extends Activity implements TopwayFmEvents.Listener {
    private static final String LAST_CHANNEL = "lastChannel", TUNER_STARTED = "tunerStarted";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashSet<Integer> presets = new LinkedHashSet<>();
    private final Runnable returnToRadio = this::returnFromFactory;
    private final Runnable stepFromXt = () -> {
        if (hasWindowFocus()) stepPreset(this.vendorDirection);
    };
    private final SteeringPress steering = new SteeringPress();
    private final WheelPresets wheel = new WheelPresets(main, this::canSwitchPreset,
        frequency -> sendCommand(Config.XT_TUNE, frequency, true), this::onTunerChannel);
    private final Runnable tunerTimeout = this::tunerTimedOut;
    private static final long SAME_PRESS_MS = 240, TUNER_REPLY_MS = 3500;
    private XtRadioEvents factoryEvents;
    private TopwayFmEvents topway;
    private MediaSession mediaKeys;
    private long lastAndroidKey = Long.MIN_VALUE / 2;
    private int androidDirection, vendorDirection;
    private long tunerUpdates, tunerCommand;

    private SharedPreferences prefs;
    private TextView channelView, statusView;
    private LinearLayout presetList;
    private FmScale scale;
    private int lastChannel, screenWidth;
    private boolean sending, tunerStarted, returning, readingFactory, channelConfirmed;
    private FactoryFmAccess.Result pendingScan;
    private TextView rdsView;
    private String lastRds;

    /** Odtwarza presety starszych wersji oraz buduje ekran do pracy w poziomie. */
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            lastChannel = state.getInt(LAST_CHANNEL);
            lastRds = state.getString("lastRds");
        }
        tunerStarted = state != null && state.getBoolean(TUNER_STARTED);
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        presets.addAll(PresetData.read(this));
        screenWidth = getResources().getConfiguration().screenWidthDp;
        getWindow().setStatusBarColor(Config.BACKGROUND);
        getWindow().setNavigationBarColor(Config.BACKGROUND);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Config.BACKGROUND);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(screenWidth >= 620 ? 22 : 12);
        panel.setPadding(padding, dp(8), padding, dp(16));
        scroll.addView(panel);
        setContentView(scroll);
        buildHeader(panel);
        buildTuner(panel);
        buildPresets(panel);
        showChannel();
        showPresets();
        configureMediaKeys();
        topway = TopwayFmEvents.open(this);
    }

    private void buildHeader
(LinearLayout panel) {
        boolean wide = screenWidth >= 620;
        LinearLayout header = row(panel);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("RadioKIA FM", 23, Config.TEXT);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout controls = wide ? header : row(panel);
        Button start = button("Uruchom tuner", Config.PANEL, Config.TEXT, () -> openFactory(true));
        Button factory = button("Fabryczne FM", Config.PANEL, Config.TEXT, () -> {
            if (openFactory(false)) setRequested(0, "Wróć do RadioKIA przez Ostatnie aplikacje.");
        });
        place(controls, start, wide ? -2 : 0, wide ? 0 : 1);
        place(controls, factory, wide ? -2 : 0, wide ? 0 : 1);
        divider(panel, 6);
    }

    private void buildTuner(LinearLayout panel) {
        boolean wide = screenWidth >= 620;
        LinearLayout tuning = row(panel);
        tuning.setGravity(Gravity.CENTER_VERTICAL);
        tuning.setPadding(0, dp(wide ? 6 : 4), 0, 0);
        Button lower = button(wide ? "‹  Szukaj niżej" : "‹", Config.PANEL, Config.TEXT, () -> send(Config.XT_SEEK_DOWN, 0));
        Button upper = button(wide ? "Szukaj wyżej  ›" : "›", Config.PANEL, Config.TEXT, () -> send(Config.XT_SEEK_UP, 0));
        lower.setContentDescription("Szukaj niżej");
        upper.setContentDescription("Szukaj wyżej");
        place(tuning, lower, dp(wide ? 150 : 52), 0);
        channelView = text("", Math.min(77, Math.max(38, screenWidth / 13f)), Config.TEXT);
        channelView.setGravity(Gravity.CENTER);
        channelView.setSingleLine();
        channelView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        channelView.setIncludeFontPadding(false);
        tuning.addView(channelView, new LinearLayout.LayoutParams(0, -2, 1));
        place(tuning, upper, dp(wide ? 150 : 52), 0);
        rdsView = text("", 20, Config.ACCENT);
        rdsView.setGravity(Gravity.CENTER);
        rdsView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(rdsView, new LinearLayout.LayoutParams(-1, -2));
        showRds();
        statusView = text("Wybierz częstotliwość lub wyszukaj stację.", 15, Config.MUTED);
        statusView.setGravity(Gravity.CENTER);
        statusView.setMinHeight(dp(29));
        statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
        scale = new FmScale(this, frequency -> send(Config.XT_TUNE, frequency));
        panel.addView(scale, new LinearLayout.LayoutParams(-1, dp(wide ? 85 : 88)));
        LinearLayout tools = row(panel);
        place(tools, button("Odczytaj RDS", Config.PANEL, Config.TEXT, () -> requestData(FactoryFmAccess.Mode.RDS)), -2, 0);
        place(tools, button("Autoskan", Config.PANEL, Config.TEXT, this::chooseScanMode), -2, 0);
        divider(panel, 6);
    }

    private void buildPresets(LinearLayout panel) {
        LinearLayout header = row(panel);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Presety", screenWidth >= 620 ? 22 : 18, Config.TEXT);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        place(header, button("Wpisz MHz", Config.PANEL, Config.TEXT, this::chooseFrequency), -2, 0);
        place(header, button("+ Zapisz preset", Config.ACCENT, Config.BACKGROUND, this::saveCurrentPreset), -2, 0);
        presetList = new LinearLayout(this);
        presetList.setOrientation(LinearLayout.VERTICAL);
        panel.addView(presetList);
    }

    /** Na Androidzie 8.1 uruchamia fabryczne FM raz przy otwarciu nowego zadania. */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !tunerStarted && supportsAutoReturn()) {
            tunerStarted = true;
            openFactory(true);
        }
        if (hasFocus) showPendingScan();
        else wheel.clear();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt(LAST_CHANNEL, lastChannel);
        state.putString("lastRds", lastRds);
        state.putBoolean(TUNER_STARTED, tunerStarted);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        mediaKeys.setActive(true);
        if (topway == null) {
            factoryEvents = new XtRadioEvents(this, this::onFactoryEvent);
            factoryEvents.start();
        }
    }

    @Override protected void onPause() {
        mediaKeys.setActive(false);
        steering.cancel();
        main.removeCallbacks(stepFromXt);
        main.removeCallbacks(tunerTimeout);
        wheel.clear(); tunerCommand++;
        if (factoryEvents != null) {
            factoryEvents.close();
            factoryEvents = null;
        }
        super.onPause();

        if (returning) {
            main.removeCallbacks(returnToRadio);
            main.postDelayed(returnToRadio, Config.FACTORY_START_MS);
        }
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(returnToRadio);
        main.removeCallbacks(tunerTimeout);
        if (topway != null) topway.close();
        FactoryFmAccess.cancel(this);
        mediaKeys.release();
        super.onDestroy();

    }

    /** Uruchamia fabryczne FM. Automatyczny powrót jest niezależny od odczytu danych. */
    private boolean openFactory(boolean autoReturn) {
        if (returning) return false;
        Intent launch = getPackageManager().getLaunchIntentForPackage(Config.FACTORY_FM);
        if (launch == null) {
            statusView.setText("Brak fabrycznej aplikacji FM.");
            return false;
        }
        try {
            startActivity(launch);
            returning = autoReturn && supportsAutoReturn();
            if (returning) {
                statusView.setText("Uruchamiam tuner…");
                main.postDelayed(returnToRadio, Config.FACTORY_START_MS + 1000);
            } else statusView.setText("Wróć do RadioKIA przez Ostatnie aplikacje.");
            return true;
        } catch (ActivityNotFoundException | SecurityException failure) {
            statusView.setText("Android nie otworzył fabrycznego FM.");
            return false;
        }
    }
    /** Przywraca zadanie RadioKIA i pozostawia fabryczne FM w tle. */
    private void returnFromFactory() {
        returning = false;
        if (isFinishing() || isDestroyed()) return;
        statusView.setText("Wybierz częstotliwość lub wyszukaj stację.");
        bringRadioToFront();
    }

    private void bringRadioToFront() {
        if (hasWindowFocus()) return;
        ActivityManager activities = getSystemService(ActivityManager.class);
        try {
            for (ActivityManager.AppTask task : activities.getAppTasks()) {
                if (task.getTaskInfo().id != getTaskId()) continue;
                task.moveToFront();
                return;
            }
        } catch (IllegalArgumentException | SecurityException ignored) { /* Zadanie mogło zostać zamknięte. */ }
        Toast.makeText(this, "Wróć do RadioKIA przez Ostatnie aplikacje.", Toast.LENGTH_SHORT).show();
    }
    private void showRds() {
        rdsView.setText(lastRds == null ? "RDS  —" : "RDS  " + lastRds);
    }

    /** Przed użyciem AS użytkownik wybiera czułość i potwierdza zmianę fabrycznej listy. */
    private void chooseScanMode() {
        if (!readAccess()) return;
        new AlertDialog.Builder(this).setTitle("Autoskan FM")
            .setMessage("LOC ogranicza wyszukiwanie do silniejszych sygnałów. DX wykrywa także słabsze. "
                + "Fabryczne AS odświeży własną listę stacji. Presety RadioKIA zapiszesz po wyborze wyników.")
            .setPositiveButton("Skanuj LOC", (dialog, which) -> requestData(FactoryFmAccess.Mode.LOCAL))
            .setNeutralButton("Skanuj DX", (dialog, which) -> requestData(FactoryFmAccess.Mode.DISTANT))
            .setNegativeButton("Anuluj", null).show();
    }

    private boolean readAccess() {
        if (FactoryFmAccess.available()) return true;
        new AlertDialog.Builder(this).setTitle("Włącz odczyt fabrycznego FM")
            .setMessage("W Ułatwieniach dostępu włącz usługę „RadioKIA FM – odczyt fabrycznego radia”. "
                + "Po wybraniu RDS lub autoskanu odczyt obejmuje tylko ekran fabrycznego FM.")
            .setPositiveButton("Otwórz ustawienia", (dialog, which) -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (ActivityNotFoundException unavailable) {
                    statusView.setText("Włącz usługę RadioKIA FM w Ułatwieniach dostępu.");
                }
            }).setNegativeButton("Anuluj", null).show();
        return false;
    }

    private void requestData(FactoryFmAccess.Mode mode) {
        if (readingFactory || sending || returning || !readAccess()) return;
        if (!FactoryFmAccess.request(this, mode, this::finishFactoryData)) return;
        if (!openFactory(false)) {
            FactoryFmAccess.cancel(this);
            return;
        }
        readingFactory = true;
        lastRds = null;
        showRds();
        if (mode != FactoryFmAccess.Mode.RDS) {
            lastChannel = 0;
            channelConfirmed = false;
            showChannel();
            showPresets();
        }
        statusView.setText(mode == FactoryFmAccess.Mode.RDS ? "Odczytuję nazwę RDS…" : "Skanuję pasmo FM…");
    }

    private void finishFactoryData(FactoryFmAccess.Result result) {
        readingFactory = false;
        if (isFinishing() || isDestroyed()) return;
        statusView.setText(result.message);
        if (result.mode == FactoryFmAccess.Mode.RDS) {
            lastRds = result.rds;
            showRds();
        } else if (!result.frequencies.isEmpty()) pendingScan = result;
        if (result.returnToRadio) bringRadioToFront();
        if (hasWindowFocus()) showPendingScan();
    }

    /** Własne presety zmienia wyłącznie potwierdzenie zaznaczonych wierszy. */
    private void showPendingScan() {
        FactoryFmAccess.Result result = pendingScan;
        if (result == null) return;
        pendingScan = null;
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        ScrollView scroll = new ScrollView(this);
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setPadding(dp(15), dp(3), dp(15), dp(8));
        scroll.addView(choices);
        TextView note = text(result.message + " Pozycje podświetlone są blisko siebie. Odsłuchaj je przed zapisem.", 15, Config.MUTED);
        choices.addView(note);
        for (int index = 0; index < result.frequencies.size(); index++) {
            int frequency = result.frequencies.get(index);
            boolean exists = presets.contains(frequency);
            LinearLayout line = row(choices);
            line.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox check = new CheckBox(this);
            check.setText(describe(frequency) + (exists ? " · zapisany" : ""));
            check.setTextSize(17);
            check.setTextColor(ScanStations.nearby(result.frequencies, index) ? Config.INDICATOR : Config.TEXT);
            check.setChecked(exists);
            check.setEnabled(!exists);
            check.setOnCheckedChangeListener((control, checked) -> {
                if (checked) selected.add(frequency);
                else selected.remove(frequency);
            });
            place(line, check, 0, 1);
            place(line, button("Odsłuchaj", Config.PANEL, Config.TEXT, () -> send(Config.XT_TUNE, frequency)), -2, 0);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Autoskan " + result.mode.stockLabel + " · " + result.frequencies.size())
            .setView(scroll).setNegativeButton("Zamknij", null).setPositiveButton("Zapisz zaznaczone", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (selected.isEmpty()) {
                note.setText("Zaznacz stacje, które chcesz dodać.");
                scroll.smoothScrollTo(0, 0);
                return;
            }
            if (presets.size() + selected.size() > Config.MAX_PRESETS) {
                note.setText("Wolnych miejsc: " + Math.max(0, Config.MAX_PRESETS - presets.size()) + ". Zaznacz mniej stacji.");
                scroll.smoothScrollTo(0, 0);
                return;
            }
            presets.addAll(selected);
            savePresets();
            statusView.setText("Dodano presetów: " + selected.size());
            dialog.dismiss();
        });
    }

    private static boolean supportsAutoReturn() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
    }

    /** Pozwala wpisać częstotliwość do strojenia albo zapisać ją w presetach. */
    private void chooseFrequency() {
        EditText input = new EditText(this);
        input.setHint("np. 101,7 MHz");
        input.setSingleLine();
        // Firmware może używać klawiatury z kropką, a wpis lub wklejony tekst może zawierać przecinek.
        input.setKeyListener(DigitsKeyListener.getInstance("0123456789.,"));
        input.setRawInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (lastChannel != 0) {
            input.setText(formatChannel(lastChannel));
            input.setSelectAllOnFocus(true);
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Częstotliwość FM").setView(input)
            .setNegativeButton("Anuluj", null).setPositiveButton("Dostrój", null)
            .setNeutralButton("Zapisz preset", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> acceptFrequency(dialog, input, false));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> acceptFrequency(dialog, input, true));
    }

    private void acceptFrequency(AlertDialog dialog, EditText input, boolean save) {
        int frequency;
        try {
            frequency = new BigDecimal(input.getText().toString().trim().replace(',', '.')).movePointRight(1).intValueExact();
            if (!validFrequency(frequency)) throw new IllegalArgumentException();
        } catch (ArithmeticException | IllegalArgumentException invalid) {
            input.setError("Podaj od 87,5 do 108,0 MHz z krokiem 0,1 MHz.");
            return;
        }
        if (save && !canSave(frequency)) {
            input.setError("Możesz zapisać maksymalnie " + Config.MAX_PRESETS + " presetów.");
            return;
        }
        if (!save && sending) {
            input.setError("Trwa wysyłanie poprzedniego polecenia.");
            return;
        }
        dialog.dismiss();
        if (save) addPreset(frequency);
        else send(Config.XT_TUNE, frequency);
    }

    /** Rozróżnia przyjęcie polecenia przez XTService i potwierdzenie częstotliwości przez tuner. */
    private void send(int transaction, int frequency) {
        if (sending || returning || readingFactory) return;
        wheel.clear();
        sendCommand(transaction, frequency, false);
    }

    private boolean canSwitchPreset() {
        return hasWindowFocus() && !sending && !returning && !readingFactory;
    }

    private void sendCommand(int transaction, int frequency, boolean preset) {
        main.removeCallbacks(tunerTimeout);
        long previousUpdate = tunerUpdates, command = ++tunerCommand;
        sending = true;
        lastRds = null;
        showRds();
        statusView.setText("Wysyłam polecenie do tunera…");
        XtRadio.send(this, transaction, frequency, error -> {
            sending = false;
            if (isDestroyed()) return;
            if (command == tunerCommand && tunerUpdates == previousUpdate && (!preset || wheel.awaiting(frequency))) {
                if (error != null) {
                    if (preset) wheel.finish(frequency);
                    statusView.setText(error);
                } else if (topway != null) {
                    awaitTuner(frequency);
                } else {
                    if (preset) wheel.finish(frequency);
                    setRequested(frequency, frequency == 0 ? "Wysłano wyszukiwanie stacji. Odczyt MHz w fabrycznym FM."
                        : "Ostatnia wysłana częstotliwość");
                }
            }
            wheel.flush();
        });
    }

    private void awaitTuner(int frequency) {
        setRequested(frequency, frequency == 0 ? "Tuner szuka stacji…" : "Czekam na potwierdzenie tunera…");
        main.removeCallbacks(tunerTimeout);
        main.postDelayed(tunerTimeout, TUNER_REPLY_MS);
    }

    private void tunerTimedOut() {
        if (wheel.pending() && !wheel.awaiting(lastChannel)) return;
        wheel.finish(lastChannel);
        statusView.setText("Tuner nie potwierdził zmiany częstotliwości.");
    }

    /** Potwierdza wybraną częstotliwość po zakończeniu fabrycznej reakcji na kierownicę. */
    @Override public void onTunerChannel(int frequency) {
        if (!wheel.acceptChannel(frequency)) return;
        main.removeCallbacks(tunerTimeout);
        tunerUpdates++;
        if (frequency != lastChannel) {
            lastRds = null;
            showRds();
        }
        lastChannel = frequency;
        channelConfirmed = true;
        showChannel();
        showPresets();
        if (!readingFactory) statusView.setText("Częstotliwość potwierdzona przez tuner");
    }

    private void showChannel() {
        String prefix = lastChannel == 0 || channelConfirmed ? "" : "Zadano  ";
        String value = prefix + (lastChannel == 0 ? "—" : formatChannel(lastChannel)) + " MHz";
        SpannableString display = new SpannableString(value);
        int unit = value.length() - 3;
        if (!prefix.isEmpty()) {
            display.setSpan(new RelativeSizeSpan(0.26f), 0, prefix.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            display.setSpan(new ForegroundColorSpan(Config.MUTED), 0, prefix.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        display.setSpan(new RelativeSizeSpan(0.37f), unit, value.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        display.setSpan(new ForegroundColorSpan(Config.ACCENT), unit, value.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        channelView.setText(display);
        scale.setFrequency(lastChannel);
    }

    private boolean canSave(int frequency) {
        return presets.contains(frequency) || presets.size() < Config.MAX_PRESETS;
    }

    private void saveCurrentPreset() {
        if (lastChannel == 0) {
            chooseFrequency();
            return;
        }
        if (canSave(lastChannel)) addPreset(lastChannel);
        else statusView.setText("Usuń preset, aby zwolnić miejsce.");
    }

    private void addPreset(int frequency) {
        presets.add(frequency);
        savePresets();
        statusView.setText("Preset zapisany: " + describe(frequency));
    }

    /** Zachowuje format wersji 0.2.0 i 0.3.0, czyli częstotliwości w dziesiątych MHz. */
    private void savePresets() {
        List<String> values = new ArrayList<>();
        for (int frequency : presets) values.add(Integer.toString(frequency));
        prefs.edit().putString(Config.PRESETS, String.join(",", values)).apply();
        showPresets();
    }

    /** Osiem miejsc na ekranie. Zapisane P9/P10 ze starszych wersji pozostają dostępne. */
    private void showPresets() {
        presetList.removeAllViews();
        int columns = screenWidth >= 620 ? 4 : 2;
        List<Integer> ordered = new ArrayList<>(presets);
        int slots = Math.max(Config.MAX_PRESETS, ordered.size());
        for (int index = 0; index < slots; index += columns) {
            LinearLayout strip = row(presetList);
            strip.setPadding(0, dp(4), 0, 0);
            for (int column = 0; column < columns; column++) {
                int slot = index + column;
                if (slot >= slots) {
                    place(strip, new View(this), 0, 1);
                    continue;
                }
                String label = "P" + (slot + 1);
                if (slot >= ordered.size()) {
                    Button empty = button(label + "   +", Config.BACKGROUND, Config.MUTED, this::chooseFrequency);
                    empty.setContentDescription(label + " – dodaj preset");
                    place(strip, empty, 0, 1);
                    continue;
                }
                int frequency = ordered.get(slot);
                boolean selected = frequency == lastChannel;
                Button preset = button(label + "   " + formatChannel(frequency), selected ? Config.PRESET_ACTIVE : Config.PANEL,
                    selected ? Config.ACCENT : Config.TEXT, () -> send(Config.XT_TUNE, frequency));
                preset.setContentDescription(label + " " + describe(frequency));
                preset.setTextSize(18);
                preset.setOnLongClickListener(view -> {
                    new AlertDialog.Builder(this).setTitle("Usunąć preset " + describe(frequency) + "?")
                        .setNegativeButton("Anuluj", null).setPositiveButton("Usuń", (dialog, action) -> {
                            presets.remove(frequency);
                            savePresets();
                        }).show();
                    return true;
                });
                place(strip, preset, 0, 1);
            }
        }
    }

    /** Aktywna sesja odbiera standardowe klawisze multimedialne bez przejmowania toru audio. */
    private void configureMediaKeys() {
        mediaKeys = new MediaSession(this, "RadioKIA FM");
        mediaKeys.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaKeys.setPlaybackState(new PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .setActions(PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS).build());
        mediaKeys.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (hasWindowFocus() && event != null && handleSteeringKey(event)) return true;
                return super.onMediaButtonEvent(intent);
            }

            @Override public void onSkipToNext() {
                if (hasWindowFocus()) stepPreset(1);
            }

            @Override public void onSkipToPrevious() {
                if (hasWindowFocus()) stepPreset(-1);
            }
        }, main);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return handleSteeringKey(event) || super.dispatchKeyEvent(event);
    }

    /** Łączy w tej samej obsłudze klawisze z Androida i sesji multimedialnej. */
    private boolean handleSteeringKey(KeyEvent event) {
        int direction;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_CHANNEL_UP: direction = 1; break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_CHANNEL_DOWN: direction = -1; break;
            default: return false;
        }
        // Na TS7 ten sam przycisk może trafić też do Androida. Zdarzenie MCU ma pierwszeństwo.
        if (topway != null) return true;
        androidDirection = direction;
        lastAndroidKey = SystemClock.uptimeMillis();
        main.removeCallbacks(stepFromXt);
        if (event.isCanceled()) {
            steering.cancel();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
            int action = steering.accept(direction, event.getDownTime(), event.getAction() == KeyEvent.ACTION_DOWN,
                event.isLongPress(), event.getEventTime() - event.getDownTime());
            if (Math.abs(action) == 1) stepPreset(action);
            else if (Math.abs(action) == 2) send(action > 0 ? Config.XT_SEEK_UP : Config.XT_SEEK_DOWN, 0);
        }
        return true;
    }

    /** Różnicuje żądania XTService od zmian, które tuner wykonuje bez odczytu zwrotnego. */
    private void onFactoryEvent(int operation, int frequency) {
        if (!hasWindowFocus() || sending || readingFactory || returning) return;
        if (operation == XtRadioEvents.TUNE) {
            if (!wheel.pending() && frequency != 0 && frequency != lastChannel) setRequested(frequency, "Polecenie strojenia z fabrycznego FM.");
            return;
        }
        if (operation == XtRadioEvents.SEEK_NEXT || operation == XtRadioEvents.SEEK_PREVIOUS) {
            main.removeCallbacks(stepFromXt);
            wheel.clear();
            setRequested(0, "Fabryczny tuner szuka stacji.");
            return;
        }
        int direction = operation == XtRadioEvents.NEXT ? 1 : -1;
        if (direction == androidDirection && SystemClock.uptimeMillis() - lastAndroidKey <= SAME_PRESS_MS) return;
        if (presets.isEmpty()) {
            setRequested(0, "Dodaj preset, aby przełączać własne stacje.");
            return;
        }
        vendorDirection = direction;
        main.removeCallbacks(stepFromXt);
        // Fabryczny odbiorca ma chwilę na obsługę własnego zdarzenia przed naszym strojeniem.
        main.postDelayed(stepFromXt, SAME_PRESS_MS);
    }

    /** Krótkie naciśnięcie wybiera własny preset po pierwszej reakcji fabrycznego tunera. */
    @Override public void onTunerSteering(int direction, boolean seek) {
        if (!hasWindowFocus() || readingFactory || returning) return;
        if (seek) {
            wheel.clear(); tunerCommand++;
            awaitTuner(0);
        } else stepPreset(direction);
    }

    private void stepPreset(int direction) {
        if (!hasWindowFocus() || readingFactory || returning) return;
        if (wheel.step(direction, lastChannel, presets.stream().mapToInt(Integer::intValue).toArray()))
            main.removeCallbacks(tunerTimeout);
        else statusView.setText("Dodaj preset, aby przełączać własne stacje.");
    }

    private void setRequested(int frequency, String status) {
        lastChannel = frequency;
        channelConfirmed = false;
        lastRds = null;
        showRds();
        showChannel();
        showPresets();
        statusView.setText(status);
    }

    private static boolean validFrequency(int frequency) {
        return frequency >= Config.FM_MIN && frequency <= Config.FM_MAX;
    }

    static String formatChannel(int frequency) {
        return Config.formatChannel(frequency);
    }

    private static String describe(int frequency) {
        return formatChannel(frequency) + " MHz";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String label, float size, int color) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout line = new LinearLayout(this);
        parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
        return line;
    }

    private void divider(LinearLayout parent, int margin) {
        View line = new View(this);
        line.setBackgroundColor(Config.LINE);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(1));
        layout.setMargins(0, dp(margin), 0, dp(margin));
        parent.addView(line, layout);
    }

    private void place(LinearLayout parent, View view, int width, float weight) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(width, -2, weight);
        layout.setMarginStart(parent.getChildCount() == 0 ? 0 : dp(8));
        parent.addView(view, layout);
    }

    private Button button(String label, int background, int color, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(color);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(background);
        fill.setCornerRadius(dp(10));
        fill.setStroke(dp(1), background == Config.ACCENT ? Config.ACCENT : Config.LINE);
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3078DDD0), fill, null));
        button.setOnClickListener(view -> action.run());
        return button;
    }
}
