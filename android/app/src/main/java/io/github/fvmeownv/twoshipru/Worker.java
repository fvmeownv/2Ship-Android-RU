package io.github.fvmeownv.twoshipru;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Выполняет длинные операции в фоне по одной, ведёт журнал (на экране и в файле).
 * Живёт всё время работы приложения, поэтому поворот экрана или сворачивание работу не прерывают.
 */
final class Worker {
    interface Task { UiAction run(Worker w) throws Exception; }
    /** Что сделать на экране после успешной операции (например, открыть диалог Android). */
    interface UiAction { void run(MainActivity a) throws Exception; }
    interface Listener {
        void onLog(String line);
        void onWorkerChanged();
        void onUiAction(UiAction action);
    }

    private static final Worker INSTANCE = new Worker();
    static Worker get() { return INSTANCE; }

    private static final int MAX_LINES = 500;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private volatile boolean busy;
    private volatile String busyTitle;
    private volatile String lastError;
    private Listener listener;
    private PrintWriter file;

    private Worker() {}

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    void attach(Listener l) { listener = l; }
    void detach(Listener l) { if (listener == l) listener = null; }
    boolean isBusy() { return busy; }
    String busyTitle() { return busyTitle; }
    String lastError() { return lastError; }
    void clearError() { lastError = null; }

    synchronized List<String> snapshot() { return new ArrayList<>(lines); }

    void log(String s) {
        synchronized (this) {
            lines.addLast(s);
            while (lines.size() > MAX_LINES) lines.removeFirst();
            if (file != null) { file.println(s); file.flush(); }
        }
        ui.post(() -> { if (listener != null) listener.onLog(s); });
    }

    void start(Context context, String title, Task task) {
        if (busy) return;
        busy = true;
        busyTitle = title;
        lastError = null;
        notifyChanged();
        final Context app = context.getApplicationContext();
        exec.execute(() -> {
            openFile(app);
            log("");
            log("=== " + title + " (" + new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(new Date()) + ") ===");
            UiAction then = null;
            try {
                then = task.run(this);
                log("Готово.");
            } catch (Problem p) {
                lastError = p.getMessage();
                log("ОШИБКА: " + p.getMessage());
            } catch (OutOfMemoryError e) {
                lastError = "Не хватило памяти. Закройте другие приложения (особенно игру) и нажмите кнопку ещё раз.";
                log("ОШИБКА: нехватка памяти, максимум " + (Runtime.getRuntime().maxMemory() >> 20) + " МБ");
            } catch (Throwable e) {
                lastError = "Что-то пошло не так: " + e.getMessage()
                        + "\nНажмите «Сохранить отчёт» внизу и пришлите файл отчёта.";
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                log("ОШИБКА: " + e);
                for (String l : sw.toString().split("\n")) log("  " + l.trim());
            } finally {
                busy = false;
                busyTitle = null;
            }
            final UiAction fThen = then;
            ui.post(() -> {
                if (listener != null) {
                    listener.onWorkerChanged();
                    if (fThen != null) listener.onUiAction(fThen);
                }
            });
        });
    }

    private void notifyChanged() {
        ui.post(() -> { if (listener != null) listener.onWorkerChanged(); });
    }

    private synchronized void openFile(Context c) {
        if (file != null) return;
        try {
            String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            File f = new File(Config.logs(c), "log_" + day + ".txt");
            file = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // журнал в файл необязателен, на экране он всё равно есть
        }
    }
}
