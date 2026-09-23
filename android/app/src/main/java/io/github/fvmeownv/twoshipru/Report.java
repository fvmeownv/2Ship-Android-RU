package io.github.fvmeownv.twoshipru;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Собирает в один архив журнал приложения, отчёты утилит, логи игры и список файлов. */
final class Report {
    private Report() {}

    static File make(Context c, Worker w) throws Exception {
        if (!State.hasAccess(c))
            throw new Problem("Отчёт сохраняется в «Загрузки», для этого сначала разрешите доступ к файлам (шаг 1).");
        File out = new File(Config.downloads(), "2Ship-RU-report-" + Worker.stamp() + ".zip");
        out.getParentFile().mkdirs();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
            put(z, "status.txt", status(c).getBytes(StandardCharsets.UTF_8));
            addDir(z, Config.logs(c), "app_logs/");
            addDir(z, Config.reports(c), "reports/");
            File gameLogs = new File(Config.devRoot(), "logs");
            if (gameLogs.isDirectory()) addDir(z, gameLogs, "game_logs/");
        }
        w.log("Отчёт сохранён: " + out.getPath());
        return out;
    }

    private static String status(Context c) {
        StringBuilder s = new StringBuilder("2Ship RU Installer report\n");
        try {
            s.append("app=").append(c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName).append('\n');
        } catch (Exception ignored) { }
        s.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        s.append("max_heap_mb=").append(Runtime.getRuntime().maxMemory() >> 20).append('\n');
        State st = State.detect(c);
        s.append("step=").append(st.step).append(" game=").append(st.game).append(" rom=").append(st.rom)
                .append(" access=").append(st.access).append(" profile=").append(st.profile).append('\n');
        s.append("\n--- ").append(Config.devRoot()).append(" ---\n");
        list(s, Config.devRoot(), "", 3);
        return s.toString();
    }

    private static void list(StringBuilder s, File dir, String indent, int depth) {
        File[] l = dir.listFiles();
        if (l == null) return;
        for (File f : l) {
            if (f.isDirectory()) {
                s.append(indent).append(f.getName()).append("/\n");
                if (depth > 1 && !f.getName().equals("saves")) list(s, f, indent + "  ", depth - 1);
            } else {
                s.append(indent).append(f.getName()).append("  ").append(f.length()).append('\n');
            }
        }
    }

    private static void addDir(ZipOutputStream z, File dir, String prefix) throws IOException {
        File[] l = dir.listFiles();
        if (l == null) return;
        for (File f : l) {
            if (f.isDirectory()) addDir(z, f, prefix + f.getName() + "/");
            else if (f.length() < 50L * 1024 * 1024) {
                z.putNextEntry(new ZipEntry(prefix + f.getName()));
                try (InputStream in = new FileInputStream(f)) {
                    byte[] b = new byte[1 << 16];
                    int n;
                    while ((n = in.read(b)) > 0) z.write(b, 0, n);
                }
                z.closeEntry();
            }
        }
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }
}
