package io.github.fvmeownv.twoshipru;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * Единственный экран: список из пяти шагов, карточка текущего шага с одной большой кнопкой,
 * журнал. Какой шаг текущий, каждый раз определяет State по фактическому состоянию устройства.
 */
public final class MainActivity extends Activity implements Worker.Listener {
    static final String ACTION_INSTALL_STATUS = "io.github.fvmeownv.twoshipru.INSTALL_STATUS";
    private static final int REQ_PICK_ROM = 11, REQ_STORAGE = 12;

    // Ночное небо над Терминой и золото глаз маски
    private static final int BG = 0xFF16112A, SURFACE = 0xFF221A3D, SURFACE_HI = 0xFF2C2350, LOG_BG = 0xFF100C20;
    private static final int TEXT = 0xFFEDE6F7, MUTED = 0xFFA99CC4, LINE = 0xFF40356A, DISABLED = 0xFF2A2342;
    private static final int ACCENT = 0xFFE9A93A, ACCENT_DOWN = 0xFFC98B22, ON_ACCENT = 0xFF1B1030;
    private static final int DONE_C = 0xFF86D295, ERROR_C = 0xFFFF9384, FOCUS = 0xFFFFFFFF;

    private static final String[] STEP_TITLES = {
            "Доступ к файлам", "Образ игры", "Русская версия игры", "Первый запуск игры", "Русский текст и графика"
    };

    private State state;
    private State.Step shownStep;
    private String notice, error;
    private boolean logShown;

    private LinearLayout stepsBox, extrasBox;
    private TextView cardTitle, cardText, errorView, noticeView, logView;
    private ProgressBar progress;
    private Button mainBtn, secondBtn, logToggle;

    // ---------------------------------------------------------------- жизненный цикл

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        Worker.get().attach(this);
        handleInstallStatus(getIntent());
    }

    @Override protected void onDestroy() {
        Worker.get().detach(this);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleInstallStatus(intent);
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        buildUi();
        refresh();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_ROM && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            final Context app = getApplicationContext();
            run("Проверка образа", w -> { Rom.prepareFromUri(app, uri, w); return null; });
        }
    }

    // ---------------------------------------------------------------- Worker.Listener

    @Override public void onLog(String line) {
        if (logView == null) return;
        logView.append(line + "\n");
        if (logView.getLineCount() > 700) fillLog();
    }

    @Override public void onWorkerChanged() { refresh(); }

    @Override public void onUiAction(Worker.UiAction action) {
        try {
            action.run(this);
        } catch (ActivityNotFoundException e) {
            error = "Android не смог открыть нужный экран на этом устройстве.";
        } catch (Exception e) {
            error = e.getMessage();
        }
        refresh();
    }

    // ---------------------------------------------------------------- действия

    private void run(String title, Worker.Task task) {
        error = null;
        notice = null;
        logShown = true;
        Worker.get().start(this, title, task);
        render();
    }

    private void onMainButton() {
        final Context app = getApplicationContext();
        error = null;
        notice = null;
        try {
            switch (state.step) {
                case ACCESS:
                    requestAccess();
                    break;
                case ROM:
                    run("Поиск образа", w -> Rom.findAndPrepare(app, w));
                    break;
                case INSTALL:
                    if (!state.canInstall) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                    } else if (state.game == Game.FOREIGN) {
                        run("Подготовка русской версии", w -> {
                            ApkBuilder.ensure(app, w);
                            Saves.backup(w);
                            w.log("Всё готово к замене. Подтвердите удаление старой версии.");
                            return Game::requestUninstall;
                        });
                    } else {
                        run("Установка русской версии", w -> {
                            File apk = ApkBuilder.ensure(app, w);
                            Installer.install(app, apk, w);
                            return null;
                        });
                    }
                    break;
                case FIRST_RUN:
                    run("Подготовка к первому запуску", w -> {
                        Game.prepareFirstRun(app, w);
                        return Game::launch;
                    });
                    break;
                case MODS:
                    run("Сборка русского текста и графики", w -> { Mods.build(app, w); return null; });
                    break;
                case DONE:
                    Game.launch(this);
                    break;
            }
        } catch (ActivityNotFoundException e) {
            error = "Android не смог открыть нужный экран на этом устройстве.";
        } catch (Exception e) {
            error = e.getMessage();
        }
        render();
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    void pickRom() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK_ROM);
    }

    private void setProfile(String profile) {
        final Context app = getApplicationContext();
        run("Смена набора: " + Mods.label(profile), w -> {
            Game.kill(app, w);
            Mods.applyProfile(profile, w);
            return null;
        });
    }

    @SuppressWarnings("deprecation")
    private void handleInstallStatus(Intent intent) {
        if (intent == null || !ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        setIntent(new Intent());
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    try { startActivity(confirm); } catch (Exception e) { error = "Не удалось открыть окно установки: " + e.getMessage(); }
                }
                return;
            case PackageInstaller.STATUS_SUCCESS:
                notice = "Русская версия игры установлена.";
                Worker.get().log("Android: игра установлена.");
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                error = "Установка отменена. Нажмите кнопку ещё раз, когда будете готовы.";
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                error = "Android не дал установить игру: мешает другая её версия. Удалите её и повторите.";
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                error = "На устройстве не хватает места для установки игры.";
                break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                error = "Эта сборка игры несовместима с устройством (нужен Android на arm64).";
                break;
            default:
                error = "Установка не удалась" + (msg != null ? ": " + msg : ".");
        }
        Worker.get().log("Итог установки: " + status + (msg != null ? " " + msg : ""));
        refresh();
    }

    // ---------------------------------------------------------------- отрисовка

    private void refresh() {
        state = State.detect(this);
        render();
    }

    private void render() {
        if (state == null || mainBtn == null) return;
        Worker w = Worker.get();
        boolean busy = w.isBusy();
        int current = state.step.ordinal();

        stepsBox.removeAllViews();
        for (int i = 0; i < STEP_TITLES.length; i++) {
            stepsBox.addView(stepRow(i, i < current ? 2 : (i == current ? 1 : 0)));
        }

        secondBtn.setVisibility(View.GONE);
        extrasBox.setVisibility(View.GONE);
        mainBtn.setVisibility(View.VISIBLE);

        switch (state.step) {
            case ACCESS:
                card("Разрешите доступ к файлам",
                        "Приложение работает с папкой игры 2S2H: кладёт туда русские файлы и делает копию сохранений. "
                                + "Для этого ему нужен доступ ко всем файлам.\n\n"
                                + "Откроются настройки Android: включите переключатель у «2Ship RU» и вернитесь назад.",
                        "Открыть настройки");
                break;
            case ROM:
                card("Ваш образ игры",
                        "Нужен ваш собственный образ Majora's Mask: версия для США 1.0, файл на 32 МБ "
                                + "(обычно называется MM_USA_v1.0.z64).\n\n"
                                + "Положите его в папку «Загрузки» — приложение найдёт его само. Или выберите файл вручную.",
                        "Найти образ");
                secondBtn.setText("Выбрать файл вручную");
                secondBtn.setOnClickListener(v -> { error = null; pickRom(); });
                secondBtn.setVisibility(View.VISIBLE);
                break;
            case INSTALL:
                if (!state.canInstall) {
                    card("Разрешите установку игры",
                            "Русскую версию игры устанавливает это приложение, и Android должен это разрешить.\n\n"
                                    + "Откроются настройки: включите «Разрешить установку из этого источника» и вернитесь назад.",
                            "Открыть настройки");
                } else if (state.game == Game.FOREIGN) {
                    card("Замена игры на русскую версию",
                            "Сейчас на устройстве обычная версия 2 Ship. Русскую нельзя поставить поверх неё, "
                                    + "поэтому старую нужно удалить.\n\n"
                                    + "1. Приложение скачает игру с GitHub её авторов и соберёт русскую версию. "
                                    + "Это несколько минут, нужен интернет.\n"
                                    + "2. Сделает копию ваших сохранений в папку 2S2H_RU_backup.\n"
                                    + "3. Android спросит, удалить ли 2 Ship, — нажмите «OK».\n\n"
                                    + "Сохранения и настройки лежат в папке 2S2H и при удалении игры не пропадают.",
                            "Подготовить и заменить");
                } else {
                    card("Установка русской версии игры",
                            "Приложение скачает игру 2 Ship с GitHub её авторов, внесёт в неё русские исправления "
                                    + "и установит. Это несколько минут, нужен интернет.\n\n"
                                    + "В конце Android попросит подтвердить установку — нажмите «Установить». "
                                    + "Если появится предупреждение Play Защиты, выберите «Всё равно установить».",
                            "Установить русскую версию");
                }
                break;
            case FIRST_RUN:
                card("Первый запуск игры",
                        "Игре нужно один раз прочитать ваш образ и создать из него свои файлы.\n\n"
                                + "• Если игра попросит доступ к файлам — разрешите.\n"
                                + "• Если попросит образ — выберите Download → 2S2H_MM_RU → MM_USA_v1.0.z64.\n"
                                + "• Дождитесь титульного экрана. Надпись на нём пока будет странной — "
                                + "это нормально, её исправит следующий шаг.\n"
                                + "• Вернитесь в это приложение.",
                        "Открыть игру");
                break;
            case MODS:
                card("Русский текст и графика",
                        "Последний шаг. Приложение закроет игру, соберёт из вашего образа русский текст, шрифт "
                                + "и графику и положит их в папку игры. Это около минуты.",
                        "Собрать и включить");
                break;
            case DONE:
                card("Готово!",
                        "Игра на русском. На титульном экране должно быть «НАЖМИ СТАРТ».\n\n"
                                + "Если игра была открыта во время установки, закройте её через список недавних "
                                + "приложений и запустите снова.",
                        "Играть");
                buildExtras();
                extrasBox.setVisibility(View.VISIBLE);
                break;
        }

        if (busy) {
            cardTitle.setText(w.busyTitle());
            cardText.setText("Идёт работа. Не закрывайте приложение и не выключайте экран — "
                    + "это может занять несколько минут.\n\nПодробности видны в журнале.");
            mainBtn.setVisibility(View.GONE);
            secondBtn.setVisibility(View.GONE);
            extrasBox.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            progress.setVisibility(View.GONE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        String err = error != null ? error : w.lastError();
        errorView.setVisibility(err != null && !busy ? View.VISIBLE : View.GONE);
        if (err != null) errorView.setText(err);
        noticeView.setVisibility(notice != null && !busy ? View.VISIBLE : View.GONE);
        if (notice != null) noticeView.setText(notice);

        logToggle.setText(logShown ? "Скрыть журнал" : "Показать журнал");
        logView.setVisibility(logShown ? View.VISIBLE : View.GONE);

        if (!busy && state.step != shownStep) {
            shownStep = state.step;
            mainBtn.requestFocus();
        }
    }

    private void card(String title, String text, String button) {
        cardTitle.setText(title);
        cardText.setText(text);
        mainBtn.setText(button);
    }

    private void buildExtras() {
        extrasBox.removeAllViews();
        extrasBox.addView(label("Что включено в игре", 17, TEXT, true), topMargin(4));
        String[][] options = {
                {Mods.BOTH, "Всё на русском"},
                {Mods.TEXT, "Только текст"},
                {Mods.GRAPHICS, "Только графика"},
                {Mods.NONE, "Без русификации — проверить, виноват ли порт в вылетах"},
        };
        for (String[] o : options) {
            boolean on = o[0].equals(state.profile);
            Button b = button((on ? "● " : "○ ") + o[1], false);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            if (on) b.setTextColor(DONE_C);
            final String p = o[0];
            b.setOnClickListener(v -> { if (!p.equals(state.profile)) setProfile(p); });
            extrasBox.addView(b, topMargin(8));
        }

        extrasBox.addView(label("Если что-то не так", 17, TEXT, true), topMargin(22));
        final Context app = getApplicationContext();
        Button rebuild = button("Пересобрать русские файлы", false);
        rebuild.setOnClickListener(v -> run("Пересборка русского текста и графики", w -> { Mods.build(app, w); return null; }));
        extrasBox.addView(rebuild, topMargin(8));
        Button reinstall = button("Переустановить игру (сохранения останутся)", false);
        reinstall.setOnClickListener(v -> run("Переустановка игры", w -> {
            File apk = ApkBuilder.ensure(app, w);
            Installer.install(app, apk, w);
            return null;
        }));
        extrasBox.addView(reinstall, topMargin(8));
    }

    private void fillLog() {
        StringBuilder sb = new StringBuilder();
        List<String> lines = Worker.get().snapshot();
        for (int i = Math.max(0, lines.size() - 400); i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        logView.setText(sb);
    }

    private void showAbout() {
        String readme;
        try {
            readme = new String(Keys.readAsset(this, Config.README_ASSET), "windows-1251");
        } catch (Exception e) {
            readme = "(не удалось открыть файл авторов: " + e.getMessage() + ")";
        }
        TextView t = new TextView(this);
        t.setText(ABOUT + "\n\n——————————\n\n" + readme.replace("\r", ""));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(dp(22), dp(14), dp(22), dp(14));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Авторы и перевод")
                .setView(sv)
                .setPositiveButton("Закрыть", null)
                .show();
    }

    private static final String ABOUT =
            "2Ship RU — установщик русификации для Android-порта 2 Ship 2 Harkinian "
                    + "(The Legend of Zelda: Majora's Mask).\n\n"
                    + "В приложении нет ни игры, ни её образа. Игра скачивается со страницы авторов порта, "
                    + "а русский текст и графика собираются из вашего собственного образа прямо на устройстве.\n\n"
                    + "Основано на работе других людей:\n"
                    + "• 2 Ship 2 Harkinian — HarbourMasters.\n"
                    + "• Первый порт на Android — Waterdish.\n"
                    + "• Используемая сборка порта — linkzenic, версия " + Config.PORT_VERSION + ".\n"
                    + "• Русский перевод — Zelda64Rus и группа «Шедевр» (v2.0 beta, 2019). "
                    + "Авторы: FoX (FoX_XoF), Антон, САНЕК; версия 1.0: gottaX, Alex (Kareg), Coregon, CaH4e3. "
                    + "© Шедевр 2006, 2007 / © Zelda64RUS 2019. Патч включён с разрешения авторов, "
                    + "ниже — их обязательный текстовый файл.\n"
                    + "• Подпись APK — библиотека apksig, © The Android Open Source Project, Apache License 2.0.\n\n"
                    + "Код установщика распространяется по лицензии MIT и не затрагивает ни игру, ни перевод.\n"
                    + "The Legend of Zelda: Majora's Mask © Nintendo, 2000. Проект любительский и с Nintendo не связан.\n\n"
                    + "github.com/fvmeownv/2Ship-Android-RU";

    // ---------------------------------------------------------------- построение экрана

    private void buildUi() {
        float widthDp = getResources().getConfiguration().screenWidthDp;
        boolean wide = widthDp >= 720;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(outer, new ScrollView.LayoutParams(-1, -2));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(22), dp(22), dp(28));
        int maxW = dp(wide ? 1100 : 640);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        outer.addView(page, new LinearLayout.LayoutParams(Math.min(maxW, screenW), -2));

        page.addView(label("Русификация Majora's Mask", 26, TEXT, true));
        page.addView(label("для 2 Ship 2 Harkinian на Android", 15, MUTED, false), topMargin(2));

        stepsBox = new LinearLayout(this);
        stepsBox.setOrientation(LinearLayout.VERTICAL);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(shape(SURFACE, 18, 0, 0));
        card.setPadding(dp(22), dp(20), dp(22), dp(22));
        cardTitle = label("", 21, TEXT, true);
        card.addView(cardTitle);
        cardText = label("", 16, TEXT, false);
        card.addView(cardText, topMargin(10));
        errorView = label("", 16, ERROR_C, false);
        card.addView(errorView, topMargin(14));
        noticeView = label("", 16, DONE_C, false);
        card.addView(noticeView, topMargin(14));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        card.addView(progress, topMargin(16));
        mainBtn = button("", true);
        mainBtn.setOnClickListener(v -> onMainButton());
        card.addView(mainBtn, topMargin(20));
        secondBtn = button("", false);
        card.addView(secondBtn, topMargin(10));

        extrasBox = new LinearLayout(this);
        extrasBox.setOrientation(LinearLayout.VERTICAL);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        logToggle = button("", false);
        logToggle.setOnClickListener(v -> { logShown = !logShown; render(); });
        footer.addView(logToggle, topMargin(0));
        Button reportBtn = button("Сохранить отчёт для диагностики", false);
        final Context app = getApplicationContext();
        reportBtn.setOnClickListener(v -> run("Сохранение отчёта", w -> {
            File f = Report.make(app, w);
            return a -> a.showNotice("Отчёт сохранён в «Загрузки»: " + f.getName());
        }));
        footer.addView(reportBtn, topMargin(8));
        Button aboutBtn = button("Авторы и перевод", false);
        aboutBtn.setOnClickListener(v -> showAbout());
        footer.addView(aboutBtn, topMargin(8));

        logView = label("", 12, MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setBackground(shape(LOG_BG, 12, 0, 0));
        logView.setPadding(dp(12), dp(10), dp(12), dp(10));
        logView.setTextIsSelectable(true);
        fillLog();

        if (wide) {
            LinearLayout cols = new LinearLayout(this);
            cols.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            left.addView(stepsBox);
            left.addView(footer, topMargin(22));
            right.addView(card);
            right.addView(extrasBox, topMargin(18));
            right.addView(logView, topMargin(18));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 2f);
            cols.addView(left, lp);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 3f);
            rp.leftMargin = dp(28);
            cols.addView(right, rp);
            page.addView(cols, topMargin(24));
        } else {
            page.addView(stepsBox, topMargin(20));
            page.addView(card, topMargin(16));
            page.addView(extrasBox, topMargin(18));
            page.addView(footer, topMargin(22));
            page.addView(logView, topMargin(14));
        }

        setContentView(scroll);
        shownStep = null;
    }

    void showNotice(String text) {
        notice = text;
        render();
    }

    private View stepRow(int i, int status) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView badge = label(status == 2 ? "✓" : String.valueOf(i + 1), 15,
                status == 1 ? ON_ACCENT : status == 2 ? BG : MUTED, true);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        if (status == 1) oval.setColor(ACCENT);
        else if (status == 2) oval.setColor(DONE_C);
        else { oval.setColor(0); oval.setStroke(dp(2), LINE); }
        badge.setBackground(oval);
        row.addView(badge, new LinearLayout.LayoutParams(dp(32), dp(32)));

        TextView t = label(STEP_TITLES[i], 16, status == 0 ? MUTED : TEXT, status == 1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(14);
        row.addView(t, lp);
        return row;
    }

    private TextView label(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 18 : 15);
        if (primary) b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        int fill = primary ? ACCENT : SURFACE_HI;
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{-android.R.attr.state_enabled}, shape(DISABLED, 14, 0, 0));
        sl.addState(new int[]{android.R.attr.state_pressed}, shape(primary ? ACCENT_DOWN : LINE, 14, 0, 0));
        sl.addState(new int[]{android.R.attr.state_focused}, shape(fill, 14, dp(3), FOCUS));
        sl.addState(new int[]{}, shape(fill, 14, primary ? 0 : dp(1), LINE));
        b.setBackground(sl);
        b.setTextColor(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{MUTED, primary ? ON_ACCENT : TEXT}));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(primary ? 60 : 50));
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        return b;
    }

    private GradientDrawable shape(int fill, int radiusDp, int strokePx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokePx > 0) d.setStroke(strokePx, strokeColor);
        return d;
    }

    private LinearLayout.LayoutParams topMargin(int dpValue) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(dpValue);
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
